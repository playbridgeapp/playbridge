import Foundation

/// Sends phone files to a PlayBridge receiver as a queue (Play all, Play next, Add to queue,
/// slideshows). Photos items must be exported before they can be served, so a long list is
/// fed lazily: the first item plays at once and each later Photos item is prepared while the
/// one before it plays. Imported files need no export and are queued straight away.
@MainActor final class PhoneQueueCoordinator: ObservableObject {
    static let shared = PhoneQueueCoordinator()

    enum Mode { case replace, next, end }
    enum Outcome: Equatable { case playing, added, addedAtEnd, failed(String) }

    /// Items still waiting to be prepared and queued; drives the "keep PlayBridge running" note.
    @Published private(set) var pending: [PhoneMedia] = []
    private var feeder: Task<Void, Never>?
    private var displayDurationMs: Int?

    /// Photos items still need this iPhone: iOS may close PlayBridge before they are sent.
    var needsAppRunning: Bool { pending.contains { $0.source == .photos } }

    func send(_ items: [PhoneMedia], mode: Mode, displayDurationMs: Int? = nil,
              library: PhoneMediaLibrary, vm: ConnectionViewModel) async -> Outcome {
        guard let first = items.first else { return .failed("Nothing to play.") }
        guard vm.localQueueSupported else { return .failed("This receiver plays one item at a time.") }
        let playing = vm.coordinator.activeContext == "player" && vm.coordinator.playlist != nil
        if mode == .replace || !playing {
            stop()
            self.displayDurationMs = displayDurationMs
            if let failure = await PhoneMediaCasting.cast(first, displayDurationMs: duration(for: first),
                                                           library: library, vm: vm) {
                return .failed(failure)
            }
            pending = Array(items.dropFirst())
            if !pending.isEmpty { startFeeder(library: library, vm: vm) }
            return .playing
        }
        if mode == .end, feeder != nil {
            // Keep the order: these go after what the running feeder still has to send.
            pending.append(contentsOf: items)
            return .added
        }
        let before = Set(vm.coordinator.playlist?.items.compactMap(\.itemId) ?? [])
        for item in items {
            guard await queue(item, library: library, vm: vm) else { return .failed("Couldn’t prepare \(item.title).") }
        }
        guard mode == .next else { return .added }
        guard vm.localQueueReorders else { return .addedAtEnd }
        // Wait for the receiver to list the new items, then move them after the current one.
        var newIDs: [String] = []
        for _ in 0..<20 {
            newIDs = (vm.coordinator.playlist?.items.compactMap(\.itemId) ?? []).filter { !before.contains($0) }
            if newIDs.count >= items.count { break }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        guard newIDs.count >= items.count, let playlist = vm.coordinator.playlist else { return .addedAtEnd }
        var order = playlist.items.compactMap(\.itemId)
        let current = playlist.currentItemId ?? playlist.items.first { $0.index == playlist.currentIndex }?.itemId
        for id in PhoneFilesModel.playNextMoveOrder(Array(newIDs.suffix(items.count))) {
            guard let current, let position = order.firstIndex(of: current) else { return .addedAtEnd }
            let before = order[(position + 1)...].first { $0 != id }
            vm.moveInQueue(itemId: id, beforeItemId: before)
            // Track the moves locally: the receiver's echo may lag behind the next move.
            order.removeAll { $0 == id }
            order.insert(id, at: (order.firstIndex(of: current) ?? position) + 1)
        }
        return .added
    }

    func stop() {
        feeder?.cancel()
        feeder = nil
        pending = []
    }

    private func duration(for item: PhoneMedia) -> Int? { item.kind == .image ? displayDurationMs : nil }

    /// Resolves (exports) one item, serves it next to the others and appends it.
    private func queue(_ item: PhoneMedia, library: PhoneMediaLibrary, vm: ConnectionViewModel) async -> Bool {
        guard let url = try? await library.resolve(item), let served = await LocalFileServer.shared.add(fileURL: url) else { return false }
        return vm.queueLocalMedia(url: served, title: item.title, contentType: LocalFileServer.mimeType(for: url),
                                  displayDurationMs: duration(for: item))
    }

    private func startFeeder(library: PhoneMediaLibrary, vm: ConnectionViewModel) {
        feeder = Task { [weak self] in
            var sent = 1
            var playbackID: String?
            while let self, !Task.isCancelled, let next = self.pending.first {
                if next.source == .photos {
                    // Keep one prepared item ahead of the one playing.
                    while !Task.isCancelled {
                        guard vm.isConnected, let playlist = vm.coordinator.playlist else {
                            try? await Task.sleep(nanoseconds: 500_000_000); continue
                        }
                        if playbackID == nil { playbackID = playlist.playbackId }
                        // Something else started playing: this queue is over.
                        if playlist.playbackId != playbackID { self.stop(); return }
                        if PhoneFilesModel.shouldPrepareNext(currentIndex: playlist.currentIndex, sentCount: sent) { break }
                        try? await Task.sleep(nanoseconds: 500_000_000)
                    }
                }
                guard !Task.isCancelled else { return }
                if self.pending.first == next { self.pending.removeFirst() }
                if await self.queue(next, library: library, vm: vm) { sent += 1 }
            }
            self?.feeder = nil
        }
    }
}
