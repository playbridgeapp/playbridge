import Foundation
import Crypto      // swift-crypto
import X509        // swift-certificates
import SwiftASN1
import Security
import Network

/// Generates (once) and persists a self-signed TLS identity for the `wss://`
/// listener, exposing it as a `sec_identity_t` plus the SPKI pin
/// (`sha256/<base64>`) that the sender pins at pairing.
///
/// tvOS has no direct cert+key → SecIdentity API (`SecIdentityCreateWithCertificate`
/// is macOS-only, and neither swift-crypto nor swift-certificates emits PKCS#12),
/// so we import the private key and certificate into the Keychain separately and
/// let the system pair them into an identity via a `kSecClassIdentity` query.
enum TLSIdentity {
    /// Label stored on the certificate; the identity query matches on it.
    private static let label = "com.playbridge.tv.tls"
    private static let pinDefaultsKey = "pb_tls_spki_pin"

    struct Result {
        let identity: sec_identity_t
        let fingerprint: String   // "sha256/<base64 SPKI>"
    }

    /// Returns the persisted identity, generating + persisting one on first run.
    static func loadOrCreate(commonName: String) throws -> Result {
        if let secIdentity = loadKeychainIdentity(),
           let pin = UserDefaults.standard.string(forKey: pinDefaultsKey),
           let identity = sec_identity_create(secIdentity) {
            return Result(identity: identity, fingerprint: pin)
        }
        return try create(commonName: commonName)
    }

    private static func loadKeychainIdentity() -> SecIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: label,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let item else { return nil }
        // Force-cast is safe: kSecClassIdentity guarantees a SecIdentity.
        return (item as! SecIdentity)
    }

    /// Removes any cert/key previously stored under our label. The Keychain
    /// outlives app reinstalls (UserDefaults does not), so without this a stale
    /// cert can linger and be served while we advertise a freshly-computed pin.
    private static func deleteExisting() {
        for cls in [kSecClassCertificate, kSecClassKey] {
            let query: [String: Any] = [
                kSecClass as String: cls,
                kSecAttrLabel as String: label,
            ]
            SecItemDelete(query as CFDictionary)
        }
    }

    private static func create(commonName: String) throws -> Result {
        // Clear any prior identity first so the fresh cert is what we actually
        // serve (otherwise SecItemAdd hits errSecDuplicateItem and keeps the old
        // cert while we report a new pin — a served/advertised split brain).
        deleteExisting()

        // 1. Key + self-signed certificate.
        let privateKey = P256.Signing.PrivateKey()
        let certKey = Certificate.PrivateKey(privateKey)
        let name = try DistinguishedName { CommonName(commonName) }
        let now = Date()
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: certKey.publicKey,
            notValidBefore: now.addingTimeInterval(-60),
            notValidAfter: now.addingTimeInterval(60 * 60 * 24 * 3650),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                SubjectAlternativeNames([.dnsName("localhost")])
            },
            issuerPrivateKey: certKey
        )
        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        let certDER = Data(serializer.serializedBytes)

        // 2. SPKI pin — P256 publicKey.derRepresentation is the SPKI DER.
        let spki = privateKey.publicKey.derRepresentation
        let pin = "sha256/" + Data(SHA256.hash(data: spki)).base64EncodedString()

        // 3. Import key + cert, then retrieve the paired identity.
        try importPrivateKey(privateKey)
        try importCertificate(certDER)
        guard let secIdentity = loadKeychainIdentity(),
              let identity = sec_identity_create(secIdentity) else {
            throw TLSError.identityUnavailable
        }
        UserDefaults.standard.set(pin, forKey: pinDefaultsKey)
        return Result(identity: identity, fingerprint: pin)
    }

    private static func importPrivateKey(_ key: P256.Signing.PrivateKey) throws {
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrKeySizeInBits as String: 256,
        ]
        var error: Unmanaged<CFError>?
        // x963Representation = 04 || X || Y || privateScalar, what SecKey expects.
        guard let secKey = SecKeyCreateWithData(
            key.x963Representation as CFData, attrs as CFDictionary, &error
        ) else {
            throw error!.takeRetainedValue() as Error
        }
        let add: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecValueRef as String: secKey,
            kSecAttrLabel as String: label,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw keychainError(status)
        }
    }

    private static func importCertificate(_ der: Data) throws {
        guard let cert = SecCertificateCreateWithData(nil, der as CFData) else {
            throw TLSError.badCertificate
        }
        let add: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: cert,
            kSecAttrLabel as String: label,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw keychainError(status)
        }
    }

    private static func keychainError(_ status: OSStatus) -> Error {
        let msg = SecCopyErrorMessageString(status, nil) as String? ?? "OSStatus \(status)"
        return NSError(
            domain: NSOSStatusErrorDomain, code: Int(status),
            userInfo: [NSLocalizedDescriptionKey: msg]
        )
    }

    enum TLSError: Error {
        case identityUnavailable
        case badCertificate
    }
}
