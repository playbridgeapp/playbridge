// SPDX-License-Identifier: MPL-2.0
import Foundation

struct BrowserHistoryDay: Identifiable {
    var id: Date { date }
    let date: Date
    let title: String
    let entries: [HistoryEntry]
}

enum BrowserLibraryQuery {
    static func matches(title: String, url: String, query: String, locale: Locale = .current) -> Bool {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty || title.range(of: query, options: [.caseInsensitive, .diacriticInsensitive], locale: locale) != nil ||
            url.range(of: query, options: [.caseInsensitive, .diacriticInsensitive], locale: locale) != nil
    }

    static func history(_ entries: [HistoryEntry], query: String, now: Date = Date(),
                        calendar: Calendar = .current, locale: Locale = .current) -> [BrowserHistoryDay] {
        let filtered = entries.filter { matches(title: $0.title, url: $0.url, query: query, locale: locale) }
        let groups = Dictionary(grouping: filtered) { calendar.startOfDay(for: $0.lastVisited) }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = locale
        formatter.timeZone = calendar.timeZone
        return groups.keys.sorted(by: >).map { day in
            let age = calendar.dateComponents([.day], from: day, to: calendar.startOfDay(for: now)).day ?? 0
            let title: String
            if age == 0 { title = NSLocalizedString("Today", comment: "Browser history day") }
            else if age == 1 { title = NSLocalizedString("Yesterday", comment: "Browser history day") }
            else if (2..<7).contains(age) { formatter.dateFormat = "EEEE"; title = formatter.string(from: day) }
            else { formatter.dateStyle = .medium; formatter.timeStyle = .none; title = formatter.string(from: day) }
            return BrowserHistoryDay(date: day, title: title, entries: groups[day]!.sorted {
                if $0.lastVisited != $1.lastVisited { return $0.lastVisited > $1.lastVisited }
                return $0.id.uuidString < $1.id.uuidString
            })
        }
    }

    static func bookmarks(_ entries: [Bookmark], query: String, locale: Locale = .current) -> [Bookmark] {
        entries.filter { matches(title: $0.title, url: $0.url, query: query, locale: locale) }
    }

    static func bookmarkAddress(_ input: String) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let resolved = BrowserAddressResolver.resolve(text, search: { _ in "" })
        guard let parts = URLComponents(string: resolved), ["http", "https"].contains(parts.scheme?.lowercased() ?? ""),
              let host = parts.host, !host.isEmpty, parts.url != nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true else { return nil }
        return resolved
    }
}
