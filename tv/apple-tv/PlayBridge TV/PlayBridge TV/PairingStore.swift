import Foundation
import Security

/// Persistent storage for the WebSocket auth token and device UUID.
///
/// The auth token is stored in the Keychain (not UserDefaults) to prevent it from
/// syncing to iCloud across devices and to avoid it appearing in device backups.
/// Any token previously stored in UserDefaults is migrated automatically on first access.
final class PairingStore {
    static let shared = PairingStore()

    private let service = "com.playbridge.tv"
    private let tokenKey = "auth_token"
    private let uuidKey = "device_uuid"

    private init() {}

    // MARK: - Auth Token

    /// The WebSocket pairing token. Generated once and persisted in the Keychain.
    var authToken: String {
        if let existing = readKeychain(key: tokenKey) { return existing }

        // Migrate from UserDefaults (legacy — first launch after upgrade)
        if let legacy = UserDefaults.standard.string(forKey: tokenKey), !legacy.isEmpty {
            saveKeychain(key: tokenKey, value: legacy)
            UserDefaults.standard.removeObject(forKey: tokenKey)
            return legacy
        }

        // First ever launch — generate fresh token
        let token = UUID().uuidString
        saveKeychain(key: tokenKey, value: token)
        return token
    }

    // MARK: - Device UUID

    /// A stable device identifier included in NSD TXT records so the phone can
    /// distinguish multiple PlayBridge TV receivers on the same network.
    var deviceUUID: String {
        // UUID is less sensitive; UserDefaults is acceptable here
        if let existing = UserDefaults.standard.string(forKey: uuidKey) { return existing }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: uuidKey)
        return id
    }

    // MARK: - Keychain Helpers

    @discardableResult
    private func saveKeychain(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }

        // Delete any existing entry first
        let deleteQuery: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecValueData: data,
            // Accessible when unlocked; excluded from iCloud Keychain sync
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlocked,
            kSecAttrSynchronizable: false,
        ]
        return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
    }

    private func readKeychain(key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
            let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
