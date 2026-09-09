import Foundation

// Compiled with the production sample parser/downloader, model and HLS parser.
enum StreamHTTP {
    static func fetchText(_ url: String, headers: [String: String]) async -> String? { nil }
    static func resolve(_ relative: String, against base: String) -> String {
        URL(string: relative, relativeTo: URL(string: base))!.absoluteString
    }
}

@main struct HLSPreviewSampleTests {
    static func main() {
        let base = URL(string: "https://example.test/video/list.m3u8")!
        let ts = HLSPreviewSample.plan("#EXTM3U\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n#EXTINF:2,\nc.ts\n#EXTINF:2,\nd.ts", base: base)
        precondition(ts?.segments.count == 3)
        precondition(ts?.segments.first?.absoluteString == "https://example.test/video/a.ts")
        let fmp4 = HLSPreviewSample.plan("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.m4s", base: base)
        precondition(fmp4?.initialization?.lastPathComponent == "init.mp4")
        precondition(fmp4?.segments.first?.lastPathComponent == "a.m4s")
        precondition(HLSPreviewSample.plan("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\na.ts", base: base) == nil)
        precondition(HLSPreviewSample.plan("#EXTM3U\n#EXT-X-BYTERANGE:123@0\n#EXTINF:2,\na.ts", base: base) == nil)
        precondition(HLSPreviewSample.plan("#EXTM3U\n#EXTINF:2,\nfile:///etc/passwd", base: base) == nil)
        let discontinuity = HLSPreviewSample.plan("#EXTM3U\n#EXTINF:2,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:2,\nb.ts", base: base)
        precondition(discontinuity?.segments.count == 1)
        precondition(HLSPreviewSample.plan("<html>not a playlist</html>", base: base) == nil)
        print("PASS: bounded TS/fMP4 sample plans, URL resolution, discontinuities and unsupported layouts")
    }
}
