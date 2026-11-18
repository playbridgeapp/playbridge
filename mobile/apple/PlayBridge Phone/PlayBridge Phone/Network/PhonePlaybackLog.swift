import Foundation

/// Debug-build trace of "play on this phone": what was chosen, how it was routed and what mpv
/// did with it. Printed to the console as `[PhonePlayback] …`, never written to a file.
///
/// Never logs a full URL, query, header value, cookie or token: addresses are reduced to
/// host and file extension, headers to their names, and mpv's own messages have every
/// address replaced and lines that mention credentials dropped.
enum PhonePlaybackLog {
    static func log(_ message: @autoclosure () -> String) {
#if DEBUG
        print("[PhonePlayback] " + message())
#endif
    }

    /// `host=cdn.example ext=m3u8`, or `loopback ext=m3u8` for the phone's own proxy.
    static func describe(_ url: URL?) -> String {
        guard let url else { return "url=none" }
        let host = url.host ?? "?"
        let ext = url.pathExtension.isEmpty ? "none" : url.pathExtension.lowercased()
        let place = ["127.0.0.1", "localhost", "::1"].contains(host) ? "loopback" : "host=\(host)"
        return "\(place) scheme=\(url.scheme ?? "?") ext=\(ext)"
    }

    static func describe(_ string: String) -> String { describe(URL(string: string)) }

    /// Header names only, sorted; values are never logged.
    static func headerNames(_ headers: [String: String]) -> String {
        headers.isEmpty ? "none" : headers.keys.map { $0.lowercased() }.sorted().joined(separator: ",")
    }

    private static let address = try! NSRegularExpression(pattern: #"[a-zA-Z][a-zA-Z0-9+.-]*://[^\s'"<>]+"#)
    private static let secret = try! NSRegularExpression(
        pattern: #"cookie|authorization|token|signature|password|bearer|x-amz|key="#, options: .caseInsensitive)

    /// An mpv or FFmpeg message without addresses; nil when it may carry a credential.
    static func redact(_ text: String) -> String? {
        let line = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty else { return nil }
        let range = NSRange(line.startIndex..., in: line)
        guard secret.firstMatch(in: line, range: range) == nil else { return nil }
        var result = ""
        var last = line.startIndex
        for match in address.matches(in: line, range: range) {
            guard let found = Range(match.range, in: line) else { continue }
            result += line[last..<found.lowerBound]
            result += "<" + describe(String(line[found])) + ">"
            last = found.upperBound
        }
        result += line[last...]
        return String(result.prefix(300))
    }
}
