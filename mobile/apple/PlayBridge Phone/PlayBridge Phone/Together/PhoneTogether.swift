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

    static func evaluate(_ raw: String) -> PartyMediaURLCheck {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host?.trimmingCharacters(in: .whitespacesAndNewlines),
              !host.isEmpty else {
            return PartyMediaURLCheck(
                normalizedURL: nil,
                rejection: "Only web video links (http/https) can be played in a watch party.",
                isPrivateNetwork: false
            )
        }
        return PartyMediaURLCheck(
            normalizedURL: url.absoluteString,
            rejection: nil,
            isPrivateNetwork: isPrivateHost(host)
        )
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
        self.playerSessionFactory = playerSessionFactory ?? { routed, _, resume in
            PlaybackSession(media: routed, route: .direct, initialResume: resume) { routed }
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

    @discardableResult
    func playInParty(
        url: String,
        title: String? = nil,
        headers: [String: String] = [:],
        subtitles: [String] = [],
        startMs: Int64 = 0
    ) -> Bool {
        guard let party = currentParty else { return false }
        let check = PartyMediaURL.evaluate(url)
        guard let validURL = check.normalizedURL else {
            showToast(check.rejection ?? "This video can't be played in a watch party.")
            return false
        }
        let cleanTitle = (title?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
            ?? URL(string: validURL)?.host
            ?? "Video"
        let item = TogetherItem(
            title: cleanTitle,
            site: URL(string: validURL)?.host,
            source: .url(validURL),
            headers: headers,
            subtitles: subtitles,
            startMs: max(0, startMs)
        )
        if party.canChangeVideo {
            if !headers.isEmpty {
                localSecretsPendingItemTitle = cleanTitle
            }
            let sent = client?.load(item: item) == true
            if !sent {
                localSecretsPendingItemTitle = nil
                showToast("Couldn't send video to the watch party")
                return false
            }
            showToast(check.isPrivateNetwork ? PartyMediaURL.privateNetworkNotice : "Playing in the watch party")
            return true
        } else if party.locked {
            showToast("The host locked the controls")
            return false
        } else if party.policy == .onlyHost {
            showToast("Only the host can change the video")
            return false
        } else {
            if !headers.isEmpty {
                localSecretsPendingItemTitle = cleanTitle
            }
            let requested = client?.requestPlay(item: item) == true
            if !requested {
                localSecretsPendingItemTitle = nil
                showToast("Couldn't ask the host to play this video")
                return false
            }
            showToast(check.isPrivateNetwork ? PartyMediaURL.privateNetworkNotice : "Asked the host to play it")
            return true
        }
    }

    @discardableResult func play() -> Bool { client?.play() == true }
    @discardableResult func pause() -> Bool { client?.pause() == true }

    @discardableResult
    func stopVideo() -> Bool {
        guard let party = currentParty, party.canChangeVideo else { return false }
        return client?.clear() == true
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
            closePlayerProgrammatically()
            localSecretsItemID = nil
            hasLocalSecretsForCurrentItem = false
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }

        case .ended(let item):
            if loadingItem == item || playerItem == item {
                closePlayerProgrammatically()
            }
            if currentParty?.item?.id == item {
                localSecretsItemID = nil
                hasLocalSecretsForCurrentItem = false
            }
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }

        case .member(let member, let name, _, let host):
            updateParty { $0 = PhoneTogetherReducer.reduce($0, event: event, nowMs: clockMs()) }
            if host, let party = currentParty, member != party.me {
                savedStore.update(party.roomId) { $0.hostName = name }
            }

        case .timeline, .waiting, .continueWithoutAvailable, .locked, .policy,
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
            handleErrorEvent(context: context, message: message)

        case .stopped(let roomId, let reason, let retryAfterSecs):
            if !roomId.isEmpty, roomId != self.roomId { return }
            // A party that ended, or whose link stopped working, can't be rejoined.
            if reason == .expired || reason == .unauthorized, let ended = self.roomId {
                savedStore.remove(ended)
            }
            self.roomId = nil
            resetSessionTracking()
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
            closePlayerProgrammatically()
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

    private func closePlayerProgrammatically() {
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
