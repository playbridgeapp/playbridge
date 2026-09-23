import Foundation

/// Parses an HLS master playlist into its quality variants. Port of `cast/HlsParser.kt`
/// (the master-parsing path). Casting a chosen variant sends that variant's URL directly
/// (the iOS app doesn't generate/serve a filtered master like the Android `HlsExportService`).
enum HLSParser {
    private static let bandwidthRE = try! NSRegularExpression(pattern: "BANDWIDTH=(\\d+)")
    private static let resolutionRE = try! NSRegularExpression(pattern: "RESOLUTION=(\\d+x\\d+)")
    private static let codecsRE = try! NSRegularExpression(pattern: "CODECS=\"([^\"]+)\"")

    static func variants(masterURL: String, headers: [String: String]) async -> [VideoQuality] {
        await inspect(masterURL: masterURL, headers: headers).qualities
    }

    static func inspect(masterURL: String, headers: [String: String]) async -> StreamManifestInfo {
        guard let content = await StreamHTTP.fetchText(masterURL, headers: headers) else { return StreamManifestInfo() }
        return parse(content, masterURL: masterURL)
    }

    static func parse(_ content: String, masterURL: String) -> StreamManifestInfo {
        guard content.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U") else {
            return StreamManifestInfo(validation: .failed)
        }

        var result: [VideoQuality] = []
        var pendingBandwidth: Int64?
        var pendingResolution: String?
        var pendingCodecs: String?

        for raw in content.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXT-X-STREAM-INF:") {
                pendingBandwidth = firstMatch(bandwidthRE, in: line).flatMap { Int64($0) }
                pendingResolution = firstMatch(resolutionRE, in: line)
                pendingCodecs = firstMatch(codecsRE, in: line)
            } else if !line.hasPrefix("#"), !line.isEmpty, let bw = pendingBandwidth {
                let url = StreamHTTP.resolve(line, against: masterURL)
                let label = pendingResolution.map { "\($0.components(separatedBy: "x").last ?? "")p" } ?? "Variant"
                result.append(VideoQuality(label: label, bandwidth: bw, url: url, codecs: pendingCodecs))
                pendingBandwidth = nil; pendingResolution = nil; pendingCodecs = nil
            }
        }
        if !result.isEmpty {
            return StreamManifestInfo(qualities: result.sorted { $0.bandwidth > $1.bandwidth }, validation: .verified, hlsRole: .master)
        }
        let hasMedia = content.contains("#EXTINF:") && content.split(whereSeparator: \.isNewline).contains {
            let line = $0.trimmingCharacters(in: .whitespaces)
            return !line.isEmpty && !line.hasPrefix("#")
        }
        return StreamManifestInfo(validation: hasMedia ? .verified : .failed, hlsRole: hasMedia ? .media : .unknown)
    }

    private static func firstMatch(_ re: NSRegularExpression, in s: String) -> String? {
        let range = NSRange(s.startIndex..., in: s)
        guard let m = re.firstMatch(in: s, range: range), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }
}
