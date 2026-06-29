import Foundation
import CryptoKit
import Security

/// Computes the SPKI pin (`sha256/<base64>`) of a server's leaf certificate and compares it to
/// the pin captured at pairing — the sender side of the TOFU scheme in `protocol/README.md`.
///
/// The pin is `SHA-256(DER SubjectPublicKeyInfo)`, base64, with a `sha256/` prefix (OkHttp
/// `CertificatePinner` format). Receivers differ in key type — Android TV / Apple TV use EC
/// P-256, the desktop uses RSA-2048 — so rather than reconstruct the SPKI per key type we
/// extract the SubjectPublicKeyInfo straight out of the certificate's DER (an X.509 ASN.1
/// walk) and hash that. This matches every receiver's reported `certFingerprint` regardless
/// of key algorithm.
enum SPKIPinning {

    /// The leaf certificate's SPKI pin, or nil if it can't be derived.
    static func pin(for trust: SecTrust) -> String? {
        guard let leaf = leafCertificate(of: trust),
              let spki = subjectPublicKeyInfoDER(of: leaf) else {
            return nil
        }
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

    /// Walks the certificate's DER to return the encoded SubjectPublicKeyInfo SEQUENCE.
    /// `Certificate ::= SEQUENCE { tbsCertificate SEQUENCE { …, spki, … }, … }`; the SPKI is
    /// the unique child SEQUENCE of tbsCertificate whose content is an AlgorithmIdentifier
    /// SEQUENCE followed by a BIT STRING. Works for RSA and EC alike.
    private static func subjectPublicKeyInfoDER(of cert: SecCertificate) -> Data? {
        let b = [UInt8](SecCertificateCopyData(cert) as Data)
        guard let certSeq = readTLV(b, 0), certSeq.tag == 0x30,
              let tbs = readTLV(b, certSeq.contentOffset), tbs.tag == 0x30 else {
            return nil
        }
        var off = tbs.contentOffset
        let tbsEnd = tbs.contentOffset + tbs.length
        while off < tbsEnd {
            guard let el = readTLV(b, off) else { return nil }
            if el.tag == 0x30,
               let algId = readTLV(b, el.contentOffset), algId.tag == 0x30,
               let bitStr = readTLV(b, algId.next), bitStr.tag == 0x03,
               bitStr.next == el.contentOffset + el.length {
                return Data(b[off..<el.next])
            }
            off = el.next
        }
        return nil
    }

    private struct TLV { let tag: UInt8; let contentOffset: Int; let length: Int; let next: Int }

    /// Minimal DER tag-length-value reader (definite-length form only, as in X.509 certs).
    private static func readTLV(_ b: [UInt8], _ offset: Int) -> TLV? {
        guard offset >= 0, offset + 1 < b.count else { return nil }
        let tag = b[offset]
        var idx = offset + 1
        let first = b[idx]; idx += 1
        var length = 0
        if first & 0x80 == 0 {
            length = Int(first)
        } else {
            let count = Int(first & 0x7F)
            guard count > 0, count <= 4, idx + count <= b.count else { return nil }
            for _ in 0..<count { length = (length << 8) | Int(b[idx]); idx += 1 }
        }
        guard length >= 0, idx + length <= b.count else { return nil }
        return TLV(tag: tag, contentOffset: idx, length: length, next: idx + length)
    }
}
