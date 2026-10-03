import Foundation

struct BridgedApp: Codable, Identifiable, Equatable, Sendable {
    let origin: URL
    let name: String
    let startURL: URL
    let iconURL: URL?
    var id: URL { origin }

    /// Home edits cannot transfer an installed app’s identity or grants to another origin.
    func editing(name: String, homeURL: String) -> BridgedApp? {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: homeURL.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        let edited = BridgedApp(origin: origin, name: name, startURL: url, iconURL: iconURL)
        return edited.isValid ? edited : nil
    }

    var isValid: Bool {
        BridgedAppDeclaration.origin(of: origin) == origin &&
        BridgedAppDeclaration.origin(of: startURL) == origin &&
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name.count <= 60 &&
        (iconURL.map { BridgedAppDeclaration.origin(of: $0) == origin } ?? true)
    }
}

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
        parse(data, for: origin) != nil
    }

    static func parse(_ data: Data, for origin: URL) -> BridgedApp? {
        guard data.count <= 16384,
              self.origin(of: origin) == origin,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              json["protocol"] as? String == "playbridge-app-v1",
              let name = json["name"] as? String,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              (json["start_url"] == nil || json["start_url"] is NSNull || json["start_url"] is String),
              let start = URL(string: json["start_url"] as? String ?? "/", relativeTo: origin)?.absoluteURL,
              self.origin(of: start) == origin else { return nil }
        let icon = (json["icon_url"] as? String).flatMap { path -> URL? in
            guard !path.isEmpty, let url = URL(string: path, relativeTo: origin)?.absoluteURL,
                  self.origin(of: url) == origin else { return nil }
            return url
        }
        return BridgedApp(origin: origin,
                         name: String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60)),
                         startURL: start, iconURL: icon)
    }

    static func fetch(_ origin: URL) async -> BridgedApp? {
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
                  response.expectedContentLength <= 16384 else { return nil }
            var data = Data()
            for try await byte in bytes {
                if data.count == 16384 { return nil }
                data.append(byte)
            }
            return parse(data, for: origin)
        } catch { return nil }
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
    private struct Entry { let app: BridgedApp?; let expires: TimeInterval }
    private var entries: [URL: Entry] = [:]
    private var pending: [URL: Task<BridgedApp?, Never>] = [:]
    private let probe: @Sendable (URL) async -> BridgedApp?
    private let now: @Sendable () -> TimeInterval
    private let lifetime: TimeInterval

    init(probe: @escaping @Sendable (URL) async -> BridgedApp? = { await BridgedAppDeclaration.fetch($0) },
         now: @escaping @Sendable () -> TimeInterval = { Date.timeIntervalSinceReferenceDate },
         lifetime: TimeInterval = 300) {
        self.probe = probe
        self.now = now
        self.lifetime = lifetime
    }

    func isDeclared(_ url: URL) async -> Bool {
        await discover(url) != nil
    }

    func discover(_ url: URL) async -> BridgedApp? {
        guard let origin = BridgedAppDeclaration.origin(of: url) else { return nil }
        if let entry = entries[origin], entry.expires > now() { return entry.app }
        let task: Task<BridgedApp?, Never>
        if let existing = pending[origin] { task = existing }
        else {
            let probe = self.probe
            task = Task { await probe(origin) }
            pending[origin] = task
        }
        let app = await task.value
        entries[origin] = Entry(app: app, expires: now() + lifetime)
        pending[origin] = nil
        return app
    }
}
