import Foundation
import Combine

class PlaylistStore: ObservableObject {
    static let maxItems = 200
    static let maxBatchItems = 50

    @Published var items: [Playbridge_PlayPayload] = []
    @Published var currentIndex: Int = -1
    @Published private(set) var itemIDs: [String] = []
    @Published private(set) var playbackID: String?
    @Published private(set) var queueRevision: UInt64 = 0

    var currentItemID: String? {
        guard currentIndex >= 0, currentIndex < itemIDs.count else { return nil }
        return itemIDs[currentIndex]
    }

    var currentItem: Playbridge_PlayPayload? {
        guard currentIndex >= 0, currentIndex < items.count else { return nil }
        return items[currentIndex]
    }

    var hasNext: Bool {
        return currentIndex < items.count - 1
    }

    var hasPrevious: Bool {
        return currentIndex > 0
    }

    func setPlaylist(items: [Playbridge_PlayPayload], startIndex: Int) {
        let accepted = Array(items.prefix(Self.maxItems))
        print("PlaylistStore: Setting playlist with \(accepted.count) items, start: \(startIndex)")
        self.items = accepted
        self.itemIDs = accepted.map { _ in UUID().uuidString }
        self.playbackID = accepted.isEmpty ? nil : UUID().uuidString
        self.queueRevision &+= 1
        self.currentIndex = (startIndex >= 0 && startIndex < accepted.count) ? startIndex : (accepted.isEmpty ? -1 : 0)
        print("PlaylistStore: Updated items count: \(self.items.count), active index: \(self.currentIndex)")
    }

    func addToQueue(item: Playbridge_PlayPayload) {
        _ = addToQueue(items: [item])
    }

    @discardableResult
    func addToQueue(items newItems: [Playbridge_PlayPayload]) -> Bool {
        guard !newItems.isEmpty, newItems.count <= Self.maxBatchItems,
              items.count + newItems.count <= Self.maxItems else { return false }
        if items.isEmpty {
            setPlaylist(items: newItems, startIndex: 0)
            return true
        }
        items.append(contentsOf: newItems)
        itemIDs.append(contentsOf: newItems.map { _ in UUID().uuidString })
        queueRevision &+= 1
        return true
    }

    func next() -> Playbridge_PlayPayload? {
        guard hasNext else { return nil }
        currentIndex += 1
        queueRevision &+= 1
        return currentItem
    }

    func previous() -> Playbridge_PlayPayload? {
        guard hasPrevious else { return nil }
        currentIndex -= 1
        queueRevision &+= 1
        return currentItem
    }

    func jumpTo(index: Int) -> Playbridge_PlayPayload? {
        guard index >= 0, index < items.count else { return nil }
        guard index != currentIndex else { return currentItem }
        currentIndex = index
        queueRevision &+= 1
        return currentItem
    }

    func jumpTo(itemID: String) -> Playbridge_PlayPayload? {
        guard let index = itemIDs.firstIndex(of: itemID) else { return nil }
        return jumpTo(index: index)
    }

    @discardableResult
    func remove(itemIDs removedIDs: Set<String>) -> Bool {
        guard !removedIDs.isEmpty, self.itemIDs.contains(where: removedIDs.contains) else { return false }
        let activeID = currentItemID
        let retained = self.itemIDs.indices.filter { !removedIDs.contains(self.itemIDs[$0]) }
        items = retained.map { items[$0] }
        self.itemIDs = retained.map { self.itemIDs[$0] }
        queueRevision &+= 1
        if items.isEmpty {
            currentIndex = -1
            playbackID = nil
        } else if let activeID, let activeIndex = self.itemIDs.firstIndex(of: activeID) {
            currentIndex = activeIndex
        } else {
            currentIndex = min(max(currentIndex, 0), items.count - 1)
        }
        return true
    }

    @discardableResult
    func move(itemID: String, beforeItemID: String?) -> Bool {
        guard let fromIndex = itemIDs.firstIndex(of: itemID),
              beforeItemID != itemID,
              beforeItemID == nil || itemIDs.contains(beforeItemID!) else { return false }
        let activeID = currentItemID
        let item = items.remove(at: fromIndex)
        let id = itemIDs.remove(at: fromIndex)
        let targetIndex = beforeItemID.flatMap { itemIDs.firstIndex(of: $0) } ?? itemIDs.count
        items.insert(item, at: targetIndex)
        itemIDs.insert(id, at: targetIndex)
        currentIndex = activeID.flatMap { itemIDs.firstIndex(of: $0) } ?? -1
        queueRevision &+= 1
        return true
    }

    func clear() {
        self.items = []
        self.itemIDs = []
        self.currentIndex = -1
        self.playbackID = nil
        self.queueRevision &+= 1
    }
}
