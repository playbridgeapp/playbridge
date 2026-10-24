import Foundation

@main struct BrowserReceiverStateTests {
    static func main() {
        testPageURL()
        testEvents()
        print("PASS browser receiver state")
    }

    static func testPageURL() {
        let urls = ["http://127.0.0.1:8770", "http://10.1.1.4:8770", "http://192.168.1.20:8770"]
        let preferred = BrowserReceiverState.pageURL(urls: urls, port: 8770, lanHost: "192.168.1.20")
        precondition(preferred == "http://192.168.1.20:8770", "Wi-Fi IPv4 is the page address")
        let synthesized = BrowserReceiverState.pageURL(urls: ["http://10.8.0.2:8771"], port: 8771, lanHost: "192.168.1.9")
        precondition(synthesized == "http://192.168.1.9:8771", "en0 is used when the host list prefers another interface")
        let fallback = BrowserReceiverState.pageURL(urls: urls, port: 8770, lanHost: nil)
        precondition(fallback == "http://10.1.1.4:8770", "Loopback is not the page address")
        precondition(BrowserReceiverState.pageURL(urls: ["http://127.0.0.1:8770"], port: 0, lanHost: "localhost") == nil)
        let page = BrowserReceiverState.pageURL(urls: urls, port: 8770, lanHost: "192.168.1.20")!
        precondition(URL(string: page)?.user == nil && URL(string: page)?.query == nil && URL(string: page)?.password == nil,
                     "The TV page URL has no user, password, or query")
        precondition(BrowserReceiverState.humanizeStartError("address already in use") == "Couldn't start browser host (ports 8770–8779 busy)")
        precondition(BrowserReceiverState.humanizeStartError("bind failed") == "Couldn't start browser host (ports 8770–8779 busy)")
        precondition(BrowserReceiverState.humanizeStartError(nil) == "Couldn't start browser host")
        precondition(BrowserReceiverState.publicError("https://cdn.example/video?token=secret") == "Couldn't complete that browser action")
        precondition(BrowserReceiverState.keepOpenNote.contains("Keep PlayBridge open"))
    }

    static func testEvents() {
        var state = BrowserHostSnapshot(running: true, urls: ["http://192.168.1.20:8770"], port: 8770)
        var activation: String? = nil
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let requested = BrowserReceiverState.reduce(&state, event: [
            "event": "pairing_requested",
            "expires_in_ms": 120_000,
            "session": ["sessionId": "s1", "receiverId": "tv-1", "name": "Living room"],
        ], now: now, pendingActivation: &activation)
        precondition(requested.activate == nil)
        precondition(state.pending.count == 1 && state.pending[0].name == "Living room")
        precondition(state.pending[0].expiresAt == now.addingTimeInterval(120))

        _ = BrowserReceiverState.reduce(&state, event: [
            "event": "pairing_requested",
            "expires_in_ms": 1_000,
            "session": ["sessionId": "s2", "receiverId": "tv-1", "name": "Living room"],
        ], now: now, pendingActivation: &activation)
        precondition(state.pending.count == 1 && state.pending[0].sessionId == "s2", "A new hello from the same TV replaces the pending code")

        activation = "s2"
        let connected = BrowserReceiverState.reduce(&state, event: [
            "event": "connected",
            "session": session("s2", name: "Living room"),
        ], now: now, pendingActivation: &activation)
        precondition(activation == nil)
        precondition(connected.activate?.sessionId == "s2")
        precondition(state.pending.isEmpty && state.ready.count == 1)

        let status = BrowserReceiverState.reduce(&state, event: [
            "event": "status",
            "session": session("s2", name: "Living room", state: "playing", position: 1500, duration: 90000, title: "Example"),
        ], now: now, pendingActivation: &activation)
        precondition(status.activate == nil)
        precondition(status.playback?.state == "playing" && status.playback?.positionMs == 1500 && status.playback?.durationMs == 90000)
        precondition(status.playback?.title == "Example")
        precondition(state.ready.count == 1, "Status chatter does not duplicate the session")

        let failed = BrowserReceiverState.reduce(&state, event: [
            "event": "error",
            "message": "https://cdn.example/media?token=secret",
            "session": session("s2", name: "Living room", state: "error", position: 0, duration: 0, title: nil),
        ], now: now, pendingActivation: &activation)
        precondition(state.lastError == "TV browser couldn’t play this stream")
        precondition(failed.playback?.state == "error")
        precondition(!((state.lastError ?? "").contains("token")))

        let dropped = BrowserReceiverState.reduce(&state, event: [
            "event": "disconnected",
            "session_id": "s2",
            "receiver_id": "tv-1",
            "name": "Living room",
        ], now: now, pendingActivation: &activation)
        precondition(state.ready.isEmpty && state.pending.isEmpty)
        precondition(dropped.playback?.state == "stopped")

        _ = BrowserReceiverState.reduce(&state, event: [
            "event": "operation",
            "requestId": "1",
            "operation": "browser_start",
            "data": ["urls": ["http://10.0.0.2:8770"], "port": 8770],
        ], now: now, pendingActivation: &activation)
        precondition(state.ready.isEmpty && state.urls == ["http://192.168.1.20:8770"], "Command results do not rewrite the host snapshot")
    }

    static func session(_ id: String, name: String, state: String = "idle", position: Int = 0, duration: Int = 0, title: String? = nil) -> [String: Any] {
        var status: [String: Any] = ["state": state, "positionMs": position, "durationMs": duration, "volume": 1, "muted": false]
        if let title { status["title"] = title }
        return [
            "sessionId": id,
            "receiverId": "tv-1",
            "name": name,
            "approved": true,
            "status": status,
        ]
    }
}
