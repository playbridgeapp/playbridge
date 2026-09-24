import Foundation

// Platform adapters are replaced so this runner exercises the production detector
// and scheduler on macOS without WebKit, AVFoundation, networking or SwiftUI.
final class UIImage {}
enum ContentBlocker { static func shouldBlock(urlString: String) -> Bool { false } }
enum HLSParser {
    static func inspect(masterURL: String, headers: [String: String]) async -> StreamManifestInfo { StreamManifestInfo() }
}
enum DASHParser {
    static func variants(mpdURL: String, headers: [String: String]) async -> [VideoQuality] { [] }
}
enum Thumbnailer {
    static func thumbnail(url: String, headers: [String: String], isHLS: Bool) async -> UIImage? { nil }
}

@MainActor
final class Probe {
    var started: [String] = []
    var startedHeaders: [[String: String]] = []
    var waiting: [Int: CheckedContinuation<Void, Never>] = [:]
    var maxActive = 0

    func wait(_ name: String, headers: [String: String]) async -> Int {
        let id = started.count
        started.append(name)
        startedHeaders.append(headers)
        await withCheckedContinuation { waiting[id] = $0; maxActive = max(maxActive, waiting.count) }
        return id
    }

    func finish(_ id: Int) { waiting.removeValue(forKey: id)?.resume() }
    func finishAll() { for id in Array(waiting.keys) { finish(id) } }

    func detector(now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }) -> VideoDetector {
        VideoDetector(loadQualities: { video in
            let id = await self.wait("quality:\(video.id)", headers: VideoDetector.mediaHeaders(for: video))
            return StreamManifestInfo(qualities: [VideoQuality(label: "request-\(id)", bandwidth: 1, url: video.url)], validation: .verified)
        }, loadThumbnail: { video in
            _ = await self.wait("thumbnail:\(video.id)", headers: VideoDetector.mediaHeaders(for: video))
            return UIImage()
        }, now: now)
    }
}

@main
struct VideoDetectorEnrichmentTests {
    @MainActor
    static func until(_ predicate: () -> Bool) async {
        for _ in 0..<2_000 {
            if predicate() { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        fatalError("Timed out waiting for detector work")
    }

    @MainActor
    static func main() async {
        let url = "https://example.test/first.m3u8"
        let probe = Probe()
        let detector = probe.detector()
        detector.ingest(["url": url])
        await until { probe.started.count == 2 }
        precondition(detector.thumbnailStates[url] == .loading)
        probe.finish(0)
        await until { detector.qualities[url] != nil }
        precondition(detector.thumbnails[url] == nil, "Qualities must not wait for the thumbnail")
        probe.finish(1)
        await until { detector.thumbnailStates[url] == .ready }
        detector.ingest(["url": url])
        precondition(probe.started.count == 2, "Duplicate detection must reuse enrichment")

        let headerProbe = Probe()
        let headerDetector = headerProbe.detector()
        let page = "https://player.example.test/watch?id=fixture"
        headerDetector.ingest(["url": url, "detectedBy": "dom_source", "originUrl": page, "ua": "FixtureBrowser/1"])
        await until { headerProbe.started.count == 2 }
        precondition(headerDetector.videos[0].headers["Origin"] == nil)
        headerDetector.ingest(["url": url, "detectedBy": "xhr_content_type", "originUrl": page,
                               "contentType": "application/vnd.apple.mpegurl", "ua": "FixtureBrowser/1"])
        precondition(headerDetector.videos[0].headers["Origin"] == "https://player.example.test")
        precondition(headerDetector.videos[0].headers["Referer"] == page)
        precondition(headerDetector.videos[0].detectedBy == "xhr_content_type")
        await until { headerProbe.started.count == 3 }
        headerProbe.finish(0); headerProbe.finish(1)
        await until { headerProbe.started.count == 4 }
        precondition(headerProbe.startedHeaders[2...3].allSatisfy { $0["Origin"] == "https://player.example.test" },
                     "Upgraded thumbnail and manifest work must carry the CORS Origin")
        headerProbe.finishAll()
        await until { headerDetector.thumbnailStates[url] == .ready && headerDetector.qualities[url] != nil }
        precondition(headerDetector.qualities[url]?.first?.label == "request-2",
                     "Results from the original header set must not replace refreshed enrichment")
        headerDetector.ingest(["url": url, "detectedBy": "dom_source", "originUrl": page])
        precondition(headerDetector.videos[0].headers["Origin"] == "https://player.example.test")
        precondition(headerProbe.started.count == 4, "Weaker observations must not clear Origin or restart enrichment")
        headerDetector.clear()

        for index in 0..<5 { detector.ingest(["url": "https://example.test/\(index).m3u8"]) }
        await until { probe.started.count == 5 }
        precondition(probe.waiting.count == 3)
        await until {
            probe.finishAll()
            return detector.qualities.count == 6 && detector.thumbnails.count == 6
        }
        precondition(probe.started.count == 12 && probe.maxActive == 3, "Work must drain with bounded concurrency")
        detector.ingest(["url": "https://example.test/sub.vtt"])
        precondition(probe.started.count == 12, "Subtitles must not start preview jobs")

        let late = Probe()
        let navigating = late.detector()
        navigating.ingest(["url": url])
        await until { late.started.count == 2 }
        navigating.clear()
        precondition(navigating.videos.isEmpty && navigating.qualities.isEmpty && navigating.thumbnailStates.isEmpty)
        navigating.ingest(["url": url])
        await until { late.started.count == 4 }
        late.finish(0); late.finish(1)
        try? await Task.sleep(nanoseconds: 10_000_000)
        precondition(navigating.qualities[url] == nil && navigating.thumbnails[url] == nil, "Old-page results must not populate a new detection of the same URL")
        late.finish(2); late.finish(3)
        await until { navigating.qualities[url] != nil && navigating.thumbnails[url] != nil }
        precondition(navigating.qualities[url]?.first?.label == "request-2")

        let closing = Probe()
        var closed: VideoDetector? = closing.detector()
        weak var weakDetector = closed
        closed?.ingest(["url": url])
        await until { closing.started.count == 2 }
        closed = nil
        precondition(weakDetector == nil, "In-flight jobs must not retain closed detectors")
        closing.finishAll()
        var clock: Int64 = 10_000
        let spaProbe = Probe()
        let spa = spaProbe.detector(now: { clock })
        spa.ingest(["url": url, "detectedBy": "fetch_url"])
        await until { spaProbe.started.count == 2 }
        clock = 20_000
        spa.beginMediaLifecycle()
        spa.ingest(["url": "https://example.test/new.m3u8", "detectedBy": "fetch_url"])
        precondition(spa.videos.map(\.lifecycleIndex) == [0, 1], "SPA navigation retains old rows but marks the new view")
        clock = 21_000
        spa.ingest(["url": url, "contentType": "application/vnd.apple.mpegurl", "detectedBy": "fetch_content_type"])
        precondition(spa.videos[0].lastSeen == clock && spa.videos[0].detectedBy == "fetch_content_type")
        precondition(spa.videos[0].lifecycleIndex == 0, "Repeated polling must not move old rows into the newest view")
        clock = 21_500
        spa.beginMediaLifecycle()
        precondition(spa.videos[1].lifecycleIndex == 2, "Requests just before navigation adopt the new lifecycle")
        spa.clear()
        spaProbe.finishAll()

        let subtitleProbe = Probe()
        let subtitleDetector = subtitleProbe.detector()
        let extensionlessSubtitle = "https://subs.example/resource/42"
        subtitleDetector.ingest([
            "url": extensionlessSubtitle,
            "contentType": "text/plain",
            "detectedBy": "body_content_subtitle",
            "mediaKind": "subtitle",
            "originUrl": "https://player.example/watch",
        ])
        precondition(subtitleDetector.videos.first?.isSubtitle == true)
        precondition(subtitleDetector.videos.first?.headers["Origin"] == "https://player.example")
        precondition(subtitleDetector.videos.first?.headers["Referer"] == "https://player.example/watch")
        precondition(subtitleProbe.started.isEmpty, "Body-confirmed subtitles must not start preview work")

        let dispositionSubtitle = "https://subs.example/resource/43"
        subtitleDetector.ingest([
            "url": dispositionSubtitle,
            "contentType": "application/octet-stream",
            "detectedBy": "fetch_content_type",
        ])
        await until { subtitleProbe.started.count == 1 }
        subtitleDetector.ingest([
            "url": dispositionSubtitle,
            "contentType": "application/x-subrip",
            "detectedBy": "subtitle_disposition",
            "mediaKind": "subtitle",
        ])
        precondition(subtitleDetector.videos.last?.isSubtitle == true)
        precondition(subtitleDetector.videos.last?.contentType == "application/x-subrip")
        subtitleDetector.clear()
        subtitleProbe.finishAll()

        print("PASS: eager enrichment, subtitle body/disposition upgrades, deduplication, concurrency, navigation invalidation and detector teardown")
    }
}
