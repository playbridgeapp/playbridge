import Foundation

/// The declaration is an origin-wide detection opt-out, independent of app installation.
enum BridgedAppDeclaration {
    static func origin(of url: URL) -> URL? {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: true),
              parts.user == nil, parts.password == nil,
              let scheme = parts.scheme?.lowercased(), let host = parts.host?.lowercased(), !host.isEmpty,
              scheme == "https" || (scheme == "http" && isLocalHost(host)),
              parts.port == nil || (1...65535).contains(parts.port!) else { return nil }
        parts.scheme = scheme
        parts.host = host
        if parts.port == (scheme == "https" ? 443 : 80) { parts.port = nil }
        parts.path = "/"
        parts.query = nil
        parts.fragment = nil
        return parts.url
    }

    private static func isLocalHost(_ host: String) -> Bool {
        if host == "localhost" { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.allSatisfy(\.isNumber) }) else { return false }
        let octets = parts.compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 172 && (16...31).contains(octets[1])) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    static func isValid(_ data: Data, for origin: URL) -> Bool {
        guard data.count <= 16384,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              json["protocol"] as? String == "playbridge-app-v1",
              let name = json["name"] as? String,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              (json["start_url"] == nil || json["start_url"] is NSNull || json["start_url"] is String),
              let start = URL(string: json["start_url"] as? String ?? "/", relativeTo: origin)?.absoluteURL,
              self.origin(of: start) == origin else { return false }
        return true
    }

    static func fetch(_ origin: URL) async -> Bool {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 2
        configuration.timeoutIntervalForResource = 2
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration, delegate: NoManifestRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: origin.appendingPathComponent(".well-known/playbridge-app.json"))
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse, response.statusCode == 200,
                  response.expectedContentLength <= 16384 else { return false }
            var data = Data()
            for try await byte in bytes {
                if data.count == 16384 { return false }
                data.append(byte)
            }
            return isValid(data, for: origin)
        } catch { return false }
    }
}

private final class NoManifestRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

actor BridgedAppDeclarationCache {
    static let shared = BridgedAppDeclarationCache()
    private struct Entry { let declared: Bool; let expires: TimeInterval }
    private var entries: [URL: Entry] = [:]
    private var pending: [URL: Task<Bool, Never>] = [:]
    private let probe: @Sendable (URL) async -> Bool
    private let now: @Sendable () -> TimeInterval
    private let lifetime: TimeInterval

    init(probe: @escaping @Sendable (URL) async -> Bool = { await BridgedAppDeclaration.fetch($0) },
         now: @escaping @Sendable () -> TimeInterval = { Date.timeIntervalSinceReferenceDate },
         lifetime: TimeInterval = 300) {
        self.probe = probe
        self.now = now
        self.lifetime = lifetime
    }

    func isDeclared(_ url: URL) async -> Bool {
        guard let origin = BridgedAppDeclaration.origin(of: url) else { return false }
        if let entry = entries[origin], entry.expires > now() { return entry.declared }
        let task: Task<Bool, Never>
        if let existing = pending[origin] { task = existing }
        else {
            let probe = self.probe
            task = Task { await probe(origin) }
            pending[origin] = task
        }
        let declared = await task.value
        entries[origin] = Entry(declared: declared, expires: now() + lifetime)
        pending[origin] = nil
        return declared
    }
}
