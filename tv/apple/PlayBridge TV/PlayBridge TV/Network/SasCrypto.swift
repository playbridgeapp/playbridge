import Foundation
import CryptoKit
import Security

enum SasCrypto {
    struct KeyPair {
        let privateKey: Curve25519.KeyAgreement.PrivateKey
        let publicKey: Curve25519.KeyAgreement.PublicKey
        
        var privateKeyBytes: Data {
            privateKey.rawRepresentation
        }
        
        var publicKeyBytes: Data {
            publicKey.rawRepresentation
        }
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
        let digest = SHA256.hash(data: data)
        return Data(digest)
    }
    
    static func hmacSha256(key: Data, data: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let authenticationCode = HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey)
        return Data(authenticationCode)
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
            if let info = info {
                input.append(info)
            }
            input.append(counter)
            t = hmacSha256(key: prk, data: input)
            let toCopy = min(t.count, length - okm.count)
            okm.append(t.prefix(toCopy))
            counter += 1
        }
        
        return okm
    }
    
    static func generateSAS(sharedSecret: Data, transcript: Data) -> String {
        var combined = Data()
        combined.append(sharedSecret)
        combined.append(transcript)
        let hash = sha256(combined)
        
        // Truncate SHA-256 to first 4 bytes big-endian
        let value = (Int(hash[0] & 0xFF) << 24) |
                    (Int(hash[1] & 0xFF) << 16) |
                    (Int(hash[2] & 0xFF) << 8) |
                    Int(hash[3] & 0xFF)
        
        let sasInt = (value & 0x7FFFFFFF) % 1000000
        return String(format: "%06d", sasInt)
    }
    
    static func generateNonce(size: Int = 16) -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        _ = SecRandomCopyBytes(kSecRandomDefault, size, &bytes)
        return Data(bytes)
    }

    /// AES-256-GCM ciphertext followed by the 16-byte authentication tag.
    static func aesGcmEncrypt(key: Data, nonce: Data, plaintext: Data, aad: Data) throws -> Data {
        guard key.count == 32, nonce.count == 12 else {
            throw CocoaError(.coderInvalidValue)
        }
        let sealed = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: AES.GCM.Nonce(data: nonce),
            authenticating: aad
        )
        var result = sealed.ciphertext
        result.append(sealed.tag)
        return result
    }
}
