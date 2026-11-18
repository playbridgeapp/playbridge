import Foundation

/// Asks a remembered link's site whether it still works. Port of Android's `LinkCheck` (#545).
/// Off by default. The saved headers (which can hold sign-in details) go only to the link's own
/// host: a redirect elsewhere isn't followed. Home-network and local addresses aren't checked.
/// Only the first byte is requested and nothing is logged.
enum LinkCheck {
    enum Result: String, Codable { case works, expired, couldNotCheck }

    /// HTTP statuses that mean the saved link no longer grants access.
    static func result(forStatus status: Int?) -> Result {
        guard let status else { return .couldNotCheck }
        if (200...299).contains(status) { return .works }
        if [401, 403, 404, 410].contains(status) { return .expired }
        return .couldNotCheck
    }

    /// Hosts that are this device, the local network or a private address.
    static func isLocal(_ host: String) -> Bool {
        let name = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if name == "localhost" || name.hasSuffix(".local") || name.hasSuffix(".lan") || name.hasSuffix(".home") || !name.contains(".") && !name.contains(":") { return true }
        let parts = name.split(separator: ".").compactMap { Int($0) }
        if parts.count == 4 {
            switch (parts[0], parts[1]) {
            case (10, _), (127, _), (0, _), (169, 254), (192, 168): return true
            case (172, let b) where (16...31).contains(b): return true
            case (100, let b) where (64...127).contains(b): return true
            default: return false
            }
        }
        return name.contains(":") && (name == "::1" || name.hasPrefix("fe80") || name.hasPrefix("fc") || name.hasPrefix("fd"))
    }

    static func check(url raw: String, headers: [String: String]) async -> Result {
        guard let url = URL(string: raw), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !isLocal(host) else { return .couldNotCheck }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        for (name, value) in headers where name.caseInsensitiveCompare("Range") != .orderedSame {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        let session = URLSession(configuration: configuration, delegate: SameHostRedirects(host: host), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            bytes.task.cancel()
            return result(forStatus: (response as? HTTPURLResponse)?.statusCode)
        } catch {
            return .couldNotCheck
        }
    }
}

/// Follows a redirect only to the same host, so saved headers never reach another site.
private final class SameHostRedirects: NSObject, URLSessionTaskDelegate {
    let host: String
    init(host: String) { self.host = host.lowercased() }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        let same = request.url?.host?.lowercased() == host && request.url?.scheme?.lowercased() == "https"
        completionHandler(same && !LinkCheck.isLocal(host) ? request : nil)
    }
}
