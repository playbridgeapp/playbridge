import Foundation
import Combine
#if canImport(UIKit)
import UIKit
#endif

/// The user's cast history choices. Port of Android's `CastHistorySettings` (#545).
struct CastHistorySettings: Equatable {
    static let enabledKey = "cast_save_history"
    static let limitKey = "cast_history_limit"
    static let keepPreviewsKey = "cast_history_keep_previews"
    static let excludeBridgedKey = "cast_history_exclude_bridged"
    static let rememberPositionKey = "cast_history_remember_position"
    static let checkLinksKey = "cast_history_check_links"
    static let limits = [25, 50, 100, 200, 500]
    static let defaultLimit = 50

    var enabled = true
    var limit = defaultLimit
    var keepPreviews = true
    /// Casts started by a bridged app, or by a website the phone can't place, are not remembered.
    var excludeBridgedApps = true
    /// Save where playback stopped while connected, so a cast can resume.
    var rememberPosition = true
    /// Ask a link's site whether it still works before offering it again. Off by default.
    var checkLinks = false

    /// The nearest allowed limit, so a stray stored value can't grow the history.
    static func coerceLimit(_ value: Int) -> Int {
        limits.min { abs($0 - value) < abs($1 - value) } ?? defaultLimit
    }

    static func load(_ defaults: UserDefaults = .standard) -> Self {
        func bool(_ key: String, _ fallback: Bool) -> Bool { defaults.object(forKey: key) as? Bool ?? fallback }
        let stored = defaults.object(forKey: limitKey) as? Int
        return .init(enabled: bool(enabledKey, true), limit: stored.map(coerceLimit) ?? defaultLimit,
                     keepPreviews: bool(keepPreviewsKey, true), excludeBridgedApps: bool(excludeBridgedKey, true),
                     rememberPosition: bool(rememberPositionKey, true), checkLinks: bool(checkLinksKey, false))
    }
}

/// Sender history contains original sources, never short-lived proxy registrations.
/// A command containing a private item is rejected as a whole. The file holds sign-in headers,
/// so it stays protected and out of backups.
final class CastHistoryStore: ObservableObject {
    struct Entry: Identifiable, Codable, Equatable {
        var id = UUID()
        var date = Date()
        let command: String
        let title: String
        let host: String
        let receiver: String?
        // Added in #545's port; absent in entries saved before it.
        var receiverKind: String?
        var pageURL: String?
        var mediaKind: String?
        var playlistCount: Int?
        /// File name of the preview picture in `previewDirectory`.
        var preview: String?
        /// The expiry the playing link states about itself.
        var expiresAt: Date?
        /// Where playback got to; nil before anything was reported (and for older entries).
        var progress: CastProgress?
        /// The last time the site was asked about the link, and what it said.
        var linkCheck: LinkCheck.Result?
        var linkCheckedAt: Date?

        func linkStatus(now: Date, checking: Bool = false) -> LinkStatus {
            .of(expiresAt: expiresAt, now: now, check: linkCheck, checkedAt: linkCheckedAt, checking: checking)
        }

        enum Status: Equatable { case failed, live, finished, inProgress, notStarted, unknown }

        /// Positions under 10 seconds aren't worth resuming.
        static let minResumeMs: Int64 = 10_000

        var status: Status {
            guard let p = progress else { return .unknown }
            if p.failed { return .failed }
            if p.live { return .live }
            if p.finished { return .finished }
            if !p.positionReported { return .unknown }
            return p.positionMs >= Self.minResumeMs ? .inProgress : .notStarted
        }

        /// 0...1 through the current item, or nil when it would be a guess.
        var progressFraction: Double? {
            guard let p = progress, p.positionReported, !p.live, p.durationMs > 0 else { return nil }
            return min(1, max(0, Double(p.positionMs) / Double(p.durationMs)))
        }

        var canResume: Bool { status == .inProgress }
    }

    /// Why a cast must not be remembered, or nil if it may be. Fails closed for websites.
    static func skipReason(_ settings: CastHistorySettings, origin: CastOrigin) -> String? {
        guard settings.enabled else { return "disabled" }
        if settings.excludeBridgedApps {
            if origin.bridgedApp { return "bridged_app" }
            if origin.pageInitiated && origin.pageURL == nil { return "unknown_website_origin" }
        }
        return nil
    }

    @Published private(set) var entries: [Entry] = []
    @Published private(set) var persistenceError: String?
    private let fileURL: URL
    let previewDirectory: URL
    private let defaults: UserDefaults

    init(fileURL: URL? = nil, defaults: UserDefaults = .standard) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.fileURL = fileURL ?? support.appendingPathComponent("cast-history.json")
        previewDirectory = self.fileURL.deletingLastPathComponent().appendingPathComponent("cast-history-previews", isDirectory: true)
        self.defaults = defaults
        if let data = try? Data(contentsOf: self.fileURL),
           let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = Array(saved.filter { Self.items(in: $0.command) != nil }.prefix(CastHistorySettings.limits.max()!))
        }
        // The old history kept 100; don't silently halve it for someone who already has more than the new default.
        if defaults.object(forKey: CastHistorySettings.limitKey) == nil, entries.count > CastHistorySettings.defaultLimit {
            defaults.set(100, forKey: CastHistorySettings.limitKey)
        }
        reconcile()
    }

    var settings: CastHistorySettings { .load(defaults) }

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

    /// The item a playlist command starts with.
    static func startItem(in command: String, items: [[String: Any]]) -> [String: Any] {
        guard let data = command.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let index = (json["payload"] as? [String: Any])?["startIndex"] as? Int,
              items.indices.contains(index) else { return items[0] }
        return items[index]
    }

    /// Remembers a cast unless the settings or its origin say not to. Returns the entry's id.
    @discardableResult
    func record(_ command: String, receiver: String?, receiverKind: String? = nil, origin: CastOrigin = .init(),
                failure: String? = nil, now: Date = Date()) -> UUID? {
        let current = settings
        guard Self.skipReason(current, origin: origin) == nil,
              command.utf8.count <= 256_000, let items = Self.items(in: command) else { return nil }
        let first = Self.startItem(in: command, items: items)
        guard let raw = first["url"] as? String else { return nil }
        let host = URL(string: raw)?.host ?? ""
        let previous = entries.first { $0.command == command }
        var entry = Entry(date: now, command: command,
                          title: (first["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? host.nonEmpty ?? "Media",
                          host: origin.pageURL.flatMap { URL(string: $0)?.host } ?? host, receiver: receiver)
        entry.receiverKind = receiverKind
        entry.pageURL = origin.pageURL ?? previous?.pageURL
        entry.mediaKind = first["mediaKind"] as? String ?? "video"
        entry.playlistCount = items.count > 1 ? items.count : nil
        entry.expiresAt = LinkExpiry.expiresAt(raw, savedAt: now)
        // Casting it again keeps where it got to until the receiver reports the new playback.
        entry.progress = failure.map { CastProgress(failed: true, failureReason: $0) } ?? previous?.progress
        if current.keepPreviews {
#if canImport(UIKit)
            entry.preview = origin.preview.flatMap { savePreview($0, id: entry.id) }
#endif
            if entry.preview == nil, let old = previous?.preview { entry.preview = old }
        }
        // Casting the same thing again moves it to the top; its picture moves with it.
        let replaced = entries.filter { $0.command == command }
        entries.removeAll { $0.command == command }
        removePreviews(of: replaced.filter { $0.preview != entry.preview })
        entries.insert(entry, at: 0)
        prune(current.limit)
        save()
        return entry.id
    }

    /// Saves where a cast got to. Ignored for an entry that's gone or when positions are off.
    func update(_ id: UUID, progress: CastProgress) {
        guard settings.rememberPosition || progress.failed,
              let index = entries.firstIndex(where: { $0.id == id }), entries[index].progress != progress else { return }
        entries[index].progress = progress
        save()
    }

    /// Ids whose link is being checked now.
    @Published private(set) var checking: Set<UUID> = []

    /// Asks the site whether `id`'s link still works and remembers the answer. The checker is
    /// injectable for tests.
    @MainActor
    func checkLink(_ id: UUID, now: Date = Date(),
                   checker: (String, [String: String]) async -> LinkCheck.Result = LinkCheck.check) async {
        guard !checking.contains(id), let entry = entries.first(where: { $0.id == id }),
              let items = Self.items(in: entry.command) else { return }
        let item = Self.startItem(in: entry.command, items: items)
        guard let url = item["url"] as? String else { return }
        checking.insert(id)
        let result = await checker(url, item["headers"] as? [String: String] ?? [:])
        checking.remove(id)
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[index].linkCheck = result
        entries[index].linkCheckedAt = now
        save()
    }

    func delete(_ id: UUID) {
        removePreviews(of: entries.filter { $0.id == id })
        entries.removeAll { $0.id == id }
        save()
    }

    func clear() {
        entries.removeAll()
        try? FileManager.default.removeItem(at: previewDirectory)
        save()
    }

    /// How many casts lowering the limit to `limit` would remove now.
    func removedBy(limit: Int) -> Int { max(0, entries.count - limit) }

    func setLimit(_ limit: Int) {
        defaults.set(CastHistorySettings.coerceLimit(limit), forKey: CastHistorySettings.limitKey)
        prune(CastHistorySettings.coerceLimit(limit))
        save()
    }

    /// Settings changed elsewhere: drop pictures when they're switched off.
    func reconcile() {
        let current = settings
        if !current.keepPreviews, entries.contains(where: { $0.preview != nil }) { removeAllPreviews() }
        if entries.count > current.limit { prune(current.limit); save() }
    }

    func removeAllPreviews() {
        try? FileManager.default.removeItem(at: previewDirectory)
        for index in entries.indices { entries[index].preview = nil }
        save()
    }

    /// Kept casts, pictures and the pictures' size, for Settings.
    var storage: (casts: Int, pictures: Int, bytes: Int64) {
        let files = entries.compactMap { $0.preview.map { previewDirectory.appendingPathComponent($0) } }
        let bytes = files.reduce(Int64(0)) { total, file in
            total + Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return (entries.count, files.count, bytes)
    }

    func previewURL(_ entry: Entry) -> URL? { entry.preview.map { previewDirectory.appendingPathComponent($0) } }

    private func prune(_ limit: Int) {
        guard entries.count > limit else { return }
        removePreviews(of: Array(entries[limit...]))
        entries = Array(entries.prefix(limit))
    }

    private func removePreviews(of removed: [Entry]) {
        for name in removed.compactMap(\.preview) where !entries.contains(where: { $0.preview == name }) {
            try? FileManager.default.removeItem(at: previewDirectory.appendingPathComponent(name))
        }
    }

#if canImport(UIKit)
    /// A small JPEG copy of the cast sheet's picture.
    private func savePreview(_ image: UIImage, id: UUID) -> String? {
        let width: CGFloat = 320
        let scale = min(1, width / max(image.size.width, 1))
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        guard size.width > 0, size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = small.jpegData(compressionQuality: 0.7) else { return nil }
        let name = id.uuidString + ".jpg"
        do {
            try FileManager.default.createDirectory(at: previewDirectory, withIntermediateDirectories: true)
            var directory = previewDirectory
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try? directory.setResourceValues(values)
            try data.write(to: previewDirectory.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return name
        } catch {
            return nil
        }
    }
#endif

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

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
