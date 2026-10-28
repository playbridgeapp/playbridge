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

    @discardableResult
    func setPlaylist(items: [Playbridge_PlayPayload], startIndex: Int) -> Bool {
        guard items.count <= Self.maxItems,
              let ids = QueueItemIDs.resolve(items.map { $0.hasItemID ? $0.itemID : nil }) else { return false }
        self.items = zip(items, ids).map { item, id in
            var item = item
            item.itemID = id
            return item
        }
        self.itemIDs = ids
        self.playbackID = items.isEmpty ? nil : UUID().uuidString
        self.queueRevision &+= 1
        self.currentIndex = items.indices.contains(startIndex) ? startIndex : (items.isEmpty ? -1 : 0)
        return true
    }

    @discardableResult
    func addToQueue(item: Playbridge_PlayPayload) -> Bool {
        addToQueue(items: [item])
    }

    @discardableResult
    func addToQueue(items newItems: [Playbridge_PlayPayload]) -> Bool {
        guard !newItems.isEmpty, newItems.count <= Self.maxBatchItems,
              items.count + newItems.count <= Self.maxItems,
              let ids = QueueItemIDs.resolve(newItems.map { $0.hasItemID ? $0.itemID : nil }, existing: itemIDs) else { return false }
        if items.isEmpty { return setPlaylist(items: newItems, startIndex: 0) }
        items.append(contentsOf: zip(newItems, ids).map { item, id in
            var item = item
            item.itemID = id
            return item
        })
        itemIDs.append(contentsOf: ids)
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
