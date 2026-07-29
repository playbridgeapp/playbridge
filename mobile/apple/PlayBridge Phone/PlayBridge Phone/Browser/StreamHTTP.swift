import Foundation

enum SenderDebugNetwork {
    static func request(
        _ source: String,
        url: String,
        method: String = "GET",
        headers: [String: String] = [:]
    ) {
#if DEBUG
        print("[DebugNetwork][\(source)] request: \(method) \(url)")
        print("[DebugNetwork][\(source)] request headers (\(headers.count)):")
        for (name, value) in headers.sorted(by: { $0.key.lowercased() < $1.key.lowercased() }) {
            print("[DebugNetwork][\(source)]   \(name): \(value)")
        }
#endif
    }

    static func response(_ source: String, response: URLResponse) {
#if DEBUG
        guard let http = response as? HTTPURLResponse else { return }
        print("[DebugNetwork][\(source)] response: HTTP \(http.statusCode) \(http.url?.absoluteString ?? "?")")
        print("[DebugNetwork][\(source)] response headers (\(http.allHeaderFields.count)):")
        let fields = http.allHeaderFields
            .map { (String(describing: $0.key), String(describing: $0.value)) }
            .sorted { $0.0.lowercased() < $1.0.lowercased() }
        for (name, value) in fields {
            print("[DebugNetwork][\(source)]   \(name): \(value)")
        }
#endif
    }
}

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
        SenderDebugNetwork.request(
            "StreamHTTP",
            url: url.absoluteString,
            headers: req.allHTTPHeaderFields ?? [:]
        )
        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            SenderDebugNetwork.response("StreamHTTP", response: response)
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
