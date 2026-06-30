import Foundation
import Combine

/// Owns the user's collections (playlists of playable items), persisted as JSON.
@MainActor
final class CollectionsStore: ObservableObject {
    @Published private(set) var collections: [MediaCollection] = []

    private let fileURL: URL = {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("collections.json")
    }()

    init() { load() }

    func collection(_ id: UUID) -> MediaCollection? { collections.first { $0.id == id } }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([MediaCollection].self, from: data) else { return }
        collections = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(collections) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    // MARK: - Collection ops

    @discardableResult
    func createCollection(name: String) -> UUID {
        let c = MediaCollection(name: name, addedAt: Date(), updatedAt: Date(), items: [])
        collections.insert(c, at: 0)
        save()
        return c.id
    }

    func rename(_ id: UUID, to name: String) {
        guard let idx = collections.firstIndex(where: { $0.id == id }) else { return }
        collections[idx].name = name
        collections[idx].updatedAt = Date()
        save()
    }

    func deleteCollection(_ id: UUID) {
        collections.removeAll { $0.id == id }
        save()
    }

    // MARK: - Item ops

    func addItem(to id: UUID, title: String, url: String, headers: [String: String] = [:],
                 logo: String? = nil, mimeType: String? = nil, sourceTag: String? = "manual") {
        guard let idx = collections.firstIndex(where: { $0.id == id }) else { return }
        let order = collections[idx].items.count
        let item = CollectionItem(title: title, url: url, headers: headers, logo: logo,
                                  mimeType: mimeType, sourceTag: sourceTag, order: order)
        collections[idx].items.append(item)
        collections[idx].updatedAt = Date()
        save()
    }

    func removeItem(_ itemID: UUID, from id: UUID) {
        guard let idx = collections.firstIndex(where: { $0.id == id }) else { return }
        collections[idx].items.removeAll { $0.id == itemID }
        reindex(&collections[idx].items)
        collections[idx].updatedAt = Date()
        save()
    }

    func move(_ itemID: UUID, in id: UUID, up: Bool) {
        guard let cIdx = collections.firstIndex(where: { $0.id == id }),
              let i = collections[cIdx].items.firstIndex(where: { $0.id == itemID }) else { return }
        let j = up ? i - 1 : i + 1
        guard j >= 0, j < collections[cIdx].items.count else { return }
        collections[cIdx].items.swapAt(i, j)
        reindex(&collections[cIdx].items)
        collections[cIdx].updatedAt = Date()
        save()
    }

    private func reindex(_ items: inout [CollectionItem]) {
        for k in items.indices { items[k].order = k }
    }
}
