import Foundation

/// Placement follows stable feature/origin IDs, never names or install order.
enum DashboardTileOrder {
    static let browserID = "browser"
    /// Kept from the old "Bridged Apps" placeholder so saved orders still place it.
    static let addWebAppID = "bridged-apps"
    static let streamsOrigin = "https://streams.playbridge.app"
    /// Browser is how videos are found and web apps are added, so it can't be hidden.
    static let alwaysShown: Set<String> = [browserID]

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

    /// Tiles the Dashboard draws: the saved order minus hidden ones, never hiding `alwaysShown`.
    static func visible(_ order: [String], hidden: Set<String>) -> [String] {
        order.filter { !hidden.contains($0) || alwaysShown.contains($0) }
    }

    static func decode(_ value: String) -> [String] {
        (try? JSONDecoder().decode([String].self, from: Data(value.utf8))) ?? []
    }

    static func encode(_ ids: [String]) -> String {
        guard let data = try? JSONEncoder().encode(ids) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }
}
