import Foundation

@main
struct ProgressWebhookTests {
    static func main() throws {
        for address in ["127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "100.64.1.1", "0.0.0.0", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::"] {
            precondition(!ProgressWebhookTransport.isPublicAddress(address), address)
        }
        for address in ["1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"] {
            precondition(ProgressWebhookTransport.isPublicAddress(address), address)
        }
        precondition(!PlaybackProgressWebhook.validURL(URL(string: "http://example.com")!))
        precondition(!PlaybackProgressWebhook.validURL(URL(string: "https://secret@example.com")!))
        precondition(!PlaybackProgressWebhook.validURL(URL(string: "https://localhost")!))
        precondition(!PlaybackProgressWebhook.validURL(URL(string: "https://example.com:8443/progress")!))
        precondition(!PlaybackProgressWebhook.validURL(URL(string: "https://example.com/progress?key=secret")!))
        var events: [[String: Any]] = []
        var now = Date()
        let reporter = PlaybackProgressWebhook(now: { now }, send: { _, _, data in
            events.append(try! JSONSerialization.jsonObject(with: data) as! [String: Any])
        })
        reporter.configure(url: "https://example.com/progress", token: "test-token", playbackID: "cast-1")
        let identity: [String: Any] = ["type": "series", "contentId": "show", "videoId": "show:1:2", "season": 1, "episode": 2]
        reporter.sample(itemID: "episode-2", content: identity, state: "playing", position: 200, duration: 10000)
        reporter.sample(itemID: "episode-2", content: identity, state: "playing", position: 300, duration: 10000)
        now = now.addingTimeInterval(31)
        reporter.sample(itemID: "episode-2", content: identity, state: "playing", position: 350, duration: 10000)
        reporter.sample(itemID: "episode-2", content: identity, state: "paused", position: 400, duration: 10000)
        reporter.sample(itemID: "episode-2", content: identity, state: "paused", position: 400, duration: 10000)
        reporter.sample(itemID: "episode-2", content: identity, state: "playing", position: 9999, duration: 10000)
        reporter.sample(itemID: "episode-2", content: identity, state: "ended", position: 0, duration: 10000)
        reporter.advance()
        reporter.sample(itemID: "episode-3", content: identity, state: "playing", position: 200, duration: 10000)
        reporter.finish(clear: true)
        reporter.sample(itemID: "episode-3", content: identity, state: "playing", position: 300, duration: 10000)
        precondition(events.compactMap { $0["event"] as? String } == ["started", "progress", "paused", "started", "ended", "started", "stopped"])
        precondition(events[4]["positionMs"] as? Int == 9999)
        precondition(events[5]["itemId"] as? String == "episode-3")
        precondition(Set(events.compactMap { $0["eventId"] as? String }).count == events.count)
        print("Progress webhook checks passed")
    }
}
