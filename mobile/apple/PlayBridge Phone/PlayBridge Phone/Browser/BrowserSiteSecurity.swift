// SPDX-License-Identifier: MPL-2.0
import Foundation
import Security

enum BrowserConnectionSecurity: Equatable {
    case unavailable, checking, secure, notSecure, mixedContent

    static func derive(url: URL?, isHome: Bool, trustValid: Bool?, onlySecureContent: Bool) -> Self {
        guard !isHome else { return .unavailable }
        switch url?.scheme?.lowercased() {
        case "http": return .notSecure
        case "https":
            guard let trustValid else { return .checking }
            guard trustValid else { return .notSecure }
            return onlySecureContent ? .secure : .mixedContent
        default: return .unavailable
        }
    }

    var label: String {
        switch self {
        case .unavailable: return "Connection information unavailable"
        case .checking: return "Checking connection security"
        case .secure: return "Connection is secure"
        case .notSecure, .mixedContent: return "Connection is not secure"
        }
    }
    var symbol: String {
        switch self {
        case .secure: return "lock.fill"
        case .notSecure: return "lock.slash"
        case .mixedContent: return "exclamationmark.triangle"
        case .checking, .unavailable: return "info.circle"
        }
    }
}

struct BrowserCertificateSummary: Equatable {
    let subject: String?
    let issuer: String?
    let validFrom: Date?
    let validUntil: Date?

    /// Uses public Security APIs only. Date accessors require iOS 18; older phones
    /// still show the subject/issuer without pretending those dates were obtained.
    static func copy(from trust: SecTrust) -> Self? {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else { return nil }
        let subject = SecCertificateCopySubjectSummary(leaf) as String?
        let issuer: String?
        if chain.count > 1 { issuer = SecCertificateCopySubjectSummary(chain[1]) as String? }
        else if SecCertificateCopyNormalizedIssuerSequence(leaf) == SecCertificateCopyNormalizedSubjectSequence(leaf) {
            issuer = subject
        } else { issuer = nil }
        var start: Date?, end: Date?
        if #available(iOS 18.0, macOS 15.0, *) {
            start = SecCertificateCopyNotValidBeforeDate(leaf) as Date?
            end = SecCertificateCopyNotValidAfterDate(leaf) as Date?
        }
        return Self(subject: subject, issuer: issuer, validFrom: start, validUntil: end)
    }
}

struct BrowserSiteSecurity: Equatable {
    var url: URL?
    var connection: BrowserConnectionSecurity = .unavailable
    var certificate: BrowserCertificateSummary?
}
