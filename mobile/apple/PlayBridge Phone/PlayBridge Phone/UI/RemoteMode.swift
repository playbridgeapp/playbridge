import Foundation

// Shared transport/timeline helpers; the remote has one context screen, without mode tabs.
enum RemoteMode {
    static func supportsRemote(externalProtocol: String?) -> Bool {
        externalProtocol == nil || externalProtocol == "roku"
    }

    static func supportsVolume(externalProtocol: String?) -> Bool {
        externalProtocol == nil || externalProtocol == "google_cast" || externalProtocol == "roku"
    }

    static func supportsExternalSeek(externalProtocol: String?) -> Bool {
        externalProtocol == "google_cast" || externalProtocol == "dlna" || externalProtocol == "airplay" || externalProtocol == "browser"
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


enum RemotePadSurface: Equatable {
    case none, swipe, buttons, pointer
}

/// In-memory choices survive context changes, but are reset when the destination changes.
struct RemotePadPreferences {
    private var buttonContexts: Set<String> = []
    private var pointerContexts: [String: Bool] = [:]

    func buttons(in context: String) -> Bool { buttonContexts.contains(context) }
    func pointer(in context: String) -> Bool? { pointerContexts[context] }
    mutating func toggleButtons(in context: String) {
        if !buttonContexts.insert(context).inserted { buttonContexts.remove(context) }
    }
    mutating func setPointer(_ pointer: Bool, in context: String) { pointerContexts[context] = pointer }
}

/// Presentation derived from existing policy. It never grants command authority.
struct RemoteInputLayout {
    let hasNavigation: Bool
    let hasPointer: Bool
    let hasKeyboard: Bool
    private let defaultPointer: Bool

    init(policy: ReceiverRemotePolicy, context: String, image: Bool, external: Bool, externalProtocol: String?, fullControl: Bool = false) {
        if policy.isLegacy {
            hasNavigation = fullControl || RemoteMode.supportsRemote(externalProtocol: externalProtocol)
            hasPointer = !external && (!fullControl || policy.state.connected)
            hasKeyboard = fullControl || !external && context == "browser"
            defaultPointer = context == "browser" || image
        } else {
            hasNavigation = fullControl || policy.hasDpad
            hasPointer = (!fullControl || policy.state.connected) && policy.hasPointer
            hasKeyboard = fullControl || policy.text.contains("replace")
            defaultPointer = hasPointer
        }
    }

    var canSwitchPadPointer: Bool { hasNavigation && hasPointer }

    func surface(in context: String, preferences: RemotePadPreferences) -> RemotePadSurface {
        if hasPointer && (!hasNavigation || preferences.pointer(in: context) ?? defaultPointer) { return .pointer }
        if hasNavigation { return preferences.buttons(in: context) ? .buttons : .swipe }
        return .none
    }
}
