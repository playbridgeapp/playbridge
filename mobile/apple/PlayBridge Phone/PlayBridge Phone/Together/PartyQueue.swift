import Foundation

/// One video waiting in this phone's party queue (Up next).
///
/// Kept only in memory for the current party. Headers can hold sign-in cookies: they are
/// never logged or saved, and only this phone's own devices get them when the item loads.
struct PartyQueueItem: Identifiable, Equatable, CustomStringConvertible {
    let id: String
    let url: String
    let title: String
    let site: String?
    let headers: [String: String]
    let subtitles: [String]
    let startMs: Int64

    init(
        id: String = UUID().uuidString,
        url: String,
        title: String,
        site: String? = nil,
        headers: [String: String] = [:],
        subtitles: [String] = [],
        startMs: Int64 = 0
    ) {
        self.id = id
        self.url = url
        self.title = title
        self.site = site
        self.headers = headers
        self.subtitles = subtitles
        self.startMs = max(0, startMs)
    }

    /// Builds an item from a link, using the link's host when there's no title.
    static func of(
        url: String,
        title: String?,
        headers: [String: String] = [:],
        subtitles: [String] = [],
        startMs: Int64 = 0
    ) -> PartyQueueItem {
        let trimmedURL = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let site = URL(string: trimmedURL)?.host.map { $0.hasPrefix("www.") ? String($0.dropFirst(4)) : $0 }
            .flatMap { $0.isEmpty ? nil : $0 }
        let cleanTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        return PartyQueueItem(
            url: trimmedURL,
            title: (cleanTitle?.isEmpty == false ? cleanTitle : nil) ?? site ?? "Video",
            site: site,
            headers: headers,
            subtitles: subtitles,
            startMs: startMs
        )
    }

    var togetherItem: TogetherItem {
        TogetherItem(title: title, site: site, source: .url(url), headers: headers,
                     subtitles: subtitles, startMs: startMs)
    }

    /// Headers stay out of descriptions.
    var description: String { "PartyQueueItem(id=\(id), site=\(site ?? "-"))" }
}

/// The ordered Up next list for the current party, held by the phone that queued it.
/// Not shared with the room and not kept across app restarts.
struct PartyQueue: Equatable {
    private(set) var items: [PartyQueueItem] = []

    var isEmpty: Bool { items.isEmpty }
    var count: Int { items.count }
    var first: PartyQueueItem? { items.first }

    mutating func append(_ newItems: [PartyQueueItem]) { items.append(contentsOf: newItems) }

    @discardableResult
    mutating func remove(_ id: String) -> PartyQueueItem? {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return nil }
        return items.remove(at: index)
    }

    /// Moves the item at `from` so it ends up at `to`. False when either index is out of range.
    @discardableResult
    mutating func move(from: Int, to: Int) -> Bool {
        guard items.indices.contains(from), items.indices.contains(to), from != to else { return false }
        let item = items.remove(at: from)
        items.insert(item, at: to)
        return true
    }

    mutating func takeNext() -> PartyQueueItem? { items.isEmpty ? nil : items.removeFirst() }

    mutating func clear() { items.removeAll() }
}

enum PartyAdvanceDecision: Equatable {
    case none
    /// The room said `item` ended; wait a moment for the party to clear it before loading.
    case waitForClear(item: String)
    case load(PartyQueueItem)
    case paused(reason: String)
}

/// Decides when Up next moves on. A pure state machine: `PhoneTogether` owns the timer.
///
/// - Moves on only after the current video really ends, never on a clear nobody asked
///   this phone for (that is someone pressing Stop).
/// - After an end, waits for the party's automatic clear, or for `graceElapsed` when no
///   clear comes, so its load can't cross with another device's clear.
/// - A new video, a Stop or a reset cancels a pending move.
/// - A queued video that fails to load pauses Up next instead of skipping the rest.
struct PartyAdvanceTracker {
    static let pausedOnFailure = "Couldn't load the next queued video. Up next is paused."

    private var activeItem: String?
    private var consumedEnded: Set<String> = []
    private var pendingEndItem: String?
    private var manualStopPending = false
    private var manualStopItem: String?
    private var awaitingQueueLoad = false
    private var lastLoadedFromQueue: String?
    private(set) var haltedOnFailure = false

    var isWaitingForClear: Bool { pendingEndItem != nil }

    mutating func reset() { self = PartyAdvanceTracker() }

    /// The user played something, or picked a queued item: clears pauses and pending moves.
    mutating func explicitLoadSent(fromQueue: Bool) {
        manualStopPending = false
        manualStopItem = nil
        pendingEndItem = nil
        haltedOnFailure = false
        awaitingQueueLoad = fromQueue
        if !fromQueue { lastLoadedFromQueue = nil }
    }

    mutating func manualStopRequested(currentItem: String?) {
        manualStopPending = true
        manualStopItem = currentItem ?? activeItem ?? pendingEndItem
        pendingEndItem = nil
        awaitingQueueLoad = false
    }

    mutating func manualStopSendFailed() {
        manualStopPending = false
        manualStopItem = nil
    }

    /// A queued load failed, either before the room took it or with an error afterwards.
    mutating func queuedLoadFailed() -> PartyAdvanceDecision {
        awaitingQueueLoad = false
        pendingEndItem = nil
        haltedOnFailure = true
        if let activeItem { consumedEnded.insert(activeItem) }
        if let lastLoadedFromQueue { consumedEnded.insert(lastLoadedFromQueue) }
        return .paused(reason: Self.pausedOnFailure)
    }

    mutating func onEvent(_ event: TogetherEvent, party: TogetherPartyState, queue: inout PartyQueue) -> PartyAdvanceDecision {
        switch event {
        case .video(let item):
            pendingEndItem = nil
            activeItem = item.id
            if item.id != manualStopItem {
                manualStopPending = false
                manualStopItem = nil
            }
            if awaitingQueueLoad {
                awaitingQueueLoad = false
                lastLoadedFromQueue = item.id
                haltedOnFailure = false
            } else {
                lastLoadedFromQueue = nil
            }
            return .none

        case .ended(let item):
            let current = party.item?.id ?? activeItem
            guard let current, item == current else { return .none }
            if consumedEnded.contains(item) || item == pendingEndItem { return .none }
            if manualStopPending && (manualStopItem == nil || manualStopItem == item) {
                consumedEnded.insert(item)
                pendingEndItem = nil
                manualStopPending = false
                manualStopItem = nil
                activeItem = nil
                return .none
            }
            consumedEnded.insert(item)
            activeItem = nil
            if haltedOnFailure || queue.isEmpty {
                pendingEndItem = nil
                return .none
            }
            pendingEndItem = item
            return .waitForClear(item: item)

        case .videoCleared:
            let pending = pendingEndItem
            pendingEndItem = nil
            let current = party.item?.id ?? activeItem
            activeItem = nil
            if manualStopPending {
                if let id = current ?? pending { consumedEnded.insert(id) }
                manualStopPending = false
                manualStopItem = nil
                return .none
            }
            return pending != nil ? decideAdvance(party: party, queue: &queue) : .none

        case .error(let context, _, _):
            if awaitingQueueLoad
                || ((context == "load" || context == "play") && lastLoadedFromQueue != nil
                    && lastLoadedFromQueue == party.item?.id) {
                return queuedLoadFailed()
            }
            return .none

        default:
            return .none
        }
    }

    /// No clear came within the grace period after an end.
    mutating func graceElapsed(party: TogetherPartyState, queue: inout PartyQueue) -> PartyAdvanceDecision {
        guard pendingEndItem != nil else { return .none }
        pendingEndItem = nil
        activeItem = nil
        return decideAdvance(party: party, queue: &queue)
    }

    /// The phone's own player failed on `item` before it started. True when that was a queued load.
    mutating func phonePlayerLoadFailed(item: String) -> Bool {
        let matches = awaitingQueueLoad || (lastLoadedFromQueue != nil && lastLoadedFromQueue == item)
        consumedEnded.insert(item)
        guard matches else { return false }
        awaitingQueueLoad = false
        pendingEndItem = nil
        haltedOnFailure = true
        return true
    }

    private mutating func decideAdvance(party: TogetherPartyState, queue: inout PartyQueue) -> PartyAdvanceDecision {
        if haltedOnFailure || queue.isEmpty { return .none }
        guard party.canChangeVideo else {
            if party.locked { return .paused(reason: "Up next paused: the host locked the controls.") }
            if party.policy == .onlyHost { return .paused(reason: "Up next paused: only the host can change the video.") }
            return .paused(reason: "Up next paused: you need the host to approve videos.")
        }
        guard let next = queue.takeNext() else { return .none }
        awaitingQueueLoad = true
        lastLoadedFromQueue = nil
        return .load(next)
    }
}
