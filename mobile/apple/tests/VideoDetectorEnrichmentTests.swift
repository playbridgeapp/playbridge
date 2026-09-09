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
    var waiting: [Int: CheckedContinuation<Void, Never>] = [:]
    var maxActive = 0

    func wait(_ name: String) async -> Int {
        let id = started.count
        started.append(name)
        await withCheckedContinuation { waiting[id] = $0; maxActive = max(maxActive, waiting.count) }
        return id
    }

    func finish(_ id: Int) { waiting.removeValue(forKey: id)?.resume() }
    func finishAll() { for id in Array(waiting.keys) { finish(id) } }

    func detector(now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }) -> VideoDetector {
        VideoDetector(loadQualities: { video in
            let id = await self.wait("quality:\(video.id)")
            return StreamManifestInfo(qualities: [VideoQuality(label: "request-\(id)", bandwidth: 1, url: video.url)], validation: .verified)
        }, loadThumbnail: { video in
            _ = await self.wait("thumbnail:\(video.id)")
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
        print("PASS: eager enrichment, independent results, deduplication, concurrency, subtitle exclusion, navigation invalidation and detector teardown")
    }
}
