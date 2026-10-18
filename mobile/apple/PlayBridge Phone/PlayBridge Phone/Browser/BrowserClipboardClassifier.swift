// SPDX-License-Identifier: MPL-2.0
import Foundation
import Darwin

enum BrowserClipboardSuggestion: Equatable {
    case link(String), text(String)

    func target(search: (String) -> String) -> String {
        switch self {
        case .link(let input): return BrowserAddressResolver.resolve(input, search: search)
        case .text(let query): return search(query)
        }
    }
}

enum BrowserClipboardClassifier {
    static let maximumInputLength = 2000
    static let maximumQueryLength = 200
    private static let nonTLDExtensions: Set<String> = [
        "gz", "tar", "tgz", "bz2", "xz", "zst", "7z", "zip", "rar",
        "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
        "mp3", "mp4", "mkv", "avi", "mov", "wav", "flac", "ogg", "m4a",
        "apk", "aab", "exe", "dmg", "pkg", "deb", "rpm", "msi", "iso",
        "bin", "cue", "rom", "jar", "aar", "js", "ts", "json", "xml", "html", "htm", "css", "scss",
        "kt", "java", "py", "c", "cpp", "h", "rs", "go", "rb", "php", "sh", "yaml", "yml", "toml", "sql", "db", "log", "md"
    ]

    static func classify(_ raw: String?) -> BrowserClipboardSuggestion? {
        guard let raw, raw.utf16.count <= maximumInputLength else { return nil }
        let collapsed = raw.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
        guard !collapsed.isEmpty else { return nil }
        // No native magnet destination exists on the iOS phone.
        guard !collapsed.lowercased().hasPrefix("magnet:") else { return nil }
        if !collapsed.contains(" ") {
            var candidate = collapsed
            while let last = candidate.last, ".,;!".contains(last) { candidate.removeLast() }
            if isLink(candidate) { return .link(candidate) }
        }
        var query = "", length = 0
        for character in collapsed {
            let count = String(character).utf16.count
            guard length + count <= maximumQueryLength else { break }
            query.append(character)
            length += count
        }
        return query.isEmpty ? nil : .text(query)
    }

    private static func isLink(_ candidate: String) -> Bool {
        let lower = candidate.lowercased()
        if lower.hasPrefix("about:") { return candidate.count > "about:".count }
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            guard let parts = URLComponents(string: candidate), let host = parts.host, !host.isEmpty else { return false }
            return parts.port.map { (1...65535).contains($0) } ?? true
        }
        let authority = String(lower.prefix { !"/?#".contains($0) })
        let host: String
        let port: String?
        if authority.hasPrefix("[") {
            guard let end = authority.firstIndex(of: "]") else { return false }
            host = String(authority[authority.index(after: authority.startIndex)..<end])
            let suffix = authority[authority.index(after: end)...]
            guard suffix.isEmpty || suffix.hasPrefix(":") else { return false }
            port = suffix.isEmpty ? nil : String(suffix.dropFirst())
            var address = in6_addr()
            guard host.withCString({ inet_pton(AF_INET6, $0, &address) }) == 1 else { return false }
        } else {
            let parts = authority.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count <= 2 else { return false }
            host = String(parts[0])
            port = parts.count == 2 ? String(parts[1]) : nil
            if host != "localhost" {
                let octets = host.split(separator: ".", omittingEmptySubsequences: false)
                let ipv4 = octets.count == 4 && octets.allSatisfy {
                    !$0.isEmpty && $0.allSatisfy { $0.isASCII && $0.isNumber } && (Int($0).map { (0...255).contains($0) } ?? false)
                }
                let domain = host.range(of: #"^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$"#, options: .regularExpression) != nil
                guard ipv4 || (domain && !nonTLDExtensions.contains(String(octets.last ?? ""))) else { return false }
            }
        }
        if let port {
            guard !port.isEmpty, port.count <= 5, port.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let number = Int(port), (1...65535).contains(number) else { return false }
        }
        return true
    }
}

/// Dismissals are tied to the clipboard generation, shared across tab changes.
struct BrowserClipboardOfferPolicy {
    private(set) var dismissedChangeCount: Int?
    func allows(_ changeCount: Int) -> Bool { dismissedChangeCount != changeCount }
    mutating func dismiss(_ changeCount: Int) { dismissedChangeCount = changeCount }
}
