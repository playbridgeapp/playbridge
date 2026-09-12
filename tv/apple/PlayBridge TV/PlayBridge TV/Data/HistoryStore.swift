import Foundation
import Combine

// MARK: - Models

struct PlaybackHistoryItem: Identifiable, Codable, Equatable {
    var id: String { url.absoluteString }
    let url: URL
    let title: String?
    let timestamp: Date
    var isFavorite: Bool
    let headers: [String: String]?
}

// MARK: - Stores
class HistoryStore: ObservableObject {
    @Published var history: [PlaybackHistoryItem] = []
    private let historyKey = "pb_playback_history"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        loadHistory()
    }

    func loadHistory() {
        if let data = defaults.data(forKey: historyKey),
            let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: data)
        {
            history = Array(decoded.prefix(100))
        }
    }

    func addToHistory(url: URL, title: String?, headers: [String: String]?) {
        let enableHistory = defaults.object(forKey: "enable_history") as? Bool ?? true
        guard enableHistory else { return }

        let isFavorite = history.first(where: { $0.url == url })?.isFavorite ?? false
        let newItem = PlaybackHistoryItem(
            url: url, title: title ?? "Unknown Media", timestamp: Date(), isFavorite: isFavorite,
            headers: headers
        )
        history.removeAll { $0.url == url }
        history.insert(newItem, at: 0)
        if history.count > 100 { history = Array(history.prefix(100)) }
        saveHistory()
    }

    func toggleFavorite(item: PlaybackHistoryItem) {
        if let index = history.firstIndex(where: { $0.url == item.url }) {
            history[index].isFavorite.toggle()
            saveHistory()
        }
    }

    func clearHistory() {
        history.removeAll()
        saveHistory()
    }

    private func saveHistory() {
        if let encoded = try? JSONEncoder().encode(history) {
            defaults.set(encoded, forKey: historyKey)
        }
    }
}
