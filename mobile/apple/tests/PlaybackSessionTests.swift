import Foundation
import AVFoundation

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?) async throws -> PhoneProxyRegistration {
        fatalError("Unexpected proxy startup")
    }
}

@main struct PlaybackSessionTests {
    @MainActor static func main() async throws {
        let nested = NSError(domain: "wrapper", code: 1, userInfo: [NSUnderlyingErrorKey:
            NSError(domain: NSURLErrorDomain, code: URLError.timedOut.rawValue,
                    userInfo: [NSLocalizedDescriptionKey: "https://secret.test/?token=private"])])
        precondition(PlaybackFailure.describe(nested).message.contains("too long"))
        precondition(!PlaybackFailure.describe(nested).message.contains("private"))
        precondition(PlaybackFailure.describe(nil, httpStatus: 403).message.contains("denied"))
        precondition(PlaybackFailure.describe(nil, httpStatus: 404).message.contains("no longer"))
        precondition(PlaybackFailure.describe(nil, httpStatus: 503).message.contains("server"))
        let formatError = NSError(domain: AVFoundationErrorDomain, code: AVError.Code.fileFormatNotRecognized.rawValue)
        precondition(PlaybackFailure.describe(formatError, isMatroska: true).message.contains("MKV"))
        precondition(!PlaybackFailure.describe(formatError).message.contains("MKV"))
        precondition(PlaybackFailure.describe(formatError, httpStatus: 403, isMatroska: true).message.contains("denied"),
                     "A server access failure must not be misreported as an MKV format failure")
        let signed = RoutedStream(url: URL(string: "https://user:password@cdn.test/private-path/video?token=secret")!,
                                  headers: ["Authorization": "Bearer private-value"],
                                  sourceURL: "https://user:password@source.test/signed-secret/video.mkv?token=secret")
        let diagnosticSession = PlaybackSession(media: signed, route: .phone) { signed }
        let initialReport = diagnosticSession.diagnosticsReport()
        precondition(initialReport.contains("Engine: AVPlayer") && initialReport.contains("Source host: source.test"))
        precondition(initialReport.contains("Matroska (MKV)") && initialReport.contains("Request header count: 1"))
        for secret in ["user:", "password", "signed-secret", "private-path", "token", "private-value"] {
            precondition(!initialReport.contains(secret), "Copied diagnostics must omit credentials and signed paths")
        }
        let opaque = RoutedStream(url: URL(string: "https://cdn.test/opaque")!, headers: [:])
        await diagnosticSession.replaceWebsiteMedia(opaque, title: "Second item", resumeMs: 0, contentType: "video/x-matroska", autoplay: false)
        precondition(diagnosticSession.diagnosticsReport().contains("Matroska (MKV)"), "Opaque URLs must retain declared format")
        await diagnosticSession.replaceWebsiteMedia(opaque, title: "Third item", resumeMs: 0, contentType: "video/mp4", autoplay: false)
        precondition(!diagnosticSession.diagnosticsReport().contains("Matroska (MKV)"), "Queue replacement must update format diagnostics")
        diagnosticSession.close()
        precondition(!diagnosticSession.diagnosticsReport().isEmpty)
        let origin = ProcessInfo.processInfo.environment["PLAYBACK_TEST_ORIGIN"]!
        let media = RoutedStream(url: URL(string: origin + "/hls/blocked.m3u8")!, headers: [:])
        var retries = 0
        let session = PlaybackSession(media: media, route: .proxy) {
            retries += 1
            return media
        }
        session.player.play()
        for _ in 0..<250 {
            if session.failure != nil { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        precondition(session.failure != nil, "AVPlayer failure must produce visible state")
        precondition(session.diagnosticsReport().contains("Playback error:") || session.diagnosticsReport().contains("Player error log:"),
                     "The report must include concrete AVPlayer evidence after playback fails")
        session.retry()
        for _ in 0..<250 {
            if retries == 1 && !session.retrying && session.failure != nil { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        precondition(retries == 1 && session.route == .proxy && session.failure != nil)
        session.close()
        session.retry()
        precondition(retries == 1 && session.player.currentItem == nil)

        var preparation: CheckedContinuation<RoutedStream, Never>?
        let pending = PlaybackSession(media: media, route: .phone) {
            await withCheckedContinuation { preparation = $0 }
        }
        pending.retry()
        for _ in 0..<50 {
            if preparation != nil { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        precondition(preparation != nil && pending.retrying)
        pending.close()
        preparation?.resume(returning: media)
        try await Task.sleep(nanoseconds: 100_000_000)
        precondition(pending.player.currentItem == nil, "Dismissed retry must not restart playback")
        print("PASS: safe failure messages, redacted nonempty native diagnostics, MKV format evidence, AVPlayer failure detection, explicit-route retry and dismissal cancellation")
    }
}
