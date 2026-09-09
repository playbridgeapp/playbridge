import Foundation

/// Conservative, host-indexed subset of filter syntax for top-level navigations.
/// Never reuse the detector's coarse domain projection for document blocking.
struct NavigationAdRules {
    private struct Rule {
        let expression: NSRegularExpression
        let popup: Bool
        let document: Bool
        let exception: Bool
        let includes: [String]
        let excludes: [String]
    }
    private var byHost: [String: [Rule]] = [:]

    init(text: String = "") {
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }
        // A disabled rule must not become active merely because its modifier is unsupported.
        func identity(_ line: String) -> String {
            let parts = line.split(separator: "$", maxSplits: 1, omittingEmptySubsequences: false)
            let options = parts.count == 2 ? parts[1].split(separator: ",").map(String.init).filter { $0 != "badfilter" }.sorted() : []
            return String(parts[0]) + (options.isEmpty ? "" : "$" + options.joined(separator: ","))
        }
        let disabled = Set(lines.filter { $0.split(separator: "$", maxSplits: 1).last?.split(separator: ",").contains("badfilter") == true }.map(identity))
        for line in lines {
            guard !line.isEmpty, !disabled.contains(identity(line)) else { continue }
            let exception = line.hasPrefix("@@")
            let raw = exception ? String(line.dropFirst(2)) : line
            guard raw.hasPrefix("||") else { continue }
            let parts = raw.dropFirst(2).split(separator: "$", maxSplits: 1, omittingEmptySubsequences: false)
            let pattern = String(parts[0])
            let host = String(pattern.prefix { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }).lowercased()
            guard host.contains("."), !host.hasPrefix("."), !host.hasSuffix("."), !host.contains("..") else { continue }
            let suffix = String(pattern.dropFirst(host.count))
            guard suffix.isEmpty || suffix.first == "^" || suffix.first == "/" || suffix.first == "|" else { continue }
            var popup = true
            // Untyped host-wide rules can safely identify destinations; untyped
            // path fragments commonly describe subresources, not whole pages.
            var document = exception || suffix.isEmpty || suffix == "^" || suffix == "^|"
            var includes: [String] = [], excludes: [String] = []
            var caseSensitive = false
            var unsupported = false
            let options = parts.count == 2 ? parts[1].split(separator: ",").map { String($0).lowercased() } : []
            if options.contains("popup") || options.contains("document") || options.contains("all") {
                popup = options.contains("popup") || options.contains("all")
                document = options.contains("document") || options.contains("all")
            }
            for option in options {
                switch option {
                case "popup", "document", "all": break
                case "~popup": popup = false
                case "~document": document = false
                case "match-case": caseSensitive = true
                default:
                    if option.hasPrefix("domain=") {
                        if option.count == 7 { unsupported = true }
                        for domain in option.dropFirst(7).split(separator: "|") {
                            let excluded = domain.hasPrefix("~")
                            let value = excluded ? String(domain.dropFirst()) : String(domain)
                            guard !value.isEmpty, value.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }) else { unsupported = true; continue }
                            if excluded { excludes.append(value) } else { includes.append(value) }
                        }
                    } else {
                        // Resource types, party/PSL constraints, rewriting, regex,
                        // important, and other unsupported semantics are not broadened.
                        unsupported = true
                    }
                }
            }
            guard !unsupported, popup || document else { continue }
            var tail = ""
            for (index, character) in suffix.enumerated() {
                switch character {
                case "*": tail += ".*"
                case "^": tail += "(?:[^a-zA-Z0-9._%-]|$)"
                case "|" where index == suffix.count - 1: tail += "$"
                default: tail += NSRegularExpression.escapedPattern(for: String(character))
                }
            }
            let expression = "^https?://(?:[^/?#:@]+\\.)*" + NSRegularExpression.escapedPattern(for: host) + "(?=[:/?#]|$)" + tail
            guard let regex = try? NSRegularExpression(pattern: expression, options: caseSensitive ? [] : [.caseInsensitive]) else { continue }
            byHost[host, default: []].append(Rule(expression: regex, popup: popup, document: document,
                                                 exception: exception, includes: includes, excludes: excludes))
        }
    }

    static func host(_ host: String, matches domain: String) -> Bool {
        host == domain || host.hasSuffix("." + domain)
    }

    /// nil means no applicable rule; false is an explicit exception.
    func decision(url: URL, source: URL?, popup: Bool) -> Bool? {
        guard let host = url.host?.lowercased(), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        let sourceHost = source?.host?.lowercased() ?? ""
        let text = url.absoluteString
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        var blocked = false
        var candidate = host
        while true {
            for rule in byHost[candidate] ?? [] {
                guard popup ? rule.popup : rule.document,
                      rule.includes.isEmpty || rule.includes.contains(where: { Self.host(sourceHost, matches: $0) }),
                      !rule.excludes.contains(where: { Self.host(sourceHost, matches: $0) }),
                      rule.expression.firstMatch(in: text, range: range) != nil else { continue }
                if rule.exception { return false }
                blocked = true
            }
            guard let dot = candidate.firstIndex(of: ".") else { break }
            candidate = String(candidate[candidate.index(after: dot)...])
        }
        return blocked ? true : nil
    }
}
