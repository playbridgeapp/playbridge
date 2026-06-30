import Foundation

enum IptvSourceType: String, Codable {
    case url
    case file
}

struct IptvChannel: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var url: String
    var logo: String?
    var groupTitle: String?
    var tvgId: String?
    var order: Int
    var headers: [String: String] = [:]
}

struct IptvPlaylist: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    /// For `.url`: the playlist URL. For `.file`: a base64 security-scoped bookmark.
    var source: String
    var sourceType: IptvSourceType
    var addedAt: Date
    var updatedAt: Date?
    var channels: [IptvChannel]

    var channelCount: Int { channels.count }
}

enum IptvError: LocalizedError {
    case invalidURL
    case noChannels
    case http(Int)
    case decode
    case fileUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidURL:     return "That doesn't look like a valid URL."
        case .noChannels:     return "No channels were found in that playlist."
        case .http(let code): return "The server returned HTTP \(code)."
        case .decode:         return "Couldn't read the playlist text."
        case .fileUnavailable: return "That file can't be accessed anymore. Please re-add it."
        }
    }
}

/// Parses M3U / M3U8 extended playlists into channels, capturing the common
/// IPTV attributes (logo, category, tvg-id) and per-channel headers.
enum M3UParser {
    static func parse(_ text: String) -> [IptvChannel] {
        // A single HLS stream (variant playlist) is not an IPTV channel list.
        if text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-TARGETDURATION") {
            return []
        }

        var channels: [IptvChannel] = []
        var order = 0

        var pendingName: String?
        var pendingLogo: String?
        var pendingGroup: String?
        var pendingTvgId: String?
        var pendingHeaders: [String: String] = [:]
        var extgrp: String?

        func reset() {
            pendingName = nil; pendingLogo = nil; pendingGroup = nil
            pendingTvgId = nil; pendingHeaders = [:]
        }

        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }

            if line.hasPrefix("#EXTINF") {
                pendingTvgId = attribute("tvg-id", in: line)
                pendingLogo = attribute("tvg-logo", in: line)
                pendingGroup = attribute("group-title", in: line)
                let tvgName = attribute("tvg-name", in: line)
                if let comma = line.firstIndex(of: ",") {
                    let title = String(line[line.index(after: comma)...]).trimmingCharacters(in: .whitespaces)
                    pendingName = title.isEmpty ? tvgName : title
                } else {
                    pendingName = tvgName
                }
            } else if line.hasPrefix("#EXTGRP:") {
                extgrp = String(line.dropFirst("#EXTGRP:".count)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("#EXTVLCOPT:") {
                let opt = String(line.dropFirst("#EXTVLCOPT:".count))
                if let eq = opt.firstIndex(of: "=") {
                    let key = opt[..<eq].lowercased()
                    let val = String(opt[opt.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                    if key.contains("user-agent") { pendingHeaders["User-Agent"] = val }
                    else if key.contains("referrer") || key.contains("referer") { pendingHeaders["Referer"] = val }
                }
            } else if line.hasPrefix("#") {
                continue
            } else {
                let url = line
                let group = pendingGroup ?? extgrp
                let name = pendingName ?? URL(string: url)?.lastPathComponent ?? "Channel \(order + 1)"
                channels.append(IptvChannel(
                    name: name, url: url, logo: pendingLogo,
                    groupTitle: group, tvgId: pendingTvgId,
                    order: order, headers: pendingHeaders
                ))
                order += 1
                reset()
                extgrp = nil
            }
        }
        return channels
    }

    /// Extracts a `key="value"` attribute from an `#EXTINF` line.
    private static func attribute(_ key: String, in line: String) -> String? {
        guard let r = line.range(of: "\(key)=\"") else { return nil }
        let rest = line[r.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        let val = String(rest[..<end])
        return val.isEmpty ? nil : val
    }
}
