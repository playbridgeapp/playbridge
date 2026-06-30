import Foundation
import Combine

struct Bookmark: Codable, Identifiable, Hashable {
    var id = UUID()
    var title: String
    var url: String
    var addedAt: Date
}

struct HistoryEntry: Codable, Identifiable, Hashable {
    var id = UUID()
    var url: String
    var title: String
    var lastVisited: Date
    var visitCount: Int
}

struct URLSuggestion: Identifiable, Hashable {
    var id: String { url }
    let title: String
    let url: String
    let isBookmark: Bool
}

enum SearchEngine: String, CaseIterable, Identifiable {
    case google, duckduckgo, bing
    var id: String { rawValue }

    var label: String {
        switch self {
        case .google: return "Google"
        case .duckduckgo: return "DuckDuckGo"
        case .bing: return "Bing"
        }
    }

    func searchURL(_ query: String) -> String {
        let q = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        switch self {
        case .google:     return "https://www.google.com/search?q=\(q)"
        case .duckduckgo: return "https://duckduckgo.com/?q=\(q)"
        case .bing:       return "https://www.bing.com/search?q=\(q)"
        }
    }

    static var current: SearchEngine {
        get { SearchEngine(rawValue: UserDefaults.standard.string(forKey: "pb_search_engine") ?? "") ?? .google }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "pb_search_engine") }
    }
}

/// Browsing history + bookmarks, persisted as JSON on disk. Mutations happen on the
/// main thread (called from the UI and the navigation delegate).
final class BrowserDataStore: ObservableObject {
    @Published private(set) var bookmarks: [Bookmark] = []
    @Published private(set) var history: [HistoryEntry] = []

    private let bookmarksURL: URL
    private let historyURL: URL
    private let maxHistory = 1500

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        bookmarksURL = dir.appendingPathComponent("bookmarks.json")
        historyURL = dir.appendingPathComponent("history.json")
        load()
    }

    private func load() {
        if let d = try? Data(contentsOf: bookmarksURL), let b = try? JSONDecoder().decode([Bookmark].self, from: d) { bookmarks = b }
        if let d = try? Data(contentsOf: historyURL), let h = try? JSONDecoder().decode([HistoryEntry].self, from: d) { history = h }
    }

    private func saveBookmarks() {
        if let d = try? JSONEncoder().encode(bookmarks) { try? d.write(to: bookmarksURL, options: .atomic) }
    }
    private func saveHistory() {
        if let d = try? JSONEncoder().encode(history) { try? d.write(to: historyURL, options: .atomic) }
    }

    // MARK: - History

    func recordVisit(url: String, title: String?) {
        guard url.hasPrefix("http"), let host = URLComponents(string: url)?.host, !host.isEmpty else { return }
        let t = (title?.isEmpty == false) ? title! : url
        if let idx = history.firstIndex(where: { $0.url == url }) {
            var e = history.remove(at: idx)
            e.lastVisited = Date()
            e.visitCount += 1
            e.title = t
            history.insert(e, at: 0)
        } else {
            history.insert(HistoryEntry(url: url, title: t, lastVisited: Date(), visitCount: 1), at: 0)
            if history.count > maxHistory { history.removeLast(history.count - maxHistory) }
        }
        saveHistory()
    }

    func removeHistory(_ id: UUID) { history.removeAll { $0.id == id }; saveHistory() }
    func clearHistory() { history = []; saveHistory() }

    // MARK: - Bookmarks

    func isBookmarked(_ url: String) -> Bool { bookmarks.contains { $0.url == url } }

    func addBookmark(url: String, title: String?) {
        guard url.hasPrefix("http"), !isBookmarked(url) else { return }
        bookmarks.insert(Bookmark(title: (title?.isEmpty == false) ? title! : url, url: url, addedAt: Date()), at: 0)
        saveBookmarks()
    }

    func removeBookmark(url: String) { bookmarks.removeAll { $0.url == url }; saveBookmarks() }
    func removeBookmark(_ id: UUID) { bookmarks.removeAll { $0.id == id }; saveBookmarks() }

    func toggleBookmark(url: String, title: String?) {
        if isBookmarked(url) { removeBookmark(url: url) } else { addBookmark(url: url, title: title) }
    }

    // MARK: - Address-bar suggestions

    func suggestions(for query: String, limit: Int = 8) -> [URLSuggestion] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return [] }
        func match(_ s: String) -> Bool { s.lowercased().contains(q) }

        var out: [URLSuggestion] = []
        var seen = Set<String>()
        for b in bookmarks where match(b.url) || match(b.title) {
            if seen.insert(b.url).inserted { out.append(URLSuggestion(title: b.title, url: b.url, isBookmark: true)) }
        }
        for h in history where match(h.url) || match(h.title) {
            if seen.insert(h.url).inserted { out.append(URLSuggestion(title: h.title, url: h.url, isBookmark: false)) }
            if out.count >= limit { break }
        }
        return Array(out.prefix(limit))
    }
}
