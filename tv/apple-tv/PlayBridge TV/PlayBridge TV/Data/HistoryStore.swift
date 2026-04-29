import Foundation
import Combine

// MARK: - Models

struct PlayRequest: Equatable {
    let url: URL
    let title: String?
    let headers: [String: String]?
    let subtitles: [String]?
    let preferredAudioLanguage: String?
    let preferredSubtitleLanguage: String?
    
    init(
        url: URL,
        title: String? = nil,
        headers: [String: String]? = nil,
        subtitles: [String]? = nil,
        preferredAudioLanguage: String? = nil,
        preferredSubtitleLanguage: String? = nil
    ) {
        self.url = url
        self.title = title
        self.headers = headers
        self.subtitles = subtitles
        self.preferredAudioLanguage = preferredAudioLanguage
        self.preferredSubtitleLanguage = preferredSubtitleLanguage
    }
}

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

    init() { loadHistory() }

    func loadHistory() {
        if let data = UserDefaults.standard.data(forKey: historyKey),
            let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: data)
        {
            DispatchQueue.main.async { self.history = decoded }
        }
    }

    func addToHistory(url: URL, title: String?, headers: [String: String]?) {
        let newItem = PlaybackHistoryItem(
            url: url, title: title ?? "Unknown Media", timestamp: Date(), isFavorite: false,
            headers: headers
        )
        DispatchQueue.main.async {
            self.history.removeAll { $0.url == url }
            self.history.insert(newItem, at: 0)
            if self.history.count > 100 { self.history = Array(self.history.prefix(100)) }
            self.saveHistory()
        }
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
            UserDefaults.standard.set(encoded, forKey: historyKey)
        }
    }
}
