import Foundation

@main
struct IptvStoreChecks {
    static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let database = root.appendingPathComponent("playlists.json")
        let source = root.appendingPathComponent("channels.m3u")
        let prior = IptvPlaylist(name: "Saved", source: "https://example.test/saved.m3u", sourceType: .url,
                                 addedAt: Date(), updatedAt: nil, channels: [])
        try JSONEncoder().encode([prior]).write(to: database)

        let store = await MainActor.run { IptvStore(fileURL: database) }
        try await waitUntilLoaded(store)
        let restored = await MainActor.run { store.playlists.map(\.name) }
        try check(restored == ["Saved"], "Asynchronous load lost a saved playlist")

        let entries = (0..<1000).map { index in
            "#EXTINF:-1,Channel \(index)\nhttps://example.test/channel/\(index)"
        }
        try ("#EXTM3U\n" + entries.joined(separator: "\n") + "\n").write(to: source, atomically: true, encoding: .utf8)
        try await store.addFilePlaylist(name: "Large", fileURL: source)
        let imported = await MainActor.run { store.playlists.first }
        try check(imported?.channelCount == 1000, "Large file playlist did not import")
        try check(try persisted(at: database).count == 2, "Add returned before its background save finished")

        await MainActor.run { store.delete(imported!.id) }
        for _ in 0..<100 {
            if try persisted(at: database).count == 1 { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        try check(try persisted(at: database).map(\.name) == ["Saved"], "Delete did not persist the latest snapshot")
        let reopened = await MainActor.run { IptvStore(fileURL: database) }
        try await waitUntilLoaded(reopened)
        let names = await MainActor.run { reopened.playlists.map(\.name) }
        try check(names == ["Saved"], "Reopening loaded stale IPTV data")
        print("PASS: IPTV asynchronous load, large import, ordered saves, delete and reopen")
    }

    static func persisted(at url: URL) throws -> [IptvPlaylist] {
        try JSONDecoder().decode([IptvPlaylist].self, from: Data(contentsOf: url))
    }

    static func waitUntilLoaded(_ store: IptvStore) async throws {
        for _ in 0..<100 {
            if await MainActor.run(body: { !store.isLoading }) { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw Failure("IPTV store did not finish loading")
    }

    static func check(_ value: Bool, _ message: String) throws {
        if !value { throw Failure(message) }
    }

    struct Failure: Error { let message: String; init(_ message: String) { self.message = message } }
}
