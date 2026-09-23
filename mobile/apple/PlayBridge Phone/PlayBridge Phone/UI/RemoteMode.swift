import Foundation

enum RemoteMode: String, CaseIterable, Identifiable {
    case context = "Context", dpad = "D-Pad", touchpad = "Touchpad", keyboard = "Keyboard"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .context: return "square.grid.2x2"
        case .dpad: return "gamecontroller"
        case .touchpad: return "hand.draw"
        case .keyboard: return "keyboard"
        }
    }

    static func supportsRemote(externalProtocol: String?) -> Bool {
        externalProtocol == nil || externalProtocol == "roku"
    }

    static func supportsVolume(externalProtocol: String?) -> Bool {
        externalProtocol == nil || externalProtocol == "google_cast" || externalProtocol == "roku"
    }

    static func supportsExternalSeek(externalProtocol: String?) -> Bool {
        externalProtocol == "google_cast" || externalProtocol == "dlna"
    }

    static func available(context: String, external: Bool, supportsRemote: Bool) -> [Self] {
        var modes: [Self] = [.context]
        if supportsRemote { modes.append(.dpad) }
        if !external { modes.append(.touchpad) }
        if !external && context == "browser" { modes.append(.keyboard) }
        return modes
    }

    static func canSeek(context: String, externalProtocol: String?, duration: Int64, isLive: Bool, isSeekable: Bool, isImage: Bool = false) -> Bool {
        guard duration > 0, !isImage else { return false }
        switch externalProtocol == nil ? context : "player" {
        case "browser": return true
        case "player":
            if externalProtocol != nil { return supportsExternalSeek(externalProtocol: externalProtocol) && !isLive }
            return !isLive && isSeekable
        default: return false
        }
    }

    static func time(_ milliseconds: Int64) -> String {
        let seconds = max(0, milliseconds / 1000)
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let remainder = seconds % 60
        let suffix = String(format: "%02lld:%02lld", minutes, remainder)
        return hours > 0 ? "\(hours):\(suffix)" : suffix
    }
}
