import Foundation
import AVFoundation

// Host harness: the same public-facing proxy address as the local fixture.
enum LocalFileServer {
    static func lanIPAddress() -> String? { "127.0.0.1" }
}

@main struct PhoneSenderServicesTests {
    @MainActor static func main() async throws {
        let origin = ProcessInfo.processInfo.environment["UPSTREAM_TEST_ORIGIN"]!
        // Simulate a listener lost after native registration. Recovery must
        // replace the host once, while a persistent failure must stop retrying.
        var probes = 0
        let recovering = PhoneSenderServices(listenerCheck: { url in
            probes += 1
            if probes == 1 { throw URLError(.cannotConnectToHost) }
            try await PhoneSenderServices.checkListener(url)
        })
        let recovered = try await recovering.register(url: origin + "/body", headers: [:],
            contentType: nil, allowedPrivateOrigins: [origin])
        precondition(probes == 2, "An unreachable listener must be replaced once")
        try await PhoneSenderServices.checkListener(recovered.url)
        var failures = 0
        let unreachable = PhoneSenderServices(listenerCheck: { _ in
            failures += 1
            throw URLError(.cannotConnectToHost)
        })
        do {
            _ = try await unreachable.register(url: origin + "/body", headers: [:],
                contentType: nil, allowedPrivateOrigins: [origin])
            fatalError("Persistent listener failure must be reported")
        } catch { precondition(failures == 2, "Recovery must be bounded") }
        var registration: PhoneProxyRegistration? = try await PhoneSenderServices.shared.register(
            url: origin + "/hls/master.m3u8",
            headers: ["Referer": "https://example.test/player", "User-Agent": "AppleFixture", "Authorization": "Bearer fixture-secret", "Cookie": "fixture=secret"],
            contentType: "application/vnd.apple.mpegurl", allowedPrivateOrigins: [origin, ProcessInfo.processInfo.environment["UPSTREAM_SEGMENT_ORIGIN"]!])
        let mp4 = try await PhoneSenderServices.shared.register(
            url: origin + "/download.mp4/?token=fixture",
            headers: ["Referer": "https://example.test/player", "User-Agent": "AppleFixture", "Authorization": "Bearer fixture-secret", "Cookie": "fixture=secret"],
            contentType: nil, allowedPrivateOrigins: [origin, ProcessInfo.processInfo.environment["UPSTREAM_SEGMENT_ORIGIN"]!])
        precondition(mp4.url.lastPathComponent == "media.mp4")
        for (range, expectedRange, expectedBody) in [("bytes=0-1", "bytes 0-1/10", "01"), ("bytes=4-7", "bytes 4-7/10", "4567")] {
            var request = URLRequest(url: mp4.url)
            request.setValue(range, forHTTPHeaderField: "Range")
            let (body, response) = try await URLSession.shared.data(for: request)
            let http = response as! HTTPURLResponse
            precondition(http.statusCode == 206 && http.value(forHTTPHeaderField: "Content-Range") == expectedRange)
            precondition(http.value(forHTTPHeaderField: "Content-Type") == "video/mp4")
            precondition(String(data: body, encoding: .utf8) == expectedBody)
        }
        withExtendedLifetime(mp4) {}
        print("PASS: Swift/Rust redirected MP4 probe and seek ranges with scoped headers")
        let root = registration!.url
        func fetch(_ url: URL) async throws -> (Data, HTTPURLResponse) {
            let (data, response) = try await URLSession.shared.data(from: url)
            return (data, response as! HTTPURLResponse)
        }
        func child(_ data: Data, base: URL) -> URL {
            let line = String(decoding: data, as: UTF8.self).split(separator: "\n")
                .first { !$0.hasPrefix("#") && !$0.isEmpty }!
            return URL(string: String(line), relativeTo: base)!.absoluteURL
        }
        let (master, masterResponse) = try await fetch(root)
        precondition(masterResponse.statusCode == 200, "Fixture HTTP \(masterResponse.statusCode): \(String(decoding: master, as: UTF8.self))")
        let mediaURL = child(master, base: root)
        precondition(mediaURL.port == root.port && mediaURL.path.contains("/s/"))
        let (media, mediaResponse) = try await fetch(mediaURL)
        precondition(mediaResponse.statusCode == 200)
        let segmentURL = child(media, base: mediaURL)
        precondition(segmentURL.port == root.port && segmentURL.path.contains("/s/"))
        let (segment, segmentResponse) = try await fetch(segmentURL)
        precondition(segmentResponse.statusCode == 200 && segment.count == 188 && segment.first == 0x47)
        precondition(segmentResponse.mimeType == "video/mp2t", "HLS video named .jpg must have a video MIME type")
        registration = nil
        var revoked = false
        for _ in 0..<30 {
            let (_, response) = try await fetch(root)
            if response.statusCode == 403 { revoked = true; break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        precondition(revoked, "Closing the registration must revoke proxy access")
        let original = URL(string: origin + "/hls/master.m3u8")!
        let failedPlayer = AVPlayer(url: URL(string: origin + "/hls/blocked.m3u8")!)
        var fallbacks = 0
        let fallback = PhonePlaybackFallback(player: failedPlayer, originalURL: original,
            headers: ["Referer": "https://example.test/player"]) { fallbacks += 1 }
        failedPlayer.play()
        for _ in 0..<200 {
            if fallbacks > 0 { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        precondition(fallbacks == 1, "A failed proxy item must retry direct playback once")
        precondition((failedPlayer.currentItem?.asset as? AVURLAsset)?.url == original)
        try await Task.sleep(nanoseconds: 200_000_000)
        precondition(fallbacks == 1)
        failedPlayer.pause()
        withExtendedLifetime(fallback) {}
        print("PASS: Swift/Rust HLS header forwarding, nested rewriting, TS MIME correction, revocation and one-shot AVPlayer fallback")
    }
}
