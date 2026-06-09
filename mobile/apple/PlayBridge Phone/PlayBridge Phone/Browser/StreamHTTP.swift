import Foundation

/// Shared HTTP helpers for fetching playlists/manifests with the stream's captured headers.
enum StreamHTTP {
    static let fallbackUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    static func fetchText(_ urlString: String, headers: [String: String]) async -> String? {
        guard let url = URL(string: urlString) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 10)
        for (k, v) in headers where k.caseInsensitiveCompare("Range") != .orderedSame {
            req.setValue(v, forHTTPHeaderField: k)
        }
        if !headers.keys.contains(where: { $0.caseInsensitiveCompare("User-Agent") == .orderedSame }) {
            req.setValue(fallbackUA, forHTTPHeaderField: "User-Agent")
        }
        do {
            let (data, _) = try await URLSession.shared.data(for: req)
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }

    /// Resolve a possibly-relative URL against a base.
    static func resolve(_ relative: String, against base: String) -> String {
        if relative.hasPrefix("http") { return relative }
        return URL(string: relative, relativeTo: URL(string: base))?.absoluteString ?? relative
    }
}
