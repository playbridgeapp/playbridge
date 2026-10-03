import Foundation
import AVFoundation

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration { fatalError("Unexpected proxy startup") }
}
enum StreamProxySettingsStore { static func load() -> RemoteProxyConfiguration { .init() } }

@main struct PhonePlaybackEngineTests {
    @MainActor static func main() async throws {
        precondition(PhonePlaybackNetworkIssue.classify("TLS: certificate verification failed for https://secret.test/token") == .certificate)
        precondition(PhonePlaybackNetworkIssue.classify("HTTP error 403 Forbidden: token=secret") == .http(403))
        precondition(PhonePlaybackNetworkIssue.classify("Failed to resolve hostname secret.test") == .dns)
        precondition(PhonePlaybackNetworkIssue.classify("Connection timed out") == .timeout)
        precondition(PhonePlaybackNetworkIssue.classify("TLS handshake failed") == .tls)
        precondition(PhonePlaybackNetworkIssue.classify("random token=secret") == nil)
        precondition(!PhonePlaybackNetworkIssue.classify("HTTP error 403 token=secret")!.summary.contains("secret"))
        precondition(!PlaybackFailure.describeMPV(-13, networkIssue: nil).message.contains("expired"))
        precondition(PlaybackFailure.describeMPV(-13, networkIssue: .certificate).message.contains("TLS certificate"))
        let mkv = URL(string: "https://source.test/video.MKV?token=secret")!
        let mp4 = URL(string: "https://source.test/video.mp4")!
        let media = RoutedStream(url: URL(string: "https://proxy.test/opaque")!, headers: ["Cookie": "first=1, second=2"],
                                 sourceURL: mkv.absoluteString)
        var engines: [TestAlternativeEngine] = []
        let factory: () -> PhoneAlternativePlaybackEngine? = { let engine = TestAlternativeEngine(); engines.append(engine); return engine }
        let session = PlaybackSession(media: media, route: .proxy, alternativeFactory: factory) { media }
        precondition(session.engineKind == .mpv && session.alternativeEngine === engines[0])
        precondition(engines[0].loads[0].0 == media.url && engines[0].loads[0].1 == media.headers,
                     "Decoder must use the selected proxy route and preserve headers")
        precondition(!engines[0].loads[0].3 && engines[0].plays == 0, "Preparing must not start decoding/playback before authorization")
        session.play(); session.seek(to: 13)
        precondition(engines[0].plays == 1 && engines[0].seeks == [13])
        engines[0].onState?(PhonePlaybackState(position: 13, duration: 90, paused: false))
        precondition(session.positionSeconds == 13 && session.durationSeconds == 90 && session.isPlaying)
        let report = session.diagnosticsReport()
        precondition(report.contains("Engine: mpv") && report.contains("Source host: source.test") && !report.contains("token") && !report.contains("second=2"))
        engines[0].onFailure?(-13)
        precondition(session.failure != nil && session.diagnosticsReport().contains("mpv error code: -13"))
        session.retry()
        for _ in 0..<100 { if !session.retrying { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(engines.count == 2 && engines[0].closes == 1 && engines[1].loads[0].2 == 13 && session.failure == nil)
        engines[0].onFailure?(-12)
        engines[0].onState?(PhonePlaybackState(position: 999))
        precondition(session.failure == nil && session.positionSeconds == 13, "A replaced decoder's callbacks must be ignored")
        let compatible = RoutedStream(url: mp4, headers: [:])
        await session.replaceWebsiteMedia(compatible, title: "MP4", resumeMs: 0, autoplay: false)
        precondition(session.engineKind == .mpv && engines[1].closes == 1 && engines.count == 3 && engines[2].loads[0].0 == mp4,
                     "MP4 must stay on mpv rather than falling back to AVPlayer")
        session.close(); session.play(); session.retry()
        engines[2].onState?(PhonePlaybackState(position: 666)); engines[2].onFailure?(-12)
        precondition(engines[2].closes == 1 && engines[2].plays == 0 && session.positionSeconds != 666)

        let previousRoute = UserDefaults.standard.object(forKey: "stream_route_default")
        UserDefaults.standard.set("direct", forKey: "stream_route_default")
        defer { if let previousRoute { UserDefaults.standard.set(previousRoute, forKey: "stream_route_default") }
                else { UserDefaults.standard.removeObject(forKey: "stream_route_default") } }
        let request = try PageCastRequest.parse(["items": [["id": "mkv", "url": mkv.absoluteString, "startPositionMs": 4000]]], linked: true)
        var events: [[String: Any]] = []
        let playback = try await WebsitePhonePlayback.start(request, alternativeFactory: factory) { _, state in events.append(state) }
        let first = engines.last!
        precondition(first.loads.last!.2 == 4 && !first.loads.last!.3)
        first.onState?(PhonePlaybackState(position: 20, duration: 30, paused: false))
        precondition(playback.snapshot()["positionMs"] as? Int64 == 20000 && playback.snapshot()["state"] as? String == "playing")
        playback.append(try PageCastRequest.parseItems([["id": "mp4", "url": mp4.absoluteString]], linked: true), endOfList: false)
        first.onEnd?()
        for _ in 0..<100 { if playback.index == 1 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(playback.index == 1 && playback.session.engineKind == .mpv, "MKV EOF must advance to the MP4 episode on mpv")
        playback.append(try PageCastRequest.parseItems([["id": "mkv-2", "url": mkv.absoluteString]], linked: true), endOfList: true)
        engines.last!.onEnd?()
        for _ in 0..<100 { if playback.index == 2 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(playback.index == 2 && playback.session.engineKind == .mpv, "MP4 EOF must advance to the MKV episode on mpv")
        let last = engines.last!
        last.onState?(PhonePlaybackState(position: 30, duration: 30, paused: true)); last.onEnd?()
        precondition(playback.snapshot()["finished"] as? Bool == true)
        playback.session.close()
        precondition(events.last?["closed"] as? Bool == true && events.last?["positionMs"] as? Int64 == 30000,
                     "Closing must persist mpv's final position before releasing the decoder")
        for (url, mime) in [(mp4, "video/mp4"), (URL(string: "https://source.test/video.m3u8")!, "application/vnd.apple.mpegurl"),
                            (URL(string: "https://source.test/video.mpd")!, "application/dash+xml"),
                            (URL(fileURLWithPath: "/tmp/local.mp3"), "audio/mpeg"),
                            (URL(string: "https://source.test/opaque")!, "application/octet-stream")] {
            let media = RoutedStream(url: url, headers: [:])
            let candidate = PlaybackSession(media: media, route: .direct, contentType: mime, alternativeFactory: factory) { media }
            precondition(candidate.engineKind == .mpv && engines.last!.loads.last!.0 == url && !engines.last!.loads.last!.3)
            candidate.close()
        }
        let missing = PlaybackSession(media: compatible, route: .direct, alternativeFactory: { nil }) { compatible }
        precondition(missing.failure != nil && missing.alternativeEngine == nil && !missing.isPlaying,
                     "Missing mpv must fail explicitly, never fall back to AVPlayer")
        missing.close()
        print("PASS mpv-only formats/files, explicit unavailable engine, routed headers, resume/retry, diagnostics, stale callbacks, multi-format queue and final progress")
    }
}
