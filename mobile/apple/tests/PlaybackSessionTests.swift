import Foundation
import AVFoundation

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration {
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
        precondition(PlaybackFailure.describe(formatError, httpStatus: 403, isMatroska: true).message.contains("denied"))
        var engines: [TestAlternativeEngine] = []
        let factory: () -> PhoneAlternativePlaybackEngine? = {
            let engine = TestAlternativeEngine(); engines.append(engine); return engine
        }
        let signed = RoutedStream(url: URL(string: "https://user:password@cdn.test/private-path/video?token=secret")!,
                                  headers: ["Authorization": "Bearer private-value"],
                                  sourceURL: "https://user:password@source.test/signed-secret/video.mkv?token=secret")
        let diagnosticSession = PlaybackSession(media: signed, route: .phone, alternativeFactory: factory) { signed }
        let initialReport = diagnosticSession.diagnosticsReport()
        precondition(initialReport.contains("Engine: mpv") && initialReport.contains("Source host: source.test"))
        precondition(initialReport.contains("Matroska (MKV)") && initialReport.contains("Request header count: 1"))
        for secret in ["user:", "password", "signed-secret", "private-path", "token", "private-value"] {
            precondition(!initialReport.contains(secret), "Copied diagnostics must omit credentials and signed paths")
        }
        let opaque = RoutedStream(url: URL(string: "https://cdn.test/opaque")!, headers: [:])
        await diagnosticSession.replaceWebsiteMedia(opaque, title: "Second item", resumeMs: 0, contentType: "video/x-matroska", autoplay: false)
        precondition(diagnosticSession.diagnosticsReport().contains("Matroska (MKV)"))
        await diagnosticSession.replaceWebsiteMedia(opaque, title: "Third item", resumeMs: 0, contentType: "video/mp4", autoplay: false)
        precondition(!diagnosticSession.diagnosticsReport().contains("Matroska (MKV)"), "mpv-only policy must not mislabel MP4 as MKV")
        diagnosticSession.close()
        precondition(!diagnosticSession.diagnosticsReport().isEmpty)

        let media = RoutedStream(url: URL(string: "https://media.invalid/blocked.m3u8")!, headers: [:])
        var retries = 0
        let session = PlaybackSession(media: media, route: .proxy, alternativeFactory: factory) {
            retries += 1
            return media
        }
        let failed = engines.last!
        failed.onState?(PhonePlaybackState(position: 17, duration: 120, paused: false))
        failed.networkIssue = .http(403)
        failed.onFailure?(-13)
        precondition(session.failure?.message.contains("denied") == true && failed.pauses == 1)
        precondition(session.diagnosticsReport().contains("mpv error code: -13") && session.diagnosticsReport().contains("Network: HTTP 403"))
        session.retry()
        try await wait { retries == 1 && !session.retrying }
        precondition(session.route == .proxy && session.failure == nil && engines.last!.loads.last!.2 == 17)
        failed.onFailure?(-12)
        precondition(session.failure == nil, "A replaced engine cannot overwrite the new attempt")
        engines.last!.networkIssue = .certificate
        engines.last!.onFailure?(-13)
        precondition(session.failure?.message.contains("TLS certificate") == true)
        precondition(session.diagnosticsReport().contains("TLS certificate verification failed"))
        session.close(); session.retry()
        precondition(retries == 1 && session.alternativeEngine == nil)

        var preparation: CheckedContinuation<RoutedStream, Never>?
        let pending = PlaybackSession(media: media, route: .phone, alternativeFactory: factory) {
            await withCheckedContinuation { preparation = $0 }
        }
        let count = engines.count
        pending.retry()
        try await wait { preparation != nil }
        pending.close()
        preparation?.resume(returning: media)
        try await Task.sleep(nanoseconds: 10_000_000)
        precondition(pending.alternativeEngine == nil && engines.count == count, "Dismissed retry must not restart mpv")
        print("PASS mpv failures, safe diagnostics, independent format evidence, explicit-route resume/retry, stale callbacks and dismissal fencing")
    }

    @MainActor private static func wait(_ predicate: () -> Bool) async throws {
        for _ in 0..<1000 {
            if predicate() { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        preconditionFailure("Timed out waiting for playback retry")
    }
}
