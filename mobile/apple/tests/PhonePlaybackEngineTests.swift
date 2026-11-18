import Foundation
import AVFoundation

final class PhoneProxyRegistration {
    let url: URL
    init(url: URL) { self.url = url }
}
/// Website media goes through the loopback proxy; keep the file name so engine selection still sees it.
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, allowedPrivateOrigins: [String] = [], forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration {
        precondition(forLocalPlayback, "Website media on this phone must use a loopback registration")
        return PhoneProxyRegistration(url: URL(string: "http://127.0.0.1:9/s/id/")!.appendingPathComponent(URL(string: url)!.lastPathComponent))
    }
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

        // AVPlayer is a per-session choice: switching re-prepares the route at the current position.
        var prepares = 0
        var avEngines: [TestAlternativeEngine] = []
        let avFactory: () -> PhoneAlternativePlaybackEngine? = { let engine = TestAlternativeEngine(); avEngines.append(engine); return engine }
        let hls = RoutedStream(url: URL(string: "http://127.0.0.1:9/s/id/index.m3u8")!, headers: [:],
                               sourceURL: "https://source.test/vod.m3u8?token=secret")
        let switching = PlaybackSession(media: hls, route: .phone, contentType: "application/vnd.apple.mpegurl",
                                        alternativeFactory: factory, avPlayerFactory: avFactory) { prepares += 1; return hls }
        let mpvEngine = engines.last!
        precondition(switching.engineKind == .mpv && avEngines.isEmpty, "Sessions start on mpv")
        mpvEngine.onState?(PhonePlaybackState(position: 42, duration: 600, paused: false))
        switching.switchEngine(to: .avPlayer)
        precondition(mpvEngine.pauses == 1, "The old engine stops while the route is prepared again")
        for _ in 0..<100 { if !switching.retrying { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(prepares == 1 && switching.engineKind == .avPlayer && mpvEngine.closes == 1 && avEngines.count == 1)
        precondition(avEngines[0].loads[0].0 == hls.url && avEngines[0].loads[0].2 == 42 && avEngines[0].loads[0].3,
                     "AVPlayer resumes at the same position and keeps playing")
        precondition(avEngines[0].contentTypes == ["application/vnd.apple.mpegurl"])
        mpvEngine.onState?(PhonePlaybackState(position: 1)); mpvEngine.onFailure?(-13)
        precondition(switching.failure == nil && switching.positionSeconds == 42, "The replaced mpv engine is ignored")
        avEngines[0].failureMessage = "AVPlayer could not play this stream."
        avEngines[0].networkIssue = .http(403)
        avEngines[0].onFailure?(-11828)
        precondition(switching.failure?.message == "AVPlayer could not play this stream.")
        let avReport = switching.diagnosticsReport()
        precondition(avReport.contains("Engine: avplayer") && avReport.contains("AVPlayer error code: -11828")
                     && avReport.contains("AVFoundation: system") && !avReport.contains("MPVKit") && !avReport.contains("token"))
        switching.retry()
        for _ in 0..<100 { if !switching.retrying { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(switching.engineKind == .avPlayer && avEngines.count == 2, "Retry keeps the chosen engine")
        await switching.replaceWebsiteMedia(hls, title: "Next", resumeMs: 0, autoplay: false)
        precondition(switching.engineKind == .avPlayer && avEngines.count == 3, "Episode changes keep the chosen engine")
        avEngines[2].onState?(PhonePlaybackState(position: 5, paused: true))
        switching.switchEngine(to: .mpv)
        for _ in 0..<100 { if !switching.retrying { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(switching.engineKind == .mpv && avEngines[2].closes == 1 && !engines.last!.loads.last!.3,
                     "Switching a paused video keeps it paused")
        switching.switchEngine(to: .mpv)
        precondition(!switching.retrying, "Choosing the current engine does nothing")
        switching.close()

        let mkvMedia = RoutedStream(url: URL(string: "http://127.0.0.1:9/s/id/video.mkv")!, headers: [:], sourceURL: mkv.absoluteString)
        let refused = PlaybackSession(media: mkvMedia, route: .phone, alternativeFactory: factory, avPlayerFactory: avFactory) { mkvMedia }
        refused.switchEngine(to: .avPlayer)
        for _ in 0..<100 { if !refused.retrying { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(refused.failure == .avPlayerMatroska && avEngines.last!.loads.isEmpty, "AVPlayer refuses MKV with a message naming mpv")
        precondition(refused.failure!.message.contains("mpv"))
        refused.close()

        // Routing and error classification for the AVPlayer engine.
        let signed = URL(string: "https://cdn.test/v.m3u8?token=secret")!
        precondition(PhoneAVPlayerSupport.needsLoopback(url: signed, headers: ["Referer": "https://site.test/"]))
        precondition(!PhoneAVPlayerSupport.needsLoopback(url: signed, headers: [:]))
        precondition(PhoneAVPlayerSupport.needsLoopback(url: URL(string: "http://cdn.test/v.mp4")!, headers: [:]), "ATS blocks plain HTTP")
        precondition(!PhoneAVPlayerSupport.needsLoopback(url: URL(string: "http://127.0.0.1:9/s/id/v.m3u8")!, headers: ["Cookie": "a"]))
        precondition(!PhoneAVPlayerSupport.needsLoopback(url: URL(fileURLWithPath: "/tmp/local.mp4"), headers: [:]))
        precondition(PhoneAVPlayerSupport.contentTypeHint(url: signed, contentType: nil) == "application/vnd.apple.mpegurl")
        precondition(PhoneAVPlayerSupport.contentTypeHint(url: mp4, contentType: "video/mp4") == "video/mp4")
        precondition(PhoneAVPlayerSupport.contentTypeHint(url: mp4, contentType: nil) == nil)
        precondition(PhoneAVPlayerSupport.networkIssue(from: URLError(.serverCertificateUntrusted)) == .certificate)
        precondition(PhoneAVPlayerSupport.networkIssue(from: URLError(.cannotFindHost)) == .dns)
        precondition(PhoneAVPlayerSupport.networkIssue(from: NSError(domain: "CoreMediaErrorDomain", code: -12938)) == .http(404))
        let wrapped = NSError(domain: AVFoundationErrorDomain, code: AVError.Code.unknown.rawValue,
                              userInfo: [NSUnderlyingErrorKey: NSError(domain: NSURLErrorDomain, code: URLError.timedOut.rawValue)])
        precondition(PhoneAVPlayerSupport.networkIssue(from: wrapped) == .timeout)
        precondition(PhoneAVPlayerSupport.networkIssue(logStatusCode: -12660, domain: "CoreMediaErrorDomain", comment: "HTTP 403: Forbidden") == .http(403))
        precondition(PhoneAVPlayerSupport.networkIssue(logStatusCode: 503, domain: "CoreMediaErrorDomain", comment: nil) == .http(503))
        precondition(PhoneAVPlayerSupport.networkIssue(logStatusCode: -1, domain: "Other", comment: "token=secret") == nil)
        let format = NSError(domain: AVFoundationErrorDomain, code: AVError.Code.fileFormatNotRecognized.rawValue)
        precondition(PhoneAVPlayerSupport.isUnsupportedFormat(format))
        precondition(PhoneAVPlayerSupport.failureMessage(error: format, networkIssue: nil).contains("mpv"))
        precondition(PhoneAVPlayerSupport.failureMessage(error: nil, networkIssue: .http(403)).contains("denied"))
        precondition(PhoneAVPlayerSupport.failureMessage(error: nil, networkIssue: nil).contains("AVPlayer"))
        precondition(PhoneAVPlayerSupport.languageCode(extendedTag: "und", localeCode: nil) == nil)
        precondition(PhoneAVPlayerSupport.languageCode(extendedTag: nil, localeCode: "fr") == "fr")

        // Ticked subtitle files drawn as an overlay, with the delay applied.
        var overlay = PhoneOverlaySubtitles()
        let srt = PhoneExternalSubtitle(url: URL(fileURLWithPath: "/tmp/a.srt"), title: "English", language: "en")
        let ass = PhoneExternalSubtitle(url: URL(fileURLWithPath: "/tmp/b.ass"), title: "Styled")
        precondition(overlay.add(srt, contents: "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n2\n00:00:04,000 --> 00:00:05,000\nAgain"))
        precondition(!overlay.add(ass, contents: "[Script Info]\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hi"), "ASS has no SRT/WebVTT cues")
        precondition(overlay.tracks.count == 1 && overlay.tracks[0].id == PhoneOverlaySubtitles.firstID
                     && overlay.tracks[0].externalURL == srt.mpvPath && overlay.tracks[0].language == "en")
        precondition(PhoneOverlaySubtitles.isOverlay(1000) && !PhoneOverlaySubtitles.isOverlay(1) && !PhoneOverlaySubtitles.isOverlay(nil))
        precondition(overlay.caption(id: 1000, position: 2, delay: 0) == "Hello")
        precondition(overlay.caption(id: 1000, position: 2, delay: 1.5) == "", "A positive delay shows captions later")
        precondition(overlay.caption(id: 1000, position: 5.5, delay: 1.5) == "Again")
        precondition(overlay.caption(id: nil, position: 2, delay: 0) == "")
        print("PASS mpv-default formats/files, explicit unavailable engine, routed headers, resume/retry, diagnostics, stale callbacks, multi-format queue, final progress, per-session AVPlayer switch, AVPlayer routing/errors and overlay subtitles")
    }
}
