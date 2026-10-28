import Foundation

@main
struct ReceiverRemotePolicyTests {
    static func raw(_ text: String) throws -> Any { try JSONSerialization.jsonObject(with: Data(text.utf8)) }
    static func parse(_ text: String) throws -> ReceiverCapabilities { .parse(try raw(text)) }
    static func descriptor(_ engines: [String: Any], surface: String = "player", volume: [String: Any]? = nil) -> ReceiverCapabilities {
        var object: [String: Any] = ["version": 1, "surfaces": [surface: ["engines": engines]]]
        if let volume { object["volume"] = volume }
        return .parse(object)
    }
    @MainActor static func main() async throws {
        let fixtureRoot = URL(fileURLWithPath: CommandLine.arguments[1])
        func fixture(_ name: String) throws -> ReceiverCapabilities {
            .parse(try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureRoot.appendingPathComponent(name + ".json"))))
        }
        var state = ReceiverRemoteState()
        state.connected = true; state.context = "player"; state.durationMs = 120_000; state.active = true
        state.features = ["queue_crud_v1", "stable_item_ids", "command_results", "subtitle_resource_add_v1"]
        state.audioIDs = ["audio-1"]; state.subtitleIDs = ["sub-1"]
        let android = try fixture("android"), apple = try fixture("apple"), desktop = try fixture("desktop"), cli = try fixture("cli")
        func resolve(_ capabilities: ReceiverCapabilities) -> ReceiverRemotePolicy { .resolve(capabilities, state: state) }
        let androidPlayer = resolve(android)
        precondition(androidPlayer.keys.count == 7 && androidPlayer.settings.count == 7)
        precondition(androidPlayer.playerEngines == ["exo", "mpv"] && androidPlayer.hasVolume("relative_step"))
        precondition(androidPlayer.volumeLabel == "Volume" && androidPlayer.allowsKey("mute"))
        precondition(!androidPlayer.hasVolume("absolute") && !androidPlayer.hasPointer)
        let applePlayer = resolve(apple)
        precondition(applePlayer.keys.count == 7 && applePlayer.settings == ["loop", "engine", "speed", "scaling", "subtitle_offset"])
        precondition(applePlayer.playerEngines == ["avplayer", "mpv"] && applePlayer.volumeLabel == "App volume")
        precondition(applePlayer.allowsAbsoluteVolume(scope: "app", level: 0.35))
        precondition(!applePlayer.allowsAbsoluteVolume(scope: "system", level: 0.35))
        precondition(!applePlayer.allowsAbsoluteVolume(scope: "app", level: .nan))
        precondition(!applePlayer.allowsAbsoluteVolume(scope: "app", level: 35))
        precondition(applePlayer.allowsControl("speed:1.5") && applePlayer.allowsControl("scaling:Zoom") && !applePlayer.hasPointer)
        precondition(applePlayer.allowsControl("sub_offset:+100"))
        state.contextEngine = "avplayer"
        state.availability = ["subtitle_offset": false]
        let appleWithoutOverlay = resolve(apple)
        precondition(!appleWithoutOverlay.settings.contains("subtitle_offset") && !appleWithoutOverlay.allowsControl("sub_offset:100"))
        precondition(appleWithoutOverlay.allowsControl("speed:1.5") && appleWithoutOverlay.allowsControl("scaling:Fill"))
        state.availability = ["subtitle_offset": true]
        precondition(resolve(apple).allowsControl("sub_offset:-100"))
        state.contextEngine = nil
        state.availability = [:]
        precondition(applePlayer.allowsControl("audio_track:audio-1") && !applePlayer.allowsControl("audio_track:stale"))
        let desktopPlayer = resolve(desktop)
        precondition(desktopPlayer.keys.isEmpty && desktopPlayer.settings == ["speed", "loop", "scaling", "subtitle_offset"] && !desktopPlayer.hasPointer)
        precondition(desktopPlayer.volumeLabel == "Volume" && desktopPlayer.volume?.fallbackScope == "app")
        precondition(desktopPlayer.hasVolume("relative_step") && desktopPlayer.allowsKey("mute") && desktopPlayer.hasVolume("level_report"))
        precondition(!desktopPlayer.allowsAbsoluteVolume(scope: "app", level: 0.5))
        let cliPlayer = resolve(cli)
        precondition(cliPlayer.keys.isEmpty && cliPlayer.settings.isEmpty && cliPlayer.tracks.isEmpty && cliPlayer.volume?.scope == "app" && cliPlayer.volumeLabel == "App volume")
        precondition(cliPlayer.queue == ["replace", "append", "jump_index"] && !cliPlayer.allowsQueue("jump_item"))
        precondition(cliPlayer.transport.count == 6 && cliPlayer.allowsControl("stop"))

        // Frozen iPhone fallback: no profile reclassification, and original helpers unchanged.
        for value in [ReceiverCapabilities.legacy, .parse(nil), try parse(#"{"version":2,"surfaces":true}"#), try parse(#"{"version":"1","surfaces":false}"#)] {
            let policy = resolve(value)
            precondition(policy.isLegacy && policy.allowsKey("home") && policy.allowsControl("speed:1.5"))
            precondition(RemoteMode.supportsVolume(externalProtocol: nil))
            let legacyInput = RemoteInputLayout(policy: policy, context: "browser", image: false, external: false, externalProtocol: nil)
            precondition(legacyInput.hasNavigation && legacyInput.hasPointer && legacyInput.hasKeyboard)
            precondition(!RemoteMode.supportsRemote(externalProtocol: "google_cast") && !RemoteMode.supportsVolume(externalProtocol: "dlna"))
        }
        // Empty v1 is authoritative even with legacy lists, hints and observed state.
        state.players = ["exo", "mpv"]
        let empty = try parse(#"{"version":1,"surfaces":{},"host":{"osFamily":"android"},"companionHints":["android_tv_remote_v2"]}"#)
        precondition(!resolve(empty).isLegacy && resolve(empty).keys.isEmpty && resolve(empty).transport.isEmpty)
        precondition(!resolve(empty).allowsKey("home") && !resolve(empty).allowsControl("play"))
        let partial = descriptor(["future": ["transport": ["pause", "future_op"], "keys": ["future_key"]]])
        precondition(resolve(partial).transport == ["pause"] && resolve(partial).keys.isEmpty)
        precondition(!resolve(partial).canToggle)
        state.playing = true
        precondition(resolve(partial).canToggle && resolve(partial).toggleCommand == "pause")
        state.playing = false

        // Current engine selects its own complete sets; unknown engine intersects ALL maps.
        let mixed = descriptor([
            "exo": ["transport": ["play", "pause", "toggle"], "settings": ["speed", "loop"]],
            "mpv": ["transport": ["toggle"], "settings": ["loop"]],
            "future": ["transport": ["toggle"]],
        ])
        state.contextEngine = "unknown"
        precondition(resolve(mixed).transport == ["toggle"] && resolve(mixed).settings.isEmpty)
        state.contextEngine = "exo"
        precondition(resolve(mixed).settings == ["speed", "loop"])
        state.availability = ["speed": false, "engine": true]
        precondition(resolve(mixed).settings == ["loop"])
        state.contextEngine = nil; state.settingsEngine = "mpv"; state.availability = [:]
        precondition(resolve(mixed).settings == ["loop"])
        state.settingsEngine = ""; state.players = ["exo"]
        precondition(resolve(mixed).settings == ["speed", "loop"])
        state.players = ["exo", "mpv"]
        precondition(resolve(mixed).settings.isEmpty)

        state.context = "browser"; state.contextEngine = "webview"
        let web = resolve(android)
        precondition(web.text == ["replace", "enter"] && !web.allowsKey("key_delete"))
        precondition(web.allowsKey("key_enter") && web.allowsKey("text:ZmFrZQ=="))
        precondition(web.allowsBrowser("toggle_play") && !web.allowsControl("play"))
        precondition(web.pointer.isSuperset(of: ["move", "click", "down", "up", "scroll", "zoom"]))
        precondition(web.allowsManager("manage_user_scripts") && web.allowsManager("manage_user_agent"))
        precondition(web.allowsLaunch("gecko") && !web.allowsLaunch("future_browser"))
        let browserMix = descriptor([
            "webview": ["pointer": ["move", "zoom"], "text": ["replace"]],
            "gecko": ["pointer": ["move"], "text": ["replace"]],
        ], surface: "browser")
        state.contextEngine = "unknown"
        precondition(resolve(browserMix).pointer == ["move"] && resolve(browserMix).text == ["replace"])
        state.contextEngine = "webview"
        precondition(resolve(browserMix).pointer == ["move", "zoom"])
        precondition(resolve(apple).pointer.isEmpty && resolve(apple).keys.isEmpty && resolve(apple).browser.isEmpty)
        state.context = "idle"
        precondition(resolve(android).keys.isEmpty && resolve(android).transport.isEmpty && resolve(android).allowsLaunch(nil))
        precondition(resolve(apple).hasVolume("relative_step")) // Common volume is surface-independent.
        let idleApple = resolve(apple)
        precondition(idleApple.keys == ["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home"])
        precondition(idleApple.hasDpad && idleApple.keys.allSatisfy(idleApple.allowsKey))
        let idleLayout = RemoteInputLayout(policy: idleApple, context: "idle", image: false, external: false, externalProtocol: nil)
        precondition(idleLayout.hasNavigation && idleLayout.surface(in: "idle", preferences: .init()) == .swipe)
        let noApp = descriptor(["mpv": ["keys": ["dpad_left"]]])
        precondition(resolve(noApp).keys.isEmpty && !resolve(noApp).hasDpad)
        let appEngines = descriptor(["native": ["keys": ["dpad_up", "back"]], "future": ["keys": ["back", "home"]]], surface: "app")
        state.contextEngine = "native"
        precondition(resolve(appEngines).keys == ["back"], "Idle ignores stale engine reports and intersects all app engines")
        state.context = "player"
        precondition(resolve(appEngines).keys.isEmpty, "App keys never leak into player controls")
        state.context = "browser"
        precondition(resolve(appEngines).keys.isEmpty)
        state.context = "idle"
        precondition(ReceiverRemotePolicy.resolve(.legacy, state: state).isLegacy)
        state.context = "mirror"
        precondition(resolve(android).keys.isEmpty && resolve(android).settings.isEmpty)
        state.context = "player"; state.contextEngine = nil; state.players = []; state.mediaKind = "image"
        precondition(resolve(desktop).pointer == ["move", "scroll", "zoom", "rotate", "transform_anchor", "reset"])
        precondition(resolve(desktop).settings.isEmpty && resolve(desktop).tracks.isEmpty && !resolve(desktop).transport.contains("seek_absolute"))
        state.durationMs = 0
        precondition(!resolve(desktop).canToggle)
        state.mediaKind = "audio"; state.durationMs = 1000
        precondition(resolve(desktop).tracks == ["audio_select"])
        state.mediaKind = "video"; state.live = true
        precondition(!resolve(android).settings.contains("loop") && !resolve(android).transport.contains("seek_relative"))
        state.live = false; state.seekable = false
        precondition(!resolve(android).transport.contains("seek_absolute"))
        state.seekable = true; state.features = []
        precondition(!resolve(apple).queue.contains("remove") && !resolve(apple).tracks.contains("subtitle_resource_add"))
        state.connected = false
        precondition(!resolve(apple).allowsKey("home") && !resolve(apple).allowsControl("stop") && !resolve(apple).allowsAbsoluteVolume(scope: "app", level: 0.5))
        state.connected = true
        let relativeOnly = descriptor(["mpv": ["transport": ["seek_relative"]]])
        precondition(resolve(relativeOnly).allowsControl("seek_forward") && !resolve(relativeOnly).allowsControl("seek_to:1000"))
        state.context = "browser"; state.positionMs = 500
        let browserRelative = descriptor(["webview": ["transport": ["seek_relative"]]], surface: "browser")
        precondition(resolve(browserRelative).browserRelativeSeek(forward: true) == "seek_to:1000")
        precondition(!resolve(browserRelative).allowsBrowser("seek_to:1000"))
        state.durationMs = .max; state.positionMs = .max
        precondition(resolve(browserRelative).browserRelativeSeek(forward: true) == "seek_to:\(Int64.max)")
        state.durationMs = 1000; state.positionMs = 500
        let browserPlayOnly = descriptor(["webview": ["transport": ["play"]]], surface: "browser")
        precondition(!resolve(browserPlayOnly).allowsBrowser("toggle_play") && !resolve(browserPlayOnly).allowsControl("play"))
        state.context = "player"

        for text in [
            #"{"version":1}"#, #"{"version":1,"surfaces":null}"#,
            #"{"version":1,"surfaces":{"player":{}}}"#,
            #"{"version":1,"surfaces":{"player":{"engines":{"mpv":{"keys":["home","home"]}}}}}"#,
            #"{"version":1,"surfaces":{"player":{"engines":{"mpv":{"transport":[1]}}}}}"#,
            #"{"version":1,"surfaces":{},"volume":{"operations":[]}}"#,
            #"{"version":1,"surfaces":{},"volume":{"scope":"app","step":true}}"#,
            #"{"version":1,"surfaces":{},"volume":{"scope":"app","step":0}}"#,
            #"{"version":1,"surfaces":{},"active":"player"}"#,
        ] {
            let value = try parse(text)
            precondition(value == .malformedV1 && !resolve(value).isLegacy && !resolve(value).allowsKey("home"))
            precondition(ReceiverCapabilities.restore(value.storage) == .malformedV1)
        }
        let booleanVersion = try parse(#"{"version":true,"surfaces":{}}"#)
        precondition(booleanVersion == .legacy)
        let tooMany = descriptor(Dictionary(uniqueKeysWithValues: (0..<17).map { ("engine\($0)", [:] as [String: Any]) }))
        precondition(tooMany == .malformedV1)
        let huge: [String: Any] = ["version": 1, "surfaces": [:], "host": ["osFamily": String(repeating: "x", count: 65536)]]
        precondition(ReceiverCapabilities.parse(huge) == .malformedV1)
        var futureHuge = huge; futureHuge["version"] = 2
        precondition(ReceiverCapabilities.parse(futureHuge) == .legacy)
        precondition(descriptor(["mpv": ["keys": [String(repeating: "x", count: 65)]]]) == .malformedV1)
        precondition(descriptor(["mpv": ["keys": (0..<65).map { "key\($0)" }]]) == .malformedV1)
        let futureIDs = descriptor(["future-engine": ["transport": ["future-operation"]]], surface: "future-surface")
        guard case .descriptor(let future) = futureIDs else { preconditionFailure("open identifiers lost") }
        precondition(future.surfaces["future-surface"]?["future-engine"]?["transport"] == ["future-operation"])
        let unknownScope = try parse(#"{"version":1,"surfaces":{},"volume":{"scope":"future","operations":["absolute"]}}"#)
        precondition(unknownScope != .malformedV1)
        let futureVolume = descriptor([:], volume: ["scope": "future", "operations": ["relative_step", "absolute"]])
        precondition(resolve(futureVolume).volume == nil)
        let noVolume = descriptor(["avplayer": ["transport": ["toggle"]]])
        precondition(!resolve(noVolume).hasVolume("relative_step") && !resolve(noVolume).allowsKey("volume_up"))

        // Auth and protected-bundle fixtures use the same reader; no plaintext pairing field is consulted.
        let contractRoot = URL(fileURLWithPath: CommandLine.arguments[2])
        for receiver in ["android-tv", "apple-tv", "desktop", "cli", "unknown-identifiers"] {
            for phase in ["auth", "bundle"] {
                let data = try Data(contentsOf: contractRoot.appendingPathComponent(receiver + "-" + phase + "-v1.json"))
                let envelope = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                let parsed = ReceiverCapabilities.parse(envelope["capabilities"])
                guard case .descriptor = parsed else { preconditionFailure("valid v1 rejected") }
                guard case .descriptor = ReceiverCapabilities.restore(parsed.storage) else { preconditionFailure("cache lost descriptor") }
            }
        }
        try await ReceiverCapabilitySessionTests.run(contractRoot: contractRoot)
        let coordinator = ConnectionCoordinator()
        coordinator.handle(#"{"type":"context","active":"player","engine":"mpv"}"#)
        coordinator.handle(#"{"type":"player_settings","engine":"mpv","loop":true,"speedAvailable":false,"loopAvailable":false,"engineAvailable":true}"#)
        coordinator.handle(#"{"type":"volume_state","scope":"app","level":0.35,"muted":true}"#)
        coordinator.handle(#"{"type":"volume_state","scope":"app","muted":false}"#)
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(coordinator.contextEngine == "mpv" && coordinator.receiverLoop == true)
        precondition(coordinator.receiverSettingsAvailability == ["speed": false, "loop": false, "engine": true])
        precondition(coordinator.receiverVolumeLevel == 0.35 && coordinator.receiverVolumeMuted == false)
        coordinator.handle(#"{"type":"volume_state","scope":"app","level":35}"#)
        coordinator.handle(#"{"type":"context","active":"browser","engine":"retired"}"#, isCurrentSession: { false })
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(coordinator.receiverVolumeLevel == 0.35 && coordinator.contextEngine == "mpv")
        coordinator.handle(#"{"type":"context","active":"idle"}"#)
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(coordinator.receiverVolumeLevel == 0.35, "Common volume survives surface changes")
        coordinator.clearReceiverMetadata()
        precondition(coordinator.contextEngine == nil && coordinator.receiverVolumeLevel == nil && coordinator.receiverLoop == nil)
        let command = try JSONSerialization.jsonObject(with: Data(WireProtocol.volumeCommand(scope: "app", level: 0.35).utf8)) as! [String: Any]
        precondition(command["action"] as? String == "volume")
        precondition((command["payload"] as? [String: Any])?["level"] as? Double == 0.35)
        var device = PairedDevice(ip: "127.0.0.1", port: 8765, name: "Fixture")
        device.receiverCapabilitiesData = apple.storage
        let encoded = try JSONEncoder().encode(device)
        let restored = try JSONDecoder().decode(PairedDevice.self, from: encoded)
        precondition(restored.receiverCapabilitiesData == apple.storage)
        var oldRecord = try JSONSerialization.jsonObject(with: encoded) as! [String: Any]
        oldRecord.removeValue(forKey: "receiverCapabilitiesData")
        let oldDevice = try JSONDecoder().decode(PairedDevice.self, from: JSONSerialization.data(withJSONObject: oldRecord))
        precondition(oldDevice.receiverCapabilitiesData == nil)
        print("PASS: current Android/Apple/Desktop/CLI builders; bounded decoding; legacy/empty/malformed/unknown; engine intersections; context/media/settings/track/queue gates; app/system/fallback volume; state fencing and native volume wire format")
    }
}
