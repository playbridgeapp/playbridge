import Foundation

/// Placement follows stable feature/origin IDs, never names or install order.
enum DashboardTileOrder {
    static let tilesPerPage = 8

    static func reconcile(saved: [String], available: [String]) -> [String] {
        let availableIDs = Set(available)
        var seen = Set<String>()
        return (saved.filter { availableIDs.contains($0) } + available).filter { seen.insert($0).inserted }
    }

    /// `position` is the final zero-based position, not a List insertion offset.
    static func move(_ ids: [String], id: String, to position: Int) -> [String] {
        guard ids.contains(id), ids.indices.contains(position) else { return ids }
        var result = ids.filter { $0 != id }
        result.insert(id, at: position)
        return result
    }

    static func pageCount(_ tileCount: Int) -> Int {
        max(1, (tileCount + tilesPerPage - 1) / tilesPerPage)
    }

    static func decode(_ value: String) -> [String] {
        (try? JSONDecoder().decode([String].self, from: Data(value.utf8))) ?? []
    }

    static func encode(_ ids: [String]) -> String {
        guard let data = try? JSONEncoder().encode(ids) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }
}
