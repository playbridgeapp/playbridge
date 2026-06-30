import Foundation
import Combine

/// Owns the user's IPTV playlists, persisted as JSON on disk. Parsing/fetching
/// happens off the main thread; published state updates on the main actor.
@MainActor
final class IptvStore: ObservableObject {
    @Published private(set) var playlists: [IptvPlaylist] = []

    private let fileURL: URL = {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("iptv_playlists.json")
    }()

    init() { load() }

    func playlist(_ id: UUID) -> IptvPlaylist? { playlists.first { $0.id == id } }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([IptvPlaylist].self, from: data) else { return }
        playlists = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(playlists) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    // MARK: - Mutations

    func addURLPlaylist(name: String, urlString: String) async throws {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme?.hasPrefix("http") == true else { throw IptvError.invalidURL }
        let text = try await Self.fetchText(url)
        let channels = await Self.parse(text)
        guard !channels.isEmpty else { throw IptvError.noChannels }
        let pl = IptvPlaylist(name: name, source: trimmed, sourceType: .url,
                              addedAt: Date(), updatedAt: Date(), channels: channels)
        playlists.insert(pl, at: 0)
        save()
    }

    func addFilePlaylist(name: String, fileURL pickedURL: URL) async throws {
        let scoped = pickedURL.startAccessingSecurityScopedResource()
        defer { if scoped { pickedURL.stopAccessingSecurityScopedResource() } }
        guard let text = try? String(contentsOf: pickedURL, encoding: .utf8) else { throw IptvError.decode }
        let channels = await Self.parse(text)
        guard !channels.isEmpty else { throw IptvError.noChannels }
        let bookmark = try? pickedURL.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let pl = IptvPlaylist(name: name, source: bookmark?.base64EncodedString() ?? "",
                              sourceType: .file, addedAt: Date(), updatedAt: Date(), channels: channels)
        playlists.insert(pl, at: 0)
        save()
    }

    func refresh(_ id: UUID) async throws {
        guard let idx = playlists.firstIndex(where: { $0.id == id }) else { return }
        let pl = playlists[idx]
        let text: String

        switch pl.sourceType {
        case .url:
            guard let url = URL(string: pl.source) else { throw IptvError.invalidURL }
            text = try await Self.fetchText(url)
        case .file:
            guard let data = Data(base64Encoded: pl.source) else { throw IptvError.fileUnavailable }
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
                throw IptvError.fileUnavailable
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let t = try? String(contentsOf: url, encoding: .utf8) else { throw IptvError.fileUnavailable }
            text = t
        }

        let channels = await Self.parse(text)
        guard !channels.isEmpty else { throw IptvError.noChannels }
        var updated = pl
        updated.channels = channels
        updated.updatedAt = Date()
        playlists[idx] = updated
        save()
    }

    func delete(_ id: UUID) {
        playlists.removeAll { $0.id == id }
        save()
    }

    // MARK: - Helpers (off main)

    private static func parse(_ text: String) async -> [IptvChannel] {
        await Task.detached(priority: .userInitiated) { M3UParser.parse(text) }.value
    }

    private static func fetchText(_ url: URL) async throws -> String {
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.setValue("PlayBridge", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw IptvError.http(http.statusCode)
        }
        guard let text = String(data: data, encoding: .utf8) else { throw IptvError.decode }
        return text
    }
}
