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

    static func available(context: String, external: Bool, browser: Bool) -> [Self] {
        guard !external else { return [.context] }
        var modes: [Self] = [.context, .dpad]
        if browser { modes.append(.touchpad) }
        if browser && context == "browser" { modes.append(.keyboard) }
        return modes
    }

    static func canSeek(context: String, externalProtocol: String?, duration: Int64, isLive: Bool, isSeekable: Bool) -> Bool {
        guard duration > 0, context == "player" || context == "browser" else { return false }
        if let externalProtocol, !["google_cast", "dlna"].contains(externalProtocol) { return false }
        // Native player settings must not disable a subsequent browser timeline.
        return context == "browser" || (!isLive && isSeekable)
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
