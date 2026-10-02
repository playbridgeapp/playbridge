import Foundation
import AVFoundation

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?) async throws -> PhoneProxyRegistration {
        fatalError("Unexpected proxy startup")
    }
}
enum StreamProxySettingsStore { static func load() -> RemoteProxyConfiguration { .init() } }

@main struct WebsitePhonePlaybackTests {
    @MainActor static func main() async throws {
        let srt = "1\r\n00:00:01,200 --> 00:00:02,300\r\n<b>First caption</b>\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nSecond\r\nline"
        let parsed = WebsiteCaptionParser.parse(srt)
        precondition(parsed.count == 2 && parsed[0].start == 1.2 && parsed[0].end == 2.3)
        precondition(parsed[0].text == "First caption" && parsed[1].text == "Second\nline")
        let vtt = WebsiteCaptionParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000 align:center\nCaption\n\n01:02:03.400 --> 01:02:03.200\nInvalid end")
        precondition(vtt.count == 1 && vtt[0].text == "Caption")
        precondition(WebsiteCaptionParser.parse("unreadable subtitle").isEmpty)

        let previousRoute = UserDefaults.standard.object(forKey: "stream_route_default")
        UserDefaults.standard.set("direct", forKey: "stream_route_default")
        defer {
            if let previousRoute { UserDefaults.standard.set(previousRoute, forKey: "stream_route_default") }
            else { UserDefaults.standard.removeObject(forKey: "stream_route_default") }
        }
        var events: [(String, [String: Any])] = []
        let request = try PageCastRequest.parse(["items": [["id": "first", "url": "https://media.invalid/first.mp4", "title": "Episode one",
            "subtitleResources": [["url": "https://media.invalid/en.vtt", "label": "English"]]]]], linked: true)
        let playback = try await WebsitePhonePlayback.start(request) { events.append(($0, $1)) }
        precondition(playback.session.player.rate == 0, "Preparation must not start playback before owner and destination validation")
        precondition(playback.session.websiteSubtitleTracks.count == 1 && playback.session.websiteSubtitleTracks[0].label == "English")
        precondition(playback.snapshot()["currentIndex"] as? Int == 0)
        let second = try PageCastRequest.parseItems([["id": "second", "url": "https://media.invalid/second.mp4", "title": "Episode two"]], linked: true)
        playback.append(second, endOfList: true)
        try await playback.jump(1)
        precondition(playback.snapshot()["currentIndex"] as? Int == 1 && playback.session.websiteSubtitleTracks.isEmpty)
        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: playback.session.player.currentItem)
        for _ in 0..<100 {
            if playback.snapshot()["finished"] as? Bool == true { break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        precondition(playback.snapshot()["finished"] as? Bool == true, "An empty tail with endOfList must finish its authority")
        precondition(events.last?.1["state"] as? String == "ended")
        playback.session.close()
        precondition(playback.snapshot()["closed"] as? Bool == true)
        precondition(events.last?.1["closed"] as? Bool == true, "Closing must save final state before the AVPlayer item is released")
        precondition(playback.session.player.currentItem == nil)
        print("PASS local prepare without playback, sidecar labels, queue transitions, tail completion, final close snapshot and SRT/WebVTT parsing")
    }
}
