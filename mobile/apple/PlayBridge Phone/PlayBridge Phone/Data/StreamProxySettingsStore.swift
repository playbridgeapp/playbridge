import Foundation
import Security

enum StreamProxySettingsStore {
    private static let urlKey = "stream_proxy_remote_url"
    private static var keychainQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.playbridge.stream-proxy",
         kSecAttrAccount as String: "remote-password"]
    }

    static func load() -> RemoteProxyConfiguration {
        var query = keychainQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        let password = status == errSecSuccess ? (result as? Data).flatMap { String(data: $0, encoding: .utf8) } ?? "" : ""
        return RemoteProxyConfiguration(baseURL: UserDefaults.standard.string(forKey: urlKey) ?? "", password: password)
    }

    static func save(_ configuration: RemoteProxyConfiguration) throws {
        _ = try configuration.validatedURL()
        let attributes: [String: Any] = [kSecValueData as String: Data(configuration.password.utf8)]
        var status = SecItemUpdate(keychainQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var query = keychainQuery.merging(attributes) { _, new in new }
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(query as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw StreamRoutingError.message("Couldn’t save the proxy password securely. Please try again.") }
        UserDefaults.standard.set(configuration.baseURL.trimmingCharacters(in: .whitespacesAndNewlines), forKey: urlKey)
    }
}
