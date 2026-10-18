// SPDX-License-Identifier: MPL-2.0
import Foundation

/// The JSON and cURL views of a detected video, image, audio or subtitle item, for the
/// cast sheet. Built on demand from what the detector holds; nothing is logged or saved.
enum DetectedMediaExport {
    static func json(_ item: DetectedVideo, headers: [String: String], qualities: [VideoQuality] = []) -> String {
        var object: [String: Any] = [
            "url": item.url,
            "kind": item.kind.rawValue,
            "detectedBy": item.detectedBy,
            "headers": headers,
        ]
        if let title = item.title, !title.isEmpty { object["title"] = title }
        if let contentType = item.contentType, !contentType.isEmpty { object["contentType"] = contentType }
        if let page = item.originUrl, !page.isEmpty { object["pageUrl"] = page }
        if item.timestamp > 0 { object["firstSeen"] = iso(item.timestamp) }
        if item.lastSeen > 0 { object["lastSeen"] = iso(item.lastSeen) }
        if !qualities.isEmpty {
            object["qualities"] = qualities.map { quality -> [String: Any] in
                var entry: [String: Any] = ["label": quality.label, "bandwidth": quality.bandwidth, "url": quality.url]
                if let codecs = quality.codecs, !codecs.isEmpty { entry["codecs"] = codecs }
                return entry
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object,
                                                     options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    /// A shell-safe `curl` command with the same headers PlayBridge sends for the item.
    static func curl(url: String, headers: [String: String]) -> String {
        var lines = ["curl -L \(quote(url))"]
        for (name, value) in headers.sorted(by: { $0.key.lowercased() < $1.key.lowercased() }) {
            lines.append("  -H \(quote("\(name): \(value)"))")
        }
        return lines.joined(separator: " \\\n")
    }

    /// POSIX single quotes; an embedded quote becomes '\''. Newlines are dropped, since
    /// header values and URLs never legitimately contain them.
    static func quote(_ value: String) -> String {
        let clean = value.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
        return "'" + clean.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func iso(_ milliseconds: Int64) -> String {
        Date(timeIntervalSince1970: Double(milliseconds) / 1_000).ISO8601Format()
    }
}
