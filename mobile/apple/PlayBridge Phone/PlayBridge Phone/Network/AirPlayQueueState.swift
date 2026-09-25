import Foundation

/// The phone owns AirPlay's queue independently of a receiver connection.
/// Queued entries should have unique IDs, even when their media URL is the same.
struct AirPlayQueueState<Item: Identifiable> {
    private(set) var current: Item?
    private(set) var upcoming: [Item] = []

    mutating func play(_ item: Item) {
        current = item
        upcoming.removeAll()
    }

    mutating func enqueue(_ item: Item) {
        if current == nil {
            current = item
        } else {
            upcoming.append(item)
        }
    }

    /// Updating subtitles or other current-item metadata must preserve the queue.
    mutating func replaceCurrent(_ item: Item) {
        current = item
    }

    @discardableResult
    mutating func advance() -> Item? {
        current = upcoming.isEmpty ? nil : upcoming.removeFirst()
        return current
    }

    /// Queue removal never interrupts the playing item.
    mutating func remove(_ id: Item.ID) {
        upcoming.removeAll { $0.id == id }
    }

    /// Destination is an insertion offset in the original array, as in SwiftUI's onMove.
    mutating func move(from source: IndexSet, to destination: Int) {
        let indices = source.filter { upcoming.indices.contains($0) }
        guard !indices.isEmpty else { return }
        let destination = min(max(destination, 0), upcoming.count)
        let moved = indices.map { upcoming[$0] }
        let insertion = destination - indices.filter { $0 < destination }.count
        for index in indices.reversed() {
            upcoming.remove(at: index)
        }
        upcoming.insert(contentsOf: moved, at: insertion)
    }

    mutating func clearUpcoming() {
        upcoming.removeAll()
    }

    mutating func stop() {
        current = nil
        upcoming.removeAll()
    }
}
