import Foundation

struct ReceiverRemoteState {
    var connected = false
    var context = "idle"
    var contextEngine: String?
    var settingsEngine = ""
    var players: [String] = []
    var features: Set<String> = []
    var mediaKind = "video"
    var durationMs: Int64 = 0
    var positionMs: Int64 = 0
    var active = false
    var playing = false
    var live = false
    var seekable = true
    var availability: [String: Bool] = [:]
    var audioIDs: Set<String> = []
    var subtitleIDs: Set<String> = []
}

/// The same resolved value authorizes UI controls and every native command route.
/// Legacy callers keep their existing eligibility checks and dispatch unchanged.
struct ReceiverRemotePolicy {
    let isLegacy: Bool
    let state: ReceiverRemoteState
    var transport: Set<String> = []
    var keys: Set<String> = []
    var pointer: Set<String> = []
    var text: Set<String> = []
    var settings: Set<String> = []
    var tracks: Set<String> = []
    var queue: Set<String> = []
    var browser: Set<String> = []
    var reporting: Set<String> = []
    var playerEngines: [String] = []
    var launchEngines: [String] = []
    var volume: ReceiverVolume?
    var hostOS: String?

    static let playerLabels = ["exo": "ExoPlayer", "mpv": "MPV", "avplayer": "AVPlayer", "internal_mpv": "MPV"]
    static let browserLabels = ["webview": "System WebView", "gecko": "GeckoView"]

    static func resolve(_ capabilities: ReceiverCapabilities, state: ReceiverRemoteState) -> Self {
        if capabilities == .legacy { return Self(isLegacy: true, state: state) }
        var policy = Self(isLegacy: false, state: state)
        guard case .descriptor(let descriptor) = capabilities else { return policy }
        let player = state.context == "player", browser = state.context == "browser"
        let engines = descriptor.surfaces[player ? "player" : browser ? "browser" : state.context == "idle" ? "app" : ""] ?? [:]
        let reported = (player || browser ? state.contextEngine : nil) ?? (player && !state.settingsEngine.isEmpty || browser && engines[state.settingsEngine] != nil ? state.settingsEngine : nil)
        let unique = Set(state.players.filter { engines[$0] != nil })
        let candidate = reported ?? (player && unique.count == 1 ? unique.first : nil)
        let operations: ReceiverDescriptor.Operations
        if let candidate, let engine = engines[candidate] { operations = engine }
        else {
            // Unknown engines, including future IDs, participate in the intersection.
            operations = ReceiverCapabilities.operationFields.reduce(into: [:]) { result, field in
                result[field] = engines.values.map { $0[field] ?? [] }.reduce(nil as Set<String>?) { common, next in
                    common.map { $0.intersection(next) } ?? next
                } ?? []
            }
        }
        func ids(_ field: String) -> Set<String> { operations[field] ?? [] }
        let image = state.mediaKind == "image", video = state.mediaKind == "video"
        let mediaTransport = player && (!image || state.durationMs > 0) || browser && state.active && state.durationMs > 0
        if mediaTransport { policy.transport = ids("transport").intersection(player ? ["play", "pause", "toggle"] : ["toggle"]) }
        if player && ids("transport").contains("stop") { policy.transport.insert("stop") }
        if (player || browser && state.active && state.durationMs > 0) && !image && !state.live && state.seekable {
            policy.transport.formUnion(ids("transport").intersection(["seek_relative"]))
            if state.durationMs > 0 { policy.transport.formUnion(ids("transport").intersection(["seek_absolute"])) }
        }
        policy.keys = ids("keys").intersection(["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home", "key_enter"])
        if browser {
            policy.pointer = ids("pointer").intersection(["move", "click", "down", "up", "scroll", "zoom"])
            if !policy.pointer.isSuperset(of: ["down", "move", "up"]) { policy.pointer.subtract(["down", "up"]) }
            policy.text = ids("text").intersection(["replace", "delete", "enter"])
            policy.browser = ids("browser").intersection(["forward", "reload", "cycle_source", "unmute", "fullscreen", "manage_user_scripts", "manage_user_agent"])
        }
        if player && image {
            let imageOps = ids("imageTransforms")
            for (operation, event) in ["pan": "move", "scroll": "scroll", "zoom": "zoom", "rotate": "rotate", "anchor": "transform_anchor", "reset": "reset"] where imageOps.contains(operation) {
                policy.pointer.insert(event)
            }
        }
        if player && !image {
            policy.settings = ids("settings").intersection(["loop", "engine", "quality", "speed", "scaling", "audio_boost", "subtitle_offset"])
            policy.settings = policy.settings.filter { state.availability[$0] != false && ($0 != "loop" || !state.live) }
            policy.tracks = ids("tracks").intersection(["audio_select"])
            if video { policy.tracks.formUnion(ids("tracks").intersection(["subtitle_select", "subtitle_url_add", "subtitle_resource_add"])) }
            if !state.features.contains("subtitle_resource_add_v1") { policy.tracks.remove("subtitle_resource_add") }
        }
        if player {
            policy.queue = ids("queue").intersection(["replace", "append", "jump_index", "jump_item", "remove", "move", "clear"])
            if !Set(["queue_crud_v1", "stable_item_ids", "command_results"]).isSubset(of: state.features) {
                policy.queue.subtract(["jump_item", "remove", "move", "clear"])
            }
        }
        policy.reporting = ids("reporting")
        if policy.settings.contains("engine") { policy.playerEngines = (descriptor.surfaces["player"] ?? [:]).keys.filter { playerLabels[$0] != nil }.sorted() }
        policy.launchEngines = (descriptor.surfaces["browser"] ?? [:]).filter { browserLabels[$0.key] != nil && ($0.value["browser"] ?? []).contains("launch") }.keys.sorted()
        policy.volume = descriptor.volume.flatMap { ["system", "app"].contains($0.scope) ? $0 : nil }
        policy.hostOS = descriptor.host["osFamily"]
        return policy
    }

    // Plain "Volume" for the device's own volume; "App volume" only when just the player is controlled.
    var volumeLabel: String { volume?.scope == "app" ? "App volume" : "Volume" }
    var canToggle: Bool { transport.contains("toggle") || transport.contains(state.playing ? "pause" : "play") }
    var toggleCommand: String { transport.contains("toggle") ? "toggle" : state.playing ? "pause" : "play" }
    var hasPointer: Bool { !pointer.isEmpty }
    var idleSelectKey: String? {
        !isLegacy && state.connected && state.context == "idle" && keys.contains("dpad_center") ? "dpad_center" : nil
    }
    var hasDpad: Bool { !keys.isDisjoint(with: ["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center"]) }
    /// Open surfaces follow the same live permissions as their entry buttons.
    var allowedPresentations: Set<String> {
        let all: Set<String> = ["settings", "subtitles", "addSubtitle", "more", "scripts", "agents", "airPlayQueue"]
        if isLegacy { return all }
        guard state.connected else { return [] }
        var result: Set<String> = []
        if !settings.isEmpty { result.insert("settings") }
        if !tracks.isDisjoint(with: ["subtitle_select", "subtitle_url_add", "subtitle_resource_add"]) { result.insert("subtitles") }
        if !tracks.isDisjoint(with: ["subtitle_url_add", "subtitle_resource_add"]) { result.insert("addSubtitle") }
        if !browser.isDisjoint(with: ["cycle_source", "unmute", "manage_user_scripts", "manage_user_agent"]) { result.insert("more") }
        if allowsManager("manage_user_scripts") { result.insert("scripts") }
        if allowsManager("manage_user_agent") { result.insert("agents") }
        return result
    }
    func hasVolume(_ operation: String) -> Bool { volume?.operations.contains(operation) == true }
    func allowsKey(_ key: String) -> Bool {
        if isLegacy { return true }
        guard state.connected else { return false }
        if key.hasPrefix("text:") { return text.contains("replace") }
        if key == "key_delete" { return text.contains("delete") }
        if key == "key_enter" { return keys.contains(key) || text.contains("enter") }
        if key == "volume_up" || key == "volume_down" { return hasVolume("relative_step") }
        if key == "mute" { return hasVolume("mute_toggle") }
        return keys.contains(key)
    }
    func allowsControl(_ command: String) -> Bool {
        if isLegacy { return true }
        guard state.connected, state.context == "player" else { return false }
        let parts = command.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        let value = parts.count == 2 ? parts[1] : ""
        switch parts[0] {
        case "play", "pause", "toggle", "stop": return transport.contains(parts[0])
        case "seek_back", "seek_forward": return transport.contains("seek_relative")
        case "seek_to": return transport.contains("seek_absolute")
        case "loop_on", "loop_off": return settings.contains("loop")
        case "switch_player": return settings.contains("engine") && playerEngines.contains(value)
        case "video_quality": return settings.contains("quality")
        case "speed": return settings.contains("speed")
        case "scaling": return settings.contains("scaling")
        case "audio_boost": return settings.contains("audio_boost")
        case "sub_offset": return settings.contains("subtitle_offset")
        case "audio_track": return tracks.contains("audio_select") && state.audioIDs.contains(value)
        case "sub_track": return tracks.contains("subtitle_select") && (state.subtitleIDs.contains(value) || ["none", "off", "no", "-1"].contains(value))
        case "add_subtitle": return tracks.contains("subtitle_url_add")
        default: return false
        }
    }
    func allowsBrowser(_ action: String) -> Bool {
        if isLegacy { return true }
        guard state.connected, state.context == "browser" else { return false }
        switch action {
        case "toggle_play": return canToggle
        case "seek_back", "seek_forward": return transport.contains("seek_relative")
        case "forward": return browser.contains("forward")
        case "refresh": return browser.contains("reload")
        case "video_target_cycle": return browser.contains("cycle_source")
        case "video_unmute": return browser.contains("unmute")
        case "maximize_video", "restore_video": return browser.contains("fullscreen")
        default: return action.hasPrefix("seek_to:") && transport.contains("seek_absolute")
        }
    }
    func allowsMouse(_ event: String) -> Bool { isLegacy || state.connected && pointer.contains(event) }
    func browserRelativeSeek(forward: Bool) -> String? {
        guard !isLegacy, state.connected, state.context == "browser", transport.contains("seek_relative") else { return nil }
        let duration = max(0, state.durationMs)
        let position = min(duration, max(0, state.positionMs))
        let target = forward ? position + min(10_000, duration - position) : max(0, position - 10_000)
        return "seek_to:\(target)"
    }
    func allowsQueue(_ operation: String) -> Bool { isLegacy || state.connected && queue.contains(operation) }
    func allowsManager(_ operation: String) -> Bool { isLegacy || state.connected && browser.contains(operation) }
    func allowsLaunch(_ engine: String?) -> Bool {
        if isLegacy { return true }
        guard state.connected else { return false }
        guard let engine, engine != "tv" else { return !launchEngines.isEmpty }
        return launchEngines.contains(engine)
    }
    func allowsAbsoluteVolume(scope: String, level: Double) -> Bool {
        !isLegacy && state.connected && hasVolume("absolute") && scope == volume?.scope && level.isFinite && (0...1).contains(level)
    }
}
