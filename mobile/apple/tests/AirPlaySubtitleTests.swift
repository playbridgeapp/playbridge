import Foundation

// Minimal routing dependencies let these pure packaging checks run without an
// iOS simulator or the optional Rust XCFramework.
final class PhoneProxyRegistration { let url = URL(string: "http://127.0.0.1/media")! }
struct RoutedStream {
    let url: URL
    let headers: [String: String]
    var registration: PhoneProxyRegistration?
    var sourceURL: String?
    var sourceHeaders: [String: String] = [:]
}
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?) async throws -> PhoneProxyRegistration {
        fatalError("No real media routes are opened by packaging unit tests")
    }
}
enum LocalFileServer { static func lanIPAddress() -> String? { "127.0.0.1" } }

@main
enum AirPlaySubtitleTests {
    static func main() throws {
        let base = URL(string: "https://media.example/root/master.m3u8?secret=test")!
        let master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio/list.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="original",NAME="Embedded",URI="subs/list.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="audio",SUBTITLES="original"
        video/list.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="audio"
        https://cdn.example/hd.m3u8
        """
        let result = try AirPlaySubtitleHLS.master(master, base: base, tracks: [("English\n\"", "captions-0.m3u8")])
        precondition(result.contains("AUDIO=\"audio\",SUBTITLES=\"pb-external\""))
        precondition(result.contains("https://media.example/root/audio/list.m3u8"))
        precondition(result.contains("https://media.example/root/video/list.m3u8"))
        precondition(result.contains("https://media.example/root/subs/list.m3u8"))
        precondition(result.contains("NAME=\"Embedded\""))
        precondition(result.contains("GROUP-ID=\"original\""))
        precondition(result.contains("AUDIO=\"audio\",SUBTITLES=\"original\""))
        precondition(result.components(separatedBy: "URI=\"captions-0.m3u8\"").count == 3)
        precondition(result.contains("NAME=\"English '\""))
        precondition(result.contains("#EXT-X-VERSION:5"))
        precondition(result.contains("LANGUAGE=\"und\""))
        let higherVersion = try AirPlaySubtitleHLS.master(master + "\n#EXT-X-VERSION:7", base: base, tracks: [("English", "captions-0.m3u8")])
        precondition(higherVersion.contains("#EXT-X-VERSION:7"))
        precondition(AirPlaySubtitleHLS.renditionTitle(result, uri: "captions-0.m3u8") == "English '")
        let duplicateTitles = try AirPlaySubtitleHLS.master(master, base: base,
            tracks: [("Embedded", "captions-0.m3u8"), ("Embedded", "captions-1.m3u8")])
        precondition(AirPlaySubtitleHLS.renditionTitle(duplicateTitles, uri: "captions-0.m3u8") == "Embedded (2)")
        precondition(AirPlaySubtitleHLS.renditionTitle(duplicateTitles, uri: "captions-1.m3u8") == "Embedded (3)")
        let firstVariant = try AirPlaySubtitleHLS.firstVariant(master, base: base)
        precondition(firstVariant.absoluteString == "https://media.example/root/video/list.m3u8")
        let playlist = "#EXTM3U\n#EXTINF:4.5,\nfirst.ts\n#EXTINF:5.5,\nsecond.ts\n#EXT-X-ENDLIST\n"
        let timeline = try AirPlaySubtitleHLS.timeline(playlist, base: base)
        precondition(timeline.duration == 10)
        precondition(timeline.firstSegment.absoluteString == "https://media.example/root/first.ts")
        for invalid in [playlist.replacingOccurrences(of: "#EXT-X-ENDLIST", with: ""),
                        playlist + "#EXT-X-DISCONTINUITY\n", playlist + "#EXT-X-MAP:URI=\"init.mp4\"\n",
                        playlist + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n"] {
            do { _ = try AirPlaySubtitleHLS.timeline(invalid, base: base); fatalError("Unsupported timeline accepted") }
            catch is AirPlaySubtitleError { }
        }
        let srt = Data("1\r\n00:00:01,250 --> 00:00:03,500\r\nHello\r\n".utf8)
        let vtt = try AirPlaySubtitleHLS.webVTT(srt, timestamp: 126_000)
        precondition(vtt.contains("X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:126000"))
        precondition(vtt.contains("00:00:01.250 --> 00:00:03.500"))
        let remapped = try AirPlaySubtitleHLS.webVTT(Data(vtt.utf8), timestamp: 90_000)
        precondition(remapped.components(separatedBy: "X-TIMESTAMP-MAP").count == 2)
        precondition(remapped.contains("MPEGTS:90000"))
        precondition(remapped.contains("Hello"))
        do { _ = try AirPlaySubtitleHLS.webVTT(Data("<html>Access denied</html>".utf8), timestamp: 0); fatalError("HTML accepted as subtitle") }
        catch is AirPlaySubtitleError { }
        var ts = [UInt8](repeating: 0xff, count: 188 * 2)
        ts[0] = 0x47; ts[1] = 0x40; ts[2] = 0x00; ts[3] = 0x10
        ts[188] = 0x47
        let pts: UInt64 = 126_000
        let pes: [UInt8] = [0, 0, 1, 0xe0, 0, 0, 0x80, 0x80, 5,
                           0x21 | UInt8((pts >> 29) & 0x0e), UInt8((pts >> 22) & 0xff),
                           UInt8((pts >> 14) & 0xfe) | 1, UInt8((pts >> 7) & 0xff), UInt8((pts << 1) & 0xfe) | 1]
        ts.replaceSubrange(4..<(4 + pes.count), with: pes)
        precondition(AirPlaySubtitleHLS.firstPresentationTimestamp(Data(ts)) == pts)
        precondition(AirPlaySubtitleHLS.firstPresentationTimestamp(Data("not a segment".utf8)) == nil)
        let mediaMaster = try AirPlaySubtitleHLS.master(playlist, base: base, tracks: [("Captions", "captions.m3u8")])
        precondition(mediaMaster.contains(base.absoluteString))
        precondition(AirPlaySubtitleHLS.subtitlePlaylist(duration: 10.2, captionURI: "captions.vtt").contains("TARGETDURATION:11"))
        print("PASS: AirPlay HLS renditions, preserved audio/subtitle groups, finite timelines, SRT conversion and MPEG-TS timestamp mapping")
    }
}
