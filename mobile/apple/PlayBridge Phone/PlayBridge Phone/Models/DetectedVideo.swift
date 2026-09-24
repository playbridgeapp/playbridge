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
struct DetectedVideo: Identifiable, Hashable {
    var id: String { url }
    let url: String
    var contentType: String?
    var detectedBy: String
    var originUrl: String?
    var headers: [String: String]
    var kind: StreamKind
    var timestamp: Int64 = 0
    var lastSeen: Int64 = 0
    var lifecycleIndex: Int = 0

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

enum SubtitleOrdering {
    /// First detection is stable across routine repeat observations on SPA pages.
    static func newestFirst(_ videos: [DetectedVideo]) -> [DetectedVideo] {
        videos.enumerated()
            .filter { $0.element.isSubtitle }
            .sorted { lhs, rhs in
                lhs.element.timestamp == rhs.element.timestamp
                    ? lhs.offset < rhs.offset
                    : lhs.element.timestamp > rhs.element.timestamp
            }
            .map(\.element)
    }
}

/// An HLS/DASH quality variant. Port of `VideoQuality` in `cast/HlsParser.kt`.
struct VideoQuality: Identifiable, Equatable {
    var id: String { "\(url)|\(label)|\(bandwidth)|\(codecs ?? "")" }
    let label: String       // e.g. "1080p", or "Auto"
    let bandwidth: Int64    // bits/sec (0 for "Auto")
    let url: String
    var codecs: String? = nil
}

/// Prefer measured quality ladders over URL naming guesses. Equal candidates retain
/// detection order when their quality and preview evidence is equal.
enum StreamThumbnailState {
    case loading, ready, unavailable
}

enum StreamValidation { case pending, verified, failed }
enum HLSPlaylistRole { case unknown, master, media }
struct StreamManifestInfo {
    var qualities: [VideoQuality] = []
    var validation: StreamValidation = .pending
    var hlsRole: HLSPlaylistRole = .unknown
}

/// Mirrors Android VideoDetector.castScore + castSheetComparator for supported
/// iOS detections. Synthetic handoff masters are not produced by the iOS detector.
enum CastStreamRanking {
    static func evidenceScore(_ method: String) -> Int {
        switch method.lowercased() {
        case "body_content_m3u8", "body_content_mpd": return 80
        case "synthetic_hls_master": return 75
        case "player_config": return 70
        case "content_type", "fetch_content_type", "xhr_content_type": return 50
        case "dom_source", "dom_video_element": return 30
        case "url_extension", "fetch_url", "xhr_url": return 20
        case "url_pattern_m3u8", "url_pattern_mpd": return 10
        case "response_body_url": return 5
        default: return 15
        }
    }

    static func sorted(_ videos: [DetectedVideo], qualities: [String: [VideoQuality]], thumbnails: [String: StreamThumbnailState] = [:], manifests: [String: StreamManifestInfo] = [:]) -> [DetectedVideo] {
        let newest = videos.map { max($0.timestamp, $0.lastSeen) }.max() ?? 0
        let lifecycle = videos.map(\.lifecycleIndex).max() ?? 0
        func total(_ video: DetectedVideo) -> Int {
            let ageMinutes = max(0, newest - max(video.timestamp, video.lastSeen)) / 60_000
            let recency = 150 - Int(min(150, ageMinutes * 50))
            return score(video, qualities: qualities[video.id] ?? [], thumbnail: thumbnails[video.id], manifest: manifests[video.id])
                + recency + (video.lifecycleIndex >= lifecycle ? 400 : 0)
        }
        return videos.enumerated().sorted { lhs, rhs in
            let left = total(lhs.element), right = total(rhs.element)
            if left != right { return left > right }
            let leftSeen = max(lhs.element.timestamp, lhs.element.lastSeen)
            let rightSeen = max(rhs.element.timestamp, rhs.element.lastSeen)
            return leftSeen == rightSeen ? lhs.offset < rhs.offset : leftSeen > rightSeen
        }.map { $0.element }
    }

    private static func score(_ video: DetectedVideo, qualities: [VideoQuality], thumbnail: StreamThumbnailState?, manifest: StreamManifestInfo?) -> Int {
        let adaptive = video.kind == .hls || video.kind == .dash
        let count = Set(qualities.map(\.id)).count
        let verified = manifest?.validation == .verified || thumbnail == .ready || (adaptive && count > 0)
            || video.detectedBy == "body_content_m3u8" || video.detectedBy == "body_content_mpd"
        let validationScore = verified ? 600 : manifest?.validation == .failed ? 0 : 300
        let master = manifest?.hlsRole == .master || (video.kind == .hls && count > 0)
        let adaptiveScore = master || (video.kind == .dash && count > 0) ? 35
            : manifest?.hlsRole == .media ? 30 : adaptive ? 20 : 10
        let ladder = count > 1 && (master || video.kind == .dash) ? 125 : 0
        return validationScore + evidenceScore(video.detectedBy) + adaptiveScore + ladder
            + (video.headers.isEmpty ? 0 : 15) + (thumbnail == .ready ? 25 : 0)
    }
}
