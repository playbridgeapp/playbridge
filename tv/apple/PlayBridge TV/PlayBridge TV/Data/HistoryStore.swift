import Combine
import CryptoKit
import Foundation
import Security

struct BookmarkSubtitlePayload: Codable, Equatable {
    let url: String
    let label: String?
    let language: String?
    let headers: [String: String]?
}

struct BookmarkQueueItemPayload: Codable, Equatable {
    let url: String
    let title: String?
    let headers: [String: String]?
    let subtitles: [BookmarkSubtitlePayload]
    let artworkURL: String?
    let mediaKind: String?
    let startPositionMs: Int64?
}

struct BookmarkResumePayload: Codable, Equatable {
    var items: [BookmarkQueueItemPayload]
    var startIndex: Int
    var startPositionMs: Int64
}

struct PlaybackHistoryItem: Identifiable, Codable, Equatable {
    var id: String { url.absoluteString }
    let url: URL
    let title: String?
    let timestamp: Date
    var isBookmarked: Bool
    var isFavorite: Bool {
        get { isBookmarked }
        set { isBookmarked = newValue }
    }
    var bookmarkedAt: Date?
    let headers: [String: String]?
    // Optional so history written by older versions decodes without a migration schema.
    var positionMs: Int64?
    var durationMs: Int64?
    var payloadData: Data?
    var thumbnailFileName: String?
    var isPrivate: Bool

    var resumePositionMs: Int64? {
        guard let positionMs, let durationMs, durationMs > 0,
              positionMs >= 30_000, Double(positionMs) / Double(durationMs) < 0.95 else {
            return nil
        }
        return positionMs
    }

    init(
        url: URL,
        title: String?,
        timestamp: Date,
        isFavorite: Bool = false,
        headers: [String: String]?,
        positionMs: Int64? = nil,
        durationMs: Int64? = nil,
        isBookmarked: Bool? = nil,
        bookmarkedAt: Date? = nil,
        payloadData: Data? = nil,
        thumbnailFileName: String? = nil,
        isPrivate: Bool = false
    ) {
        self.url = url
        self.title = title
        self.timestamp = timestamp
        let bookmarked = isBookmarked ?? isFavorite
        self.isBookmarked = bookmarked
        self.bookmarkedAt = bookmarked ? (bookmarkedAt ?? timestamp) : nil
        self.headers = headers
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.payloadData = payloadData
        self.thumbnailFileName = thumbnailFileName
        self.isPrivate = isPrivate
    }

    private enum CodingKeys: String, CodingKey {
        case url
        case title
        case timestamp
        case isFavorite
        case isBookmarked
        case bookmarkedAt
        case headers
        case positionMs
        case durationMs
        case payloadData
        case thumbnailFileName
        case isPrivate
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = try c.decode(URL.self, forKey: .url)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        let bookmarked = try c.decodeIfPresent(Bool.self, forKey: .isBookmarked)
            ?? c.decodeIfPresent(Bool.self, forKey: .isFavorite)
            ?? false
        isBookmarked = bookmarked
        bookmarkedAt = try c.decodeIfPresent(Date.self, forKey: .bookmarkedAt) ?? (bookmarked ? timestamp : nil)
        headers = try c.decodeIfPresent([String: String].self, forKey: .headers)
        positionMs = try c.decodeIfPresent(Int64.self, forKey: .positionMs)
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs)
        payloadData = try c.decodeIfPresent(Data.self, forKey: .payloadData)
        thumbnailFileName = try c.decodeIfPresent(String.self, forKey: .thumbnailFileName)
        isPrivate = try c.decodeIfPresent(Bool.self, forKey: .isPrivate) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(url, forKey: .url)
        try c.encodeIfPresent(title, forKey: .title)
        try c.encode(timestamp, forKey: .timestamp)
        try c.encode(isBookmarked, forKey: .isFavorite)
        try c.encode(isBookmarked, forKey: .isBookmarked)
        try c.encodeIfPresent(bookmarkedAt, forKey: .bookmarkedAt)
        try c.encodeIfPresent(headers, forKey: .headers)
        try c.encodeIfPresent(positionMs, forKey: .positionMs)
        try c.encodeIfPresent(durationMs, forKey: .durationMs)
        try c.encodeIfPresent(payloadData, forKey: .payloadData)
        try c.encodeIfPresent(thumbnailFileName, forKey: .thumbnailFileName)
        if isPrivate {
            try c.encode(isPrivate, forKey: .isPrivate)
        }
    }
}

protocol HistoryKeyProvider {
    func loadOrCreateKey() throws -> SymmetricKey
}

struct KeychainHistoryKeyProvider: HistoryKeyProvider {
    private let service = "com.playbridge.tv.history"
    private let account = "history-encryption-key-v1"

    func loadOrCreateKey() throws -> SymmetricKey {
        if let existing = try readKey() { return existing }

        let key = SymmetricKey(size: .bits256)
        let bytes = key.withUnsafeBytes { Data($0) }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: bytes,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem, let existing = try readKey() { return existing }
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return key
    }

    private func readKey() throws -> SymmetricKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        guard let bytes = item as? Data, bytes.count == 32 else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(errSecDecode))
        }
        return SymmetricKey(data: bytes)
    }
}

class HistoryStore: ObservableObject {
    @Published private(set) var history: [PlaybackHistoryItem] = []
    @Published private(set) var storageUnavailable = false

    private let legacyKey = "pb_playback_history"
    private let encryptedKey = "pb_playback_history_encrypted_v1"
    private let defaults: UserDefaults
    private let keyProvider: HistoryKeyProvider
    private let thumbnailDirectory: URL?
    private var key: SymmetricKey?
    private var lastPersistedAt = Date.distantPast
    private var hasUnsavedProgress = false

    init(
        defaults: UserDefaults = .standard,
        keyProvider: HistoryKeyProvider = KeychainHistoryKeyProvider(),
        thumbnailDirectory: URL? = nil
    ) {
        self.defaults = defaults
        self.keyProvider = keyProvider
        self.thumbnailDirectory = thumbnailDirectory
        loadHistory()
    }

    var bookmarkedItems: [PlaybackHistoryItem] {
        history
            .filter(\.isBookmarked)
            .sorted { ($0.bookmarkedAt ?? $0.timestamp) > ($1.bookmarkedAt ?? $1.timestamp) }
    }

    var visibleHistory: [PlaybackHistoryItem] {
        history.filter { !$0.isPrivate }
    }

    func isBookmarked(url: URL) -> Bool {
        history.first { $0.url == url }?.isBookmarked ?? false
    }

    func loadHistory() {
        do {
            key = try keyProvider.loadOrCreateKey()
            storageUnavailable = false
        } catch {
            key = nil
            storageUnavailable = true
            // Preserve legacy records in memory, but never write another plaintext copy.
            if defaults.data(forKey: encryptedKey) == nil,
               let data = defaults.data(forKey: legacyKey),
               let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: data) {
                history = decoded
                trimKeepingBookmarks()
            }
            return
        }

        guard let key else { return }
        if let encrypted = defaults.data(forKey: encryptedKey) {
            do {
                let box = try AES.GCM.SealedBox(combined: encrypted)
                let data = try AES.GCM.open(box, using: key)
                history = try JSONDecoder().decode([PlaybackHistoryItem].self, from: data)
                trimKeepingBookmarks()
                // A previous migration may have written the encrypted copy but not removed the old one.
                defaults.removeObject(forKey: legacyKey)
            } catch {
                // Do not replace unreadable ciphertext with an empty history on the next save.
                storageUnavailable = true
                if let legacy = defaults.data(forKey: legacyKey),
                   let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: legacy) {
                    history = decoded
                    trimKeepingBookmarks()
                } else {
                    history = []
                }
            }
        } else if let legacy = defaults.data(forKey: legacyKey) {
            guard let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: legacy) else {
                storageUnavailable = true
                return
            }
            history = decoded
            trimKeepingBookmarks()
            saveHistory() // Encrypt first; remove the plaintext key only after a successful write.
        } else {
            history = []
        }
        pruneMissingThumbnails()
    }

    func addToHistory(url: URL, title: String?, headers: [String: String]?) {
        let enableHistory = defaults.object(forKey: "enable_history") as? Bool ?? true
        guard enableHistory else { return }

        let existing = history.first { $0.url == url }
        let newItem = PlaybackHistoryItem(
            url: url,
            title: title ?? "Unknown Media",
            timestamp: Date(),
            isFavorite: existing?.isBookmarked ?? false,
            headers: headers,
            positionMs: existing?.positionMs,
            durationMs: existing?.durationMs,
            isBookmarked: existing?.isBookmarked ?? false,
            bookmarkedAt: existing?.bookmarkedAt,
            payloadData: existing?.payloadData,
            thumbnailFileName: existing?.thumbnailFileName,
            isPrivate: false
        )
        history.removeAll { $0.url == url }
        history.insert(newItem, at: 0)
        trimKeepingBookmarks()
        saveHistory()
    }

    func updateProgress(url: URL, positionMs: Int, durationMs: Int) {
        let enableHistory = defaults.object(forKey: "enable_history") as? Bool ?? true
        guard durationMs > 0, positionMs >= 0,
              let index = history.firstIndex(where: { $0.url == url }),
              enableHistory || history[index].isBookmarked else { return }
        let duration = Int64(durationMs)
        let position = min(Int64(positionMs), duration)
        guard history[index].positionMs != position || history[index].durationMs != duration else { return }
        history[index].positionMs = position
        history[index].durationMs = duration
        hasUnsavedProgress = true
        if Date().timeIntervalSince(lastPersistedAt) >= 15 { saveHistory() }
    }

    func flushProgress() {
        if hasUnsavedProgress { saveHistory() }
    }

    func toggleFavorite(item: PlaybackHistoryItem) {
        _ = toggleBookmark(
            url: item.url,
            title: item.title,
            headers: item.headers,
            payloadData: item.payloadData,
            positionMs: item.positionMs,
            durationMs: item.durationMs
        )
    }

    static let maxPlainHistoryCount = 100
    static let maxBookmarkQueueItems = 10
    static let maxBookmarkPayloadBytes = 16 * 1024
    static let maxEncryptedBlobBytes = 400 * 1024

    /// Bounds a bookmark resume payload so it fits comfortably inside UserDefaults on tvOS.
    /// Keeps the current item plus up to `maxBookmarkQueueItems - 1` following queue items,
    /// degrading gracefully to the single item (and stripping large headers/subtitles if needed).
    static func boundedPayloadData(_ data: Data?, maxBytes: Int = maxBookmarkPayloadBytes) -> Data? {
        guard let data else { return nil }
        guard let payload = try? JSONDecoder().decode(BookmarkResumePayload.self, from: data) else {
            return data.count <= maxBytes ? data : nil
        }
        guard !payload.items.isEmpty else { return nil }
        let start = min(max(0, payload.startIndex), payload.items.count - 1)
        let end = min(payload.items.count, start + maxBookmarkQueueItems)
        let sliced = BookmarkResumePayload(
            items: Array(payload.items[start..<end]),
            startIndex: 0,
            startPositionMs: payload.startPositionMs
        )
        let encoder = JSONEncoder()
        if let encoded = try? encoder.encode(sliced), encoded.count <= maxBytes {
            return encoded
        }
        let current = payload.items[start]
        let single = BookmarkResumePayload(
            items: [current],
            startIndex: 0,
            startPositionMs: payload.startPositionMs
        )
        if let encoded = try? encoder.encode(single), encoded.count <= maxBytes {
            return encoded
        }
        let strippedItem = BookmarkQueueItemPayload(
            url: current.url,
            title: current.title,
            headers: nil,
            subtitles: [],
            artworkURL: current.artworkURL,
            mediaKind: current.mediaKind,
            startPositionMs: current.startPositionMs
        )
        let minimal = BookmarkResumePayload(
            items: [strippedItem],
            startIndex: 0,
            startPositionMs: payload.startPositionMs
        )
        if let encoded = try? encoder.encode(minimal), encoded.count <= maxBytes {
            return encoded
        }
        return nil
    }

    /// Toggles bookmark state for `url`. Private or history-off casts are stored only while bookmarked.
    @discardableResult
    func toggleBookmark(
        url: URL,
        title: String?,
        headers: [String: String]?,
        payloadData: Data? = nil,
        positionMs: Int64? = nil,
        durationMs: Int64? = nil
    ) -> Bool {
        let boundedPayload = Self.boundedPayloadData(payloadData)
        if let index = history.firstIndex(where: { $0.url == url }) {
            let nowBookmarked = !history[index].isBookmarked
            if nowBookmarked {
                history[index].isBookmarked = true
                history[index].bookmarkedAt = Date()
                if let boundedPayload {
                    history[index].payloadData = boundedPayload
                }
                if let positionMs {
                    history[index].positionMs = positionMs
                }
                if let durationMs {
                    history[index].durationMs = durationMs
                }
                saveHistory()
                return true
            } else {
                BookmarkThumbnailPolicy.deleteThumbnail(
                    fileName: history[index].thumbnailFileName,
                    directory: thumbnailDirectory
                )
                if history[index].isPrivate {
                    history.remove(at: index)
                } else {
                    history[index].isBookmarked = false
                    history[index].bookmarkedAt = nil
                    history[index].thumbnailFileName = nil
                    history[index].payloadData = nil
                }
                saveHistory()
                return false
            }
        } else {
            let now = Date()
            let newItem = PlaybackHistoryItem(
                url: url,
                title: title ?? "Unknown Media",
                timestamp: now,
                isFavorite: true,
                headers: headers,
                positionMs: positionMs,
                durationMs: durationMs,
                isBookmarked: true,
                bookmarkedAt: now,
                payloadData: boundedPayload,
                thumbnailFileName: nil,
                isPrivate: true
            )
            history.insert(newItem, at: 0)
            trimKeepingBookmarks()
            saveHistory()
            return true
        }
    }

    func setThumbnailFileName(_ fileName: String?, for url: URL) {
        guard let index = history.firstIndex(where: { $0.url == url }),
              history[index].isBookmarked else { return }
        let previous = history[index].thumbnailFileName
        if let previous, previous != fileName {
            BookmarkThumbnailPolicy.deleteThumbnail(fileName: previous, directory: thumbnailDirectory)
        }
        history[index].thumbnailFileName = fileName
        saveHistory()
    }

    func resumePayload(for item: PlaybackHistoryItem, startOver: Bool = false) -> BookmarkResumePayload? {
        let resumeMs: Int64 = startOver ? 0 : (item.resumePositionMs ?? item.positionMs ?? 0)
        if let data = item.payloadData,
           var decoded = try? JSONDecoder().decode(BookmarkResumePayload.self, from: data),
           !decoded.items.isEmpty {
            decoded.startPositionMs = resumeMs
            return decoded
        }
        return BookmarkResumePayload(
            items: [
                BookmarkQueueItemPayload(
                    url: item.url.absoluteString,
                    title: item.title,
                    headers: item.headers,
                    subtitles: [],
                    artworkURL: nil,
                    mediaKind: nil,
                    startPositionMs: resumeMs
                ),
            ],
            startIndex: 0,
            startPositionMs: resumeMs
        )
    }

    func clearHistory() {
        if storageUnavailable {
            history.removeAll()
            hasUnsavedProgress = false
            defaults.removeObject(forKey: encryptedKey)
            defaults.removeObject(forKey: legacyKey)
            storageUnavailable = key == nil
            lastPersistedAt = .distantPast
            return
        }
        history.removeAll { !$0.isBookmarked }
        hasUnsavedProgress = false
        if history.isEmpty {
            defaults.removeObject(forKey: encryptedKey)
            defaults.removeObject(forKey: legacyKey)
            storageUnavailable = key == nil
            lastPersistedAt = .distantPast
        } else {
            saveHistory()
        }
    }

    /// Returns the thumbnail file URL if the file still exists on disk; otherwise clears the stale
    /// `thumbnailFileName` and returns `nil` so the UI shows the placeholder tile.
    func validThumbnailURL(for item: PlaybackHistoryItem) -> URL? {
        guard item.isBookmarked, let fileName = item.thumbnailFileName else { return nil }
        let fileURL = BookmarkThumbnailPolicy.fileURL(for: fileName, directory: thumbnailDirectory)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            return fileURL
        }
        if let index = history.firstIndex(where: { $0.url == item.url }),
           history[index].thumbnailFileName == fileName {
            history[index].thumbnailFileName = nil
            saveHistory()
        }
        return nil
    }

    /// Returns true if `url` is currently bookmarked and has no valid thumbnail file on disk
    /// (clearing any stale `thumbnailFileName` if its Caches file was purged).
    func needsBookmarkThumbnail(for url: URL) -> Bool {
        guard let index = history.firstIndex(where: { $0.url == url }),
              history[index].isBookmarked else { return false }
        guard let fileName = history[index].thumbnailFileName else { return true }
        let fileURL = BookmarkThumbnailPolicy.fileURL(for: fileName, directory: thumbnailDirectory)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            return false
        }
        history[index].thumbnailFileName = nil
        saveHistory()
        return true
    }

    func pruneMissingThumbnails() {
        var changed = false
        for index in history.indices {
            guard history[index].isBookmarked, let fileName = history[index].thumbnailFileName else { continue }
            let fileURL = BookmarkThumbnailPolicy.fileURL(for: fileName, directory: thumbnailDirectory)
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                history[index].thumbnailFileName = nil
                changed = true
            }
        }
        if changed {
            saveHistory()
        }
    }

    private func trimKeepingBookmarks() {
        var plainCount = 0
        history = history.filter { item in
            if item.isBookmarked { return true }
            plainCount += 1
            return plainCount <= Self.maxPlainHistoryCount
        }
    }

    @discardableResult
    func saveHistory(maxBlobBytes: Int = maxEncryptedBlobBytes) -> Bool {
        guard !storageUnavailable, let key else { return false }
        do {
            var candidate = history
            while true {
                let data = try JSONEncoder().encode(candidate)
                let box = try AES.GCM.seal(data, using: key)
                guard let combined = box.combined else {
                    storageUnavailable = true
                    return false
                }
                if combined.count <= maxBlobBytes {
                    if candidate.count != history.count {
                        history = candidate
                    }
                    defaults.set(combined, forKey: encryptedKey)
                    defaults.removeObject(forKey: legacyKey)
                    lastPersistedAt = Date()
                    hasUnsavedProgress = false
                    return true
                }
                // Drop oldest non-bookmarked item first before refusing to overwrite existing defaults.
                if let oldestPlainIndex = candidate.lastIndex(where: { !$0.isBookmarked }) {
                    candidate.remove(at: oldestPlainIndex)
                } else {
                    // Never overwrite existing UserDefaults with an oversized blob.
                    return false
                }
            }
        } catch {
            storageUnavailable = true
            return false
        }
    }
}
