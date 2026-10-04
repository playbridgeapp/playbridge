import Foundation
import AVFoundation

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration {
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
        precondition(WebsiteCaptionParser.caption(parsed, position: 2, delay: 1).isEmpty)
        precondition(WebsiteCaptionParser.caption(parsed, position: 2.5, delay: 1) == "First caption")
        precondition(WebsiteCaptionParser.caption(parsed, position: 0.5, delay: -1) == "First caption")
        precondition(WebsiteCaptionParser.caption(parsed, position: 2.3, delay: 0).isEmpty)
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
        var engines: [TestAlternativeEngine] = []
        let factory: () -> PhoneAlternativePlaybackEngine? = {
            let engine = TestAlternativeEngine(); engines.append(engine); return engine
        }
        let request = try PageCastRequest.parse(["initialOrientation": "landscape", "items": [["id": "first", "url": "https://media.invalid/first.mp4", "title": "Episode one",
            "subtitleResources": [["url": "https://media.invalid/en.vtt", "label": "English"]]]]], linked: true)
        let playback = try await WebsitePhonePlayback.start(request, alternativeFactory: factory) { events.append(($0, $1)) }
        precondition(engines.last!.plays == 0 && !engines.last!.loads.last!.3, "Preparation must not start playback before owner and destination validation")
        precondition(playback.session.websiteSubtitleTracks.count == 1 && playback.session.websiteSubtitleTracks[0].label == "English")
        precondition(playback.snapshot()["currentIndex"] as? Int == 0)
        precondition(playback.session.initialOrientation == "landscape")
        let second = try PageCastRequest.parseItems([["id": "second", "url": "https://media.invalid/second.mp4", "title": "Episode two"]], linked: true)
        precondition(playback.session.websiteQueueTitles == ["Episode one"] && playback.session.websiteQueueIndex == 0)
        playback.append(second, endOfList: false)
        precondition(playback.session.websiteQueueTitles == ["Episode one", "Episode two"])
        engines.last!.onEnd?()
        try await wait { playback.session.websiteQueueIndex == 1 && !playback.session.websiteQueueChangingItem }
        precondition(playback.snapshot()["currentIndex"] as? Int == 1 && playback.session.websiteSubtitleTracks.isEmpty,
                     "A queued episode must advance automatically, not require a manual jump")
        precondition(events.contains { $0.1["currentIndex"] as? Int == 0 && $0.1["state"] as? String == "ended" },
                     "The completed episode must be reported before switching identity")
        engines.last!.onState?(PhonePlaybackState(position: 17, duration: 50, paused: false))
        let eventsBeforeJump = events.count
        playback.session.onWebsiteJump?(0)
        try await wait { playback.session.websiteQueueIndex == 0 && !playback.session.websiteQueueChangingItem }
        precondition(events.dropFirst(eventsBeforeJump).contains {
            $0.1["currentIndex"] as? Int == 1 && $0.1["positionMs"] as? Int64 == 17000
        }, "Manual native navigation must flush the old episode progress before changing identity")
        precondition(playback.session.websiteSubtitleTracks.count == 1, "Previous uses the same queue and restores sidecars")
        precondition(playback.session.initialOrientation == "landscape", "Episode changes must retain the opening policy without rotating again")
        playback.session.onWebsiteJump?(1)
        try await wait { playback.session.websiteQueueIndex == 1 && !playback.session.websiteQueueChangingItem }

        engines.last!.onEnd?()
        try await wait { playback.session.websiteWaitingForNext }
        precondition(playback.snapshot()["finished"] as? Bool == false, "An unresolved tail is not the end of a series")
        let third = try PageCastRequest.parseItems([["id": "third", "url": "https://media.invalid/third.mp4", "title": "Episode three"]], linked: true)
        playback.append(third, endOfList: false)
        try await wait { playback.session.websiteQueueIndex == 2 && !playback.session.websiteQueueChangingItem }
        precondition(!playback.session.websiteWaitingForNext && playback.session.websiteQueueTitles.count == 3,
                     "Late supply must release waiting and advance without closing the player")
        engines.last!.onEnd?()
        try await wait { playback.session.websiteWaitingForNext }
        playback.append([], endOfList: true)
        precondition(!playback.session.websiteWaitingForNext)
        precondition(playback.snapshot()["finished"] as? Bool == true, "An empty tail with endOfList must finish its authority")
        precondition(events.last?.1["state"] as? String == "ended")
        playback.session.close()
        precondition(playback.snapshot()["closed"] as? Bool == true)
        precondition(events.last?.1["closed"] as? Bool == true, "Closing must save final state before mpv is released")
        precondition(playback.session.alternativeEngine == nil && playback.session.onWebsiteJump == nil)
        playback.append(second, endOfList: false)
        precondition(playback.session.websiteQueueTitles.count == 3, "Late supply cannot mutate a closed player")
        print("PASS local prepare, reactive queue, next/previous controls, automatic advance, late supply, tail completion, close snapshot and captions")
    }

    @MainActor private static func wait(_ predicate: () -> Bool) async throws {
        for _ in 0..<1000 {
            if predicate() { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        preconditionFailure("Timed out waiting for website queue transition")
    }
}
