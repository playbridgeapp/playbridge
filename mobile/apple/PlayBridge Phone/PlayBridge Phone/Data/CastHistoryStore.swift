import Foundation
import Combine

/// Sender history contains original sources, never short-lived proxy registrations.
/// A command containing a private item is rejected as a whole.
final class CastHistoryStore: ObservableObject {
    struct Entry: Identifiable, Codable {
        var id = UUID()
        var date = Date()
        let command: String
        let title: String
        let host: String
        let receiver: String?
    }

    @Published private(set) var entries: [Entry] = []
    @Published private(set) var persistenceError: String?
    private let fileURL: URL

    init(fileURL: URL? = nil) {
        self.fileURL = fileURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cast-history.json")
        if let data = try? Data(contentsOf: self.fileURL),
           let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = Array(saved.filter { Self.items(in: $0.command) != nil }.prefix(100))
        }
    }

    static func items(in command: String) -> [[String: Any]]? {
        guard let data = command.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["type"] as? String == "command",
              let payload = json["payload"] as? [String: Any] else { return nil }
        let items: [[String: Any]]
        switch json["action"] as? String {
        case "playlist": items = payload["items"] as? [[String: Any]] ?? []
        case "queue_add": items = (payload["item"] as? [String: Any]).map { [$0] } ?? []
        default: return nil
        }
        guard !items.isEmpty, !items.contains(where: { $0["skipHistory"] as? Bool == true }),
              items.allSatisfy({ item in
                  guard let raw = item["url"] as? String, let url = URL(string: raw) else { return false }
                  return ["http", "https"].contains(url.scheme?.lowercased() ?? "") && url.host != nil
              }) else { return nil }
        return items
    }

    func record(_ command: String, receiver: String?) {
        guard command.utf8.count <= 256_000, let items = Self.items(in: command),
              let first = items.first, let raw = first["url"] as? String else { return }
        entries.removeAll { $0.command == command }
        entries.insert(Entry(command: command, title: first["title"] as? String ?? URL(string: raw)?.host ?? "Media",
                             host: URL(string: raw)?.host ?? "", receiver: receiver), at: 0)
        entries = Array(entries.prefix(100))
        save()
    }

    func delete(_ id: UUID) { entries.removeAll { $0.id == id }; save() }
    func clear() { entries.removeAll(); save() }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(entries)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var url = fileURL
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
            persistenceError = nil
        } catch { persistenceError = "Couldn’t save changes to cast history." }
    }
}
