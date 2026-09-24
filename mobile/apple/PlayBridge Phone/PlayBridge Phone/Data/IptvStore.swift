import Foundation
import Combine

/// Owns the user's IPTV playlists, persisted as JSON on disk. Parsing/fetching
/// happens off the main thread; published state updates on the main actor.
@MainActor
final class IptvStore: ObservableObject {
    @Published private(set) var playlists: [IptvPlaylist] = []
    @Published private(set) var isLoading = true
    private var saveRevision = 0

    private static func defaultFileURL() -> URL {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("iptv_playlists.json")
    }
    private let fileURL: URL

    private lazy var persistence = IptvPersistence(fileURL: fileURL)

    init(fileURL: URL? = nil) {
        self.fileURL = fileURL ?? Self.defaultFileURL()
        Task { await finishLoading() }
    }

    func playlist(_ id: UUID) -> IptvPlaylist? { playlists.first { $0.id == id } }

    // MARK: - Persistence

    private func finishLoading() async {
        guard isLoading else { return }
        let loaded = await persistence.load()
        guard isLoading else { return }
        playlists = loaded
        isLoading = false
    }

    @discardableResult
    private func enqueueSave() -> Task<Void, Never> {
        saveRevision += 1
        let revision = saveRevision
        let snapshot = playlists
        let persistence = persistence
        return Task { await persistence.save(snapshot, revision: revision) }
    }

    // MARK: - Mutations

    func addURLPlaylist(name: String, urlString: String) async throws {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme?.hasPrefix("http") == true else { throw IptvError.invalidURL }
        let text = try await Self.fetchText(url)
        let channels = await Self.parse(text)
        guard !channels.isEmpty else { throw IptvError.noChannels }
        await finishLoading()
        let pl = IptvPlaylist(name: name, source: trimmed, sourceType: .url,
                              addedAt: Date(), updatedAt: Date(), channels: channels)
        playlists.insert(pl, at: 0)
        await enqueueSave().value
    }

    func addFilePlaylist(name: String, fileURL pickedURL: URL) async throws {
        let (text, bookmark) = try await Task.detached(priority: .userInitiated) {
            let scoped = pickedURL.startAccessingSecurityScopedResource()
            defer { if scoped { pickedURL.stopAccessingSecurityScopedResource() } }
            guard let text = try? String(contentsOf: pickedURL, encoding: .utf8) else { throw IptvError.decode }
            let bookmark = try? pickedURL.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            return (text, bookmark?.base64EncodedString() ?? "")
        }.value
        let channels = await Self.parse(text)
        guard !channels.isEmpty else { throw IptvError.noChannels }
        await finishLoading()
        let pl = IptvPlaylist(name: name, source: bookmark,
                              sourceType: .file, addedAt: Date(), updatedAt: Date(), channels: channels)
        playlists.insert(pl, at: 0)
        await enqueueSave().value
    }

    func refresh(_ id: UUID) async throws {
        await finishLoading()
        guard let pl = playlist(id) else { return }
        let text: String

        switch pl.sourceType {
        case .url:
            guard let url = URL(string: pl.source) else { throw IptvError.invalidURL }
            text = try await Self.fetchText(url)
        case .file:
            text = try await Task.detached(priority: .userInitiated) {
                guard let data = Data(base64Encoded: pl.source) else { throw IptvError.fileUnavailable }
                var stale = false
                guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
                    throw IptvError.fileUnavailable
                }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard let text = try? String(contentsOf: url, encoding: .utf8) else { throw IptvError.fileUnavailable }
                return text
            }.value
        }

        let channels = await Self.parse(text)
        guard !channels.isEmpty else { throw IptvError.noChannels }
        guard let idx = playlists.firstIndex(where: { $0.id == id }) else { return }
        var updated = playlists[idx]
        updated.channels = channels
        updated.updatedAt = Date()
        playlists[idx] = updated
        await enqueueSave().value
    }

    func delete(_ id: UUID) {
        guard !isLoading else { return }
        playlists.removeAll { $0.id == id }
        enqueueSave()
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

/// Serializes large JSON writes away from SwiftUI and ignores snapshots overtaken by newer edits.
private actor IptvPersistence {
    let fileURL: URL
    private var latestRevision = 0

    init(fileURL: URL) { self.fileURL = fileURL }

    func load() -> [IptvPlaylist] {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([IptvPlaylist].self, from: data) else { return [] }
        return decoded
    }

    func save(_ playlists: [IptvPlaylist], revision: Int) {
        guard revision > latestRevision else { return }
        latestRevision = revision
        guard let data = try? JSONEncoder().encode(playlists) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
