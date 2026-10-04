import Foundation

/// Captured browser headers adapted for mpv's comma-separated http-header-fields option.
enum MPVHTTPHeaders {
    static func fields(from headers: [String: String]?) -> String {
        (headers ?? [:])
            .filter { name, _ in
                // mpv owns byte ranges for demuxer probes, resume and seeks. Replaying
                // the browser's Range pins every request to the captured offset and
                // prevents reading a non-faststart MP4's trailing moov atom.
                // User-Agent is supplied separately through mpv's user-agent option.
                !["user-agent", "range", "if-range"].contains(name.lowercased())
            }
            .sorted { $0.key < $1.key }
            .map { name, value in
                let escaped = value
                    .replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: ",", with: "\\,")
                return "\(name): \(escaped)"
            }
            .joined(separator: ",")
    }
}
