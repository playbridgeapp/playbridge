import Foundation
import CryptoKit

/// Existing phone UUID access and local ATV name derivation. Never log either result.
enum PlayBridgePhoneIdentity {
    private static let lock = NSLock()
    static func localDeviceUUID(defaults: UserDefaults = .standard, create: () -> String = { UUID().uuidString }) -> String {
        localDeviceUUID(read: { defaults.string(forKey: "pb_device_uuid") },
                        write: { defaults.set($0, forKey: "pb_device_uuid") }, create: create)
    }
    static func localDeviceUUID(read: () -> String?, write: (String) -> Void, create: () -> String) -> String {
        lock.lock(); defer { lock.unlock() }
        if let existing = read() { return existing }
        let fresh = create()
        write(fresh)
        return fresh
    }
    static func atvClientName(label: String, uuid: String) -> String {
        let suffix = SHA256.hash(data: Data(("atv-remote:" + uuid).utf8))
            .prefix(4).map { String(format: "%02x", $0) }.joined()
        return label + " " + suffix
    }
}
