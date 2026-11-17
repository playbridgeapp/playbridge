import Foundation

final class PhoneProxyRegistration {
    let url: URL
    init(url: URL) { self.url = url }
}

final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(
        url: String,
        headers: [String: String],
        contentType: String?,
        allowedPrivateOrigins: [String] = [],
        forLocalPlayback: Bool = false
    ) async throws -> PhoneProxyRegistration {
        PhoneProxyRegistration(url: URL(string: url)!)
    }
}

enum StreamProxySettingsStore {
    static func load() -> RemoteProxyConfiguration { .init() }
}

@MainActor
final class TestAlternativeEngine: PhoneAlternativePlaybackEngine {
    var onState: ((PhonePlaybackState) -> Void)?
    var onEnd: (() -> Void)?
    var onFailure: ((Int32) -> Void)?
    var networkIssue: PhonePlaybackNetworkIssue?
    var loads: [(URL, [String: String], Double, Bool)] = []
    var plays = 0
    var pauses = 0
    var closes = 0
    var seeks: [Double] = []
    var speeds: [Double] = []

    func configure(_ options: PhonePlayerOptions) {}
    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool) {
        loads.append((url, headers, resume, autoplay))
        onState?(PhonePlaybackState(position: resume, paused: !autoplay))
    }
    func play() { plays += 1 }
    func pause() { pauses += 1 }
    func seek(to seconds: Double) { seeks.append(seconds) }
    func setSpeed(_ speed: Double) { speeds.append(speed) }
    func selectAudio(_ id: Int?) {}
    func selectSubtitle(_ id: Int?) {}
    func close() { closes += 1 }
}

final class FakeTogetherSession: TogetherSessionProtocol {
    var hosted: [(relay: String, device: TogetherDevice)] = []
    var joined: [(invite: String, device: TogetherDevice)] = []
    var pairRegisters: [String] = []
    var pairAccepts: [(device: TogetherDevice, guest: Bool)] = []
    var pairCancels = 0
    var pairSends: [(pairing: String, target: String, id: String?)] = []
    var snapshots: [(positionMs: Int64, playing: Bool, buffering: Bool)] = []
    var loadeds: [(item: String, durationMs: Int64?)] = []
    var loadedItems: [TogetherItem] = []
    var clears = 0
    var playRequests: [TogetherItem] = []
    var plays = 0
    var pauses = 0
    var seeks: [Int64] = []
    var locks: [Bool] = []
    var policies: [TogetherPolicy] = []
    var replies: [(request: String, allowed: Bool, always: Bool)] = []
    var rejoins: [(saved: String, device: TogetherDevice)] = []
    var addOwns: [(member: String, boxKey: String)] = []
    var continueWithouts: [String] = []
    var shareCredentialCalls: [String] = []
    var screens: [Bool] = []
    var leaves = 0
    var closes = 0

    func host(relay: String, device: TogetherDevice) -> Bool { hosted.append((relay, device)); return true }
    func join(invite: String, device: TogetherDevice) -> Bool { joined.append((invite, device)); return true }
    func rejoin(saved: String, device: TogetherDevice) -> Bool { rejoins.append((saved, device)); return true }
    func pairRegister(pairing: String) -> Bool { pairRegisters.append(pairing); return true }
    func pairAccept(device: TogetherDevice, guest: Bool) -> Bool { pairAccepts.append((device, guest)); return true }
    func pairCancel() -> Bool { pairCancels += 1; return true }
    func pairSend(pairing: String, target: String, id: String?) -> Bool { pairSends.append((pairing, target, id)); return true }
    func snapshot(positionMs: Int64, playing: Bool, buffering: Bool) -> Bool {
        snapshots.append((positionMs, playing, buffering)); return true
    }
    func loaded(item: String, durationMs: Int64?) -> Bool { loadeds.append((item, durationMs)); return true }
    func load(item: TogetherItem) -> Bool { loadedItems.append(item); return true }
    func clear() -> Bool { clears += 1; return true }
    func requestPlay(item: TogetherItem) -> Bool { playRequests.append(item); return true }
    func play() -> Bool { plays += 1; return true }
    func pause() -> Bool { pauses += 1; return true }
    func seek(positionMs: Int64) -> Bool { seeks.append(positionMs); return true }
    func lock(_ locked: Bool) -> Bool { locks.append(locked); return true }
    func policy(_ policy: TogetherPolicy) -> Bool { policies.append(policy); return true }
    func reply(request: String, allowed: Bool, always: Bool) -> Bool {
        replies.append((request, allowed, always)); return true
    }
    func addOwn(member: String, boxKey: String) -> Bool { addOwns.append((member, boxKey)); return true }
    func continueWithout(member: String) -> Bool { continueWithouts.append(member); return true }
    func shareCredentials(member: String) -> Bool { shareCredentialCalls.append(member); return true }
    func setScreen(_ screen: Bool) -> Bool { screens.append(screen); return true }
    func leave() -> Bool { leaves += 1; return true }
    func close() { closes += 1 }
}

@MainActor
final class FakeTvBridge: WatchTogetherTvBridge {
    var connectedPlayBridgeTvName: String? = "Living Room Apple TV"
    var connectedPlayBridgeTvIdentity: WatchTogetherConnectedTv? = WatchTogetherConnectedTv(
        name: "Living Room Apple TV",
        uuid: "uuid-tv-1",
        endpointKey: "192.168.1.10:8765"
    )
    var tvSupportsWatchTogether: Bool = true
    var nextResult: WatchTogetherTvJoinResult = WatchTogetherTvJoinResult(
        member: "tv-1", boxKey: "box-pub", signKey: "sign-pub"
    )
    var calls: [(invite: String, from: String)] = []

    func addTvToWatchTogether(invite: String, from: String) async throws -> WatchTogetherTvJoinResult {
        calls.append((invite, from))
        return nextResult
    }
}

private func parseJSON(_ raw: String?) -> [String: Any] {
    guard let raw, let data = raw.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        fatalError("Invalid JSON: \(raw ?? "nil")")
    }
    return obj
}

@main
struct WatchTogetherTests {
    @MainActor
    static func main() async throws {
        // Never touch the real Keychain from tests.
        PhoneTogether.makeSavedBackend = { MemorySavedWatchPartyBackend() }
        testWatchTogetherLinks()
        testProtocolAndGuestJson()
        testMediaURLChecks()
        testReducerLifecycle()
        try await testPhoneTogetherSessionAndPlayer()
        try await testOwnTvMemberMatchAndAddVisibility()
        testMPVSeekStateTracking()
        testReconnectAndNoPersistence()
        testOpeningInviteLinksWhileInAParty()
        testSavedPartyRules()
        testSavedPartiesRejoinAsTheSameMember()
        testPartyLinkChecks()
        testPartyQueueAndAdvanceRules()
        testPartyTargetAndUpNext()
        try await testQueuedVideoReusesThePlayer()
        try await testWebsiteLinkedSessionInParty()
        try await testWebsiteLinkedSessionLimits()
        print("PASS: Phone Watch Together checks")
    }

    private static func testWatchTogetherLinks() {
        let room = "0123456789abcdef0123456789abcdef"
        precondition(WatchTogetherLinks.isInvite("https://party.playbridge.app/j/\(room)#k=abc&h=def"))
        precondition(WatchTogetherLinks.isInvite("https://example.com/together/j/\(room)#h=def&k=abc"))
        precondition(
            WatchTogetherLinks.invite("https://party.playbridge.app/j/\(room)?k=abc&h=def")
                == "https://party.playbridge.app/j/\(room)#k=abc&h=def"
        )
        precondition(!WatchTogetherLinks.isInvite("https://party.playbridge.app/j/\(room)"))
        precondition(!WatchTogetherLinks.isInvite("https://party.playbridge.app/j/\(room)#k=abc"))
        precondition(!WatchTogetherLinks.isInvite("https://party.playbridge.app/j/notaroom#k=abc&h=def"))
        precondition(!WatchTogetherLinks.isInvite("https://example.com/watch?v=1"))
        precondition(WatchTogetherLinks.roomId("https://party.playbridge.app/j/\(room)#k=abc&h=def") == room)
        precondition(WatchTogetherLinks.roomId("https://party.playbridge.app/j/\(room)?k=abc&h=def") == room)
        precondition(WatchTogetherLinks.roomId("https://party.playbridge.app/j/\(room)") == nil)
    }

    private static func testProtocolAndGuestJson() {
        let boundary = StubTogetherBoundary()
        let client = try! TogetherClient(native: boundary, onEvent: { _ in })
        precondition(client.pairAccept(device: TogetherDevice(name: "iPhone", kind: .phone, screen: true), guest: true))
        let pairAcceptCmd = parseJSON(boundary.submitted.last)
        precondition(pairAcceptCmd["command"] as? String == "pair_accept")
        precondition(pairAcceptCmd["guest"] as? Bool == true)
        client.close()

        let joinMsg = parseJSON(WireProtocol.watchTogetherJoin(invite: "https://party.playbridge.app/j/r#k=1&h=2", from: "My iPhone"))
        precondition(joinMsg["type"] as? String == "watch_together_join")
        precondition(joinMsg["invite"] as? String == "https://party.playbridge.app/j/r#k=1&h=2")
        precondition(joinMsg["from"] as? String == "My iPhone")

        let limitEvent = TogetherJson.event(from: #"{"event":"stopped","room":"r1","reason":"room_limit","retry_after":120}"#)
        precondition(limitEvent == .stopped(roomId: "r1", reason: .roomLimit, retryAfterSecs: 120))
        precondition(TogetherJson.event(from: #"{"event":"unreachable"}"#) == .unreachable)
    }

    private static func testMediaURLChecks() {
        let pub = PartyMediaURL.evaluate("https://cdn.example.com/video.mp4")
        precondition(pub.normalizedURL == "https://cdn.example.com/video.mp4")
        precondition(pub.rejection == nil)
        precondition(!pub.isPrivateNetwork)

        let lan = PartyMediaURL.evaluate("http://192.168.1.42:8080/stream.m3u8")
        precondition(lan.rejection == nil)
        precondition(lan.isPrivateNetwork)

        let localFile = PartyMediaURL.evaluate("file:///var/mobile/video.mp4")
        precondition(localFile.normalizedURL == nil)
        precondition(localFile.rejection != nil)
    }

    private static func testReducerLifecycle() {
        var party = TogetherPartyState(
            roomId: "r1", invite: "https://party.playbridge.app/j/abc#k=1&h=2",
            relay: "https://party.playbridge.app", me: "m1", host: true, watching: true
        )
        party = PhoneTogetherReducer.reduce(party, event: .member(member: "m1", name: "Phone", kind: .phone, host: true), nowMs: 100)
        party = PhoneTogetherReducer.reduce(party, event: .member(member: "m2", name: "Friend", kind: .phone, host: false), nowMs: 100)
        party.own.insert("tv1")
        party = PhoneTogetherReducer.reduce(party, event: .connected(members: ["m1", "m2"]), nowMs: 120)
        precondition(party.connected && party.members.count == 2)

        let item = TogetherItem(id: "i1", title: "Movie", site: "example.com", source: .url("https://example.com/v.mp4"), startMs: 5_000)
        party = PhoneTogetherReducer.reduce(party, event: .video(item: item), nowMs: 200)
        party = PhoneTogetherReducer.reduce(party, event: .duration(120_000), nowMs: 220)
        party = PhoneTogetherReducer.reduce(party, event: .timeline(item: "i1", playing: true, positionMs: 10_000, rev: 1), nowMs: 1_000)
        precondition(party.positionNow(atMs: 3_500) == 12_500)

        party = PhoneTogetherReducer.reduce(party, event: .playRequested(request: "req1", from: "m2", title: "Clip", site: "site.test"), nowMs: 3_600)
        precondition(party.requests.count == 1)
        party = PhoneTogetherReducer.reduce(party, event: .requestExpired(request: "req1"), nowMs: 3_700)
        precondition(party.requests.isEmpty)

        // Ended for another item preserves active item; matching item clears it.
        party = PhoneTogetherReducer.reduce(party, event: .ended(item: "other"), nowMs: 4_000)
        precondition(party.item?.id == "i1")
        party = PhoneTogetherReducer.reduce(party, event: .ended(item: "i1"), nowMs: 4_100)
        precondition(party.item == nil && !party.playing)
    }

    @MainActor
    private static func testPhoneTogetherSessionAndPlayer() async throws {
        let suite = "PhoneTogetherTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let session = FakeTogetherSession()
        var engines: [TestAlternativeEngine] = []
        var now: Int64 = 1_000

        let together = PhoneTogether(
            defaults: defaults,
            clockMs: { now },
            sessionFactory: { _ in session },
            playerSessionFactory: { routed, _, resume in
                let engine = TestAlternativeEngine()
                engines.append(engine)
                return PlaybackSession(
                    media: routed, route: .direct, initialResume: resume,
                    preferencesStore: defaults, alternativeFactory: { engine }
                ) { routed }
            }
        )

        // Default watching state follows connected TV presence.
        precondition(together.watchHereByDefault(connectedTvName: nil))
        precondition(!together.watchHereByDefault(connectedTvName: "Apple TV"))

        // Code-pair invite flow MUST accept as guest: true.
        together.showCode(watchHere: true)
        precondition(session.pairRegisters == [PhoneTogether.defaultPairingURL])
        together.handleEventForTesting(.pairingCode(code: "ABCDEF", qr: "q", qrSize: 1, qrModules: "1", expiresAtMs: 99_999))
        together.handleEventForTesting(.pairingInvite(from: "Host Phone", relay: "https://party.playbridge.app"))
        together.acceptInvite()
        precondition(session.pairAccepts.count == 1 && session.pairAccepts[0].guest)

        // Enter room as host with connected TV -> addOwn, and no host duties handed over.
        together.handleEventForTesting(.room(
            roomId: "room-1", invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=1&h=2",
            relay: "https://party.playbridge.app", member: "me-1", boxKey: "b", signKey: "s", host: true
        ))
        together.handleEventForTesting(.member(member: "me-1", name: "iPhone", kind: .phone, host: true))
        together.handleEventForTesting(.member(member: "friend-1", name: "Friend", kind: .phone, host: false))

        let tvBridge = FakeTvBridge()
        together.addConnectedTv(using: tvBridge)
        try await Task.sleep(nanoseconds: 50_000_000)
        precondition(session.addOwns.contains { $0.member == "tv-1" && $0.boxKey == "box-pub" })
        precondition(together.currentParty?.own.contains("tv-1") == true)

        // Play item with sign-in headers: offered ONLY to non-own peer, NOT to own TV.
        let sent = together.playInParty(
            url: "https://media.example.com/ep1.mp4",
            title: "Episode 1",
            headers: ["Cookie": "session=topsecret"]
        )
        precondition(sent)
        precondition(session.loadedItems.last?.headers["Cookie"] == "session=topsecret")

        let ep1 = TogetherItem(
            id: "item-1", title: "Episode 1", site: "media.example.com",
            source: .url("https://media.example.com/ep1.mp4"), startMs: 0
        )
        together.handleEventForTesting(.video(item: ep1))
        precondition(!together.canOfferShareSignIn(for: "me-1"))
        precondition(!together.canOfferShareSignIn(for: "tv-1"))
        precondition(together.canOfferShareSignIn(for: "friend-1"))
        together.shareSignIn(member: "friend-1")
        precondition(session.shareCredentialCalls == ["friend-1"])
        precondition(!together.canOfferShareSignIn(for: "friend-1"))

        // Player opens from .player(.load), reports loaded once ready, reuses session on replace, closes on EOF keeping watching=true.
        together.handleEventForTesting(.player(.load(item: ep1, headers: ["Cookie": "session=topsecret"], startMs: 12_000)))
        precondition(together.activePlayerItem?.itemID == "item-1")
        precondition(engines.count == 1 && engines[0].loads.last?.2 == 12.0 && engines[0].loads.last?.3 == false)

        engines[0].onState?(PhonePlaybackState(position: 12.0, duration: 90.0, paused: true, buffering: false, loaded: true))
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(session.loadeds.contains { $0.item == "item-1" && $0.durationMs == 90_000 })

        // User transport through PlaybackSession routes to party client, not local engine directly.
        together.activePlayerItem?.session.play()
        together.activePlayerItem?.session.pause()
        together.activePlayerItem?.session.seek(to: 45)
        precondition(session.plays == 1 && session.pauses == 1 && session.seeks == [45_000])

        // Replacement reuses the same PlaybackSession.
        let ep2 = TogetherItem(id: "item-2", title: "Episode 2", site: "media.example.com", source: .url("https://media.example.com/ep2.mp4"), startMs: 0)
        together.handleEventForTesting(.video(item: ep2))
        together.handleEventForTesting(.player(.load(item: ep2, headers: [:], startMs: 0)))
        precondition(engines.count == 1 && together.activePlayerItem?.itemID == "item-2")

        // EOF closes the player after a short hold (a next video could reuse it), without disabling watching.
        together.handleEventForTesting(.ended(item: "item-2"))
        precondition(together.activePlayerItem != nil)
        try await Task.sleep(nanoseconds: PhoneTogether.playerHoldNs + 300_000_000)
        precondition(together.activePlayerItem == nil)
        precondition(together.currentParty?.watching == true)
        together.userClosedPlayer()
        precondition(session.screens.isEmpty)

        // Manual Stop & user dismissal turning off Watch here.
        together.handleEventForTesting(.player(.load(item: ep1, headers: [:], startMs: 0)))
        precondition(together.activePlayerItem != nil)
        together.userClosedPlayer()
        precondition(session.screens == [false] && together.currentParty?.watching == false)

        now += 100
        _ = together.stopVideo()
        precondition(session.clears == 1)

        together.leave()
        precondition(session.leaves == 1 && together.state == .idle)
    }

    @MainActor
    private static func testOwnTvMemberMatchAndAddVisibility() async throws {
        let addedTv = WatchTogetherConnectedTv(
            name: "Living Room Apple TV",
            uuid: "uuid-tv-1",
            endpointKey: "192.168.1.10:8765"
        )
        let otherSameNameTv = WatchTogetherConnectedTv(
            name: "Living Room Apple TV",
            uuid: "uuid-tv-2",
            endpointKey: "192.168.1.11:8765"
        )
        let renamedSameUuid = WatchTogetherConnectedTv(
            name: "Bedroom Apple TV",
            uuid: "uuid-tv-1",
            endpointKey: "192.168.1.50:8765"
        )

        var party = TogetherPartyState(
            roomId: "room-tv",
            invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=a&h=b",
            relay: "https://party.playbridge.app",
            me: "m-phone",
            host: true
        )
        party.own = ["m-tv-1"]
        party.members = [
            "m-tv-1": TogetherPartyMember(id: "m-tv-1", name: "Living Room Apple TV", kind: .tv, host: false),
            "m-guest-tv": TogetherPartyMember(id: "m-guest-tv", name: "Guest TV", kind: .tv, host: false),
        ]
        let lanIdentities = ["m-tv-1": addedTv]

        // Exact LAN match succeeds, duplicate-name TV does not collide, renamed TV with same UUID matches.
        precondition(PhoneTogether.isOwnTvMemberMatch(device: addedTv, party: party, ownTvIdentities: lanIdentities))
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: otherSameNameTv, party: party, ownTvIdentities: lanIdentities))
        precondition(PhoneTogether.isOwnTvMemberMatch(device: renamedSameUuid, party: party, ownTvIdentities: lanIdentities))

        // UUID-less endpoint match distinguishes same-name TVs by ip:port.
        let endpointTv1 = WatchTogetherConnectedTv(name: "Living Room Apple TV", uuid: "", endpointKey: "192.168.1.10:8765")
        let endpointTv2 = WatchTogetherConnectedTv(name: "Living Room Apple TV", uuid: "", endpointKey: "192.168.1.11:8765")
        precondition(PhoneTogether.isOwnTvMemberMatch(device: endpointTv1, party: party, ownTvIdentities: lanIdentities))
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: endpointTv2, party: party, ownTvIdentities: lanIdentities))

        // Guest TV sharing the name (not in party.own) does not match.
        let guestNamedTv = WatchTogetherConnectedTv(name: "Guest TV", uuid: "uuid-guest", endpointKey: "192.168.1.30:8765")
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: guestNamedTv, party: party, ownTvIdentities: lanIdentities))

        // After memberLeft removes the TV, button becomes available again.
        let afterLeave = PhoneTogetherReducer.reduce(party, event: .memberLeft(member: "m-tv-1"), nowMs: 100)
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: addedTv, party: afterLeave, ownTvIdentities: [:]))

        // Pairing-code fallback (in party.own with no LAN identity recorded) matches saved TV by name.
        let pairedTv = WatchTogetherConnectedTv(name: "Living Room Apple TV")
        var pairingParty = party
        pairingParty.own = ["m-paired-tv"]
        pairingParty.members = [
            "m-paired-tv": TogetherPartyMember(id: "m-paired-tv", name: "Living Room Apple TV", kind: .tv, host: false),
        ]
        precondition(PhoneTogether.isOwnTvMemberMatch(device: pairedTv, party: pairingParty, ownTvIdentities: [:]))
        precondition(!PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Other TV"),
            party: pairingParty,
            ownTvIdentities: [:]
        ))

        // End-to-end coordinator add -> hide -> memberLeft -> show again.
        let suite = "PhoneTogetherAddTv.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in FakeTogetherSession() })
        together.handleEventForTesting(.room(
            roomId: "r-tv",
            invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=a&h=b",
            relay: "https://party.playbridge.app",
            member: "me",
            boxKey: "b",
            signKey: "s",
            host: true
        ))
        let bridge = FakeTvBridge()
        precondition(!together.isConnectedTvInParty(using: bridge))
        together.addConnectedTv(using: bridge)
        try await Task.sleep(nanoseconds: 30_000_000)
        together.handleEventForTesting(.member(member: "tv-1", name: "Living Room Apple TV", kind: .tv, host: false))
        precondition(together.isConnectedTvInParty(using: bridge))

        // Duplicate-name TV still shows Add button.
        let duplicateBridge = FakeTvBridge()
        duplicateBridge.connectedPlayBridgeTvIdentity = otherSameNameTv
        precondition(!together.isConnectedTvInParty(using: duplicateBridge))

        // Once TV leaves, Add button returns.
        together.handleEventForTesting(.memberLeft(member: "tv-1"))
        precondition(!together.isConnectedTvInParty(using: bridge))

        // Connected desktop PlayBridge receiver (.desktop):
        // - Room starts with self in members (count == 1, .phone, host == true)
        // - Adding desktop hides the Add button and brings count to 2
        // - Defensive: memberLeft(me) never removes self
        // - Strict name fallback accepts .tv / .desktop in own, rejects .phone / .cli
        precondition(together.currentParty?.members.count == 1)
        precondition(together.currentParty?.members["me"]?.kind == .phone)
        precondition(together.currentParty?.members["me"]?.host == true)
        together.handleEventForTesting(.memberLeft(member: "me"))
        precondition(together.currentParty?.members["me"] != nil)

        let desktopIdentity = WatchTogetherConnectedTv(
            name: "Atuls-MacBook-Air.local",
            uuid: "uuid-desktop-1",
            endpointKey: "192.168.1.40:8765"
        )
        let desktopBridge = FakeTvBridge()
        desktopBridge.connectedPlayBridgeTvName = desktopIdentity.name
        desktopBridge.connectedPlayBridgeTvIdentity = desktopIdentity
        precondition(!together.isConnectedTvInParty(using: desktopBridge))
        together.addConnectedTv(using: desktopBridge)
        try await Task.sleep(nanoseconds: 30_000_000)
        // Before MemberInfo arrives, recorded identity already hides Add button.
        precondition(together.isConnectedTvInParty(using: desktopBridge))
        together.handleEventForTesting(.member(member: "tv-1", name: desktopIdentity.name, kind: .desktop, host: false))
        precondition(together.isConnectedTvInParty(using: desktopBridge))
        precondition(together.currentParty?.members.count == 2)
        let ordered = together.currentParty?.orderedMembers ?? []
        precondition(ordered.map(\.id) == ["me", "tv-1"])
        precondition(together.toastMessage == "Atuls-MacBook-Air.local joined the party")

        // After desktop leaves, Add button comes back and count drops back to 1.
        together.handleEventForTesting(.memberLeft(member: "tv-1"))
        precondition(!together.isConnectedTvInParty(using: desktopBridge))
        precondition(together.currentParty?.members.count == 1)

        // Pairing-code name fallback: accepts .desktop and .tv, rejects .phone and .cli even if in own.
        var fallbackParty = party
        fallbackParty.own = ["m-desk", "m-phone-own", "m-cli-own"]
        fallbackParty.members = [
            "m-desk": TogetherPartyMember(id: "m-desk", name: "Studio Mac", kind: .desktop, host: false),
            "m-phone-own": TogetherPartyMember(id: "m-phone-own", name: "Same Name Phone", kind: .phone, host: false),
            "m-cli-own": TogetherPartyMember(id: "m-cli-own", name: "Same Name CLI", kind: .cli, host: false),
        ]
        precondition(PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Studio Mac"),
            party: fallbackParty,
            ownTvIdentities: [:]
        ))
        precondition(!PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Same Name Phone"),
            party: fallbackParty,
            ownTvIdentities: [:]
        ))
        precondition(!PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Same Name CLI"),
            party: fallbackParty,
            ownTvIdentities: [:]
        ))

        // Add fallback label when connectedPlayBridgeTvName is nil never uses "TV".
        let namelessBridge = FakeTvBridge()
        namelessBridge.connectedPlayBridgeTvName = nil
        namelessBridge.connectedPlayBridgeTvIdentity = nil
        together.addConnectedTv(using: namelessBridge)
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(together.toastMessage == "That device joined the party")
    }

    private static func testMPVSeekStateTracking() {
        var seek = PhoneMPVSeekState()
        // Normal paused playback without an active seek never reports buffering (prevents sync deadlock).
        let pausedIdle = seek.sample(mpvSeeking: false, timePos: 12.0, paused: true, pausedForCache: true, now: 1.0)
        precondition(pausedIdle.position == 12.0 && pausedIdle.buffering == false)

        // Playing cache stall reports buffering.
        let playingStall = seek.sample(mpvSeeking: false, timePos: 12.0, paused: false, pausedForCache: true, now: 1.1)
        precondition(playingStall.position == 12.0 && playingStall.buffering == true)

        // When seek(to: 47.0) runs and mpv has stale time-pos=0 and paused-for-cache=false while seeking:
        // position stays at 47.0 and buffering stays true across >1s network wait so engine doesn't re-seek loop.
        seek.beginSeek(to: 47.0, now: 2.0)
        seek.markMPVSeekingEvent()
        let duringSlowSeek1 = seek.sample(mpvSeeking: true, timePos: 0.0, paused: true, pausedForCache: false, now: 3.2)
        precondition(duringSlowSeek1.position == 47.0 && duringSlowSeek1.buffering == true)
        let duringSlowSeek2 = seek.sample(mpvSeeking: true, timePos: 0.0, paused: false, pausedForCache: false, now: 4.5)
        precondition(duringSlowSeek2.position == 47.0 && duringSlowSeek2.buffering == true)

        // Once MPV_EVENT_PLAYBACK_RESTART fires, seek completes and live position + buffering resume.
        seek.finishSeek()
        let settled = seek.sample(mpvSeeking: false, timePos: 47.1, paused: false, pausedForCache: false, now: 4.6)
        precondition(settled.position == 47.1 && settled.buffering == false)

        // Guard: if mpv ignores seek (never enters seeking), target clears after 0.75s so buffering can't wedge.
        seek.beginSeek(to: 80.0, now: 5.0)
        let pendingBeforeTimeout = seek.sample(mpvSeeking: false, timePos: 48.0, paused: false, pausedForCache: false, now: 5.2)
        precondition(pendingBeforeTimeout.position == 80.0 && pendingBeforeTimeout.buffering == true)
        let expiredIgnored = seek.sample(mpvSeeking: false, timePos: 48.0, paused: false, pausedForCache: false, now: 5.9)
        precondition(expiredIgnored.position == 48.0 && expiredIgnored.buffering == false)

        // Guard: even if mpv's seeking property stays true indefinitely, maxSeekDurationSeconds caps buffering.
        seek.beginSeek(to: 120.0, now: 10.0)
        let midLongSeek = seek.sample(mpvSeeking: true, timePos: 48.0, paused: false, pausedForCache: false, now: 20.0)
        precondition(midLongSeek.position == 120.0 && midLongSeek.buffering == true)
        let cappedLongSeek = seek.sample(mpvSeeking: true, timePos: 48.0, paused: false, pausedForCache: false, now: 25.5)
        precondition(cappedLongSeek.position == 48.0 && cappedLongSeek.buffering == false)

        // Header sanitizer for mpv http-header-fields: strips Range/If-Range/User-Agent/Host/Sec-* while keeping Cookie & Referer.
        let filtered = PhoneMPVHTTPHeaders.filteredEntries(from: [
            "Referer": "https://standin.example/",
            "Cookie": "session=abc, def=1",
            "User-Agent": "CustomUA/1.0",
            "Range": "bytes=0-",
            "if-range": "\"etag\"",
            "Sec-Fetch-Mode": "no-cors",
            "Host": "cdn.example.com",
            "Accept-Encoding": "gzip",
        ])
        precondition(filtered == [
            "Cookie: session=abc, def=1",
            "Referer: https://standin.example/",
        ])
        precondition(PhoneMPVHTTPHeaders.userAgent(from: ["user-agent": "CustomUA/1.0"]) == "CustomUA/1.0")
        precondition(PhoneMPVHTTPHeaders.filteredEntries(from: ["Cookie": "a=1\r\nEvil: 1"]) == nil)

        // Merging item + command headers keeps non-secret item headers while letting command secrets win.
        let merged = PhoneTogether.mergedItemHeaders(
            itemHeaders: ["Referer": "https://standin.example/", "User-Agent": "Mozilla/5.0"],
            commandHeaders: ["Cookie": "session=secret", "referer": "https://override.example/"]
        )
        precondition(merged["Cookie"] == "session=secret")
        precondition(merged["User-Agent"] == "Mozilla/5.0")
        precondition(merged["referer"] == "https://override.example/")
        precondition(merged["Referer"] == nil)
    }

    @MainActor
    private static func testReconnectAndNoPersistence() {
        let suite = "PhoneTogetherReconnect.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        let inviteURL = URL(string: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef?k=abc&h=def")!
        precondition(together.handleIncomingURL(inviteURL, connectedTvName: nil))
        precondition(together.pendingOpenScreen)
        together.consumePendingOpenScreen()
        precondition(!together.pendingOpenScreen)

        together.handleEventForTesting(.room(
            roomId: "room-bg", invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=abc&h=def",
            relay: "https://party.playbridge.app", member: "me", boxKey: "b", signKey: "s", host: false
        ))
        together.handleEventForTesting(.connected(members: ["me"]))

        together.applicationEnteredBackground()
        together.handleEventForTesting(.disconnected)
        together.applicationBecameActive()
        precondition(together.currentParty?.connected == false)

        together.handleEventForTesting(.connected(members: ["me"]))
        precondition(together.currentParty?.connected == true)
        precondition(together.currentParty?.notice == "Reconnected to the watch party")

        // Unreachable marks reconnectFailed cleanly with Leave still available.
        together.handleEventForTesting(.unreachable)
        precondition(together.currentParty?.reconnectFailed == true)

        // App kill / new instance starts strictly idle with no persisted party or auto-rejoin.
        together.setRelayURL("https://custom.relay.example")
        let relaunched = PhoneTogether(defaults: defaults, sessionFactory: { _ in FakeTogetherSession() })
        precondition(relaunched.state == .idle)
        precondition(!relaunched.isInParty)
        precondition(relaunched.relayURL == "https://custom.relay.example")
    }

    @MainActor
    private static func testOpeningInviteLinksWhileInAParty() {
        let suite = "PhoneTogetherOpenInvite.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        let room = "0123456789abcdef0123456789abcdef"
        let link = "https://party.playbridge.app/j/\(room)#k=abc&h=def"
        together.handleEventForTesting(.room(
            roomId: room, invite: link, relay: "https://party.playbridge.app",
            member: "me", boxKey: "b", signKey: "s", host: true
        ))
        together.handleEventForTesting(.connected(members: ["me"]))

        // This party's own link, in the form the invite page hands over: stays put, as host.
        let own = URL(string: "https://party.playbridge.app/j/\(room)?k=abc&h=def")!
        precondition(together.handleIncomingURL(own, connectedTvName: nil))
        precondition(session.joined.isEmpty)
        precondition(together.switchRequest == nil)
        precondition(together.currentParty?.host == true)

        // Another party's link asks first; staying keeps this party.
        let other = "https://party.playbridge.app/j/fedcba9876543210fedcba9876543210#k=xyz&h=uvw"
        precondition(together.handleIncomingURL(URL(string: other)!, connectedTvName: nil))
        precondition(together.switchRequest?.invite == other)
        precondition(session.joined.isEmpty)
        together.cancelSwitch()
        precondition(together.switchRequest == nil)
        precondition(together.currentParty?.roomId == room)

        // Confirming leaves this party and joins the other.
        precondition(together.handleIncomingURL(URL(string: other)!, connectedTvName: nil))
        together.confirmSwitch()
        precondition(together.switchRequest == nil)
        precondition(session.joined.map(\.0) == [other])
        precondition(together.state == .starting)
    }
}

extension WatchTogetherTests {
    static func testSavedPartyRules() {
        let now: Int64 = 1_800_000_000_000
        var clock = now
        let backend = MemorySavedWatchPartyBackend()
        let store = SavedWatchPartyStore(backend: backend, nowMs: { clock })
        func party(_ room: String, joinedAt: Int64 = now, endsAt: Int64? = now + 3_600_000) -> SavedWatchParty {
            SavedWatchParty(
                roomId: room, relay: "party.playbridge.app", host: false, saved: "secret-\(room)",
                joinedAtMs: joinedAt,
                endsAtMs: SavedWatchParties.endsAt(roomEndsAtMs: endsAt, joinedAtMs: joinedAt)
            )
        }

        // Kept and read back after a restart; never described with its secret.
        store.put(party("a"))
        precondition(SavedWatchPartyStore(backend: backend, nowMs: { clock }).get("a")?.saved == "secret-a")
        precondition(!party("a").description.contains("secret"))

        // Ended and undated parties drop out.
        store.put(party("soon", endsAt: now + 60_000))
        store.put(party("undated", endsAt: nil))
        clock = now + 2 * 60_000
        store.prune()
        precondition(store.get("soon") == nil && backend.items["soon"] == nil)
        precondition(store.get("undated") != nil)
        clock = now + SavedWatchParties.undatedLifetimeMs + 1
        store.prune()
        precondition(store.parties.isEmpty)

        // Newest twenty, newest first.
        clock = now
        for index in 0..<(SavedWatchParties.maxSaved + 2) { store.put(party("r\(index)", joinedAt: now + Int64(index))) }
        precondition(store.parties.count == SavedWatchParties.maxSaved)
        precondition(store.parties.first?.roomId == "r21" && store.get("r0") == nil)

        // Damaged items are dropped on load.
        backend.set(Data("nope".utf8), room: "broken")
        precondition(SavedWatchPartyStore(backend: backend, nowMs: { clock }).get("broken") == nil)
        precondition(backend.items["broken"] == nil)

        var p = party("w")
        precondition(SavedWatchParties.endsIn(SavedWatchParty(
            roomId: "w", relay: "", host: false, saved: "", joinedAtMs: now, endsAtMs: now + 130 * 60_000
        ), nowMs: now) == "Ends in 2 h 10 min")
        precondition(SavedWatchParties.endsIn(p, nowMs: now) == "Ends in 1 h")
        precondition(SavedWatchParties.endsIn(p, nowMs: now + 3_599_000) == "Ends in less than a minute")
        precondition(p.title == "Watch party")
        p.hostName = "Sam"
        precondition(p.title == "Sam's party")
    }

    @MainActor
    static func testSavedPartiesRejoinAsTheSameMember() {
        let suite = "PhoneTogetherSaved.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let backend = MemorySavedWatchPartyBackend()
        PhoneTogether.makeSavedBackend = { backend }
        defer { PhoneTogether.makeSavedBackend = { MemorySavedWatchPartyBackend() } }

        let room = "0123456789abcdef0123456789abcdef"
        let link = "https://party.playbridge.app/j/\(room)#k=abc&h=def"
        let endsAt = SavedWatchParties.nowMs() + 3_600_000
        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        together.startHosting(watchHere: false)
        together.handleEventForTesting(.room(
            roomId: room, invite: link, relay: "party.playbridge.app", member: "me",
            boxKey: "b", signKey: "s", host: true, endsAtMs: endsAt, saved: "SAVED-HOST"
        ))
        precondition(together.savedParties.first?.roomId == room)
        precondition(together.savedParties.first?.host == true)
        precondition(together.savedParties.first?.endsAtMs == endsAt)

        // After a kill: a new instance lists it, and opening the link rejoins with the saved keys.
        let relaunchedSession = FakeTogetherSession()
        let relaunched = PhoneTogether(defaults: defaults, sessionFactory: { _ in relaunchedSession })
        precondition(relaunched.state == .idle)
        relaunched.loadSavedParties()
        precondition(relaunched.savedParties.map(\.roomId) == [room])
        relaunched.openInvite(link, watchHere: false)
        precondition(relaunchedSession.joined.isEmpty)
        precondition(relaunchedSession.rejoins.map(\.saved) == ["SAVED-HOST"])
        precondition(relaunched.state == .starting)

        // Back in it: rejoining again does nothing.
        relaunched.handleEventForTesting(.room(
            roomId: room, invite: link, relay: "party.playbridge.app", member: "me",
            boxKey: "b", signKey: "s", host: true, endsAtMs: endsAt, saved: "SAVED-HOST"
        ))
        relaunched.rejoin(relaunched.savedParties[0], watchHere: false)
        precondition(relaunchedSession.rejoins.count == 1)

        // A party that ended is forgotten; so is one the user removes.
        relaunched.handleEventForTesting(.stopped(roomId: room, reason: .expired))
        precondition(relaunched.savedParties.isEmpty && backend.items.isEmpty)
        backend.set(try! JSONEncoder().encode(SavedWatchParty(
            roomId: "r2", relay: "", host: false, saved: "S2",
            joinedAtMs: SavedWatchParties.nowMs(), endsAtMs: endsAt
        )), room: "r2")
        let third = PhoneTogether(defaults: defaults, sessionFactory: { _ in FakeTogetherSession() })
        third.loadSavedParties()
        precondition(third.savedParties.map(\.roomId) == ["r2"])
        third.forgetSaved("r2")
        precondition(third.savedParties.isEmpty && backend.items.isEmpty)
    }

    static func testPartyLinkChecks() {
        PartyMediaURL.ownAddresses = { ["192.168.1.29"] }
        defer { PartyMediaURL.ownAddresses = { [] } }
        for loopback in ["http://127.0.0.1:8080/v.mp4", "http://localhost/v.mp4", "http://[::1]/v.mp4", "http://0.0.0.0/v.mp4"] {
            precondition(PartyMediaURL.evaluate(loopback).rejection != nil, loopback)
        }
        precondition(PartyMediaURL.evaluate("http://192.168.1.29:51234/proxy/x").rejection != nil)
        precondition(PartyMediaURL.evaluate("blob:https://site.test/1").rejection != nil)
        precondition(PartyMediaURL.evaluate("").rejection != nil)
        let nas = PartyMediaURL.evaluate("http://192.168.1.40:8096/v.mkv")
        precondition(nas.rejection == nil && nas.isPrivateNetwork)
        precondition(PartyMediaURL.evaluate("http://100.80.1.2/v.mp4").isPrivateNetwork)
        precondition(PartyMediaURL.evaluate("http://nas.local/v.mp4").isPrivateNetwork)
        precondition(!PartyMediaURL.evaluate("https://127.example.com/v.mp4").isPrivateNetwork)

        let item = PartyQueueItem.of(url: " https://www.example.com/a.mp4 ", title: "  ", headers: ["Cookie": "c"])
        precondition(item.title == "example.com" && item.site == "example.com")
        precondition(!String(describing: item).contains("Cookie") && !String(describing: item).contains("example.com/a"))
    }

    static func testPartyQueueAndAdvanceRules() {
        var queue = PartyQueue()
        let a = PartyQueueItem(url: "https://e.test/a", title: "A")
        let b = PartyQueueItem(url: "https://e.test/b", title: "B")
        let c = PartyQueueItem(url: "https://e.test/c", title: "C")
        queue.append([a, b, c])
        precondition(queue.move(from: 2, to: 0) && queue.items.map(\.title) == ["C", "A", "B"])
        precondition(!queue.move(from: 0, to: 5))
        precondition(queue.remove(a.id)?.title == "A" && queue.items.map(\.title) == ["C", "B"])

        func video(_ id: String) -> TogetherItem {
            TogetherItem(id: id, title: id, source: .url("https://e.test/\(id)"))
        }
        var party = TogetherPartyState(roomId: "r", invite: "i", relay: "x", me: "m", host: true)
        party.item = video("v1")
        var tracker = PartyAdvanceTracker()

        // A real end waits for the party's clear, then loads the next.
        precondition(tracker.onEvent(.video(item: video("v1")), party: party, queue: &queue) == .none)
        precondition(tracker.onEvent(.ended(item: "v1"), party: party, queue: &queue) == .waitForClear(item: "v1"))
        precondition(tracker.onEvent(.ended(item: "v1"), party: party, queue: &queue) == .none)
        precondition(tracker.onEvent(.videoCleared, party: party, queue: &queue) == .load(c))
        precondition(queue.items.map(\.title) == ["B"])

        // A clear nobody asked for (Stop) doesn't advance.
        party.item = video("v2")
        _ = tracker.onEvent(.video(item: video("v2")), party: party, queue: &queue)
        precondition(tracker.onEvent(.videoCleared, party: party, queue: &queue) == .none)
        precondition(queue.count == 1)

        // This phone's Stop: the end that follows doesn't advance either.
        party.item = video("v3")
        _ = tracker.onEvent(.video(item: video("v3")), party: party, queue: &queue)
        tracker.manualStopRequested(currentItem: "v3")
        precondition(tracker.onEvent(.ended(item: "v3"), party: party, queue: &queue) == .none)

        // No clear within the grace period: advance anyway.
        party.item = video("v4")
        _ = tracker.onEvent(.video(item: video("v4")), party: party, queue: &queue)
        precondition(tracker.onEvent(.ended(item: "v4"), party: party, queue: &queue) == .waitForClear(item: "v4"))
        precondition(tracker.graceElapsed(party: party, queue: &queue) == .load(b))
        precondition(tracker.graceElapsed(party: party, queue: &queue) == .none)

        // A queued video that fails pauses Up next instead of skipping the rest.
        queue.append([a])
        precondition(tracker.onEvent(.error(context: "load", message: "x", id: nil), party: party, queue: &queue)
                     == .paused(reason: PartyAdvanceTracker.pausedOnFailure))
        party.item = video("v5")
        _ = tracker.onEvent(.video(item: video("v5")), party: party, queue: &queue)
        precondition(tracker.onEvent(.ended(item: "v5"), party: party, queue: &queue) == .none)
        tracker.explicitLoadSent(fromQueue: false)
        party.item = video("v6")
        _ = tracker.onEvent(.video(item: video("v6")), party: party, queue: &queue)
        precondition(tracker.onEvent(.ended(item: "v6"), party: party, queue: &queue) == .waitForClear(item: "v6"))

        // A guest who lost the right to change the video pauses instead of loading.
        var guest = TogetherPartyState(roomId: "r", invite: "i", relay: "x", me: "m", host: false)
        guest.item = party.item
        guest.policy = .onlyHost
        precondition(tracker.onEvent(.videoCleared, party: guest, queue: &queue)
                     == .paused(reason: "Up next paused: only the host can change the video."))
        precondition(queue.count == 1)
    }

    @MainActor
    static func testQueuedVideoReusesThePlayer() async throws {
        let suite = "PhoneTogetherHold.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = FakeTogetherSession()
        var engines: [TestAlternativeEngine] = []
        let together = PhoneTogether(
            defaults: defaults, sessionFactory: { _ in session },
            playerSessionFactory: { routed, _, resume in
                let engine = TestAlternativeEngine()
                engines.append(engine)
                return PlaybackSession(media: routed, route: .direct, initialResume: resume,
                                       preferencesStore: defaults, alternativeFactory: { engine }) { routed }
            })
        together.startHosting(watchHere: true)
        together.handleEventForTesting(.room(
            roomId: "h1", invite: "https://party.playbridge.app/j/h1#k=1&h=2",
            relay: "party.playbridge.app", member: "me", boxKey: "b", signKey: "s", host: true))
        precondition(together.playInParty(url: "https://e.test/1.mp4", title: "One"))
        precondition(together.enqueue([PartyQueueItem.of(url: "https://e.test/2.mp4", title: "Two")]))
        let one = TogetherItem(id: "i1", title: "One", source: .url("https://e.test/1.mp4"))
        together.handleEventForTesting(.video(item: one))
        together.handleEventForTesting(.player(.load(item: one, headers: [:], startMs: 0)))
        let first = together.activePlayerItem?.session
        precondition(first != nil && engines.count == 1)

        // The end, the party's clear and the queued load keep the same player up.
        together.handleEventForTesting(.ended(item: "i1"))
        precondition(together.activePlayerItem?.session === first)
        together.handleEventForTesting(.player(.clear))
        together.handleEventForTesting(.videoCleared)
        precondition(together.activePlayerItem?.session === first)
        precondition(session.loadedItems.last?.title == "Two")
        let two = TogetherItem(id: "i2", title: "Two", source: .url("https://e.test/2.mp4"))
        together.handleEventForTesting(.video(item: two))
        together.handleEventForTesting(.player(.load(item: two, headers: [:], startMs: 0)))
        precondition(together.activePlayerItem?.session === first && engines.count == 1)
        precondition(together.activePlayerItem?.itemID == "i2")
        precondition(engines[0].loads.last?.0.absoluteString == "https://e.test/2.mp4")

        // This phone's Stop closes the player straight away.
        precondition(together.stopVideo())
        together.handleEventForTesting(.player(.clear))
        precondition(together.activePlayerItem == nil)
        together.handleEventForTesting(.videoCleared)

        // An end with nothing next closes the player after the short hold.
        let three = TogetherItem(id: "i3", title: "Three", source: .url("https://e.test/3.mp4"))
        together.handleEventForTesting(.video(item: three))
        together.handleEventForTesting(.player(.load(item: three, headers: [:], startMs: 0)))
        precondition(together.activePlayerItem != nil)
        together.handleEventForTesting(.ended(item: "i3"))
        precondition(together.activePlayerItem != nil)
        try await Task.sleep(nanoseconds: PhoneTogether.playerHoldNs + 300_000_000)
        precondition(together.activePlayerItem == nil)
        together.leave()
    }

    @MainActor
    static func testPartyTargetAndUpNext() {
        let suite = "PhoneTogetherQueue.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        precondition(!together.isPartySelected)
        together.startHosting(watchHere: false)
        together.handleEventForTesting(.room(
            roomId: "q1", invite: "https://party.playbridge.app/j/q1#k=1&h=2",
            relay: "party.playbridge.app", member: "me", boxKey: "b", signKey: "s", host: true
        ))
        // Joining picks the party; picking another device keeps the phone in it.
        precondition(together.isPartySelected)
        together.deselectPartyTarget()
        precondition(!together.isPartySelected && together.isInParty)
        together.selectPartyTarget()
        precondition(together.isPartySelected)

        // A playlist: the first plays, the rest wait in Up next; local links are skipped.
        let rest = [
            PartyQueueItem.of(url: "https://e.test/2.mp4", title: "Two"),
            PartyQueueItem.of(url: "http://127.0.0.1:9/3.mp4", title: "Local"),
            PartyQueueItem.of(url: "https://e.test/4.mp4", title: "Four"),
        ]
        precondition(together.playInParty(url: "https://e.test/1.mp4", title: "One", queueItems: rest))
        precondition(session.loadedItems.map(\.title) == ["One"])
        precondition(together.queue.map(\.title) == ["Two", "Four"])
        precondition(together.enqueue([PartyQueueItem.of(url: "https://e.test/5.mp4", title: "Five")]))
        together.moveQueueItem(from: 2, to: 0)
        precondition(together.queue.map(\.title) == ["Five", "Two", "Four"])

        // The video ends and the party clears it: the next one loads.
        let one = TogetherItem(id: "i1", title: "One", source: .url("https://e.test/1.mp4"))
        together.handleEventForTesting(.video(item: one))
        together.handleEventForTesting(.ended(item: "i1"))
        precondition(session.loadedItems.count == 1)
        together.handleEventForTesting(.videoCleared)
        precondition(session.loadedItems.map(\.title) == ["One", "Five"])
        precondition(together.queue.map(\.title) == ["Two", "Four"])

        // Stop keeps the queue and doesn't advance it.
        let five = TogetherItem(id: "i5", title: "Five", source: .url("https://e.test/5.mp4"))
        together.handleEventForTesting(.video(item: five))
        precondition(together.stopVideo() && session.clears == 1)
        together.handleEventForTesting(.videoCleared)
        precondition(session.loadedItems.count == 2 && together.queue.count == 2)

        // No clear after an end: the grace period moves on.
        let again = TogetherItem(id: "i6", title: "Again", source: .url("https://e.test/6.mp4"))
        together.handleEventForTesting(.video(item: again))
        together.handleEventForTesting(.ended(item: "i6"))
        together.elapseEndClearGraceForTesting()
        precondition(session.loadedItems.last?.title == "Two" && together.queue.map(\.title) == ["Four"])

        // Play now, then Clear.
        precondition(together.enqueue([PartyQueueItem.of(url: "https://e.test/7.mp4", title: "Seven")]))
        precondition(together.playQueuedItemNow(together.queue[1].id))
        precondition(session.loadedItems.last?.title == "Seven" && together.queue.map(\.title) == ["Four"])
        together.clearQueue()
        precondition(together.queue.isEmpty)

        // A guest who can't change the video can't queue.
        together.handleEventForTesting(.room(
            roomId: "q2", invite: "https://party.playbridge.app/j/q2#k=1",
            relay: "party.playbridge.app", member: "me2", boxKey: "b", signKey: "s", host: false
        ))
        precondition(together.isPartySelected && together.queue.isEmpty)
        precondition(!together.enqueue([PartyQueueItem.of(url: "https://e.test/8.mp4", title: "Eight")]))

        // Leaving drops the queue and the target.
        together.leave()
        precondition(!together.isPartySelected && together.queue.isEmpty && !together.partyTargetSelected)
    }

    private static func pageRequest(_ items: [(String, String)], start: Int = 0) -> PageCastRequest {
        PageCastRequest(items: items.map { ["id": $0.0, "url": $0.1, "title": $0.0.uppercased()] },
                        startIndex: start, metadata: nil, skipPreplay: false, privateOrigins: [])
    }

    private static func settle() async throws { try await Task.sleep(nanoseconds: 30_000_000) }

    @MainActor
    private static func hostedParty(host: Bool = true, room: String = "web1") -> (PhoneTogether, FakeTogetherSession) {
        let suite = "PhoneTogetherLinked.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        together.startHosting(watchHere: false)
        together.handleEventForTesting(.room(
            roomId: room, invite: "https://party.playbridge.app/j/\(room)#k=1&h=2",
            relay: "party.playbridge.app", member: "me", boxKey: "b", signKey: "s", host: host
        ))
        return (together, session)
    }

    @MainActor
    static func testWebsiteLinkedSessionInParty() async throws {
        let (together, session) = hostedParty()
        var changes = 0
        var finished: [String] = []
        let request = pageRequest([("a", "https://e.test/a.mp4"), ("b", "https://e.test/b.mp4"),
                                   ("c", "http://127.0.0.1:9/c.mp4")])
        let link = try PartyLinkedSession.start(request, gateway: together)
        link.onChange = { _ in changes += 1 }
        link.onFinish = { finished.append($0) }
        // The first plays, the rest wait in Up next; links only this phone can open are left out.
        precondition(session.loadedItems.map(\.title) == ["A"])
        precondition(together.queue.map(\.title) == ["B"] && link.knownIds == ["a", "b"])
        var snapshot = link.snapshot()
        precondition(snapshot.state == "connecting" && snapshot.items.map(\.id) == ["a", "b"] && snapshot.currentIndex == 0)

        // The party starts the video: the site sees it paused, then playing.
        together.handleEventForTesting(.video(item: TogetherItem(id: "ra", title: "A", source: .url("https://e.test/a.mp4"))))
        try await settle()
        precondition(link.snapshot().state == "paused" && changes > 0)
        together.handleEventForTesting(.timeline(item: "ra", playing: true, positionMs: 4_000, rev: 1))
        try await settle()
        snapshot = link.snapshot()
        precondition(snapshot.state == "playing" && snapshot.title == "A" && snapshot.positionMs >= 4_000)

        // The site supplies more; removing one from Up next drops it from the site's list.
        precondition(link.supply([["id": "d", "url": "https://e.test/d.mp4", "title": "D"]]) == nil)
        precondition(together.queue.map(\.title) == ["B", "D"])
        together.removeFromQueue(together.queue[0].id)
        try await settle()
        precondition(link.snapshot().items.map(\.id) == ["a", "d"])

        // The video ends and Up next moves on: the site's index follows.
        together.handleEventForTesting(.ended(item: "ra"))
        together.handleEventForTesting(.videoCleared)
        try await settle()
        precondition(session.loadedItems.map(\.title) == ["A", "D"])
        snapshot = link.snapshot()
        precondition(snapshot.currentIndex == 1 && snapshot.state == "connecting")
        together.handleEventForTesting(.video(item: TogetherItem(id: "rd", title: "D", source: .url("https://e.test/d.mp4"))))
        try await settle()
        precondition(link.snapshot().state == "paused")

        // Jumping back plays the earlier video again; it becomes the current one after the
        // ones already played (same order as Android).
        precondition(link.jump(0) == nil && session.loadedItems.last?.title == "A")
        snapshot = link.snapshot()
        precondition(snapshot.items.map(\.id) == ["d", "a"] && snapshot.currentIndex == 1)
        precondition(link.jump(5) == PageCastPartyFailure(code: "invalid_request"))

        // Replace swaps the site's queued videos for the new list, leaving others' alone.
        precondition(together.enqueue([PartyQueueItem.of(url: "https://e.test/mine.mp4", title: "Mine")]))
        precondition(link.append([["id": "e", "url": "https://e.test/e.mp4", "title": "E"]]) == nil)
        precondition(link.replace([["id": "x", "url": "https://e.test/x.mp4", "title": "X"],
                                   ["id": "y", "url": "https://e.test/y.mp4", "title": "Y"]], startIndex: 0) == nil)
        precondition(session.loadedItems.last?.title == "X" && together.queue.map(\.title) == ["Mine", "Y"])
        precondition(link.knownIds == ["x", "y"])

        // Clearing Up next stops the site being asked for more.
        precondition(!link.isDemandSuppressed)
        together.clearQueue()
        try await settle()
        precondition(link.isDemandSuppressed && link.snapshot().items.map(\.id) == ["x"])

        // Playing something by hand takes over.
        precondition(finished.isEmpty)
        precondition(together.playInParty(url: "https://e.test/other.mp4", title: "Other"))
        try await settle()
        precondition(finished == ["superseded"])
        link.close()

        // Picking another device, or leaving, ends a session.
        let second = try PartyLinkedSession.start(pageRequest([("p", "https://e.test/p.mp4")]), gateway: together)
        var secondFinished: [String] = []
        second.onFinish = { secondFinished.append($0) }
        together.deselectPartyTarget()
        try await settle()
        precondition(secondFinished == ["receiver_changed"])
        precondition(second.validateAvailable()?.endSession == "receiver_changed")
        together.selectPartyTarget()
        let third = try PartyLinkedSession.start(pageRequest([("q", "https://e.test/q.mp4")]), gateway: together)
        var thirdFinished: [String] = []
        third.onFinish = { thirdFinished.append($0) }
        together.leave()
        try await settle()
        precondition(thirdFinished.first == "receiver_stopped" || thirdFinished.first == "receiver_changed")
        print("PASS website linked session in the watch party")
    }

    @MainActor
    static func testWebsiteLinkedSessionLimits() async throws {
        // A guest in an "Ask me" party asks the host for the first video and queues nothing.
        let (guest, guestSession) = hostedParty(host: false, room: "ask1")
        guest.handleEventForTesting(.policy(.ask))
        let asking = try PartyLinkedSession.start(pageRequest([("a", "https://e.test/a.mp4"), ("b", "https://e.test/b.mp4")]),
                                                  gateway: guest)
        precondition(guestSession.playRequests.map(\.title) == ["A"] && guestSession.loadedItems.isEmpty)
        precondition(guest.queue.isEmpty && asking.isDemandSuppressed && asking.knownIds == ["a"])
        precondition(asking.append([["id": "c", "url": "https://e.test/c.mp4"]])?.code == "unsupported_queue")
        precondition(asking.replace([["id": "c", "url": "https://e.test/c.mp4"], ["id": "d", "url": "https://e.test/d.mp4"]],
                                    startIndex: 0)?.code == "unsupported_queue")
        precondition(asking.jump(0) == nil && guestSession.playRequests.count == 2)
        // The host turns on "Anyone": the guest may queue again.
        guest.handleEventForTesting(.policy(.anyone))
        try await settle()
        precondition(!asking.isDemandSuppressed)
        // The host switches to "Only me": the session can't go on.
        var finished: [String] = []
        asking.onFinish = { finished.append($0) }
        guest.handleEventForTesting(.policy(.onlyHost))
        try await settle()
        precondition(finished == ["not_allowed"] && asking.validateAvailable()?.code == "not_allowed")
        asking.close()
        do {
            _ = try PartyLinkedSession.start(pageRequest([("a", "https://e.test/a.mp4")]), gateway: guest)
            preconditionFailure("A guest in a host-only party can't start one")
        } catch let error as PageCastError { precondition(error.code == "not_allowed") }

        // A locked party refuses websites, and so does a link nobody else can open.
        let (host, _) = hostedParty(room: "lock1")
        do {
            _ = try PartyLinkedSession.start(pageRequest([("a", "http://127.0.0.1:9/a.mp4")]), gateway: host)
            preconditionFailure("Local links can't start a party session")
        } catch let error as PageCastError { precondition(error.code == "unsupported_target") }
        host.handleEventForTesting(.locked(true))
        do {
            _ = try PartyLinkedSession.start(pageRequest([("a", "https://e.test/a.mp4")]), gateway: host)
            preconditionFailure("A locked party refuses websites")
        } catch let error as PageCastError { precondition(error.code == "not_allowed") }
        print("PASS website linked session limits in the watch party")
    }
}

private final class StubTogetherBoundary: TogetherNativeBoundary {
    var submitted: [String] = []
    var isAvailable: Bool { true }
    func abiVersion() -> UInt32 { TogetherClient.expectedAbiVersion }
    func start() -> OpaquePointer? { OpaquePointer(bitPattern: 0x101) }
    func submitJson(handle: OpaquePointer, commandJson: String) -> Bool {
        submitted.append(commandJson)
        return true
    }
    func nextEvent(handle: OpaquePointer, waitMs: UInt64) -> String? { nil }
    func cancel(handle: OpaquePointer) {}
    func free(handle: OpaquePointer) {}
}
