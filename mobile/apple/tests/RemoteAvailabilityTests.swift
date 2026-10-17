import Foundation

enum RemoteAvailabilityTests {
    static func run() {
        let fixtures: [(Bool, Bool, Bool, Bool, RemoteAvailability, String)] = [
            (true,true,true,false,.full,"PlayBridge · Full control"),
            (false,true,true,false,.tvRemoteOnly,"TV remote only"),
            (false,false,true,false,.offline,"Offline"),
            (false,false,false,false,.unpaired,"Offline"),
            (true,false,true,false,.receiver,"PlayBridge"),
            (true,true,false,false,.receiver,"PlayBridge"),
            (false,true,false,false,.unpaired,"Offline"),
            (false,true,true,true,.offline,"Offline"),
            (true,true,true,true,.receiver,"PlayBridge")]
        for (receiver,tv,paired,ambiguous,state,status) in fixtures {
            precondition(RemoteAvailability.resolve(receiver: receiver, tvRemote: tv, paired: paired, ambiguous: ambiguous) == state)
            precondition(RemoteAvailability.pickerStatus(receiver: receiver, tvRemote: tv, paired: paired, ambiguous: ambiguous) == status)
        }
        let receiverControls: Set<String> = ["nowPlaying","seek","transport","queue","tracks","receiverInput"]
        let tvControls: Set<String> = ["pad","back","home","keyboard","volume","mute","tvMenu"]
        precondition(RemoteAvailability.full.visibleControls.isSuperset(of: receiverControls.union(tvControls)))
        precondition(RemoteAvailability.tvRemoteOnly.visibleControls.isDisjoint(with: receiverControls))
        precondition(RemoteAvailability.tvRemoteOnly.visibleControls.isSuperset(of: tvControls.union(["openPlayBridge"])))
        precondition(RemoteAvailability.offline.visibleControls == ["header","picker","retry","switchTV"])
        precondition(!RemoteAvailability.unpaired.tvControls)
        let first = RemoteTVChoice(endpoint: "playbridge:a", paired: true, reachable: true)
        let second = RemoteTVChoice(endpoint: "playbridge:b", paired: true, reachable: true)
        precondition(RemoteTVSelection.resolve(selected: nil, choices: []) == .picker)
        precondition(RemoteTVSelection.resolve(selected: nil, choices: [first]) == .selected(first.endpoint))
        precondition(RemoteTVSelection.resolve(selected: nil, choices: [first,first]) == .selected(first.endpoint))
        precondition(RemoteTVSelection.resolve(selected: nil, choices: [first,second]) == .picker)
        precondition(RemoteTVSelection.resolve(selected: "playbridge:offline", choices: [first,second]) == .selected("playbridge:offline"))
        precondition(RemoteTVSelection.resolve(selected: nil, choices: [.init(endpoint: first.endpoint, paired: true, reachable: true, ambiguous: true)]) == .picker)
        precondition(RemoteTVSelection.resolve(selected: nil, choices: [.init(endpoint: first.endpoint, paired: false, reachable: true)]) == .picker)
        print("PASS: independent remote states, exact picker labels, visible control sets and selected/unique/multiple/offline/ambiguous TV policy")
    }
}
