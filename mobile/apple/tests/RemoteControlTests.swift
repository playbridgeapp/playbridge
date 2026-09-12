import Foundation

@main
struct RemoteControlTests {
    @MainActor static func main() async throws {
        precondition(RemoteMode.available(context: "browser", external: false, browser: true) == [.context, .dpad, .touchpad, .keyboard])
        precondition(RemoteMode.available(context: "player", external: false, browser: false) == [.context, .dpad])
        precondition(RemoteMode.available(context: "player", external: true, browser: false) == [.context])
        precondition(RemoteMode.canSeek(context: "browser", externalProtocol: nil, duration: 1000, isLive: true, isSeekable: false))
        precondition(!RemoteMode.canSeek(context: "player", externalProtocol: nil, duration: 1000, isLive: true, isSeekable: true))
        precondition(!RemoteMode.canSeek(context: "player", externalProtocol: "roku", duration: 1000, isLive: false, isSeekable: true))
        precondition(RemoteMode.canSeek(context: "player", externalProtocol: "google_cast", duration: 1000, isLive: false, isSeekable: true))
        precondition(!RemoteMode.canSeek(context: "idle", externalProtocol: nil, duration: 1000, isLive: false, isSeekable: true))
        precondition(RemoteMode.time(3_661_000) == "1:01:01" && RemoteMode.time(-1) == "00:00")
        let coordinator = ConnectionCoordinator()
        coordinator.handle(#"{"type":"tracks","audio":[{"id":7,"name":"English","selected":true}],"subtitle":[{"id":"none","name":"Off","selected":true}]}"#)
        coordinator.handle(#"{"type":"player_settings","speed":1.5,"scaling":"Zoom","isLive":true,"isSeekable":false,"speedAvailable":false}"#)
        try await Task.sleep(nanoseconds: 50_000_000)
        precondition(coordinator.audioTracks.first?.id == "7")
        precondition(coordinator.subtitleTracks.first?.id == "none")
        precondition(coordinator.playerIsLive && !coordinator.playerIsSeekable && !coordinator.speedAvailable)
        precondition(coordinator.playerSpeed == 1.5 && coordinator.playerScaling == "Zoom")
        coordinator.handle(#"{"type":"context","active":"idle"}"#)
        try await Task.sleep(nanoseconds: 50_000_000)
        precondition(coordinator.audioTracks.isEmpty && !coordinator.playerIsLive && coordinator.playerIsSeekable)
        let message = WireProtocol.browserControlCommand("refresh")
        let json = try JSONSerialization.jsonObject(with: Data(message.utf8)) as! [String: Any]
        precondition(json["action"] as? String == "browser_control")
        precondition((json["payload"] as? [String: Any])?["action"] as? String == "refresh")
        print("PASS: remote modes, timeline capabilities, track IDs, settings reset and browser commands")
    }
}
