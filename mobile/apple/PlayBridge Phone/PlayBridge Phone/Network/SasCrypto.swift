import Foundation
import CryptoKit
import Security

/// Sender-side SAS crypto for the pairing handshake. Byte-for-byte parity with the
/// receivers' `SasCrypto` (Android Kotlin / desktop Dart / Apple TV Swift): X25519
/// key agreement, SHA-256, HMAC-SHA-256, HKDF (RFC 5869), and the 6-digit Short
/// Authentication String. Used by `WebSocketClient` for commit/challenge/reveal/confirmation.
enum SasCrypto {
    struct KeyPair {
        let privateKey: Curve25519.KeyAgreement.PrivateKey
        let publicKey: Curve25519.KeyAgreement.PublicKey

        var privateKeyBytes: Data { privateKey.rawRepresentation }
        var publicKeyBytes: Data { publicKey.rawRepresentation }
    }

    static func generateX25519KeyPair() -> KeyPair {
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        return KeyPair(privateKey: privateKey, publicKey: privateKey.publicKey)
    }

    static func calculateECDH(privateKey: Curve25519.KeyAgreement.PrivateKey, publicKeyBytes: Data) throws -> Data {
        let peerPublicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKeyBytes)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)
        return sharedSecret.withUnsafeBytes { Data($0) }
    }

    static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    static func hmacSha256(key: Data, data: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        return Data(HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey))
    }

    static func hkdfExtract(salt: Data?, ikm: Data) -> Data {
        let actualSalt = salt ?? Data(repeating: 0, count: 32)
        return hmacSha256(key: actualSalt, data: ikm)
    }

    static func hkdfExpand(prk: Data, info: Data?, length: Int = 32) -> Data {
        var okm = Data()
        var t = Data()
        var counter: UInt8 = 1
        while okm.count < length {
            var input = Data()
            input.append(t)
            if let info = info { input.append(info) }
            input.append(counter)
            t = hmacSha256(key: prk, data: input)
            let toCopy = min(t.count, length - okm.count)
            okm.append(t.prefix(toCopy))
            counter += 1
        }
        return okm
    }

    /// 6-digit SAS = `truncate(SHA-256(sharedSecret ‖ transcript))` first 4 bytes
    /// big-endian → `& 0x7FFFFFFF` → `% 1_000_000`, zero-padded.
    static func generateSAS(sharedSecret: Data, transcript: Data) -> String {
        var combined = Data()
        combined.append(sharedSecret)
        combined.append(transcript)
        let hash = sha256(combined)
        let value = (Int(hash[0] & 0xFF) << 24) |
                    (Int(hash[1] & 0xFF) << 16) |
                    (Int(hash[2] & 0xFF) << 8) |
                    Int(hash[3] & 0xFF)
        let sasInt = (value & 0x7FFFFFFF) % 1_000_000
        return String(format: "%06d", sasInt)
    }

    static func generateNonce(_ size: Int = 16) -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        _ = SecRandomCopyBytes(kSecRandomDefault, size, &bytes)
        return Data(bytes)
    }
}
