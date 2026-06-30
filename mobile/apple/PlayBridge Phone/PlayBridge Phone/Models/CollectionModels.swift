import Foundation

/// A single playable item saved into a collection. v1 stores web/stream URLs
/// (manual entries and IPTV channels), including any headers needed to play them.
struct CollectionItem: Codable, Identifiable, Hashable {
    var id = UUID()
    var title: String
    var url: String
    var headers: [String: String] = [:]
    var logo: String?
    var mimeType: String?
    var sourceTag: String?      // "manual" | "iptv"
    var order: Int
}

/// A hand-curated, ordered list of playable items. Named `MediaCollection` to
/// avoid colliding with Swift's `Collection`.
struct MediaCollection: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var addedAt: Date
    var updatedAt: Date?
    var items: [CollectionItem]

    var itemCount: Int { items.count }
}
