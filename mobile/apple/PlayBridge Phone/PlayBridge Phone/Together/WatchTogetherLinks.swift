import Foundation

/// Recognizes Watch Together invite links (`https://relay/j/<id>#k=...&h=...`),
/// including the query form handed from the invite page (`?k=...&h=...`).
enum WatchTogetherLinks {
    private static let inviteRegex = try! NSRegularExpression(
        pattern: #"^https?://[^/?#]+(/[^?#]*)?/j/[0-9a-f]{32}#(?=.*\bk=)(?=.*\bh=).+$"#
    )
    private static let handedOverRegex = try! NSRegularExpression(
        pattern: #"^(https?://[^/?#]+(?:/[^?#]*)?/j/[0-9a-f]{32})\?([^#]*\bk=[^#]*)$"#
    )

    static func isInvite(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        return inviteRegex.firstMatch(in: trimmed, range: range) != nil
    }

    static func invite(_ url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if isInvite(trimmed) { return trimmed }
        let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        guard let match = handedOverRegex.firstMatch(in: trimmed, range: range),
              let baseRange = Range(match.range(at: 1), in: trimmed),
              let queryRange = Range(match.range(at: 2), in: trimmed) else {
            return nil
        }
        let rebuilt = "\(trimmed[baseRange])#\(trimmed[queryRange])"
        return isInvite(rebuilt) ? rebuilt : nil
    }

    private static let roomRegex = try! NSRegularExpression(pattern: #"/j/([0-9a-f]{32})#"#)

    /// The room ID in an invite link, or nil for other links. Not secret, unlike the link.
    static func roomId(_ raw: String) -> String? {
        guard let link = invite(raw) else { return nil }
        let range = NSRange(link.startIndex..<link.endIndex, in: link)
        guard let match = roomRegex.firstMatch(in: link, range: range),
              let roomRange = Range(match.range(at: 1), in: link) else { return nil }
        return String(link[roomRange])
    }
}
