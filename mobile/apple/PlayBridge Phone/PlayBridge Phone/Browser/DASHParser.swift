import Foundation

/// Parses an MPEG-DASH manifest for its video quality tiers. Port of `cast/DashParser.kt`.
/// As on Android, every tier casts the **full MPD URL** — the TV player handles ABR; the tiers
/// are informational.
enum DASHParser {
    private static let videoCodecs = ["avc", "hvc", "hev", "vp8", "vp9", "av01", "dvh"]
    private static let audioCodecs = ["mp4a", "ac-3", "ec-3", "opus", "flac", "vorbis"]

    static func variants(mpdURL: String, headers: [String: String]) async -> [VideoQuality] {
        guard let content = await StreamHTTP.fetchText(mpdURL, headers: headers),
              let data = content.data(using: .utf8) else { return [] }
        let parser = XMLParser(data: data)
        let delegate = Delegate(mpdURL: mpdURL)
        parser.delegate = delegate
        parser.parse()
        // Dedup by resolution@bandwidth, highest first.
        var seen = Set<String>()
        return delegate.qualities
            .filter { seen.insert("\($0.label)@\($0.bandwidth)").inserted }
            .sorted { $0.bandwidth > $1.bandwidth }
    }

    private final class Delegate: NSObject, XMLParserDelegate {
        let mpdURL: String
        var qualities: [VideoQuality] = []
        private var inVideoSet = false
        private var setWidth = 0, setHeight = 0

        init(mpdURL: String) { self.mpdURL = mpdURL }

        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                    qualifiedName: String?, attributes attr: [String: String]) {
            switch name {
            case "AdaptationSet":
                let mime = attr["mimeType"] ?? ""
                let ctype = attr["contentType"] ?? ""
                let codecs = attr["codecs"] ?? ""
                inVideoSet = mime.hasPrefix("video/") || ctype == "video"
                    || DASHParser.videoCodecs.contains(where: codecs.hasPrefix)
                setWidth = Int(attr["width"] ?? "") ?? 0
                setHeight = Int(attr["height"] ?? "") ?? 0
            case "Representation" where inVideoSet:
                let bandwidth = Int64(attr["bandwidth"] ?? "") ?? 0
                let height = Int(attr["height"] ?? "") ?? setHeight
                let codecs = attr["codecs"]
                let isAudio = codecs.map { c in DASHParser.audioCodecs.contains(where: c.hasPrefix) } ?? false
                if bandwidth > 0, height > 0, !isAudio {
                    qualities.append(VideoQuality(label: "\(height)p", bandwidth: bandwidth, url: mpdURL, codecs: codecs))
                }
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
            if name == "AdaptationSet" { inVideoSet = false; setWidth = 0; setHeight = 0 }
        }
    }
}
