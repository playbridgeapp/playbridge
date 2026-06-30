import Foundation
import UIKit

/// Persists the paired receiver (token + SPKI pin live in the Keychain since they're
/// security-sensitive) and this phone's stable identity used in the pairing handshake.
/// Mirrors `ConnectionStore` + the device-UUID handling on Android.
final class PairingStore {
    static let shared = PairingStore()

    private let keychainService = "com.playbridge.phone"
    private let keychainAccount = "paired_device"
    private let savedDevicesAccount = "saved_devices"
    private let deviceUUIDKey = "pb_device_uuid"

    private let defaults = UserDefaults.standard

    // MARK: - This phone's identity (sent in pairing_request)

    /// Stable per-install UUID. Generated once and persisted.
    var localDeviceUUID: String {
        if let existing = defaults.string(forKey: deviceUUIDKey) { return existing }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: deviceUUIDKey)
        return fresh
    }

    /// Human-readable name shown on the TV's Allow/Deny prompt.
    var localDeviceName: String {
        let name = UIDevice.current.name
        return name.isEmpty ? "iPhone" : name
    }

    // MARK: - Paired device (Keychain)

    func loadPairedDevice() -> PairedDevice? {
        guard let data = keychainRead(account: keychainAccount) else { return nil }
        return try? JSONDecoder().decode(PairedDevice.self, from: data)
    }

    func savePairedDevice(_ device: PairedDevice) {
        guard let data = try? JSONEncoder().encode(device) else { return }
        keychainWrite(data, account: keychainAccount)
    }

    func clearPairedDevice() {
        keychainDelete(account: keychainAccount)
    }

    // MARK: - Saved devices (history)

    func loadSavedDevices() -> [PairedDevice] {
        guard let data = keychainRead(account: savedDevicesAccount) else { return [] }
        return (try? JSONDecoder().decode([PairedDevice].self, from: data)) ?? []
    }

    func saveSavedDevices(_ devices: [PairedDevice]) {
        guard let data = try? JSONEncoder().encode(devices) else { return }
        keychainWrite(data, account: savedDevicesAccount)
    }

    // MARK: - Keychain primitives

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
    }

    private func keychainRead(account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private func keychainWrite(_ data: Data, account: String) {
        // Upsert: try update first, fall back to add.
        let attrs: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(baseQuery(account: account) as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = baseQuery(account: account)
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    private func keychainDelete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }
}
