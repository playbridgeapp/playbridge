import Foundation

/// Phone presentation only. Receiver capabilities and ATV credential authority stay separate.
enum RemoteAvailability: String {
    case full, receiver, tvRemoteOnly, offline, unpaired
    static let receiverMissing = "PlayBridge isn't running on this TV."
    static let offlineTitle = "Can't reach this TV"
    static let offlineMessage = "Make sure it's on and on the same Wi-Fi."
    static let playBridgePackage = "com.playbridge.player"

    static func resolve(receiver: Bool, tvRemote: Bool, paired: Bool, ambiguous: Bool = false) -> Self {
        if receiver { return tvRemote && paired && !ambiguous ? .full : .receiver }
        if tvRemote && paired && !ambiguous { return .tvRemoteOnly }
        return paired || ambiguous ? .offline : .unpaired
    }
    var receiverControls: Bool { self == .full || self == .receiver }
    var tvControls: Bool { self == .full || self == .tvRemoteOnly }
    var visibleControls: Set<String> {
        var controls: Set<String> = ["header", "picker"]
        if receiverControls { controls.formUnion(["nowPlaying", "seek", "transport", "queue", "tracks", "receiverInput"]) }
        if tvControls { controls.formUnion(["pad", "back", "home", "keyboard", "volume", "mute", "tvMenu"]) }
        if self == .tvRemoteOnly { controls.insert("openPlayBridge") }
        if self == .offline { controls.formUnion(["retry", "switchTV"]) }
        return controls
    }
    static func pickerStatus(receiver: Bool, tvRemote: Bool, paired: Bool, ambiguous: Bool = false) -> String {
        if receiver { return tvRemote && paired && !ambiguous ? "PlayBridge · Full control" : "PlayBridge" }
        return tvRemote && paired && !ambiguous ? "TV remote only" : "Offline"
    }
}

struct RemoteTVChoice: Equatable {
    let endpoint: String
    let paired: Bool
    let reachable: Bool
    var ambiguous = false
}

enum RemoteTVSelection: Equatable {
    case selected(String), picker
    static func resolve(selected: String?, choices: [RemoteTVChoice]) -> Self {
        // Selection is intent: an offline selected TV must never be replaced by another TV.
        if let selected { return .selected(selected) }
        let reachable = Set(choices.filter { $0.paired && $0.reachable && !$0.ambiguous }.map(\.endpoint))
        return reachable.count == 1 ? .selected(reachable.first!) : .picker
    }
}
