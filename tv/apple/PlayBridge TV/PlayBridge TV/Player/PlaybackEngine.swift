import Foundation

enum PlaybackEngine: String, CaseIterable {
    case avplayer
    case mpv

    var name: String {
        switch self {
        case .avplayer: return "AVPlayer"
        case .mpv: return "MPV"
        }
    }

    var menuID: Int {
        switch self {
        case .avplayer: return 0
        case .mpv: return 2
        }
    }

    static let capabilityPlayers = allCases.map(\.rawValue)

    /// The engine when neither the sender nor Settings picks one. mpv plays the
    /// widest range of sources (Matroska, AV1, DTS/TrueHD, image subtitles and
    /// files AVPlayer rejects); AVPlayer stays selectable, and mpv takes over
    /// whenever AVPlayer fails.
    static let defaultEngine = PlaybackEngine.mpv

    static func migrateLegacyPreference(in defaults: UserDefaults) {
        guard defaults.string(forKey: "preferredPlayer")?.lowercased() == "vlc" else { return }
        defaults.set(PlaybackEngine.mpv.rawValue, forKey: "preferredPlayer")
    }

    static func menuOrder(current: PlaybackEngine) -> [PlaybackEngine] {
        [current] + allCases.filter { $0 != current }
    }

    init?(command: String) {
        switch command.lowercased() {
        case "avplayer", "native", "exo", "exoplayer": self = .avplayer
        // Older senders/history can still request VLC; route them to the broad-format engine.
        case "vlc", "mpv": self = .mpv
        default: return nil
        }
    }
}
