import Foundation
import CryptoKit
import Security

/// Computes the SPKI pin (`sha256/<base64>`) of a server's leaf certificate and compares it to
/// the pin captured at pairing — the sender side of the TOFU scheme in `protocol/README.md`.
///
/// The pin is `SHA-256(DER SubjectPublicKeyInfo)`, base64, with a `sha256/` prefix (OkHttp
/// `CertificatePinner` format). The receiver's identity is always EC P-256 (`TLSIdentity.swift`
/// uses `P256.Signing.PrivateKey`), so we reconstruct the SPKI by prepending the fixed P-256
/// ASN.1 header to the raw public-key bytes (`SecKeyCopyExternalRepresentation` gives the X9.63
/// point, not the SPKI wrapper). This is the standard TrustKit approach.
enum SPKIPinning {

    /// ASN.1 DER SubjectPublicKeyInfo header for an `ecPublicKey` over `prime256v1` (P-256).
    /// Followed by the 65-byte uncompressed point (`04 || X || Y`).
    private static let p256SPKIHeader: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86,
        0x48, 0xCE, 0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A,
        0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03,
        0x42, 0x00,
    ]

    /// The leaf certificate's SPKI pin, or nil if it can't be derived (e.g. non-EC key).
    static func pin(for trust: SecTrust) -> String? {
        guard let leaf = leafCertificate(of: trust),
              let publicKey = SecCertificateCopyKey(leaf),
              let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            return nil
        }
        // EC P-256 external representation is the 65-byte uncompressed point.
        guard raw.count == 65, raw.first == 0x04 else { return nil }
        var spki = Data(p256SPKIHeader)
        spki.append(raw)
        let digest = SHA256.hash(data: spki)
        return "sha256/" + Data(digest).base64EncodedString()
    }

    private static func leafCertificate(of trust: SecTrust) -> SecCertificate? {
        if #available(iOS 15.0, *) {
            return (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
        } else {
            guard SecTrustGetCertificateCount(trust) > 0 else { return nil }
            return SecTrustGetCertificateAtIndex(trust, 0)
        }
    }
}
