import Combine
import Foundation

/// Plays a list of phone files of any length on a PlayBridge receiver (Play all, Play next,
/// Add to queue, slideshows). The phone keeps the whole list; the receiver only holds a window
/// of it (`PhoneQueueWindow`), which this coordinator keeps in step, matching Android:
///
/// - The receiver's `playlist_status` (it moved on, or someone edited its queue) and edits on
///   the phone wake an update, which waits briefly so quick skips settle, then sends removes,
///   adds and moves, each tied to this playback and confirmed.
/// - Edits made on the receiver are taken in, unless the phone has its own edit waiting.
/// - Anything else starting on the receiver ends the list.
///
/// Files are prepared (Photos items exported) and served only as the window reaches them.
/// Photos videos are large to export, so lists with them keep a shorter window.
/// A receiver that ignores sender item ids falls back to queueing items one by one.
@MainActor final class PhoneQueueCoordinator: ObservableObject {
    static let shared = PhoneQueueCoordinator()

    enum Mode { case replace, next, end }
    enum Outcome: Equatable { case playing, added, addedAtEnd, failed(String) }

    /// One entry of the phone's list, as Up next shows it.
    struct Item: Identifiable, Equatable {
        let id: String
        let media: PhoneMedia
        let displayDurationMs: Int?
    }
    private typealias Entry = Item

    /// The list is playing on the receiver.
    @Published private(set) var active = false
    /// Index of the current item in the phone's list.
    @Published private(set) var position = 0
    @Published private(set) var count = 0
    /// The whole list, for Up next.
    @Published private(set) var items: [Item] = []
    /// Items after the current one.
    var remaining: Int { max(0, count - position - 1) }
    var current: Item? { items.indices.contains(position) ? items[position] : nil }

    private var entries: [Entry] = []
    private var byId: [String: Entry] = [:]
    private var cursor = 0
    /// Ids the receiver has confirmed it holds (or held).
    private var sent: Set<String> = []
    /// Served URLs by entry id, so trimmed items stop being served.
    private var served: [String: String] = [:]
    private var playbackId: String?
    private var latest: PlaylistUiState?
    private var dirty = false
    /// An item being jumped to: window updates wait until the receiver is on it.
    private var jumpingTo: String?
    /// Ids removed from the phone's list that the receiver still holds.
    private var pendingRemoves: [String] = []
    private var awaitRevision: UInt64 = 0
    private var generation = 0
    private var updating = false
    private var pendingUpdate = false
    private var settleTask: Task<Void, Never>?
    private var attachTask: Task<Void, Never>?
    private var subscription: AnyCancellable?
    private weak var vm: ConnectionViewModel?
    private weak var library: PhoneMediaLibrary?

    /// The phone serves the files: iOS closing PlayBridge stops the list after the window.
    var needsAppRunning: Bool { active }

    func send(_ items: [PhoneMedia], mode: Mode, displayDurationMs: Int? = nil,
              library: PhoneMediaLibrary, vm: ConnectionViewModel) async -> Outcome {
        guard !items.isEmpty else { return .failed("Nothing to play.") }
        guard vm.localQueueSupported else { return .failed("This receiver plays one item at a time.") }
        attach(vm: vm, library: library)
        let newEntries = items.map {
            Entry(id: UUID().uuidString, media: $0, displayDurationMs: $0.kind == .image ? displayDurationMs : nil)
        }
        let playing = vm.coordinator.activeContext == "player" && vm.coordinator.playlist != nil
        if mode == .replace || !playing || !active {
            if mode != .replace, playing, !active {
                // Something else is playing: add to the receiver's own queue, one by one.
                return await appendToForeignQueue(newEntries, vm: vm, library: library)
            }
            return await start(newEntries) ? .playing : .failed("Couldn’t send to \(PhoneMediaCasting.receiverName(vm)).")
        }
        if mode == .next { entries.insert(contentsOf: newEntries, at: cursor + 1) } else { entries += newEntries }
        newEntries.forEach { byId[$0.id] = $0 }
        dirty = true
        publish()
        scheduleUpdate()
        return .added
    }

    /// Moves `delta` items through the list (slideshow previous and next).
    func step(_ delta: Int) {
        Task { _ = await jump(to: cursor + delta) }
    }

    /// Plays entry `index` of the list. In the receiver's window it is a plain jump; otherwise
    /// the entry is added to the receiver's queue and jumped to, in the same playback, and the
    /// window then rebuilds around it.
    func jump(to index: Int) async -> Bool {
        guard active, entries.indices.contains(index), let vm, let library, let pid = playbackId else { return false }
        let entry = entries[index]
        let session = generation
        // Until the receiver is on the new item, a window update would see it as far ahead of
        // the old current item and remove it again.
        // It ends when the receiver reports the new item (in update), or after a few seconds.
        jumpingTo = entry.id
        var jumped = false
        defer {
            if !jumped, jumpingTo == entry.id { jumpingTo = nil }
            if jumped {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    if self?.jumpingTo == entry.id { self?.jumpingTo = nil }
                    self?.scheduleUpdate()
                }
            }
            scheduleUpdate()
        }
        if latest?.items.contains(where: { $0.itemId == entry.id }) != true {
            guard let url = try? await library.resolve(entry.media),
                  let media = await LocalFileServer.shared.add(fileURL: url) else { return false }
            guard await vm.queueLocalMedia(url: media, title: entry.media.title, contentType: LocalFileServer.mimeType(for: url),
                                           displayDurationMs: entry.displayDurationMs, itemId: entry.id, playbackId: pid)?.ok == true,
                  session == generation
            else { LocalFileServer.shared.remove(served: media); return false }
            sent.insert(entry.id)
            served[entry.id] = media
        }
        let requestID = UUID().uuidString
        jumped = await vm.sendConfirmedQueueCommand(
            WireProtocol.playlistJumpCommand(index: 0, itemId: entry.id, playbackId: pid, useQueueV1: true, requestID: requestID),
            requestID: requestID)?.ok == true
        return jumped
    }

    /// Removes entry `id` (not the current one) from the list, and from the receiver if it holds it.
    func remove(_ id: String) {
        guard let index = entries.firstIndex(where: { $0.id == id }), index != cursor else { return }
        entries.remove(at: index)
        byId[id] = nil
        if index < cursor { cursor -= 1 }
        if sent.contains(id) { pendingRemoves.append(id) }
        edited()
    }

    /// Moves entries at `offsets` of the upcoming items to `destination` among them (List.onMove).
    func moveUpcoming(from offsets: IndexSet, to destination: Int) {
        guard entries.indices.contains(cursor) else { return }
        var upcoming = Array(entries[(cursor + 1)...])
        upcoming.move(fromOffsets: offsets, toOffset: destination)
        entries = Array(entries[...cursor]) + upcoming
        edited()
    }

    /// Clear queue: drops everything after the current item, which keeps playing.
    func clearUpcoming() {
        guard entries.indices.contains(cursor) else { return }
        for entry in entries[(cursor + 1)...] {
            byId[entry.id] = nil
            if sent.contains(entry.id) { pendingRemoves.append(entry.id) }
        }
        entries.removeSubrange((cursor + 1)...)
        edited()
    }

    /// Plays entry `id` of the list.
    func play(_ id: String) {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        Task { _ = await jump(to: index) }
    }

    private func edited() {
        dirty = true
        publish()
        scheduleUpdate()
    }

    /// Forgets the list; the receiver keeps playing what it holds.
    func stop() { clear() }

    // MARK: - Sending

    private func attach(vm: ConnectionViewModel, library: PhoneMediaLibrary) {
        self.library = library
        guard self.vm !== vm else { return }
        self.vm = vm
        subscription = vm.coordinator.$playlist
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in self?.onStatus(status) }
    }

    private func start(_ list: [Entry]) async -> Bool {
        clear()
        entries = list
        list.forEach { byId[$0.id] = $0 }
        return await sendFrom(0, session: generation)
    }

    private func sendFrom(_ index: Int, session: Int) async -> Bool {
        guard let vm, let library, entries.indices.contains(index) else { return false }
        let entry = entries[index]
        guard let url = try? await library.resolve(entry.media),
              let media = await LocalFileServer.shared.serve(fileURL: url, subtitle: nil)?.media,
              session == generation, vm.isConnected else { return false }
        vm.castLocalMedia(url: media, title: entry.media.title, contentType: LocalFileServer.mimeType(for: url),
                          displayDurationMs: entry.displayDurationMs, itemId: entry.id)
        PhoneFilesNowPlaying.shared.itemID = entry.media.id
        cursor = index
        sent.insert(entry.id)
        served[entry.id] = media
        active = true
        publish()
        attachTask?.cancel()
        attachTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            await self?.fallbackIfIdsIgnored(session: session)
        }
        return true
    }

    /// A receiver that keeps sender item ids reports the first item under its id. One that
    /// doesn't never will: queue what's left (up to its limit) one by one instead.
    private func fallbackIfIdsIgnored(session: Int) async {
        guard session == generation, playbackId == nil, let vm, let library,
              vm.coordinator.playlist?.playbackId != nil else { return }
        let rest = Array(entries.dropFirst(cursor + 1).prefix(PhoneQueueWindow.tvLimit - 1))
        attachTask = nil // This task: clearing must not cancel it.
        clear()
        _ = await appendToForeignQueue(rest, vm: vm, library: library)
    }

    private func appendToForeignQueue(_ list: [Entry], vm: ConnectionViewModel, library: PhoneMediaLibrary) async -> Outcome {
        for entry in list {
            guard let pid = vm.coordinator.playlist?.playbackId,
                  let url = try? await library.resolve(entry.media),
                  let media = await LocalFileServer.shared.add(fileURL: url),
                  await vm.queueLocalMedia(url: media, title: entry.media.title, contentType: LocalFileServer.mimeType(for: url),
                                           displayDurationMs: entry.displayDurationMs, itemId: entry.id, playbackId: pid)?.ok == true
            else { return .failed("Couldn’t add \(entry.media.title).") }
        }
        return .added
    }

    private func onStatus(_ status: PlaylistUiState?) {
        guard let status, !entries.isEmpty else { return }
        let ids = status.items.compactMap(\.itemId)
        if playbackId == nil {
            // The new playback is ours once the receiver lists one of our items.
            guard ids.contains(where: { byId[$0] != nil }) else { return }
            playbackId = status.playbackId
        } else if status.playbackId != playbackId {
            clear()
            return
        }
        latest = status
        scheduleUpdate()
    }

    private func scheduleUpdate() {
        settleTask?.cancel()
        settleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard !Task.isCancelled else { return }
            await self?.update()
        }
    }

    private func update() async {
        if updating { pendingUpdate = true; return }
        updating = true
        defer {
            updating = false
            if pendingUpdate { pendingUpdate = false; scheduleUpdate() }
        }
        guard let status = latest, let pid = playbackId, status.queueRevision >= awaitRevision,
              let current = status.currentItemId, jumpingTo == nil || jumpingTo == current,
              let at = entries.firstIndex(where: { $0.id == current }) else { return }
        jumpingTo = nil
        let tv = status.items.compactMap(\.itemId)
        cursor = at
        if !dirty {
            let adopted = PhoneQueueWindow.adopt(order: entries.map(\.id), cursor: cursor, sent: sent, tv: tv, tvCurrent: current)
            if adopted != entries.map(\.id) {
                let kept = Set(adopted)
                for entry in entries where !kept.contains(entry.id) {
                    byId[entry.id] = nil
                    sent.remove(entry.id)
                }
                entries = adopted.compactMap { byId[$0] }
                cursor = entries.firstIndex { $0.id == current } ?? cursor
            }
        }
        dirty = false
        publish()
        let onTv = Set(tv)
        pendingRemoves.removeAll { !onTv.contains($0) }
        var plan = PhoneQueueWindow.plan(order: entries.map(\.id), cursor: cursor, tv: tv, tvCurrent: current, limits: limits)
        let extra = pendingRemoves.filter { !plan.removes.contains($0) }
        plan.removes = extra + plan.removes
        guard !plan.isEmpty else { return }
        if await !apply(plan, playbackId: pid, session: generation) {
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                self?.scheduleUpdate()
            }
        }
    }

    /// Photos videos are slow to export: keep fewer of them prepared ahead.
    private var limits: PhoneQueueWindow.Limits {
        let upcoming = entries.dropFirst(cursor + 1).prefix(20)
        let heavy = upcoming.contains { $0.media.source == .photos && $0.media.kind == .video }
        return heavy ? PhoneQueueWindow.Limits(ahead: 2, aheadLow: 1) : PhoneQueueWindow.Limits()
    }

    /// Sends `plan`: removes first, then adds, then moves. False if a step went unconfirmed.
    private func apply(_ plan: WindowPlan, playbackId pid: String, session: Int) async -> Bool {
        guard let vm, let library else { return false }
        func ok(_ result: QueueCommandResult?) -> Bool {
            guard let result, session == generation else { return false }
            if result.error == "stale_playback" || result.error == "no_active_playback" { clear(); return false }
            if let revision = result.queueRevision { awaitRevision = revision }
            return result.ok
        }
        if !plan.removes.isEmpty {
            let requestID = UUID().uuidString
            guard ok(await vm.sendConfirmedQueueCommand(
                WireProtocol.queueRemove(itemIds: plan.removes, playbackId: pid, requestID: requestID), requestID: requestID))
            else { return false }
            pendingRemoves.removeAll { plan.removes.contains($0) }
            for id in plan.removes {
                sent.remove(id)
                if let url = served.removeValue(forKey: id) { LocalFileServer.shared.remove(served: url) }
            }
        }
        for id in plan.adds {
            guard session == generation, let entry = byId[id] else { return false }
            guard let url = try? await library.resolve(entry.media),
                  let media = await LocalFileServer.shared.add(fileURL: url) else { return false }
            guard ok(await vm.queueLocalMedia(url: media, title: entry.media.title, contentType: LocalFileServer.mimeType(for: url),
                                              displayDurationMs: entry.displayDurationMs, itemId: id, playbackId: pid))
            else { LocalFileServer.shared.remove(served: media); return false }
            sent.insert(id)
            served[id] = media
        }
        for move in plan.moves {
            let requestID = UUID().uuidString
            guard ok(await vm.sendConfirmedQueueCommand(
                WireProtocol.queueMove(itemId: move.itemId, beforeItemId: move.before, playbackId: pid, requestID: requestID),
                requestID: requestID)) else { return false }
        }
        return true
    }

    private func clear() {
        attachTask?.cancel()
        settleTask?.cancel()
        entries = []
        byId = [:]
        sent = []
        served = [:]
        cursor = 0
        playbackId = nil
        latest = nil
        dirty = false
        jumpingTo = nil
        pendingRemoves = []
        awaitRevision = 0
        generation += 1
        active = false
        publish()
    }

    private func publish() {
        position = cursor
        count = entries.count
        if items.count != entries.count || items.map(\.id) != entries.map(\.id) { items = entries }
        if entries.isEmpty { active = false }
    }
}
