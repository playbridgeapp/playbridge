import Combine
import Foundation

enum PartyLinkedPlayResult: Equatable {
    /// `askOnly`: this member can only ask the host, so nothing was queued.
    case ok(askOnly: Bool, queuedIds: [String])
    case error(String)
}

enum PartyLinkedEnqueueResult: Equatable {
    case ok([PartyQueueItem])
    case error(String)
}

/// The watch party as a website's linked session sees it. `PhoneTogether` in the app;
/// tests use their own.
@MainActor
protocol PartyLinkedGateway: AnyObject {
    var currentParty: TogetherPartyState? { get }
    var isPartySelected: Bool { get }
    var partyStates: AnyPublisher<PhoneTogetherState, Never> { get }
    var partyTargetChanges: AnyPublisher<Bool, Never> { get }
    var queueChanges: AnyPublisher<PartyQueueChange, Never> { get }
    func evaluateLink(_ url: String) -> PartyMediaURLCheck
    func playLinkedInParty(_ item: PartyQueueItem, queueItems: [PartyQueueItem],
                           replaceQueueIds: Set<String>) -> PartyLinkedPlayResult
    func enqueueLinkedItems(_ items: [PartyQueueItem]) -> PartyLinkedEnqueueResult
    func playQueuedItemNow(_ id: String) -> Bool
    func replayLinkedItem(_ item: PartyQueueItem) -> PartyLinkedPlayResult
    func partyNowMs() -> Int64
}

extension PartyQueueItem {
    /// A checked website item (see `PageCastRequest.parseItems`) as a party video.
    /// Subtitles other members can't open are left out.
    static func fromPageItem(_ item: [String: Any]) -> PartyQueueItem? {
        guard let url = item["url"] as? String else { return nil }
        let subtitles = (item["subtitles"] as? [String] ?? [])
            + (item["subtitleResources"] as? [[String: Any]] ?? []).compactMap { $0["url"] as? String }
        return PartyQueueItem.of(url: url, title: item["title"] as? String,
                                 headers: item["headers"] as? [String: String] ?? [:],
                                 subtitles: subtitles.filter { PartyMediaURL.evaluate($0).rejection == nil },
                                 startMs: (item["start_position_ms"] as? NSNumber)?.int64Value ?? 0)
    }
}

/// A website's linked session played in the watch party (port of Android's
/// `WatchPartyLinkedSessionSink`). The first video plays and the rest go to Up next; it
/// tracks which Up next videos belong to the website, follows the party's state, and
/// handles "Ask me" parties, losing permission, and the user editing or clearing Up next.
@MainActor
final class PartyLinkedSession: PageCastPartyLink {
    private struct Entry: Equatable {
        let linkedId: String
        let item: PartyQueueItem
    }

    private let gateway: PartyLinkedGateway
    private var played: [Entry] = []
    private var current: Entry?
    private var upcoming: [Entry] = []
    private var upcomingByQueueId: [String: Entry] = [:]
    private var admittedIds: Set<String> = []
    private var openingRoomId: String?
    private var latestParty: TogetherPartyState?
    private var awaitingVideoEcho = false
    private var hasSeenRoomVideo = false
    private var askOnlyMode = false
    private var queueClearedByUser = false
    private var closed = false
    private var subscriptions = Set<AnyCancellable>()

    var onChange: ((Bool) -> Void)?
    var onFinish: ((String) -> Void)?

    private init(gateway: PartyLinkedGateway) {
        self.gateway = gateway
    }

    /// Plays `request` in the party from its start index. Throws `PageCastError` with the
    /// website's error code when the party refuses it.
    static func start(_ request: PageCastRequest, gateway: PartyLinkedGateway) throws -> PartyLinkedSession {
        guard let party = gateway.currentParty else { throw PageCastError(code: "receiver_stopped") }
        guard gateway.isPartySelected else { throw PageCastError(code: "receiver_changed") }
        if party.locked || (party.policy == .onlyHost && !party.host) { throw PageCastError(code: "not_allowed") }
        guard request.items.indices.contains(request.startIndex),
              let linkedId = request.items[request.startIndex]["id"] as? String,
              let first = PartyQueueItem.fromPageItem(request.items[request.startIndex]) else {
            throw PageCastError(code: "invalid_request")
        }
        guard gateway.evaluateLink(first.url).rejection == nil else { throw PageCastError(code: "unsupported_target") }
        let session = PartyLinkedSession(gateway: gateway)
        let rest = party.canChangeVideo ? session.entries(Array(request.items.dropFirst(request.startIndex + 1))) : []
        switch gateway.playLinkedInParty(first, queueItems: rest.map(\.item), replaceQueueIds: []) {
        case .error(let code):
            throw PageCastError(code: code)
        case .ok(let askOnly, let queuedIds):
            session.openingRoomId = party.roomId
            session.latestParty = party
            session.adopt(first: Entry(linkedId: linkedId, item: first), rest: rest, askOnly: askOnly, queuedIds: queuedIds)
            session.observe()
            return session
        }
    }

    var knownIds: Set<String> { admittedIds }

    var isDemandSuppressed: Bool { askOnlyMode || queueClearedByUser }

    func validateAvailable() -> PageCastPartyFailure? {
        guard let party = gateway.currentParty, openingRoomId == nil || party.roomId == openingRoomId else {
            return PageCastPartyFailure(code: "receiver_stopped", endSession: "receiver_stopped")
        }
        guard gateway.isPartySelected else {
            return PageCastPartyFailure(code: "receiver_changed", endSession: "receiver_changed")
        }
        if Self.lostPermissionForGood(party) {
            return PageCastPartyFailure(code: "not_allowed", endSession: "not_allowed")
        }
        return nil
    }

    func append(_ items: [[String: Any]]) -> PageCastPartyFailure? {
        add(items, clearNeedWhenNoneFit: false)
    }

    func supply(_ items: [[String: Any]]) -> PageCastPartyFailure? {
        add(items, clearNeedWhenNoneFit: true)
    }

    func replace(_ items: [[String: Any]], startIndex: Int) -> PageCastPartyFailure? {
        if let failure = validateAvailable() { return failure }
        guard let party = gateway.currentParty else { return PageCastPartyFailure(code: "receiver_stopped") }
        if party.locked { return PageCastPartyFailure(code: "not_allowed") }
        if !party.canChangeVideo && items.count > 1 { return PageCastPartyFailure(code: "unsupported_queue") }
        guard items.indices.contains(startIndex), let linkedId = items[startIndex]["id"] as? String,
              let first = PartyQueueItem.fromPageItem(items[startIndex]) else {
            return PageCastPartyFailure(code: "invalid_request")
        }
        guard gateway.evaluateLink(first.url).rejection == nil else { return PageCastPartyFailure(code: "unsupported_target") }
        let rest = party.canChangeVideo ? entries(Array(items.dropFirst(startIndex + 1))) : []
        switch gateway.playLinkedInParty(first, queueItems: rest.map(\.item), replaceQueueIds: Set(upcomingByQueueId.keys)) {
        case .error(let code):
            return PageCastPartyFailure(code: code)
        case .ok(let askOnly, let queuedIds):
            adopt(first: Entry(linkedId: linkedId, item: first), rest: rest, askOnly: askOnly, queuedIds: queuedIds)
            return nil
        }
    }

    func jump(_ index: Int) -> PageCastPartyFailure? {
        if let failure = validateAvailable() { return failure }
        guard let party = gateway.currentParty else { return PageCastPartyFailure(code: "receiver_stopped") }
        if party.locked { return PageCastPartyFailure(code: "not_allowed") }
        let ordered = orderedEntries()
        guard ordered.indices.contains(index) else { return PageCastPartyFailure(code: "invalid_request") }
        let target = ordered[index]
        if !party.canChangeVideo {
            guard index == currentIndex(in: ordered) else { return PageCastPartyFailure(code: "unsupported_queue") }
            if case .error(let code) = gateway.replayLinkedItem(target.item) { return PageCastPartyFailure(code: code) }
            awaitingVideoEcho = true
            return nil
        }
        let queueId = target.item.id
        if upcomingByQueueId[queueId] != nil {
            guard gateway.playQueuedItemNow(queueId) else { return PageCastPartyFailure(code: "connect_failed") }
            // The queue change arriving later finds nothing left to promote.
            let promoted = upcomingByQueueId.removeValue(forKey: queueId) ?? target
            upcoming.removeAll { $0.item.id == queueId }
            if let current { played.append(current) }
            current = promoted
            awaitingVideoEcho = true
            return nil
        }
        if case .error(let code) = gateway.replayLinkedItem(target.item) { return PageCastPartyFailure(code: code) }
        if target != current {
            played.removeAll { $0.item.id == target.item.id }
            if let current { played.append(current) }
            current = target
        }
        awaitingVideoEcho = true
        return nil
    }

    func snapshot() -> PageCastPartySnapshot {
        let party = latestParty ?? gateway.currentParty
        let ordered = orderedEntries()
        let roomItem = party?.item
        let state: String
        if awaitingVideoEcho { state = "connecting" }
        else if roomItem != nil { state = party?.playing == true ? "playing" : "paused" }
        else if hasSeenRoomVideo && upcoming.isEmpty { state = "ended" }
        else { state = "connecting" }
        let showingRoom = !awaitingVideoEcho && roomItem != nil
        return PageCastPartySnapshot(
            state: state,
            positionMs: showingRoom ? party?.positionNow(atMs: gateway.partyNowMs()) ?? 0 : 0,
            durationMs: showingRoom ? party?.durationMs ?? 0 : 0,
            title: showingRoom ? roomItem?.title : current?.item.title,
            currentIndex: currentIndex(in: ordered),
            items: ordered.map { PageCastPartySnapshot.Item(id: $0.linkedId, title: $0.item.title) }
        )
    }

    func close() {
        closed = true
        subscriptions.removeAll()
        onChange = nil
        onFinish = nil
    }

    // MARK: - Private

    private func add(_ items: [[String: Any]], clearNeedWhenNoneFit: Bool) -> PageCastPartyFailure? {
        if let failure = operationRefusal() { return failure }
        let valid = entries(items)
        guard !valid.isEmpty else { return PageCastPartyFailure(code: "unsupported_target", clearNeed: clearNeedWhenNoneFit) }
        switch gateway.enqueueLinkedItems(valid.map(\.item)) {
        case .error(let code):
            return PageCastPartyFailure(code: code)
        case .ok(let added):
            let addedIds = Set(added.map(\.id))
            for entry in valid where addedIds.contains(entry.item.id) {
                upcoming.append(entry)
                upcomingByQueueId[entry.item.id] = entry
                admittedIds.insert(entry.linkedId)
            }
            return nil
        }
    }

    private func adopt(first: Entry, rest: [Entry], askOnly: Bool, queuedIds: [String]) {
        played = []
        current = first
        upcoming = []
        upcomingByQueueId = [:]
        admittedIds = [first.linkedId]
        askOnlyMode = askOnly
        queueClearedByUser = false
        if !askOnly {
            let accepted = Set(queuedIds)
            upcoming = rest.filter { accepted.contains($0.item.id) }
            for entry in upcoming {
                upcomingByQueueId[entry.item.id] = entry
                admittedIds.insert(entry.linkedId)
            }
        }
        awaitingVideoEcho = true
        hasSeenRoomVideo = false
    }

    /// Changes arrive on the next main-queue turn, after the call that caused them
    /// has finished its own bookkeeping (as on Android).
    private func observe() {
        gateway.partyStates.receive(on: DispatchQueue.main).sink { [weak self] state in
            MainActor.assumeIsolated { self?.handle(state) }
        }.store(in: &subscriptions)
        gateway.partyTargetChanges.receive(on: DispatchQueue.main).sink { [weak self] selected in
            MainActor.assumeIsolated {
                guard let self, !self.closed, !selected else { return }
                self.onFinish?("receiver_changed")
            }
        }.store(in: &subscriptions)
        gateway.queueChanges.receive(on: DispatchQueue.main).sink { [weak self] change in
            MainActor.assumeIsolated { self?.handle(change) }
        }.store(in: &subscriptions)
    }

    private func handle(_ state: PhoneTogetherState) {
        guard !closed else { return }
        guard case .inParty(let party) = state else {
            if case .idle = state { onFinish?("receiver_stopped") }
            else if case .ended = state { onFinish?("receiver_stopped") }
            return
        }
        if let openingRoomId, party.roomId != openingRoomId { onFinish?("receiver_stopped"); return }
        if Self.lostPermissionForGood(party) { onFinish?("not_allowed"); return }
        let wasAskOnly = askOnlyMode
        latestParty = party
        askOnlyMode = !party.canChangeVideo
        if let roomItem = party.item {
            if awaitingVideoEcho, let current, Self.matches(roomItem, current.item) {
                awaitingVideoEcho = false
                hasSeenRoomVideo = true
            } else if !awaitingVideoEcho {
                hasSeenRoomVideo = true
            }
        }
        onChange?(wasAskOnly && !askOnlyMode && !queueClearedByUser)
    }

    private func handle(_ change: PartyQueueChange) {
        guard !closed else { return }
        switch change.update {
        case .manualPlay:
            onFinish?("superseded")
        case .loadedFromQueue(let item):
            if let promoted = upcomingByQueueId.removeValue(forKey: item.id) {
                if let current { played.append(current) }
                current = promoted
                awaitingVideoEcho = true
                sync(change.snapshot)
                onChange?(!isDemandSuppressed)
            } else {
                sync(change.snapshot)
                onChange?(false)
            }
        case .removed(let id):
            if upcomingByQueueId.removeValue(forKey: id) != nil {
                sync(change.snapshot)
                onChange?(false)
            }
        case .reordered:
            let before = upcoming.map(\.item.id)
            sync(change.snapshot)
            if upcoming.map(\.item.id) != before { onChange?(false) }
        case .cleared:
            let hadItems = !upcoming.isEmpty
            upcoming = []
            upcomingByQueueId = [:]
            queueClearedByUser = true
            if hadItems || current != nil { onChange?(false) }
        case .enqueued, .replacedSessionQueue:
            sync(change.snapshot)
        }
    }

    /// Keeps the website's waiting videos in Up next's order, dropping any that left it.
    private func sync(_ snapshot: [PartyQueueItem]) {
        upcoming = snapshot.compactMap { upcomingByQueueId[$0.id] }
        let live = Set(upcoming.map(\.item.id))
        upcomingByQueueId = upcomingByQueueId.filter { live.contains($0.key) }
    }

    private func orderedEntries() -> [Entry] {
        played + (current.map { [$0] } ?? []) + upcoming
    }

    private func currentIndex(in ordered: [Entry]) -> Int {
        guard let current else { return 0 }
        return ordered.firstIndex(of: current) ?? 0
    }

    private func operationRefusal() -> PageCastPartyFailure? {
        if let failure = validateAvailable() { return failure }
        guard let party = gateway.currentParty else { return PageCastPartyFailure(code: "receiver_stopped") }
        if party.locked { return PageCastPartyFailure(code: "not_allowed") }
        if !party.canChangeVideo { return PageCastPartyFailure(code: "unsupported_queue") }
        return nil
    }

    private func entries(_ items: [[String: Any]]) -> [Entry] {
        items.compactMap { raw in
            guard let id = raw["id"] as? String, let item = PartyQueueItem.fromPageItem(raw),
                  gateway.evaluateLink(item.url).rejection == nil else { return nil }
            return Entry(linkedId: id, item: item)
        }
    }

    private static func matches(_ roomItem: TogetherItem, _ item: PartyQueueItem) -> Bool {
        guard case .url(let url) = roomItem.source else { return false }
        return url == item.url && roomItem.title == item.title
    }

    /// A guest in a party only the host controls can't get permission back by waiting.
    private static func lostPermissionForGood(_ party: TogetherPartyState) -> Bool {
        !party.host && party.policy == .onlyHost
    }
}
