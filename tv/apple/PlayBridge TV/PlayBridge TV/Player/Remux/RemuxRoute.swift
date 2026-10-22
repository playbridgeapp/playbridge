import Foundation

/// Experimental: AVPlayer plays Matroska through an on-device HLS remux
/// (`HLSRemuxer` served by `LocalHLSServer`). Anything the remux rejects fails
/// the AVPlayer item, which already falls back to mpv.
enum RemuxRoute {
    static let settingKey = "avplayerRemuxMatroska"
    static let extensions: Set<String> = ["mkv", "mk3d"]

    /// Remote or local Matroska by path extension (query strings ignored).
    static func isCandidate(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), ["http", "https", "file"].contains(scheme) else { return false }
        return extensions.contains(url.pathExtension.lowercased())
    }

    enum Resource: Equatable {
        case master, media, initSegment, segment(Int)

        var contentType: String {
            switch self {
            case .master, .media: return "application/vnd.apple.mpegurl"
            case .initSegment: return "video/mp4"
            case .segment: return "video/iso.segment"
            }
        }
    }

    /// `/<token>/<resource>` from a request path; the token is an opaque session id.
    static func parse(path: String) -> (token: String, resource: Resource)? {
        let parts = path.split(separator: "?", maxSplits: 1).first?.split(separator: "/") ?? []
        guard parts.count == 2, !parts[0].isEmpty else { return nil }
        let token = String(parts[0])
        switch parts[1] {
        case "master.m3u8": return (token, .master)
        case "index.m3u8": return (token, .media)
        case "init.mp4": return (token, .initSegment)
        default:
            let name = parts[1]
            guard name.hasPrefix("seg"), name.hasSuffix(".m4s"),
                  let index = Int(name.dropFirst(3).dropLast(4)), index >= 0 else { return nil }
            return (token, .segment(index))
        }
    }
}
