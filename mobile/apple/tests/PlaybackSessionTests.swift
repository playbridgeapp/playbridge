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
        print("PASS: safe failure messages, AVPlayer failure detection, explicit-route retry and dismissal cancellation")
    }
}
