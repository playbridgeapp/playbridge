import Foundation

/// Stage 5 v1 ReceiverCapabilities descriptor and auth/bundle payload builder for Apple TV.
/// Implements the contract defined in protocol/docs/RECEIVER_CAPABILITIES.md.
enum TvCapabilities {
    static let defaultFeatures = [
        "queue_crud_v1",
        "stable_item_ids",
        "command_results",
        "subtitle_resource_add_v1",
        "progress_webhook_v1",
    ]

    private static let playerTransport = [
        "play",
        "pause",
        "toggle",
        "stop",
        "seek_absolute",
        "seek_relative",
    ]

    private static let playerKeys = [
        "dpad_up",
        "dpad_down",
        "dpad_left",
        "dpad_right",
        "dpad_center",
        "back",
        "home",
    ]

    private static let playerTracks = [
        "audio_select",
        "subtitle_select",
        "subtitle_url_add",
        "subtitle_resource_add",
    ]

    private static let playerQueue = [
        "replace",
        "append",
        "jump_index",
        "jump_item",
        "remove",
        "move",
        "clear",
    ]

    private static let playerReporting = [
        "status",
        "context",
        "tracks",
        "playlist_status",
        "player_settings",
        "timeline",
        "buffered",
    ]

    /// Common app-volume route. Independent of the player surface. No system
    /// scope and no fallbackScope. `step` matches `PlayerAppVolume.step`.
    private static let appVolume: [String: Any] = [
        "scope": "app",
        "operations": [
            "relative_step",
            "absolute",
            "mute_toggle",
            "level_report",
            "mute_report",
        ],
        "step": PlayerAppVolume.step,
    ]

    /// Each engine owns its settings list. AVPlayer restricts subtitle_offset at
    /// runtime to the PlayBridge sidecar overlay via subtitleOffsetAvailable.
    private static func buildPlayerEngineCapabilities(for engine: PlaybackEngine) -> [String: Any] {
        let settings: [String]
        switch engine {
        case .mpv: settings = ["loop", "engine", "speed", "scaling", "subtitle_offset"]
        case .avplayer: settings = ["loop", "engine", "speed", "scaling", "subtitle_offset"]
        }
        return [
            "transport": playerTransport,
            "keys": playerKeys,
            "settings": settings,
            "tracks": playerTracks,
            "queue": playerQueue,
            "reporting": playerReporting,
        ]
    }

    /// Constructs the Stage 5 v1 ReceiverCapabilities descriptor for Apple TV.
    static func buildDescriptor(engines: [PlaybackEngine] = PlaybackEngine.allCases) -> [String: Any] {
        var engineMap: [String: Any] = [:]
        for engine in engines {
            engineMap[engine.rawValue] = buildPlayerEngineCapabilities(for: engine)
        }

        let descriptor: [String: Any] = [
            "version": 1,
            "surfaces": [
                "player": [
                    "engines": engineMap,
                ]
            ],
            "volume": appVolume,
            "host": [
                "osFamily": "tvos",
                "deviceClass": "tv",
            ],
        ]
        return descriptor
    }

    static let currentDescriptor: [String: Any] = buildDescriptor()

    static func buildAuthResponsePayload(
        certFingerprint: String?,
        players: [String] = PlaybackEngine.capabilityPlayers,
        features: [String] = defaultFeatures,
        capabilities: [String: Any]? = currentDescriptor
    ) -> [String: Any] {
        var response: [String: Any] = [
            "type": "auth_response",
            "success": true,
            "players": players,
            "features": features,
        ]
        if let fp = certFingerprint { response["certFingerprint"] = fp }
        if let capabilities { response["capabilities"] = capabilities }
        return response
    }

    static func buildCredentialBundlePayload(
        token: String,
        certFingerprint: String?,
        players: [String] = PlaybackEngine.capabilityPlayers,
        features: [String] = defaultFeatures,
        capabilities: [String: Any]? = currentDescriptor
    ) -> [String: Any] {
        var credentials: [String: Any] = [
            "token": token,
            "players": players,
            "features": features,
        ]
        if let fp = certFingerprint { credentials["certFingerprint"] = fp }
        if let capabilities { credentials["capabilities"] = capabilities }
        return credentials
    }
}
