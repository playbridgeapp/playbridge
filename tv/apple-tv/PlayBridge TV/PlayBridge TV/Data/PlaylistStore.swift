import Foundation
import Combine

class PlaylistStore: ObservableObject {
    @Published var items: [Playbridge_PlayPayload] = []
    @Published var currentIndex: Int = -1

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
        print("PlaylistStore: Setting playlist with \(items.count) items, start: \(startIndex)")
        self.items = items
        self.currentIndex = (startIndex >= 0 && startIndex < items.count) ? startIndex : 0
        print("PlaylistStore: Updated items count: \(self.items.count), active index: \(self.currentIndex)")
    }

    func addToQueue(item: Playbridge_PlayPayload) {
        self.items.append(item)
        if self.currentIndex == -1 {
            self.currentIndex = 0
        }
    }

    func next() -> Playbridge_PlayPayload? {
        guard hasNext else { return nil }
        currentIndex += 1
        return currentItem
    }

    func previous() -> Playbridge_PlayPayload? {
        guard hasPrevious else { return nil }
        currentIndex -= 1
        return currentItem
    }

    func jumpTo(index: Int) -> Playbridge_PlayPayload? {
        guard index >= 0, index < items.count else { return nil }
        currentIndex = index
        return currentItem
    }

    func clear() {
        self.items = []
        self.currentIndex = -1
    }
}
