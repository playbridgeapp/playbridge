// SPDX-License-Identifier: MPL-2.0
import Foundation

enum BrowserAddressResolver {
    static func resolve(_ input: String, search: (String) -> String) -> String {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = text.lowercased()
        // Never run pasted scripts from the address bar.
        if lower.hasPrefix("javascript:") { return search(text) }
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") || lower.hasPrefix("about:") { return text }
        if ["file:", "data:", "blob:", "mailto:", "tel:", "magnet:"].contains(where: lower.hasPrefix) { return text }
        if text.range(of: "^[a-z][a-z0-9+.-]*://", options: [.regularExpression, .caseInsensitive]) != nil { return text }
        guard !text.contains(where: \.isWhitespace) else { return search(text) }
        let authority = lower.prefix { !"/?#".contains($0) }
        if authority.hasPrefix("["), let end = authority.firstIndex(of: "]") {
            let host = String(authority[authority.index(after: authority.startIndex)..<end])
            let literal = String(host.split(separator: "%", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            let groups = literal.split(separator: ":", omittingEmptySubsequences: false)
            let first = UInt16(groups.first ?? "", radix: 16) ?? 0
            let loopback = groups.count > 1 && UInt16(groups.last ?? "", radix: 16) == 1 &&
                groups.dropLast().allSatisfy { $0.isEmpty || UInt16($0, radix: 16) == 0 }
            let local = loopback || (0xfe80...0xfebf).contains(first) || (0xfc00...0xfdff).contains(first)
            return (local ? "http://" : "https://") + text
        }
        let parts = authority.split(separator: ":", omittingEmptySubsequences: false)
        let host = String(parts[0])
        let hasPort = parts.count == 2 && !parts[1].isEmpty && parts[1].allSatisfy(\.isNumber)
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        let ipv4 = octets.count == 4 && octets.allSatisfy { !$0.isEmpty && $0.allSatisfy(\.isNumber) && (Int($0).map { (0...255).contains($0) } ?? false) }
        let local = host == "localhost" || host.hasSuffix(".localhost") || host.hasSuffix(".local") || host.hasSuffix(".lan") || (hasPort && !host.contains("."))
        if local || ipv4 { return "http://" + text }
        if hasPort || text.contains(".") { return "https://" + text }
        return search(text)
    }
}
