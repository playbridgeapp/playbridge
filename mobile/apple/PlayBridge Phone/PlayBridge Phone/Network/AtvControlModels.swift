import Foundation

struct AtvControlTicket: Equatable {
    let owner: UUID
    let connection: UUID
    let endpoint: String
}

enum AtvControlAction: Equatable {
    case key(String), volume(AtvVolumeAction), edit(deletes: Int, suffix: String, enter: Bool)
    case launch(String), power
    var isNavigation: Bool {
        if case .key(let key) = self { return RemoteNavigationKey.names.contains(key) }
        return false
    }
    static let keyNames = ["dpad_up": "KEYCODE_DPAD_UP", "dpad_down": "KEYCODE_DPAD_DOWN",
        "dpad_left": "KEYCODE_DPAD_LEFT", "dpad_right": "KEYCODE_DPAD_RIGHT", "dpad_center": "KEYCODE_DPAD_CENTER",
        "back": "KEYCODE_BACK", "home": "KEYCODE_HOME", "key_enter": "KEYCODE_ENTER", "key_delete": "KEYCODE_DEL", "menu": "KEYCODE_MENU"]
    static func remoteKey(_ key: String) -> Self? {
        switch key {
        case "volume_up": return .volume(.up)
        case "volume_down": return .volume(.down)
        case "mute": return .volume(.mute)
        default: return keyNames[key] == nil ? nil : .key(key)
        }
    }
}

struct AtvFullControlSnapshot {
    var endpoint: String?
    var ticket: AtvControlTicket?
    var paired = false
    var available = false
    var needsRepair = false
    var storageFailure = false
    var television: AtvTelevisionState?
}
