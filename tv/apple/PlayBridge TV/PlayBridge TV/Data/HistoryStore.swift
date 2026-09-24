import Combine
import CryptoKit
import Foundation
import Security

struct PlaybackHistoryItem: Identifiable, Codable, Equatable {
    var id: String { url.absoluteString }
    let url: URL
    let title: String?
    let timestamp: Date
    var isFavorite: Bool
    let headers: [String: String]?
    // Optional so history written by older versions decodes without a migration schema.
    var positionMs: Int64?
    var durationMs: Int64?

    var resumePositionMs: Int64? {
        guard let positionMs, let durationMs, durationMs > 0,
              positionMs >= 30_000, Double(positionMs) / Double(durationMs) < 0.95 else {
            return nil
        }
        return positionMs
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
    private var key: SymmetricKey?
    private var lastPersistedAt = Date.distantPast
    private var hasUnsavedProgress = false

    init(defaults: UserDefaults = .standard,
         keyProvider: HistoryKeyProvider = KeychainHistoryKeyProvider()) {
        self.defaults = defaults
        self.keyProvider = keyProvider
        loadHistory()
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
               let data = defaults.data(forKey: legacyKey) {
                history = (try? JSONDecoder().decode([PlaybackHistoryItem].self, from: data))
                    .map { Array($0.prefix(100)) } ?? []
            }
            return
        }

        guard let key else { return }
        if let encrypted = defaults.data(forKey: encryptedKey) {
            do {
                let box = try AES.GCM.SealedBox(combined: encrypted)
                let data = try AES.GCM.open(box, using: key)
                history = Array(try JSONDecoder().decode([PlaybackHistoryItem].self, from: data).prefix(100))
                // A previous migration may have written the encrypted copy but not removed the old one.
                defaults.removeObject(forKey: legacyKey)
            } catch {
                // Do not replace unreadable ciphertext with an empty history on the next save.
                storageUnavailable = true
                if let legacy = defaults.data(forKey: legacyKey),
                   let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: legacy) {
                    history = Array(decoded.prefix(100))
                } else {
                    history = []
                }
            }
        } else if let legacy = defaults.data(forKey: legacyKey) {
            guard let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: legacy) else {
                storageUnavailable = true
                return
            }
            history = Array(decoded.prefix(100))
            saveHistory() // Encrypt first; remove the plaintext key only after a successful write.
        } else {
            history = []
        }
    }

    func addToHistory(url: URL, title: String?, headers: [String: String]?) {
        let enableHistory = defaults.object(forKey: "enable_history") as? Bool ?? true
        guard enableHistory else { return }

        let existing = history.first { $0.url == url }
        let newItem = PlaybackHistoryItem(
            url: url, title: title ?? "Unknown Media", timestamp: Date(),
            isFavorite: existing?.isFavorite ?? false, headers: headers,
            positionMs: existing?.positionMs, durationMs: existing?.durationMs
        )
        history.removeAll { $0.url == url }
        history.insert(newItem, at: 0)
        if history.count > 100 { history = Array(history.prefix(100)) }
        saveHistory()
    }

    func updateProgress(url: URL, positionMs: Int, durationMs: Int) {
        guard defaults.object(forKey: "enable_history") as? Bool ?? true,
              durationMs > 0, positionMs >= 0,
              let index = history.firstIndex(where: { $0.url == url }) else { return }
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
        if let index = history.firstIndex(where: { $0.url == item.url }) {
            history[index].isFavorite.toggle()
            saveHistory()
        }
    }

    func clearHistory() {
        history.removeAll()
        hasUnsavedProgress = false
        defaults.removeObject(forKey: encryptedKey)
        defaults.removeObject(forKey: legacyKey)
        storageUnavailable = key == nil
        lastPersistedAt = .distantPast
    }

    private func saveHistory() {
        guard !storageUnavailable, let key else { return }
        do {
            let data = try JSONEncoder().encode(history)
            let box = try AES.GCM.seal(data, using: key)
            guard let combined = box.combined else {
                storageUnavailable = true
                return
            }
            defaults.set(combined, forKey: encryptedKey)
            defaults.removeObject(forKey: legacyKey)
            lastPersistedAt = Date()
            hasUnsavedProgress = false
        } catch {
            storageUnavailable = true
        }
    }
}
