import Foundation

enum WebsiteCaptionParser {
    static func parse(_ raw: String) -> [(start: Double, end: Double, text: String)] {
        let normalized = raw.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let timing = try! NSRegularExpression(pattern: #"(?m)^\s*((?:\d+:)?\d{2}:\d{2}[.,]\d{3})\s+-->\s+((?:\d+:)?\d{2}:\d{2}[.,]\d{3})[^\n]*\n"#)
        let text = normalized as NSString
        let matches = timing.matches(in: normalized, range: NSRange(location: 0, length: text.length))
        return matches.prefix(100_000).compactMap { match in
            func seconds(_ stamp: String) -> Double {
                stamp.replacingOccurrences(of: ",", with: ".").split(separator: ":").reduce(0) { $0 * 60 + (Double($1) ?? 0) }
            }
            let start = seconds(text.substring(with: match.range(at: 1)))
            let end = seconds(text.substring(with: match.range(at: 2)))
            guard end > start else { return nil }
            let after = NSMaxRange(match.range)
            let boundary = text.range(of: "\n\n", options: [], range: NSRange(location: after, length: text.length - after))
            let length = min(16_384, (boundary.location == NSNotFound ? text.length : boundary.location) - after)
            let caption = text.substring(with: NSRange(location: after, length: length))
                .replacingOccurrences(of: #"<[^>]*>"#, with: "", options: .regularExpression)
            return (start, end, caption)
        }
    }
}
