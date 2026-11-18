import Combine
import Foundation
#if canImport(UIKit)
import UIKit
#endif

protocol TogetherSessionProtocol: AnyObject {
    @discardableResult func host(relay: String, device: TogetherDevice) -> Bool
    @discardableResult func join(invite: String, device: TogetherDevice) -> Bool
    @discardableResult func rejoin(saved: String, device: TogetherDevice) -> Bool
    @discardableResult func pairRegister(pairing: String) -> Bool
    @discardableResult func pairAccept(device: TogetherDevice, guest: Bool) -> Bool
    @discardableResult func pairCancel() -> Bool
    @discardableResult func pairSend(pairing: String, target: String, id: String?) -> Bool
    @discardableResult func snapshot(positionMs: Int64, playing: Bool, buffering: Bool) -> Bool
    @discardableResult func loaded(item: String, durationMs: Int64?) -> Bool
    @discardableResult func load(item: TogetherItem) -> Bool
    @discardableResult func clear() -> Bool
    @discardableResult func requestPlay(item: TogetherItem) -> Bool
    @discardableResult func play() -> Bool
    @discardableResult func pause() -> Bool
    @discardableResult func seek(positionMs: Int64) -> Bool
    @discardableResult func lock(_ locked: Bool) -> Bool
    @discardableResult func policy(_ policy: TogetherPolicy) -> Bool
    @discardableResult func reply(request: String, allowed: Bool, always: Bool) -> Bool
    @discardableResult func addOwn(member: String, boxKey: String) -> Bool
    @discardableResult func continueWithout(member: String) -> Bool
    @discardableResult func shareCredentials(member: String) -> Bool
    @discardableResult func setScreen(_ screen: Bool) -> Bool
    @discardableResult func leave() -> Bool
    func close()
}

extension TogetherClient: TogetherSessionProtocol {}

/// Another party to go to, picked while this phone is in one: an invite link, or a saved
/// party to rejoin. Both hold secrets: never log them.
struct WatchTogetherSwitchRequest: Equatable, CustomStringConvertible {
    let invite: String?
    let watchHere: Bool
    var saved: SavedWatchParty? = nil

    var description: String {
        "WatchTogetherSwitchRequest(room=\(saved?.roomId ?? "-"), watchHere=\(watchHere))"
    }
}

struct WatchTogetherTvJoinResult: Equatable {
    let member: String
    let boxKey: String
    let signKey: String
}

struct WatchTogetherConnectedTv: Equatable {
    let name: String
    let uuid: String
    let endpointKey: String?

    init(name: String, uuid: String = "", endpointKey: String? = nil) {
        self.name = name
        self.uuid = uuid
        self.endpointKey = endpointKey
    }
}

@MainActor
protocol WatchTogetherTvBridge: AnyObject {
    var connectedPlayBridgeTvName: String? { get }
    var connectedPlayBridgeTvIdentity: WatchTogetherConnectedTv? { get }
    var tvSupportsWatchTogether: Bool { get }
    func addTvToWatchTogether(invite: String, from: String) async throws -> WatchTogetherTvJoinResult
}

extension WatchTogetherTvBridge {
    var connectedPlayBridgeTvIdentity: WatchTogetherConnectedTv? {
        connectedPlayBridgeTvName.map { WatchTogetherConnectedTv(name: $0) }
    }
}

struct TogetherPartyMember: Equatable {
    let id: String
    let name: String
    let kind: TogetherDeviceKind
    let host: Bool
}

struct TogetherPartyRequest: Equatable, Identifiable {
    let id: String
    let from: String
    let title: String
    let site: String?
}

struct TogetherPartyState: Equatable, CustomStringConvertible {
    let roomId: String
    let invite: String
    let relay: String
    let me: String
    let host: Bool
    /// True while this phone plays the video too; false turns the phone into a remote only.
    var watching: Bool = false
    var connected: Bool = false
    var reconnectFailed: Bool = false
    var members: [String: TogetherPartyMember] = [:]
    /// Devices added by this phone; only they receive sign-in headers automatically.
    var own: Set<String> = []
    var sharedSecretsForItem: Set<String> = []
    var item: TogetherItem? = nil
    var playing: Bool = false
    var positionMs: Int64 = 0
    var positionAtMs: Int64 = 0
    var durationMs: Int64? = nil
    var waitingFor: [String] = []
    var continueWithout: [String] = []
    var locked: Bool = false
    var policy: TogetherPolicy = .onlyHost
    var requests: [TogetherPartyRequest] = []
    var notice: String? = nil
    var live: Bool = false

    /// Invite keys stay out of string descriptions and logs.
    var description: String {
        "TogetherPartyState(room=\(roomId), relay=\(relay), host=\(host), watching=\(watching))"
    }

    var canChangeVideo: Bool {
        host || (policy == .anyone && !locked)
    }

    var orderedMembers: [TogetherPartyMember] {
        members.values.sorted { lhs, rhs in
            if lhs.host != rhs.host { return lhs.host && !rhs.host }
            if (lhs.id == me) != (rhs.id == me) { return lhs.id == me }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    func positionNow(atMs nowMs: Int64 = PhoneTogether.monotonicMs()) -> Int64 {
        let elapsed = max(0, nowMs - positionAtMs)
        let raw = playing ? positionMs + elapsed : positionMs
        if let durationMs, durationMs > 0 {
            return min(durationMs, max(0, raw))
        }
        return max(0, raw)
    }
}

enum PhoneTogetherState: Equatable {
    case idle
    case starting
    case gettingCode
    case code(code: String, qrSize: Int, qrModules: String, expiresAtMs: Int64)
    case invited(from: String, relay: String)
    case inParty(TogetherPartyState)
    case ended(message: String)
}

struct PartyMediaURLCheck: Equatable {
    let normalizedURL: String?
    let rejection: String?
    let isPrivateNetwork: Bool
}

enum PartyMediaURL {
    private static let privateNotice = "Only people on this Wi-Fi can load this video."

    static var privateNetworkNotice: String { privateNotice }

    /// This phone's own addresses: links to them are served by this phone, and other
    /// members can't open them. The app sets it to the Wi-Fi address; tests set their own.
    static var ownAddresses: () -> Set<String> = { [] }

    static func evaluate(_ raw: String) -> PartyMediaURLCheck {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        func refused(_ reason: String) -> PartyMediaURLCheck {
            PartyMediaURLCheck(normalizedURL: nil, rejection: reason, isPrivateNetwork: false)
        }
        guard !trimmed.isEmpty else { return refused("This video doesn't have a playable link.") }
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased() else {
            return refused("This video's link isn't valid for a watch party.")
        }
        switch scheme {
        case "http", "https": break
        case "file": return refused("Files stored on this phone can't be played in a watch party.")
        case "data", "blob": return refused("This phone-only stream can't be shared with a watch party.")
        default: return refused("Only web video links (http/https) can be played in a watch party.")
        }
        guard let rawHost = url.host?.trimmingCharacters(in: .whitespacesAndNewlines), !rawHost.isEmpty else {
            return refused("This video's link isn't valid for a watch party.")
        }
        let host = normalized(rawHost)
        if isLoopbackHost(host) {
            return refused("Links to this phone itself can't be played in a watch party, because other devices can't reach them.")
        }
        if ownAddresses().contains(where: { normalized($0) == host }) {
            return refused("Links served by this phone can't be played in a watch party.")
        }
        return PartyMediaURLCheck(
            normalizedURL: url.absoluteString,
            rejection: nil,
            isPrivateNetwork: isPrivateHost(host)
        )
    }

    private static func normalized(_ host: String) -> String {
        var lower = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if let zone = lower.firstIndex(of: "%") { lower = String(lower[..<zone]) }
        return lower
    }

    static func isLoopbackHost(_ host: String) -> Bool {
        let lower = normalized(host)
        if lower == "localhost" || lower.hasSuffix(".localhost") { return true }
        if lower == "::1" || lower == "::" || lower == "0.0.0.0" { return true }
        let octets = lower.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        return octets.count == 4 && octets[0] == 127 && octets.allSatisfy { $0.map { (0...255).contains($0) } == true }
    }

    static func isPrivateHost(_ host: String) -> Bool {
        let lower = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if lower == "localhost" || lower.hasSuffix(".local") || lower == "::1" {
            return true
        }
        if lower.hasPrefix("fe80:") || lower.hasPrefix("fc") || lower.hasPrefix("fd") {
            return true
        }
        let octets = lower.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else {
            return false
        }
        switch (octets[0], octets[1]) {
        case (10, _), (127, _): return true
        case (169, 254): return true
        case (100, 64...127): return true
        case (172, 16...31): return true
        case (192, 168): return true
        default: return false
        }
    }
}

enum PhoneTogetherReducer {
    static func reduce(
        _ party: TogetherPartyState,
        event: TogetherEvent,
        nowMs: Int64
    ) -> TogetherPartyState {
        var next = party
        switch event {
        case .connected:
            next.connected = true
            next.reconnectFailed = false
            next.notice = nil
        case .disconnected:
            next.connected = false
            next.reconnectFailed = false
            next.notice = "Reconnecting…"
        case .unreachable:
            next.reconnectFailed = true
            next.notice = "Can't reach \(next.relay). Still trying…"
        case .member(let member, let name, let kind, let host):
            next.members[member] = TogetherPartyMember(id: member, name: name, kind: kind, host: host)
        case .memberLeft(let member):
            if member != next.me {
                next.members.removeValue(forKey: member)
            }
            next.own.remove(member)
            next.sharedSecretsForItem.remove(member)
        case .video(let item):
            next.item = item
            next.playing = false
            next.positionMs = item.startMs
            next.positionAtMs = nowMs
            next.durationMs = nil
            next.live = false
            next.waitingFor = []
            next.continueWithout = []
            next.sharedSecretsForItem = []
            next.notice = nil
        case .videoCleared:
            next = clearItem(next, nowMs: nowMs)
        case .ended(let item):
            if next.item?.id == item {
                next = clearItem(next, nowMs: nowMs)
            }
        case .timeline(_, let playing, let positionMs, _):
            next.playing = playing
            next.positionMs = positionMs
            next.positionAtMs = nowMs
        case .waiting(let members):
            next.waitingFor = members
        case .continueWithoutAvailable(let members):
            next.continueWithout = members
        case .locked(let locked):
            next.locked = locked
        case .policy(let policy):
            next.policy = policy
        case .playRequested(let request, let from, let title, let site):
            if !next.requests.contains(where: { $0.id == request }) {
                next.requests.append(TogetherPartyRequest(id: request, from: from, title: title, site: site))
            }
        case .requestExpired(let request):
            next.requests.removeAll { $0.id == request }
        case .warning(let message):
            next.notice = message
        case .live(let live):
            next.live = live
        case .duration(let durationMs):
            next.durationMs = durationMs
        case .room, .player, .memberJoined, .credentialsReceived, .requestAnswered, .pairSent,
             .pairingCode, .pairingInvite, .error, .stopped, .rejected:
            break
        }
        return next
    }

    static func clearItem(_ party: TogetherPartyState, nowMs: Int64) -> TogetherPartyState {
        var next = party
        next.item = nil
        next.playing = false
        next.positionMs = 0
        next.positionAtMs = nowMs
        next.durationMs = nil
        next.live = false
        next.waitingFor = []
        next.continueWithout = []
        next.sharedSecretsForItem = []
        next.notice = nil
        return next
    }
}

struct PartyPlaybackPresentation: Identifiable {
    let id = UUID()
    let session: PlaybackSession
    var itemID: String
    var title: String
}

/// Manages active Watch Together parties in memory only (no disk persistence across kill).
/// Never logs invite links, pairing codes, headers, or raw command JSON.
@MainActor
final class PhoneTogether: ObservableObject {
    static let shared = PhoneTogether()

    static let sharedRelayURL = "https://party.playbridge.app"
    static let defaultPairingURL = "https://pair.playbridge.app"

    private static let prefRelayKey = "watch_together_relay_url"
    private static let prefPairingKey = "watch_together_pairing_url"
    private static let reconnectGraceSeconds: UInt64 = 10
    private static let reconnectBannerDurationNs: UInt64 = 4_000_000_000

    @Published private(set) var state: PhoneTogetherState = .idle
    @Published private(set) var toastMessage: String?
    @Published private(set) var addingConnectedTv = false
    @Published private(set) var hasLocalSecretsForCurrentItem = false
    @Published private(set) var ownTvIdentities: [String: WatchTogetherConnectedTv] = [:]
    @Published var activePlayerItem: PartyPlaybackPresentation?
    @Published var pendingOpenScreen = false
    /// An invite to another party that arrived while this phone is in one.
    @Published private(set) var switchRequest: WatchTogetherSwitchRequest?
    /// Parties this phone can rejoin, newest first. Fills once `loadSavedParties()` ran.
    @Published private(set) var savedParties: [SavedWatchParty] = []
    /// The party is where casts go. Joining selects it; picking another device in Play on
    /// clears it, and the phone stays in the party.
    @Published private(set) var partyTargetSelected = false
    /// Up next: videos this phone queued for the party. Other members see only the current one.
    @Published private(set) var queue: [PartyQueueItem] = []
    /// Why Up next isn't moving on, when it paused.
    @Published private(set) var queueNotice: String?
    /// Every change to Up next, sent right after it happens. A website's linked session
    /// follows it to know which of its videos are still waiting.
    let queueUpdates = PassthroughSubject<PartyQueueChange, Never>()

    /// How long to wait after a video ends for the party's own clear before loading the next.
    static let endClearGraceNs: UInt64 = 1_500_000_000
    private var partyQueue = PartyQueue()
    private var advanceTracker = PartyAdvanceTracker()
    private var graceAdvanceTask: Task<Void, Never>?
    /// After the party's video ends or clears, the player stays up (paused) for a moment so
    /// a next video loads into it. Dismissing the full-screen player and presenting a new one
    /// straight after fails on iOS.
    private var holdingPlayer = false
    private var holdPlayerTask: Task<Void, Never>?
    /// This phone pressed Stop: close the player at once.
    private var stopRequestedHere = false
    static let playerHoldNs: UInt64 = 2_500_000_000
    static let playerHoldForQueueNs: UInt64 = 8_000_000_000

    /// Where saved parties are kept. Tests swap in memory so they never touch the Keychain.
    static var makeSavedBackend: () -> SavedWatchPartyBackend = { KeychainSavedWatchPartyBackend() }
    private var savedStoreInstance: SavedWatchPartyStore?
    private var savedStore: SavedWatchPartyStore {
        if let savedStoreInstance { return savedStoreInstance }
        let store = SavedWatchPartyStore(backend: Self.makeSavedBackend())
        store.onChange = { [weak self] parties in self?.savedParties = parties }
        savedStoreInstance = store
        return store
    }

    private let defaults: UserDefaults
    private let clockMs: () -> Int64
    private let sessionFactory: (@escaping (TogetherEvent) -> Void) throws -> TogetherSessionProtocol
    private let playerSessionFactory: (RoutedStream, String, Double) -> PlaybackSession

    private var client: TogetherSessionProtocol?
    private var roomId: String?
    private var watchOnJoin = false
    private var loadingItem: String?
    private var playerItem: String?
    private var loadedSent = false
    private var loadedReportedEarly = false
    private var localSecretsPendingItemTitle: String?
    private var localSecretsItemID: String?
    private var suppressUserDismissWatchStop = false
    private var playerStateCancellables = Set<AnyCancellable>()
    private var snapshotTimer: AnyCancellable?
    private var wasBackgrounded = false
    private var disconnectedSinceBackground = false
    private var reconnectNoticeTask: Task<Void, Never>?
    private var reconnectTimeoutTask: Task<Void, Never>?

    init(
        defaults: UserDefaults = .standard,
        clockMs: @escaping () -> Int64 = { PhoneTogether.monotonicMs() },
        sessionFactory: @escaping (@escaping (TogetherEvent) -> Void) throws -> TogetherSessionProtocol = { onEvent in
            try TogetherClient(onEvent: onEvent)
        },
        playerSessionFactory: ((RoutedStream, String, Double) -> PlaybackSession)? = nil
    ) {
        self.defaults = defaults
        self.clockMs = clockMs
        self.sessionFactory = sessionFactory
        self.playerSessionFactory = playerSessionFactory ?? { routed, title, resume in
            PlaybackSession(media: routed, route: .direct, title: title, initialResume: resume) { routed }
        }
    }

    nonisolated static func monotonicMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    var relayURL: String {
        let saved = defaults.string(forKey: Self.prefRelayKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (saved?.isEmpty == false) ? saved! : Self.sharedRelayURL
    }

    func setRelayURL(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(trimmed, forKey: Self.prefRelayKey)
        objectWillChange.send()
    }

    var pairingURL: String {
        let saved = defaults.string(forKey: Self.prefPairingKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (saved?.isEmpty == false) ? saved! : Self.defaultPairingURL
    }

    func setPairingURL(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(trimmed, forKey: Self.prefPairingKey)
        objectWillChange.send()
    }

    var isSharedRelay: Bool { relayURL == Self.sharedRelayURL }

    var isInParty: Bool {
        if case .inParty = state { return true }
        return false
    }

    /// In a party, with the party picked as the place casts go.
    var isPartySelected: Bool { isInParty && partyTargetSelected }

    func selectPartyTarget() {
        if isInParty { partyTargetSelected = true }
    }

    func deselectPartyTarget() {
        partyTargetSelected = false
    }

    var currentParty: TogetherPartyState? {
        if case .inParty(let party) = state { return party }
        return nil
    }

    var deviceDisplayName: String {
        #if canImport(UIKit)
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "iPhone" : name
        #else
        return Host.current().localizedName ?? "Phone"
        #endif
    }

    func watchHereByDefault(connectedTvName: String?) -> Bool {
        connectedTvName == nil
    }

    func consumeToast() {
        toastMessage = nil
    }

    func consumePendingOpenScreen() {
        pendingOpenScreen = false
    }

    // MARK: - Universal Link Entry

    @discardableResult
    func handleIncomingURL(_ url: URL, connectedTvName: String?) -> Bool {
        guard let invite = WatchTogetherLinks.invite(url.absoluteString) else {
            return false
        }
        openInvite(invite, watchHere: watchHereByDefault(connectedTvName: connectedTvName))
        pendingOpenScreen = true
        return true
    }

    /// Opens an invite link that came from outside the app. The link of the party this phone is
    /// in leaves it there: joining again would cost it its keys, and a host its host role.
    /// Another party's link waits for `confirmSwitch()`.
    func openInvite(_ invite: String, watchHere: Bool) {
        let roomId = WatchTogetherLinks.roomId(invite)
        // A party this phone was in comes back as the same member, so a host stays host.
        let saved = roomId.flatMap { savedStore.get($0) }
        if let party = currentParty {
            if let roomId, party.roomId == roomId { return }
            switchRequest = WatchTogetherSwitchRequest(invite: invite, watchHere: watchHere, saved: saved)
        } else if let saved {
            enterSaved(saved, watchHere: watchHere)
        } else {
            join(invite: invite, watchHere: watchHere)
        }
    }

    /// Leaves the current party and joins the one in the pending `switchRequest`.
    func confirmSwitch() {
        guard let request = switchRequest else { return }
        switchRequest = nil
        leave()
        if let saved = request.saved {
            enterSaved(saved, watchHere: request.watchHere)
        } else if let invite = request.invite {
            join(invite: invite, watchHere: request.watchHere)
        }
    }

    // MARK: - Saved parties

    /// Reads the saved parties and drops the ones that ended.
    func loadSavedParties() {
        savedStore.prune()
    }

    /// Comes back to `saved` as the same member: as host if this phone started it. In another
    /// party, asks first through `switchRequest`.
    func rejoin(_ saved: SavedWatchParty, watchHere: Bool) {
        if let party = currentParty {
            if party.roomId == saved.roomId { return }
            switchRequest = WatchTogetherSwitchRequest(invite: nil, watchHere: watchHere, saved: saved)
        } else {
            enterSaved(saved, watchHere: watchHere)
        }
    }

    /// Forgets a saved party; this phone can't rejoin it as the same member after that.
    func forgetSaved(_ roomId: String) {
        savedStore.remove(roomId)
    }

    private func enterSaved(_ saved: SavedWatchParty, watchHere: Bool) {
        guard let session = ensureClient() else { return }
        if isJoiningByCode { _ = session.pairCancel() }
        resetSessionTracking()
        state = .starting
        watchOnJoin = watchHere
        if !session.rejoin(saved: saved.saved, device: makeDevice(screen: watchHere)) {
            state = .ended(message: "Couldn't rejoin that party.")
        }
    }

    /// Keeps the party this phone just entered, so it can come back after the app is closed.
    private func rememberParty(roomId: String, relay: String, host: Bool, endsAtMs: Int64?, saved: String?) {
        guard let saved else { return }
        let previous = savedStore.get(roomId)
        let joinedAt = previous?.joinedAtMs ?? SavedWatchParties.nowMs()
        savedStore.put(SavedWatchParty(
            roomId: roomId,
            relay: relay,
            host: host,
            saved: saved,
            joinedAtMs: joinedAt,
            endsAtMs: SavedWatchParties.endsAt(roomEndsAtMs: endsAtMs, joinedAtMs: joinedAt),
            hostName: previous?.hostName,
            own: previous?.own ?? []
        ))
        // Back as the same member: the devices this phone added are still its own.
        guard let own = previous?.own, !own.isEmpty else { return }
        for device in own {
            _ = client?.addOwn(member: device.member, boxKey: device.boxKey)
        }
        updateParty { party in
            for device in own { party.own.insert(device.member) }
        }
    }

    func cancelSwitch() {
        switchRequest = nil
    }

    // MARK: - Lifecycle (No background audio keep-alive, reconnect cleanly on foreground)

    func applicationEnteredBackground() {
        guard isInParty else { return }
        wasBackgrounded = true
        reconnectTimeoutTask?.cancel()
        reconnectTimeoutTask = nil
        if currentParty?.connected == false {
            disconnectedSinceBackground = true
        }
    }

    func applicationBecameActive() {
        guard wasBackgrounded else { return }
        wasBackgrounded = false
        guard let party = currentParty else {
            disconnectedSinceBackground = false
            return
        }
        if !party.connected {
            disconnectedSinceBackground = true
            scheduleReconnectFailureTimer()
        }
    }

    // MARK: - Party Setup & Membership

    func startHosting(watchHere: Bool) {
        guard let session = ensureClient() else { return }
        if isJoiningByCode { _ = session.pairCancel() }
        resetSessionTracking()
        state = .starting
        watchOnJoin = watchHere
        if !session.host(relay: relayURL, device: makeDevice(screen: watchHere)) {
            state = .ended(message: "Couldn't start a watch party.")
        }
    }

    func join(invite: String, watchHere: Bool) {
        guard let session = ensureClient() else { return }
        let normalized = WatchTogetherLinks.invite(invite) ?? invite.trimmingCharacters(in: .whitespacesAndNewlines)
        if isJoiningByCode { _ = session.pairCancel() }
        resetSessionTracking()
        state = .starting
        watchOnJoin = watchHere
        if !session.join(invite: normalized, device: makeDevice(screen: watchHere)) {
            state = .ended(message: "That invite link isn't valid.")
        }
    }

    func showCode(watchHere: Bool) {
        guard !isInParty, let session = ensureClient() else { return }
        watchOnJoin = watchHere
        state = .gettingCode
        if !session.pairRegister(pairing: pairingURL) {
            state = .ended(message: "Couldn't get a code.")
        }
    }

    func cancelCode() {
        guard isJoiningByCode else { return }
        _ = client?.pairCancel()
        state = .idle
    }

    func acceptInvite() {
        guard case .invited = state else { return }
        state = .starting
        if client?.pairAccept(device: makeDevice(screen: watchOnJoin), guest: true) != true {
            state = .ended(message: "Couldn't join.")
        }
    }

    func leave() {
        _ = client?.leave()
        roomId = nil
        resetSessionTracking()
        clearQueueAndTarget()
        state = .idle
        closePlayerProgrammatically()
    }

    func dismissEnded() {
        if case .ended = state {
            state = .idle
        }
    }

    func setWatchHere(_ watch: Bool) {
        guard let party = currentParty, party.watching != watch else { return }
        guard client?.setScreen(watch) == true else { return }
        updateParty { $0.watching = watch }
        if !watch {
            closePlayerProgrammatically()
        }
    }

    func isConnectedTvInParty(using tvBridge: WatchTogetherTvBridge) -> Bool {
        guard let party = currentParty,
              let device = tvBridge.connectedPlayBridgeTvIdentity else { return false }
        return Self.isOwnTvMemberMatch(device: device, party: party, ownTvIdentities: ownTvIdentities)
    }

    static func isOwnTvMemberMatch(
        device: WatchTogetherConnectedTv,
        party: TogetherPartyState,
        ownTvIdentities: [String: WatchTogetherConnectedTv]
    ) -> Bool {
        party.own.contains { memberID in
            let member = party.members[memberID]
            if let member, member.kind != .tv, member.kind != .desktop { return false }
            if let recorded = ownTvIdentities[memberID] {
                if !recorded.uuid.isEmpty, !device.uuid.isEmpty {
                    return sameTvUUID(device.uuid, recorded.uuid)
                }
                if let recordedEndpoint = recorded.endpointKey, !recordedEndpoint.isEmpty {
                    return device.endpointKey == recordedEndpoint
                }
                return false
            } else {
                guard let member, member.kind == .tv || member.kind == .desktop else { return false }
                return !device.name.isEmpty && member.name == device.name
            }
        }
    }

    private static func sameTvUUID(_ lhs: String, _ rhs: String) -> Bool {
        func head(_ id: String) -> String {
            guard let range = id.range(of: "::") else { return id }
            let prefix = String(id[..<range.lowerBound])
            return prefix.isEmpty ? id : prefix
        }
        let left = head(lhs)
        let right = head(rhs)
        return !left.isEmpty && left == right
    }

    func addConnectedTv(using tvBridge: WatchTogetherTvBridge) {
        guard let party = currentParty, tvBridge.tvSupportsWatchTogether, !addingConnectedTv else { return }
        addingConnectedTv = true
        let invite = party.invite
        let fromName = deviceDisplayName
        let connectedIdentity = tvBridge.connectedPlayBridgeTvIdentity
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.addingConnectedTv = false }
            do {
                let tv = try await tvBridge.addTvToWatchTogether(invite: invite, from: fromName)
                guard let latest = self.currentParty, latest.roomId == party.roomId else { return }
                _ = self.client?.addOwn(member: tv.member, boxKey: tv.boxKey)
                self.savedStore.update(party.roomId) { saved in
                    saved.own.removeAll { $0.member == tv.member }
                    saved.own.append(SavedOwnDevice(member: tv.member, boxKey: tv.boxKey))
                }
                if let connectedIdentity {
                    self.ownTvIdentities[tv.member] = connectedIdentity
                }
                self.updateParty { $0.own.insert(tv.member) }
                let label = connectedIdentity?.name ?? tvBridge.connectedPlayBridgeTvName ?? "That device"
                self.showToast("\(label) joined the party")
            } catch {
                self.showToast(error.localizedDescription)
            }
        }
    }

    func addByCode(_ target: String) {
        guard isInParty else { return }
        let trimmed = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        _ = client?.pairSend(pairing: pairingURL, target: trimmed, id: nil)
    }

    // MARK: - Media & Playback Controls

    /// Plays a video in the party: changes it when allowed, otherwise asks the host.
    /// `queueItems` (the rest of a playlist) go to Up next in order. `headers` may hold
    /// sign-in headers; only this phone's own devices get them.
    @discardableResult
    func playInParty(
        url: String,
        title: String? = nil,
        headers: [String: String] = [:],
        subtitles: [String] = [],
        startMs: Int64 = 0,
        queueItems: [PartyQueueItem] = []
    ) -> Bool {
        guard let party = currentParty else { return false }
        let check = PartyMediaURL.evaluate(url)
        guard let validURL = check.normalizedURL else {
            showToast(check.rejection ?? "This video can't be played in a watch party.")
            return false
        }
        let queued = PartyQueueItem.of(url: validURL, title: title, headers: headers,
                                       subtitles: subtitles, startMs: startMs)
        let item = queued.togetherItem
        if party.canChangeVideo {
            advanceTracker.explicitLoadSent(fromQueue: false)
            guard sendLoad(item) else {
                showToast("Couldn't send video to the watch party")
                return false
            }
            emitQueue(.manualPlay)
            queueNotice = nil
            let checked = queueItems.map { ($0, PartyMediaURL.evaluate($0.url)) }.filter { $0.1.rejection == nil }
            let anyPrivate = check.isPrivateNetwork || checked.contains { $0.1.isPrivateNetwork }
            if checked.isEmpty {
                showToast(anyPrivate ? PartyMediaURL.privateNetworkNotice : "Playing in the watch party")
            } else {
                partyQueue.append(checked.map(\.0))
                syncQueue()
                emitQueue(.enqueued(checked.map(\.0.id)))
                showToast(anyPrivate ? PartyMediaURL.privateNetworkNotice
                          : "Playing in the watch party · \(checked.count) up next")
            }
            return true
        } else if party.locked {
            showToast("The host locked the controls")
            return false
        } else if party.policy == .onlyHost {
            showToast("Only the host can change the video")
            return false
        } else {
            if !item.headers.isEmpty {
                localSecretsPendingItemTitle = item.title
            }
            let requested = client?.requestPlay(item: item) == true
            if !requested {
                localSecretsPendingItemTitle = nil
                showToast("Couldn't ask the host to play this video")
                return false
            }
            emitQueue(.manualPlay)
            showToast(check.isPrivateNetwork ? PartyMediaURL.privateNetworkNotice : "Asked the host to play it")
            return true
        }
    }

    /// Loads `item` for the whole party, remembering whether it carries this phone's sign-in.
    private func sendLoad(_ item: TogetherItem) -> Bool {
        if !item.headers.isEmpty { localSecretsPendingItemTitle = item.title }
        guard client?.load(item: item) == true else {
            localSecretsPendingItemTitle = nil
            return false
        }
        return true
    }

    /// Sends videos cast elsewhere in the app: the first plays and the rest go to Up next,
    /// or with `queue` all of them go to Up next.
    @discardableResult
    func send(_ items: [PartyQueueItem], queue: Bool) -> Bool {
        guard let first = items.first else {
            showToast("This playlist doesn't contain playable links.")
            return false
        }
        if queue { return enqueue(items) }
        return playInParty(url: first.url, title: first.title, headers: first.headers,
                           subtitles: first.subtitles, startMs: first.startMs,
                           queueItems: Array(items.dropFirst()))
    }

    /// Shows a message about the party, from anywhere in the app.
    func notify(_ message: String) { showToast(message) }

    // MARK: - Up next

    /// Adds videos to the end of Up next without playing them. Only for members who can
    /// change the video; links other members can't open are skipped.
    @discardableResult
    func enqueue(_ items: [PartyQueueItem]) -> Bool {
        guard let party = currentParty else { return false }
        guard party.canChangeVideo else {
            showToast("Only people who can change the video can add to the party queue.")
            return false
        }
        guard !items.isEmpty else { return false }
        let checked = items.map { ($0, PartyMediaURL.evaluate($0.url)) }
        let valid = checked.filter { $0.1.rejection == nil }
        guard !valid.isEmpty else {
            showToast(checked.first?.1.rejection ?? "This video can't be added to a watch party.")
            return false
        }
        partyQueue.append(valid.map(\.0))
        queueNotice = nil
        syncQueue()
        emitQueue(.enqueued(valid.map(\.0.id)))
        if valid.contains(where: { $0.1.isPrivateNetwork }) {
            showToast(PartyMediaURL.privateNetworkNotice)
        } else if valid.count < items.count {
            showToast("Added \(valid.count) to the party queue (skipped \(items.count - valid.count) that only work on this phone)")
        } else {
            showToast(valid.count == 1 ? "Added to the party queue" : "Added \(valid.count) videos to the party queue")
        }
        return true
    }

    func removeFromQueue(_ id: String) {
        guard partyQueue.remove(id) != nil else { return }
        syncQueue()
        emitQueue(.removed(id))
    }

    func moveQueueItem(from: Int, to: Int) {
        guard partyQueue.move(from: from, to: to) else { return }
        syncQueue()
        emitQueue(.reordered)
    }

    func clearQueue() {
        partyQueue.clear()
        queueNotice = nil
        syncQueue()
        emitQueue(.cleared)
    }

    @discardableResult
    func playNextInQueueNow() -> Bool {
        guard let next = partyQueue.first else { return false }
        return playQueuedItemNow(next.id)
    }

    /// Plays a queued video now and takes it out of Up next.
    @discardableResult
    func playQueuedItemNow(_ id: String) -> Bool {
        guard let party = currentParty else { return false }
        guard party.canChangeVideo else {
            showToast(party.locked ? "The host locked the controls" : "Only the host can change the video")
            return false
        }
        guard let target = partyQueue.items.first(where: { $0.id == id }) else { return false }
        advanceTracker.explicitLoadSent(fromQueue: true)
        guard sendLoad(target.togetherItem) else {
            if case .paused(let reason) = advanceTracker.queuedLoadFailed() { queueNotice = reason }
            showToast("Couldn't send \"\(target.title)\" to the watch party")
            return false
        }
        partyQueue.remove(id)
        queueNotice = nil
        syncQueue()
        emitQueue(.loadedFromQueue(target))
        if PartyMediaURL.evaluate(target.url).isPrivateNetwork { showToast(PartyMediaURL.privateNetworkNotice) }
        return true
    }

    /// Runs the post-end wait right away. For tests.
    func elapseEndClearGraceForTesting() {
        graceAdvanceTask?.cancel()
        graceAdvanceTask = nil
        guard let party = currentParty else { return }
        graceElapsed(party)
    }

    private func graceElapsed(_ party: TogetherPartyState) {
        let decision = advanceTracker.graceElapsed(party: party, queue: &partyQueue)
        if decision == .none { closeHeldPlayer() }
        handleAdvance(decision)
    }

    private func advance(on event: TogetherEvent) -> PartyAdvanceDecision {
        guard let party = currentParty else { return .none }
        return advanceTracker.onEvent(event, party: party, queue: &partyQueue)
    }

    private func handleAdvance(_ decision: PartyAdvanceDecision) {
        switch decision {
        case .none:
            break
        case .waitForClear:
            graceAdvanceTask?.cancel()
            graceAdvanceTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: Self.endClearGraceNs)
                guard !Task.isCancelled, let self, let party = self.currentParty else { return }
                self.graceAdvanceTask = nil
                self.graceElapsed(party)
            }
        case .paused(let reason):
            closeHeldPlayer()
            syncQueue()
            queueNotice = reason
            showToast(reason)
        case .load(let item):
            syncQueue()
            if sendLoad(item.togetherItem) {
                queueNotice = nil
                emitQueue(.loadedFromQueue(item))
            } else {
                closeHeldPlayer()
                if case .paused(let reason) = advanceTracker.queuedLoadFailed() {
                    queueNotice = reason
                    showToast(reason)
                }
            }
        }
    }

    private func cancelGraceAdvance() {
        graceAdvanceTask?.cancel()
        graceAdvanceTask = nil
    }

    private func syncQueue() {
        if queue != partyQueue.items { queue = partyQueue.items }
    }

    private func emitQueue(_ update: PartyQueueUpdate) {
        queueUpdates.send(PartyQueueChange(update: update, snapshot: partyQueue.items))
    }

    // MARK: - Website linked sessions

    /// Plays a website's video in the party and puts the rest of its list in Up next,
    /// replacing the videos in `replaceQueueIds` that the website queued before. A member
    /// who may only ask the host sends a request for the first video and queues nothing.
    func playLinkedInParty(
        _ item: PartyQueueItem,
        queueItems: [PartyQueueItem] = [],
        replaceQueueIds: Set<String> = []
    ) -> PartyLinkedPlayResult {
        guard let party = currentParty else { return .error("receiver_stopped") }
        guard isPartySelected else { return .error("receiver_changed") }
        let check = PartyMediaURL.evaluate(item.url)
        if let rejection = check.rejection {
            showToast(rejection)
            return .error("unsupported_target")
        }
        if let refusal = linkedRefusal(party) { return .error(refusal) }
        if !party.canChangeVideo {
            guard askHost(item) else { return .error("connect_failed") }
            let removed = partyQueue.removeAll(replaceQueueIds)
            if !removed.isEmpty {
                syncQueue()
                emitQueue(.replacedSessionQueue(removed))
            }
            showToast(check.isPrivateNetwork ? PartyMediaURL.privateNetworkNotice : "Asked the host to play it")
            return .ok(askOnly: true, queuedIds: [])
        }
        advanceTracker.explicitLoadSent(fromQueue: false)
        guard sendLoad(item.togetherItem) else {
            showToast("Couldn't send video to the watch party")
            return .error("connect_failed")
        }
        let removed = partyQueue.removeAll(replaceQueueIds)
        queueNotice = nil
        let checked = queueItems.map { ($0, PartyMediaURL.evaluate($0.url)) }.filter { $0.1.rejection == nil }
        partyQueue.append(checked.map(\.0))
        syncQueue()
        if !removed.isEmpty {
            emitQueue(.replacedSessionQueue(removed))
        } else if !checked.isEmpty {
            emitQueue(.enqueued(checked.map(\.0.id)))
        }
        let anyPrivate = check.isPrivateNetwork || checked.contains { $0.1.isPrivateNetwork }
        if anyPrivate {
            showToast(PartyMediaURL.privateNetworkNotice)
        } else {
            showToast(checked.isEmpty ? "Playing in the watch party" : "Playing in the watch party · \(checked.count) up next")
        }
        return .ok(askOnly: false, queuedIds: checked.map(\.0.id))
    }

    /// Adds a website's next videos to Up next, skipping links other members can't open.
    func enqueueLinkedItems(_ items: [PartyQueueItem]) -> PartyLinkedEnqueueResult {
        guard let party = currentParty else { return .error("receiver_stopped") }
        guard isPartySelected else { return .error("receiver_changed") }
        if let refusal = linkedRefusal(party) { return .error(refusal) }
        guard party.canChangeVideo else {
            showToast("Only people who can change the video can add to the party queue.")
            return .error("unsupported_queue")
        }
        guard !items.isEmpty else { return .error("invalid_request") }
        let checked = items.map { ($0, PartyMediaURL.evaluate($0.url)) }
        let valid = checked.filter { $0.1.rejection == nil }
        guard !valid.isEmpty else {
            showToast(checked.first?.1.rejection ?? "This video can't be added to a watch party.")
            return .error("unsupported_target")
        }
        partyQueue.append(valid.map(\.0))
        queueNotice = nil
        syncQueue()
        emitQueue(.enqueued(valid.map(\.0.id)))
        if valid.contains(where: { $0.1.isPrivateNetwork }) {
            showToast(PartyMediaURL.privateNetworkNotice)
        } else if valid.count < items.count {
            showToast("Added \(valid.count) to the party queue (skipped \(items.count - valid.count) that only work on this phone)")
        } else {
            showToast(valid.count == 1 ? "Added to the party queue" : "Added \(valid.count) videos to the party queue")
        }
        return .ok(valid.map(\.0))
    }

    /// Plays a website's video again, or one it already played, without touching Up next.
    func replayLinkedItem(_ item: PartyQueueItem) -> PartyLinkedPlayResult {
        guard let party = currentParty else { return .error("receiver_stopped") }
        guard isPartySelected else { return .error("receiver_changed") }
        let check = PartyMediaURL.evaluate(item.url)
        if let rejection = check.rejection {
            showToast(rejection)
            return .error("unsupported_target")
        }
        if let refusal = linkedRefusal(party) { return .error(refusal) }
        if !party.canChangeVideo {
            guard askHost(item) else { return .error("connect_failed") }
            showToast(check.isPrivateNetwork ? PartyMediaURL.privateNetworkNotice : "Asked the host to play it")
            return .ok(askOnly: true, queuedIds: [])
        }
        advanceTracker.explicitLoadSent(fromQueue: false)
        guard sendLoad(item.togetherItem) else {
            showToast("Couldn't send video to the watch party")
            return .error("connect_failed")
        }
        queueNotice = nil
        if check.isPrivateNetwork { showToast(PartyMediaURL.privateNetworkNotice) }
        return .ok(askOnly: false, queuedIds: [])
    }

    /// Same rule as Android: a locked party, or one only the host controls, refuses websites.
    private func linkedRefusal(_ party: TogetherPartyState) -> String? {
        if party.locked {
            showToast("The host locked the controls")
            return "not_allowed"
        }
        if party.policy == .onlyHost && !party.host {
            showToast("Only the host can change the video")
            return "not_allowed"
        }
        return nil
    }

    private func askHost(_ item: PartyQueueItem) -> Bool {
        let together = item.togetherItem
        if !together.headers.isEmpty { localSecretsPendingItemTitle = together.title }
        guard client?.requestPlay(item: together) == true else {
            localSecretsPendingItemTitle = nil
            showToast("Couldn't ask the host to play this video")
            return false
        }
        return true
    }

    private func clearQueueAndTarget() {
        cancelGraceAdvance()
        partyQueue.clear()
        advanceTracker.reset()
        syncQueue()
        queueNotice = nil
        partyTargetSelected = false
    }

    @discardableResult func play() -> Bool { client?.play() == true }
    @discardableResult func pause() -> Bool { client?.pause() == true }

    @discardableResult
    func stopVideo() -> Bool {
        guard let party = currentParty, party.canChangeVideo else { return false }
        advanceTracker.manualStopRequested(currentItem: party.item?.id)
        stopRequestedHere = true
        guard client?.clear() == true else {
            advanceTracker.manualStopSendFailed()
            stopRequestedHere = false
            return false
        }
        cancelGraceAdvance()
        return true
    }

    @discardableResult
    func seekBy(deltaMs: Int64) -> Bool {
        guard let party = currentParty else { return false }
        return client?.seek(positionMs: max(0, party.positionNow(atMs: clockMs()) + deltaMs)) == true
    }

    @discardableResult
    func seekTo(positionMs: Int64) -> Bool {
        client?.seek(positionMs: max(0, positionMs)) == true
    }

    func lock(_ locked: Bool) { _ = client?.lock(locked) }
    func policy(_ policy: TogetherPolicy) { _ = client?.policy(policy) }
    func continueWithout(member: String) { _ = client?.continueWithout(member: member) }

    func shareSignIn(member: String) {
        guard let party = currentParty else { return }
        guard client?.shareCredentials(member: member) == true else { return }
        updateParty { $0.sharedSecretsForItem.insert(member) }
        let name = party.members[member]?.name ?? "Peer"
        showToast("Shared sign-in with \(name)")
    }

    func answer(request: TogetherPartyRequest, allowed: Bool, always: Bool = false) {
        _ = client?.reply(request: request.id, allowed: allowed, always: always)
        updateParty { $0.requests.removeAll { $0.id == request.id } }
    }

    func canOfferShareSignIn(for memberID: String) -> Bool {
        guard let party = currentParty, party.item != nil else { return false }
        guard memberID != party.me, !party.own.contains(memberID) else { return false }
        return (hasLocalSecretsForCurrentItem || !(party.item?.headers.isEmpty ?? true))
            && !party.sharedSecretsForItem.contains(memberID)
    }

    // MARK: - Player Callbacks

    func userClosedPlayer() {
        releaseHoldKeepingPlayer()
        let wasProgrammatic = suppressUserDismissWatchStop
        suppressUserDismissWatchStop = false
        let closingSession = activePlayerItem?.session
        detachPlayerObservers()
        activePlayerItem = nil
        closingSession?.close()
        loadingItem = nil
        playerItem = nil
        loadedSent = false
        loadedReportedEarly = false
        if !wasProgrammatic {
            setWatchHere(false)
        }
    }

    // MARK: - Event Handling

    func handleEventForTesting(_ event: TogetherEvent) {
        onEvent(event)
    }

    private func ensureClient() -> TogetherSessionProtocol? {
        if let client { return client }
        do {
            let created = try sessionFactory { [weak self] event in
                DispatchQueue.main.async {
                    self?.onEvent(event)
                }
            }
            client = created
            return created
        } catch {
            state = .ended(message: "Watch Together isn't available on this device.")
            return nil
        }
    }

    private func onEvent(_ event: TogetherEvent) {
        switch event {
        case .room(let roomId, let invite, let relay, let member, _, _, let host, let endsAtMs, let saved):
            if self.roomId != roomId {
                resetSessionTracking()
                clearQueueAndTarget()
            }
            self.roomId = roomId
            let selfMember = TogetherPartyMember(
                id: member,
                name: deviceDisplayName,
                kind: .phone,
                host: host
            )
            state = .inParty(TogetherPartyState(
                roomId: roomId,
                invite: invite,
                relay: relay,
                me: member,
                host: host,
                watching: watchOnJoin,
                members: [member: selfMember]
            ))
            partyTargetSelected = true
            rememberParty(roomId: roomId, relay: relay, host: host, endsAtMs: endsAtMs, saved: saved)

        case .player(let command):
            onPlayerCommand(command)

        case .connected:
            reconnectTimeoutTask?.cancel()
            reconnectTimeoutTask = nil
            updateParty { party in
                party = PhoneTogetherReducer.reduce(party, event: event, nowMs: clockMs())
                if disconnectedSinceBackground {
                    party.notice = "Reconnected to the watch party"
                }
            }
            if disconnectedSinceBackground {
                disconnectedSinceBackground = false
                scheduleClearReconnectNotice()
            }

        case .disconnected:
            reconnectNoticeTask?.cancel()
            if wasBackgrounded || currentParty != nil {
                disconnectedSinceBackground = true
            }
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            if !wasBackgrounded {
                scheduleReconnectFailureTimer()
            }

        case .unreachable:
            reconnectTimeoutTask?.cancel()
            reconnectTimeoutTask = nil
            disconnectedSinceBackground = true
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }

        case .memberLeft(let member):
            ownTvIdentities.removeValue(forKey: member)
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }

        case .video(let item):
            cancelGraceAdvance()
            stopRequestedHere = false
            handleAdvance(advance(on: event))
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            if let pending = localSecretsPendingItemTitle, pending == item.title {
                localSecretsItemID = item.id
                localSecretsPendingItemTitle = nil
                hasLocalSecretsForCurrentItem = true
            } else if localSecretsItemID != item.id {
                localSecretsItemID = nil
                hasLocalSecretsForCurrentItem = !item.headers.isEmpty
            }

        case .videoCleared:
            cancelGraceAdvance()
            let decision = advance(on: event)
            if stopRequestedHere {
                stopRequestedHere = false
                closePlayerProgrammatically()
            } else if case .load = decision {
                holdPlayer(Self.playerHoldForQueueNs)
            } else if !holdingPlayer {
                // Someone else's Up next may load the next video right away.
                holdPlayer(Self.playerHoldNs)
            }
            localSecretsItemID = nil
            hasLocalSecretsForCurrentItem = false
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            handleAdvance(decision)

        case .ended(let item):
            let decision = advance(on: event)
            if loadingItem == item || playerItem == item {
                if case .waitForClear = decision { holdPlayer(Self.playerHoldForQueueNs) }
                else { holdPlayer(Self.playerHoldNs) }
            }
            if currentParty?.item?.id == item {
                localSecretsItemID = nil
                hasLocalSecretsForCurrentItem = false
            }
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            handleAdvance(decision)

        case .member(let member, let name, _, let host):
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            if host, let party = currentParty, member != party.me {
                savedStore.update(party.roomId) { $0.hostName = name }
            }

        case .locked, .policy:
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            if !advanceTracker.haltedOnFailure, currentParty?.canChangeVideo == true { queueNotice = nil }

        case .timeline, .waiting, .continueWithoutAvailable,
             .playRequested, .requestExpired, .warning, .live, .duration:
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }

        case .requestAnswered(_, let allowed):
            showToast(allowed ? "The host is playing your video" : "The host said no")

        case .pairSent:
            showToast("Invite sent. Confirm on that device.")

        case .pairingCode(let code, _, let qrSize, let qrModules, let expiresAtMs):
            if case .gettingCode = state {
                state = .code(code: code, qrSize: qrSize, qrModules: qrModules, expiresAtMs: expiresAtMs)
            } else if case .code = state {
                state = .code(code: code, qrSize: qrSize, qrModules: qrModules, expiresAtMs: expiresAtMs)
            }

        case .pairingInvite(let from, let relay):
            if case .code = state {
                state = .invited(from: from, relay: relay)
            }

        case .error(let context, let message, _):
            if isInParty { handleAdvance(advance(on: event)) }
            handleErrorEvent(context: context, message: message)

        case .stopped(let roomId, let reason, let retryAfterSecs):
            if !roomId.isEmpty, roomId != self.roomId { return }
            // A party that ended, or whose link stopped working, can't be rejoined.
            if reason == .expired || reason == .unauthorized, let ended = self.roomId {
                savedStore.remove(ended)
            }
            self.roomId = nil
            resetSessionTracking()
            clearQueueAndTarget()
            closePlayerProgrammatically()
            if isInParty || isStarting {
                state = Self.stoppedState(reason: reason, retryAfterSecs: retryAfterSecs)
            }

        case .memberJoined, .credentialsReceived, .rejected:
            break
        }
    }

    private func handleErrorEvent(context: String, message: String) {
        switch context {
        case "pair_send":
            let detail = message.isEmpty ? "unknown error" : message
            showToast("Couldn't send the invite: \(detail)")
        case "pair":
            switch state {
            case .gettingCode:
                state = .ended(message: "Couldn't get a code. Check your connection and try again.")
            case .code:
                state = .ended(message: "That code stopped working. Get a new one to join.")
            case .invited, .starting:
                state = .ended(message: "Couldn't join that watch party.")
            default:
                break
            }
        case "host", "join":
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            state = .ended(message: trimmed.isEmpty ? "Couldn't join." : trimmed)
        default:
            break
        }
    }

    private static func stoppedState(reason: TogetherStop, retryAfterSecs: Int64) -> PhoneTogetherState {
        switch reason {
        case .left:
            return .idle
        case .roomFull:
            return .ended(message: "This watch party is full.")
        case .expired:
            return .ended(message: "This watch party has ended.")
        case .unauthorized:
            return .ended(message: "This invite isn't valid any more.")
        case .replaced:
            return .ended(message: "You joined this party somewhere else.")
        case .roomLimit, .relayBusy:
            return .ended(message: TogetherFormatting.limitMessage(reason, retryAfterSecs: retryAfterSecs)
                ?? "The watch party server is busy. Try again soon.")
        case .error:
            return .ended(message: "The watch party connection failed.")
        }
    }

    private func onPlayerCommand(_ command: TogetherPlayerCommand) {
        guard currentParty?.watching == true else { return }
        switch command {
        case .load(let item, let headers, let startMs):
            openOrReplacePlayer(item: item, headers: headers, startMs: startMs)
        case .clear:
            if stopRequestedHere { closePlayerProgrammatically() }
            else if !holdingPlayer { holdPlayer(Self.playerHoldNs) }
        case .play:
            guard playerItem != nil || loadingItem != nil else { return }
            activePlayerItem?.session.applyPartyPlay()
        case .pause:
            guard playerItem != nil || loadingItem != nil else { return }
            activePlayerItem?.session.applyPartyPause()
        case .seek(let positionMs):
            guard playerItem != nil || loadingItem != nil else { return }
            activePlayerItem?.session.applyPartySeek(to: Double(max(0, positionMs)) / 1000.0)
        case .rate(let rate):
            guard playerItem != nil || loadingItem != nil else { return }
            activePlayerItem?.session.applyPartyRate(rate)
        case .goLive:
            guard playerItem != nil || loadingItem != nil else { return }
            activePlayerItem?.session.applyPartyGoLive()
        }
    }

    private func openOrReplacePlayer(item: TogetherItem, headers: [String: String], startMs: Int64) {
        guard case .url(let raw) = item.source,
              let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            showToast("This phone can't play \"\(item.title)\" yet")
            return
        }
        releaseHoldKeepingPlayer()
        loadingItem = item.id
        loadedSent = false
        loadedReportedEarly = false
        let effectiveHeaders = Self.mergedItemHeaders(itemHeaders: item.headers, commandHeaders: headers)
        let routed = RoutedStream(
            url: url,
            headers: effectiveHeaders,
            registration: nil,
            sourceURL: raw,
            sourceHeaders: effectiveHeaders
        )
        let resumeSeconds = Double(max(0, startMs)) / 1000.0
        if var existing = activePlayerItem {
            existing.itemID = item.id
            existing.title = item.title
            existing.session.title = item.title
            activePlayerItem = existing
            existing.session.loadPartyReplacement(media: routed, resume: resumeSeconds, autoplay: false)
        } else {
            let session = playerSessionFactory(routed, item.title, resumeSeconds)
            session.partyPlayInterceptor = { [weak self] in
                _ = self?.play()
                return true
            }
            session.partyPauseInterceptor = { [weak self] in
                _ = self?.pause()
                return true
            }
            session.partySeekInterceptor = { [weak self] seconds in
                _ = self?.seekTo(positionMs: Int64(max(0, seconds * 1000)))
                return true
            }
            attachPlayerObservers(session: session)
            activePlayerItem = PartyPlaybackPresentation(session: session, itemID: item.id, title: item.title)
        }
    }

    private func attachPlayerObservers(session: PlaybackSession) {
        detachPlayerObservers()
        session.$mpvState
            .receive(on: RunLoop.main)
            .sink { [weak self, weak session] mpv in
                guard let self, let session, self.activePlayerItem?.session === session else { return }
                self.handlePlayerState(session: session, state: mpv)
            }
            .store(in: &playerStateCancellables)
        session.$failure
            .compactMap { $0 }
            .receive(on: RunLoop.main)
            .sink { [weak self, weak session] failure in
                guard let self, let session, self.activePlayerItem?.session === session else { return }
                if let item = self.loadingItem, !self.loadedSent,
                   self.advanceTracker.phonePlayerLoadFailed(item: item) {
                    self.queueNotice = PartyAdvanceTracker.pausedOnFailure
                }
                self.showToast(failure.message)
            }
            .store(in: &playerStateCancellables)
        snapshotTimer = Timer.publish(every: 0.25, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                guard let self, let session = self.activePlayerItem?.session,
                      let item = self.playerItem ?? self.loadingItem,
                      self.loadedSent,
                      self.activePlayerItem?.itemID == item else { return }
                let mpv = session.mpvState
                _ = self.client?.snapshot(
                    positionMs: Int64(max(0, mpv.position * 1000)),
                    playing: !mpv.paused,
                    buffering: mpv.buffering
                )
            }
    }

    private func handlePlayerState(session: PlaybackSession, state: PhonePlaybackState) {
        guard let item = loadingItem, activePlayerItem?.itemID == item else { return }
        let durationMs = state.duration > 0 ? Int64(state.duration * 1000) : 0
        let ready = state.loaded || durationMs > 0
        let liveKnown: Bool? = durationMs > 0 ? false : nil
        switch TogetherLoadedPlanner.decideLoaded(
            alreadyReported: loadedSent,
            ready: ready,
            liveKnown: liveKnown,
            durationMs: durationMs,
            force: ready
        ) {
        case .report(let reportedDurationMs, let reportedEarly):
            loadedSent = true
            loadedReportedEarly = reportedEarly
            playerItem = item
            _ = client?.loaded(item: item, durationMs: reportedDurationMs)
        case .wait:
            if let correction = TogetherLoadedPlanner.lateDurationCorrection(
                alreadyReported: loadedSent,
                reportedEarly: loadedReportedEarly,
                liveKnown: liveKnown,
                durationMs: durationMs
            ) {
                loadedReportedEarly = false
                _ = client?.loaded(item: item, durationMs: correction)
            }
        }
        if loadedSent {
            _ = client?.snapshot(
                positionMs: Int64(max(0, state.position * 1000)),
                playing: !state.paused,
                buffering: state.buffering
            )
        }
    }

    private func detachPlayerObservers() {
        playerStateCancellables.removeAll()
        snapshotTimer?.cancel()
        snapshotTimer = nil
    }

    /// Pauses the player and keeps it up for `ns`, then closes it unless a next video came.
    private func holdPlayer(_ ns: UInt64) {
        guard let held = activePlayerItem else {
            closePlayerProgrammatically()
            return
        }
        held.session.applyPartyPause()
        loadingItem = nil
        playerItem = nil
        loadedSent = false
        loadedReportedEarly = false
        holdingPlayer = true
        holdPlayerTask?.cancel()
        holdPlayerTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: ns)
            guard !Task.isCancelled, let self, self.holdingPlayer else { return }
            self.closePlayerProgrammatically()
        }
    }

    /// A next video arrived: it reuses the held player.
    private func releaseHoldKeepingPlayer() {
        holdPlayerTask?.cancel()
        holdPlayerTask = nil
        holdingPlayer = false
    }

    /// Up next won't load anything: close a held player now.
    private func closeHeldPlayer() {
        if holdingPlayer { closePlayerProgrammatically() }
    }

    private func closePlayerProgrammatically() {
        releaseHoldKeepingPlayer()
        loadingItem = nil
        playerItem = nil
        loadedSent = false
        loadedReportedEarly = false
        detachPlayerObservers()
        if let closing = activePlayerItem {
            suppressUserDismissWatchStop = true
            activePlayerItem = nil
            closing.session.close()
        }
    }

    private func scheduleReconnectFailureTimer() {
        reconnectTimeoutTask?.cancel()
        reconnectTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.reconnectGraceSeconds * 1_000_000_000)
            guard !Task.isCancelled, let self, let party = self.currentParty, !party.connected else { return }
            self.updateParty {
                $0.reconnectFailed = true
                $0.notice = "Could not reconnect to the watch party."
            }
        }
    }

    private func scheduleClearReconnectNotice() {
        reconnectNoticeTask?.cancel()
        reconnectNoticeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.reconnectBannerDurationNs)
            guard !Task.isCancelled, let self else { return }
            self.updateParty {
                if $0.notice == "Reconnected to the watch party" {
                    $0.notice = nil
                }
            }
        }
    }

    private func resetSessionTracking() {
        reconnectNoticeTask?.cancel()
        reconnectNoticeTask = nil
        reconnectTimeoutTask?.cancel()
        reconnectTimeoutTask = nil
        wasBackgrounded = false
        disconnectedSinceBackground = false
        addingConnectedTv = false
        ownTvIdentities.removeAll()
        hasLocalSecretsForCurrentItem = false
        localSecretsPendingItemTitle = nil
        localSecretsItemID = nil
    }

    private var isJoiningByCode: Bool {
        switch state {
        case .gettingCode, .code, .invited: return true
        default: return false
        }
    }

    private var isStarting: Bool {
        if case .starting = state { return true }
        return false
    }

    private func makeDevice(screen: Bool) -> TogetherDevice {
        TogetherDevice(name: deviceDisplayName, kind: .phone, screen: screen)
    }

    private func updateParty(_ mutate: (inout TogetherPartyState) -> Void) {
        guard case .inParty(var party) = state else { return }
        mutate(&party)
        state = .inParty(party)
    }

    nonisolated static func mergedItemHeaders(
        itemHeaders: [String: String],
        commandHeaders: [String: String]
    ) -> [String: String] {
        var merged: [String: String] = [:]
        var canonicalKeyByLower: [String: String] = [:]
        for (key, value) in itemHeaders {
            let lower = key.lowercased()
            if let existingKey = canonicalKeyByLower[lower] {
                merged.removeValue(forKey: existingKey)
            }
            canonicalKeyByLower[lower] = key
            merged[key] = value
        }
        for (key, value) in commandHeaders {
            let lower = key.lowercased()
            if let existingKey = canonicalKeyByLower[lower] {
                merged.removeValue(forKey: existingKey)
            }
            canonicalKeyByLower[lower] = key
            merged[key] = value
        }
        return merged
    }

    private func showToast(_ message: String) {
        toastMessage = message
    }
}

extension PhoneTogether: PartyLinkedGateway {
    var partyStates: AnyPublisher<PhoneTogetherState, Never> { $state.eraseToAnyPublisher() }
    var partyTargetChanges: AnyPublisher<Bool, Never> { $partyTargetSelected.eraseToAnyPublisher() }
    var queueChanges: AnyPublisher<PartyQueueChange, Never> { queueUpdates.eraseToAnyPublisher() }
    func evaluateLink(_ url: String) -> PartyMediaURLCheck { PartyMediaURL.evaluate(url) }
    func partyNowMs() -> Int64 { clockMs() }
}
