enum PlaybackEngine: String, CaseIterable {
    case avplayer
    case vlc
    case mpv

    var name: String {
        switch self {
        case .avplayer: return "AVPlayer"
        case .vlc: return "VLC"
        case .mpv: return "MPV"
        }
    }

    var menuID: Int {
        switch self {
        case .avplayer: return 0
        case .vlc: return 1
        case .mpv: return 2
        }
    }

    static func menuOrder(current: PlaybackEngine) -> [PlaybackEngine] {
        [current] + allCases.filter { $0 != current }
    }

    init?(command: String) {
        switch command.lowercased() {
        case "avplayer", "native", "exo", "exoplayer": self = .avplayer
        case "vlc": self = .vlc
        case "mpv": self = .mpv
        default: return nil
        }
    }
}
