import Foundation

/// Host-only tests: no sockets, receivers, simulators or credential stores are opened.
enum ReceiverCapabilitySessionTests {
    static func check(_ condition: @autoclosure () throws -> Bool, _ message: String = "", file: StaticString = #file, line: UInt = #line) throws {
        let value = try condition()
        precondition(value, message, file: file, line: line)
    }
    static func object(_ text: String) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
    }
    static func frame(_ revision: String, _ descriptor: String = #"{"version":1,"surfaces":{}}"#) throws -> String {
        #"{"type":"receiver_capabilities","revision":\#(revision),"capabilities":\#(descriptor)}"#
    }
    static let full = #"{"version":1,"surfaces":{"browser":{"engines":{"webview":{"pointer":["move","zoom"],"text":["replace","enter"],"browser":["manage_user_scripts","manage_user_agent"]}}}},"volume":{"scope":"system","operations":["relative_step"]},"companionHints":["android_tv_remote_v2"]}"#
    static func metadata(_ descriptor: String = full, supported: Bool = true) throws -> [String: Any] {
        try object(#"{"capabilities":\#(descriptor),"features":\#(supported ? "[\"receiver_capabilities_v1\"]" : "[]")}"#)
    }
    static func subscribed(_ descriptor: String = full) throws -> (ReceiverCapabilitySession, UUID) {
        let owner = ReceiverCapabilitySession()
        let id = owner.begin()
        try check(owner.acceptAuthenticated(try metadata(descriptor), sessionID: id))
        try check(owner.subscription(sessionID: id) != nil)
        return (owner, id)
    }

    @MainActor static func run(contractRoot: URL) async throws {
        func fixture(_ name: String) throws -> [String: Any] {
            try JSONSerialization.jsonObject(with: Data(contentsOf: contractRoot.appendingPathComponent(name + ".json"))) as! [String: Any]
        }
        func liveFixture(_ name: String) throws -> String {
            try String(contentsOf: contractRoot.appendingPathComponent(name + ".json"), encoding: .utf8)
        }
        // Subscription follows accepted authenticated metadata, once per current socket.
        let owner = ReceiverCapabilitySession()
        let first = owner.begin()
        try check(owner.subscription(sessionID: first) == nil)
        try check(owner.observe(try frame("1"), sessionID: first) == nil)
        try check(!owner.snapshot.authenticated && owner.snapshot.capabilities == .legacy)
        try check(owner.acceptAuthenticated(try metadata(supported: false), sessionID: first))
        let beforeUnsupported = owner.snapshot.capabilities
        try check(owner.subscription(sessionID: first) == nil)
        try check(owner.observe(try frame("1"), sessionID: first) == nil)
        try check(owner.snapshot.capabilities == beforeUnsupported)
        let next = owner.begin()
        try check(!owner.acceptAuthenticated(try metadata(), sessionID: first))
        try check(owner.acceptAuthenticated(try metadata(), sessionID: next))
        try check(owner.observe(try frame("1"), sessionID: next) == nil, "Unsolicited update")
        let command = owner.subscription(sessionID: next)!
        try check(NSDictionary(dictionary: try object(command)).isEqual(to: try fixture("live-subscribe")))
        try check(WireProtocol.applyingHistoryPreference(command, prevent: true) == command)
        try check(owner.subscription(sessionID: next) == nil && owner.subscription(sessionID: first) == nil)

        // Auth and verified protected bundles enter the same trusted metadata API.
        for phase in ["auth", "bundle"] {
            var trusted = try fixture("android-tv-" + phase + "-v1")
            trusted["features"] = ["receiver_capabilities_v1"]
            let session = ReceiverCapabilitySession(), id = UUID()
            try check(!session.acceptAuthenticated(trusted, sessionID: id))
            try check(session.acceptAuthenticated(trusted, sessionID: session.snapshot.id))
            try check(session.subscription(sessionID: session.snapshot.id) != nil)
        }

        // Complete replacement removes every optional field; old/repeated auth cannot restore it.
        let empty = ReceiverCapabilities.parse(try object(frame("1"))["capabilities"])
        try check(owner.observe(try liveFixture("live-update-v1"), sessionID: next) == empty)
        try check(owner.snapshot.capabilities == empty)
        guard case .descriptor(let replaced) = owner.snapshot.capabilities else { preconditionFailure() }
        try check(replaced.surfaces.isEmpty && replaced.volume == nil && replaced.companionHints.isEmpty)
        try check(owner.observe(try frame("1", full), sessionID: next) == nil)
        try check(owner.observe(try frame("0", full), sessionID: next) == nil)
        try check(!owner.acceptAuthenticated(try metadata(), sessionID: next))
        try check(owner.snapshot.capabilities == empty && owner.subscription(sessionID: next) == nil)

        // Opaque future shape advances ordering while retaining the last descriptor.
        let (future, futureID) = try subscribed()
        let beforeFuture = future.snapshot.capabilities
        try check(future.observe(try liveFixture("live-update-future"), sessionID: futureID) == nil)
        try check(future.observe(try frame("1"), sessionID: futureID) == nil)
        try check(future.observe(try frame("2"), sessionID: futureID) == nil)
        try check(future.snapshot.capabilities == beforeFuture)
        try check(future.observe(try frame("3"), sessionID: futureID) == empty)
        for version in [#""2""#, "true", "null"] {
            let (session, id) = try subscribed()
            try check(session.observe(try frame("9", #"{"version":\#(version),"future":[true]}"#), sessionID: id) == nil)
            try check(session.observe(try frame("8"), sessionID: id) == nil)
            try check(session.snapshot.capabilities == beforeFuture)
        }

        // Malformed recognized v1 consumes ordering and fails closed until a newer valid v1.
        let (bad, badID) = try subscribed()
        try check(bad.observe(try liveFixture("invalid-live-malformed-v1"), sessionID: badID) == .malformedV1)
        try check(bad.observe(try frame("1", full), sessionID: badID) == nil)
        try check(bad.observe(try frame("2", #"{"version":2,"surfaces":true}"#), sessionID: badID) == nil)
        try check(bad.snapshot.capabilities == .malformedV1)
        try check(bad.observe(try frame("3", full), sessionID: badID) == beforeFuture)
        let oversized = #"{"version":1,"surfaces":{},"companionHints":["\#(String(repeating: "x", count: 70_000))"]}"#
        try check(bad.observe(try frame("4", oversized), sessionID: badID) == .malformedV1)

        // Invalid revisions/envelopes never consume revision 1 (including CFBoolean bridging).
        var invalid: [String] = try ["invalid-live-fractional-revision", "invalid-live-large-revision", "invalid-live-string-revision", "invalid-live-zero-revision", "invalid-live-subscribe-payload"].map(liveFixture)
        for revision in ["-1", "true", "false", "null", #""1""#, "1.00001", "9007199254740991.1", "1e100", "1.00000000000000000000000000000000000000001", "9007199254740991.00000000000000000000001"] {
            invalid.append(try frame(revision))
        }
        for text in [
            #"{"type":"receiver_capabilities","revision":1,"revision":2,"capabilities":{"version":1,"surfaces":{}}}"#,
            #"{"type":"receiver_capabilities","capabilities":{"version":1,"surfaces":{}}}"#,
            #"{"type":"receiver_capabilities","revision":1}"#,
            #"{"type":"receiver_capabilities","revision":1,"capabilities":[]}"#,
            #"{"type":"receiver_capabilities","revision":1,"capabilities":{"surfaces":{}}}"#,
            #"{"type":"receiver_capabilities","revision":1,"capabilities":{"version":{},"surfaces":{}}}"#,
            #"{"type":"receiver_capabilities","revision":1,"capabilities":{"version":[],"surfaces":{}}}"#,
            #"{"type":"receiver_capabilities","revision":1,"capabilities":{"version":1,"surfaces":{}},"extra":true}"#,
            #"{"type":"status","revision":1,"capabilities":{"version":1,"surfaces":{}}}"#,
        ] { invalid.append(text) }
        for (index, update) in invalid.enumerated() {
            let (session, id) = try subscribed()
            try check(session.observe(update, sessionID: id) == nil, "Invalid envelope case \(index)")
            try check(session.snapshot.capabilities == beforeFuture)
            try check(session.observe(try frame("1"), sessionID: id) == empty)
        }
        for revision in ["1.0", "1e0", "1e-0", "1e+0", "1000e-3", "0.01e2", "9007199254740991", "9.007199254740991e15", "1000000000000000000000000000000000000000e-39"] {
            let (session, id) = try subscribed()
            try check(session.observe(try frame(revision), sessionID: id) == empty)
            try check(session.observe(try frame("1", full), sessionID: id) == nil)
        }

        // Reordered/escaped envelope keys and nested lookalikes do not confuse raw number validation.
        let (escaped, escapedID) = try subscribed()
        let escapedFrame = #"{"capabilities":{"version":1,"surfaces":{}},"rev\u0069sion":1,"type":"receiver_capabilities"}"#
        try check(escaped.observe(escapedFrame, sessionID: escapedID) == empty)
        let futureNested = #"{"capabilities":{"version":2,"future":[{"revision":999,"text":"\"revision\":1"}]},"type":"receiver_capabilities","revision":5}"#
        try check(escaped.observe(futureNested, sessionID: escapedID) == nil)
        try check(escaped.observe(try frame("4", full), sessionID: escapedID) == nil)
        try check(escaped.snapshot.capabilities == empty)

        // New sockets reset ordering and auth; retired callbacks cannot mutate new state.
        let (switched, retired) = try subscribed()
        try check(switched.observe(try frame("9007199254740991"), sessionID: retired) == empty)
        let current = switched.begin()
        try check(!switched.snapshot.authenticated && switched.snapshot.capabilities == .legacy)
        try check(switched.observe(try frame("2", full), sessionID: retired) == nil)
        try check(switched.observe(try frame("1"), sessionID: current) == nil)
        try check(!switched.acceptAuthenticated(try metadata(), sessionID: retired))
        try check(switched.acceptAuthenticated(try metadata(), sessionID: current))
        try check(switched.subscription(sessionID: current) != nil)
        try check(switched.observe(try frame("9007199254740991"), sessionID: retired) == nil)
        try check(switched.snapshot.capabilities == beforeFuture)
        try check(switched.observe(try frame("1"), sessionID: current) == empty)
        switched.begin()
        try check(switched.observe(try frame("2", full), sessionID: current) == nil)

        // Actual coordinator/media state is untouched by metadata replacement.
        let coordinator = ConnectionCoordinator()
        coordinator.handle(#"{"type":"context","active":"browser","engine":"webview"}"#)
        coordinator.handle(#"{"type":"status","state":"playing","position":12,"duration":120000,"title":"Fixture","playbackId":"p","currentItemId":"item"}"#)
        coordinator.handle(#"{"type":"volume_state","scope":"system","level":0.4}"#)
        coordinator.handle(#"{"type":"player_settings","engine":"webview","speedAvailable":false}"#)
        try await Task.sleep(nanoseconds: 30_000_000)
        let playback = coordinator.playback, volume = coordinator.receiverVolumeLevel
        let availability = coordinator.receiverSettingsAvailability
        let (live, liveID) = try subscribed()
        var state = ReceiverRemoteState()
        state.connected = true; state.context = "browser"; state.contextEngine = nil
        let policy = { ReceiverRemotePolicy.resolve(live.snapshot.capabilities, state: state) }
        try check(policy().allowsMouse("zoom") && policy().allowsKey("text:ZHJhZnQ="))
        try check(policy().allowedPresentations.isSuperset(of: ["scripts", "agents", "more"]))
        let gecko = #"{"version":1,"surfaces":{"browser":{"engines":{"webview":{"pointer":["move","zoom"],"text":["replace","enter"]},"gecko":{"pointer":["move"],"text":["replace","enter"]}}}}}"#
        try check(live.observe(try frame("1", gecko), sessionID: liveID) != nil)
        // Before any UI publication, the same execution resolver denies removed zoom.
        try check(!policy().allowsMouse("zoom") && policy().allowsMouse("move"))
        try check(policy().text.contains("replace") && policy().allowedPresentations.isEmpty)
        state.contextEngine = "webview"
        try check(policy().allowsMouse("zoom"), "Known WebView keeps its engine-specific zoom")
        state.contextEngine = "gecko"
        try check(!policy().allowsMouse("zoom"))
        try check(live.observe(try frame("2", full), sessionID: liveID) != nil)
        state.contextEngine = nil
        try check(policy().allowsMouse("zoom"))
        try check(live.observe(try frame("3"), sessionID: liveID) != nil)
        try check(!policy().allowsKey("text:ZHJhZnQ=") && !policy().allowsKey("key_enter"))
        try check(policy().allowedPresentations.isEmpty)
        try check(coordinator.playback?.playbackId == playback?.playbackId && coordinator.playback?.currentItemId == playback?.currentItemId)
        try check(coordinator.playback?.positionMs == 12 && coordinator.contextEngine == "webview")
        try check(coordinator.receiverVolumeLevel == volume && coordinator.receiverSettingsAvailability == availability)
        print("PASS: live subscription/auth/bundle; exact envelope/revision bounds; replacement/future/malformed; reconnect/stale sessions; immediate Gecko policy removal/restoration; unchanged media/context/volume/settings")
    }
}
