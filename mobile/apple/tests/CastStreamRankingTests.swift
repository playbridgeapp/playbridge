import Foundation

// Standalone fixture runner: compile with Models/DetectedVideo.swift and
// Browser/{HLSParser,DASHParser}.swift. No network or simulator required.
enum StreamHTTP {
    static func fetchText(_ url: String, headers: [String: String]) async -> String? {
        if url.hasSuffix("ladder.m3u8") {
            return """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            low.m3u8
            """
        }
        if url.hasSuffix("master.m3u8") {
            return "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST"
        }
        if url.hasSuffix("video.mpd") {
            return """
            <MPD><Period><AdaptationSet contentType="video">
            <Representation height="1080" bandwidth="2000000"/>
            <Representation height="480" bandwidth="800000"/>
            </AdaptationSet></Period></MPD>
            """
        }
        return nil
    }

    static func resolve(_ relative: String, against base: String) -> String {
        URL(string: relative, relativeTo: URL(string: base))!.absoluteString
    }
}

@main
struct CastStreamRankingTests {
    static func video(_ path: String, _ kind: StreamKind) -> DetectedVideo {
        DetectedVideo(url: "https://example.test/\(path)", detectedBy: "fixture", headers: [:], kind: kind)
    }

    static func main() async {
        let child = video("master.m3u8", .hls)
        let ladder = video("ladder.m3u8", .hls)
        let dash = video("video.mpd", .dash)
        let file = video("clip.mp4", .mp4)
        let hlsQualities = await HLSParser.variants(masterURL: ladder.url, headers: [:])
        let childQualities = await HLSParser.variants(masterURL: child.url, headers: [:])
        let dashQualities = await DASHParser.variants(mpdURL: dash.url, headers: [:])
        precondition(hlsQualities.map(\.label) == ["1080p", "480p"])
        precondition(hlsQualities.first?.url == "https://example.test/high.m3u8")
        precondition(childQualities.isEmpty)
        precondition(dashQualities.count == 2 && dashQualities.allSatisfy { $0.url == dash.url })

        let input = [child, file, ladder, dash]
        var olderSubtitle = video("older.vtt", .subtitle)
        olderSubtitle.timestamp = 1_000
        olderSubtitle.lastSeen = 50_000
        var newerSubtitle = video("newer.vtt", .subtitle)
        newerSubtitle.timestamp = 2_000
        newerSubtitle.lastSeen = 2_000
        precondition(SubtitleOrdering.newestFirst([olderSubtitle, file, newerSubtitle]).map(\.id) ==
                     [newerSubtitle.id, olderSubtitle.id], "Repeat observations must not move older subtitles above new ones")
        let sound = video("song.mp3", .audio)
        let picture = video("cover.jpg", .image)
        precondition(DetectedVideo.classify(url: sound.url, contentType: nil) == .audio)
        precondition(DetectedVideo.classify(url: picture.url, contentType: nil) == .image)
        precondition(DetectedVideo.classify(url: "https://example.test/opaque", contentType: "audio/aac") == .audio)
        precondition(DetectedVideo.classify(url: "https://example.test/opaque", contentType: "image/webp") == .image)
        precondition(CastMediaTab.prioritized(videos: [sound, newerSubtitle, picture]) ==
                     [.audio, .subtitle, .image, .video])
        precondition(CastMediaTab.prioritized(videos: [picture]) == [.image, .video, .audio, .subtitle])
        precondition(CastMediaTab.prioritized(videos: [file, newerSubtitle], includeSubtitles: false) ==
                     [.video, .audio, .image])
        let pending = CastStreamRanking.sorted(input, qualities: [:])
        precondition(pending.map(\.id) == [child.id, ladder.id, dash.id, file.id], "Pending ties preserve detection order")
        let enriched = CastStreamRanking.sorted(input, qualities: [ladder.id: hlsQualities, dash.id: dashQualities])
        precondition(enriched.map(\.id) == [ladder.id, dash.id, child.id, file.id], "Parsed ladders outrank misleading master filenames")
        let duplicate = CastStreamRanking.sorted([child, ladder], qualities: [child.id: [hlsQualities[0], hlsQualities[0]], ladder.id: hlsQualities])
        precondition(duplicate.first?.id == ladder.id, "Duplicate variants must not manufacture a multi-quality ladder")
        let failed = CastStreamRanking.sorted(input, qualities: [ladder.id: [], dash.id: []])
        precondition(failed == pending, "Failed or empty probes preserve fallback order")
        let secondChild = video("second.m3u8", .hls)
        let thirdChild = video("third.m3u8", .hls)
        let three = [child, secondChild, thirdChild]
        let previews: [String: StreamThumbnailState] = [child.id: .unavailable, secondChild.id: .unavailable, thirdChild.id: .ready]
        precondition(CastStreamRanking.sorted(three, qualities: [:], thumbnails: previews).map(\.id) == [thirdChild.id, child.id, secondChild.id], "Last stream must rise above two failed previews")
        precondition(CastStreamRanking.sorted(three, qualities: [child.id: hlsQualities], thumbnails: previews).first?.id == child.id, "Multi-quality priority survives preview failure")
        precondition(CastStreamRanking.sorted(three, qualities: [:], thumbnails: [thirdChild.id: .loading]) == three, "Loading previews must not reorder equal streams")
        precondition(CastStreamRanking.sorted([child, file], qualities: [:], thumbnails: [child.id: .unavailable, file.id: .ready]).first?.id == file.id, "Ready preview beats a type-only guess")
        // Ported scenarios from Android BuildCastSheetVideosTest.
        var old = ladder
        old.timestamp = 1_000
        old.lastSeen = 1_000
        old.detectedBy = "body_content_m3u8"
        var fresh = child
        fresh.timestamp = 601_000
        fresh.lastSeen = fresh.timestamp
        fresh.detectedBy = "body_content_m3u8"
        let cached = [old.id: hlsQualities]
        precondition(CastStreamRanking.sorted([old, fresh], qualities: cached, thumbnails: [fresh.id: .ready]).first?.id == fresh.id, "Fresh ready stream must overtake a stale ladder")
        old.lastSeen = 541_000
        precondition(CastStreamRanking.sorted([old, fresh], qualities: cached, thumbnails: [fresh.id: .ready]).first?.id == old.id, "Same-window ladder retains priority")
        old.lastSeen = 1_000
        fresh.lifecycleIndex = 1
        fresh.detectedBy = "fetch_url"
        precondition(CastStreamRanking.sorted([old, fresh], qualities: cached).first?.id == fresh.id, "Pending new SPA view must outrank stale previous view")
        old.lastSeen = fresh.lastSeen
        precondition(CastStreamRanking.sorted([old, fresh], qualities: cached).first?.id == old.id, "Actively observed verified old stream holds until the new stream verifies")
        precondition(CastStreamRanking.sorted([old, fresh], qualities: cached, thumbnails: [fresh.id: .ready]).first?.id == fresh.id)
        var equalNew = fresh
        equalNew.lifecycleIndex = 0
        equalNew.detectedBy = old.detectedBy
        old.timestamp = 600_000; old.lastSeen = 600_000
        precondition(CastStreamRanking.sorted([old, equalNew], qualities: [:], thumbnails: [old.id: .ready, equalNew.id: .ready]).first?.id == equalNew.id, "Newest successful preview wins an equal-score tie")
        precondition(CastStreamRanking.sorted([child, file], qualities: [:], manifests: [child.id: StreamManifestInfo(validation: .failed)]).first?.id == file.id, "Failed validation ranks below pending")
        precondition(HLSParser.parse("#EXTM3U\n#EXTINF:10,\nsegment.ts", masterURL: child.url).hlsRole == .media)
        print("PASS: preview promotion and quality priority; HLS/DASH fixtures, quality-first ranking, stable ties, duplicates and empty probes")
    }
}
