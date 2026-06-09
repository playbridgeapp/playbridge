import Foundation

/// Classification of a detected stream. Mirrors the `detectedBy`/content-type buckets the Android
/// `VideoDetector` works with.
enum StreamKind: String {
    case hls, dash, mp4, subtitle, other

    var badge: String {
        switch self {
        case .hls: return "HLS"
        case .dash: return "DASH"
        case .mp4: return "MP4"
        case .subtitle: return "SUB"
        case .other: return "VID"
        }
    }
}

/// A playable stream (or subtitle) sniffed from a web page. Port of `DetectedVideo` in
/// `cast/VideoDetector.kt`.
struct DetectedVideo: Identifiable, Equatable {
    var id: String { url }
    let url: String
    var contentType: String?
    var detectedBy: String
    var originUrl: String?
    var headers: [String: String]
    var kind: StreamKind

    var isSubtitle: Bool { kind == .subtitle }

    /// A short display title derived from the URL's last path component (or host).
    var displayTitle: String {
        guard let comps = URLComponents(string: url) else { return url }
        let last = (comps.path as NSString).lastPathComponent
        if !last.isEmpty, last != "/" { return last }
        return comps.host ?? url
    }

    var host: String { URLComponents(string: url)?.host ?? "" }

    static func classify(url: String, contentType: String?) -> StreamKind {
        let lower = url.lowercased().components(separatedBy: "?").first ?? url.lowercased()
        let ct = contentType?.lowercased() ?? ""
        if lower.hasSuffix(".vtt") || lower.hasSuffix(".srt") || ct.contains("vtt") || ct.contains("subrip") {
            return .subtitle
        }
        if lower.contains(".m3u8") || ct.contains("mpegurl") { return .hls }
        if lower.hasSuffix(".mpd") || ct.contains("application/dash") { return .dash }
        let videoExts = [".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".flv", ".wmv", ".3gp"]
        if videoExts.contains(where: lower.hasSuffix) || ct.hasPrefix("video/") { return .mp4 }
        return .other
    }
}

/// An HLS/DASH quality variant. Port of `VideoQuality` in `cast/HlsParser.kt`.
struct VideoQuality: Identifiable, Equatable {
    var id: String { url + label }
    let label: String       // e.g. "1080p", or "Auto"
    let bandwidth: Int64    // bits/sec (0 for "Auto")
    let url: String
    var codecs: String? = nil
}
