// SPDX-License-Identifier: MPL-2.0
import Foundation

/// PlayBridge implementation of the spike's ublock/safari/mobile conditional profile.
/// Unknown expressions retain both branches, matching the upstream preparser's fallback.
/// This processes filter data only. No uBO JavaScript resources are downloaded or executed.
enum AdblockListPreparser {
    static let profile = ["ublock", "safari", "mobile"]
    private static func token(_ raw: String) -> Bool? {
        let negated = raw.hasPrefix("!")
        let name = negated ? String(raw.dropFirst()) : raw
        let enabled = ["ext_ublock", "env_safari", "env_mobile"]
        let disabled = ["ext_ubol", "ext_devbuild", "env_brave", "env_chromium", "env_edge", "env_firefox",
                        "env_legacy", "env_mv3", "false", "ext_abp", "adguard", "adguard_app_android",
                        "adguard_app_cli", "adguard_app_ios", "adguard_app_mac", "adguard_app_windows",
                        "adguard_ext_android_cb", "adguard_ext_chromium", "adguard_ext_chromium_mv3",
                        "adguard_ext_edge", "adguard_ext_firefox", "adguard_ext_opera", "adguard_ext_safari"]
        if enabled.contains(name) { return !negated }
        if disabled.contains(name) || name.hasPrefix("cap_") { return negated }
        return nil
    }
    static func evaluate(_ raw: String) -> Bool? {
        var expression = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if expression.hasPrefix("("), expression.hasSuffix(")") { expression = String(expression.dropFirst().dropLast()) }
        let parts = expression.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count % 2 == 1, let first = parts.first, var value = token(first) else { return nil }
        var index = 1
        while index < parts.count {
            guard let next = token(parts[index + 1]) else { return nil }
            switch parts[index] {
            case "&&": value = value && next
            case "||": value = value || next
            default: return nil
            }
            index += 2
        }
        return value
    }
    static func activeLines(_ text: String) -> [String] {
        var conditions: [Bool?] = []
        var output: [String] = []
        for line in text.components(separatedBy: .newlines) {
            if line.hasPrefix("!#if ") { conditions.append(evaluate(String(line.dropFirst(5)))); continue }
            if line.trimmingCharacters(in: .whitespaces) == "!#else" {
                if let last = conditions.popLast() { conditions.append(last.map { !$0 }) }
                continue
            }
            if line.trimmingCharacters(in: .whitespaces) == "!#endif" {
                if !conditions.isEmpty { conditions.removeLast() }
                continue
            }
            if !conditions.contains(where: { $0 == false }) { output.append(line) }
        }
        return output
    }
    enum Failure: Error { case invalidInclude, includeLimit, response, tooLarge }
    /// Same-origin HTTPS/HTTP includes only; bounded depth, fetch count and expanded bytes.
    final class Budget {
        var requests = 0
        var downloadedBytes = 0
    }
    static func expand(text: String, url: URL, seen: Set<URL> = [], depth: Int = 0, budget: Budget = Budget(),
                       fetch: (URL) async throws -> String) async throws -> String {
        guard depth <= 8, seen.count < 64 else { throw Failure.includeLimit }
        var output: [String] = []
        var bytes = 0
        for line in activeLines(text) {
            let value: String
            if line.hasPrefix("!#include ") {
                let name = String(line.dropFirst(10)).trimmingCharacters(in: .whitespacesAndNewlines)
                guard let included = URL(string: name, relativeTo: url)?.absoluteURL,
                      included.scheme == url.scheme, included.host == url.host, included.port == url.port,
                      included.user == nil, included.password == nil,
                      ["http", "https"].contains(included.scheme ?? "") else { throw Failure.invalidInclude }
                guard !seen.contains(included), included != url else { throw Failure.includeLimit }
                budget.requests += 1
                guard budget.requests <= 64 else { throw Failure.includeLimit }
                let next = try await fetch(included)
                budget.downloadedBytes += next.utf8.count
                guard budget.downloadedBytes <= 32 * 1024 * 1024 else { throw Failure.tooLarge }
                value = try await expand(text: next, url: included, seen: seen.union([url]), depth: depth + 1, budget: budget, fetch: fetch)
            } else { value = line }
            bytes += value.utf8.count + 1
            guard bytes <= 16 * 1024 * 1024 else { throw Failure.tooLarge }
            output.append(value)
        }
        return output.joined(separator: "\n")
    }
}
