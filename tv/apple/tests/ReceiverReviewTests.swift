import Foundation

@main
struct ReceiverReviewTests {
    static func main() {
        var credentials = PairingCredentialState()
        credentials.authorize(deviceUUID: "phone-a", deviceName: "A", token: "test-a")
        credentials.authorize(deviceUUID: "phone-b", deviceName: "B", token: "test-b")
        var connections = ConnectionAuthorization<Int>()
        connections.authorize(1, token: "test-a")
        assert(connections.isAuthorized(1, credentials: credentials))
        assert(!connections.isAuthorized(2, credentials: credentials), "An authenticated phone must not authorize another socket")
        connections.authorize(2, token: "test-b")
        credentials.forgetDevice(deviceUUID: "phone-a")
        assert(!connections.isAuthorized(1, credentials: credentials))
        assert(connections.isAuthorized(2, credentials: credentials))
        connections.remove(2)
        assert(!connections.isAuthorized(2, credentials: credentials))
        connections.authorize(3, token: "test-b")
        credentials.authorize(deviceUUID: "phone-b", deviceName: "B", token: "replacement")
        assert(!connections.isAuthorized(3, credentials: credentials), "Re-pairing revokes old sessions")
        connections.authorize(4, token: "replacement")
        connections.removeAll()
        assert(!connections.isAuthorized(4, credentials: credentials))
        connections.authorize(5, token: "replacement")
        credentials.forgetAllDevices()
        assert(!connections.isAuthorized(5, credentials: credentials))
        print("PASS: per-socket auth, disconnect, re-pairing, revocation and stop")

        let suite = "PlayBridgeTVReviewTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = HistoryStore(defaults: defaults)
        let url = URL(string: "https://example.com/video.mp4")!
        store.addToHistory(url: url, title: "First", headers: nil)
        assert(store.history.count == 1)
        store.toggleFavorite(item: store.history[0])
        store.addToHistory(url: url, title: "Replay", headers: nil)
        assert(store.history.count == 1 && store.history[0].isFavorite)
        let restored = HistoryStore(defaults: defaults)
        assert(restored.history == store.history, "Restore must complete before subsequent mutations")
        restored.clearHistory()
        assert(HistoryStore(defaults: defaults).history.isEmpty)
        defaults.set(false, forKey: "enable_history")
        restored.addToHistory(url: url, title: nil, headers: nil)
        assert(restored.history.isEmpty)
        defaults.set(true, forKey: "enable_history")
        for index in 0..<110 {
            restored.addToHistory(url: URL(string: "https://example.com/\(index)")!, title: nil, headers: nil)
        }
        assert(restored.history.count == 100)
        assert(restored.history.first?.url.lastPathComponent == "109")
        restored.clearHistory()
        assert(HistoryStore(defaults: defaults).history.isEmpty)
        print("PASS: favorite replay, synchronous restore/clear, disabled history and bounds")

        for invalid in [Double.nan, .infinity, -.infinity, -1, Double.greatestFiniteMagnitude, Double(Int.max)] {
            assert(PlaybackTime.milliseconds(invalid) == 0)
            assert(PlaybackTime.seconds(invalid) == 0)
        }
        assert(PlaybackTime.milliseconds(1.25) == 1250)
        assert(PlaybackTime.seconds(3661.9) == 3661)
        print("PASS: unknown/overflow playback times cannot crash integer conversion")
    }
}
