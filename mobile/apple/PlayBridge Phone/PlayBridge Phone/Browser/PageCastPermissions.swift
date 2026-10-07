import Foundation
import CryptoKit
import Security

extension Notification.Name {
    static let pageCastPermissionsChanged = Notification.Name("pageCastPermissionsChanged")
}

/// Persist only exact origins, never media URLs or request credentials. Accessed
/// by browser/UI on the main thread; notifications invalidate active sessions.
final class PageCastPermissions {
    static let shared = PageCastPermissions()
    private let defaults: UserDefaults
    private let approvalsKey = "pageCast.approvedOrigins"
    private let grantsKey = "pageCast.privateOriginGrants"

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    static func origin(_ url: URL?) -> String? {
        guard let url, let parts = PageCastNetworkPolicy.components(url.absoluteString),
              let scheme = parts.scheme?.lowercased(), let host = url.host?.lowercased() else { return nil }
        let bracketed = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        let effectivePort = parts.port ?? (scheme == "https" ? 443 : 80)
        let suffix = effectivePort == (scheme == "https" ? 443 : 80) ? "" : ":\(effectivePort)"
        return "\(scheme)://\(bracketed)\(suffix)"
    }

    private func normalized(_ raw: String) -> String? {
        guard let parts = PageCastNetworkPolicy.components(raw), ["", "/"].contains(parts.path),
              parts.query == nil, parts.fragment == nil else { return nil }
        return Self.origin(parts.url)
    }

    var approvedOrigins: [String] {
        Array(Set((defaults.stringArray(forKey: approvalsKey) ?? []).compactMap(normalized))).sorted()
    }

    func isApproved(_ origin: String) -> Bool {
        guard let normalized = normalized(origin) else { return false }
        return approvedOrigins.contains(normalized)
    }

    func approve(_ origin: String) {
        guard let normalized = normalized(origin), !isApproved(normalized) else { return }
        defaults.set(Array(Set(approvedOrigins + [normalized])).sorted(), forKey: approvalsKey)
        changed("approve", origin: normalized)
    }

    func privateOrigins(for origin: String) -> Set<String> {
        guard let website = normalized(origin), let stored = grants[website] else { return [] }
        // A request is bounded to 16 grants, but a website can accumulate more
        // across separate approvals. Validate each persisted origin separately.
        return stored.reduce(into: Set<String>()) { result, entry in
            if let origins = try? PageCastRequest.parsePrivateOrigins([entry]) { result.formUnion(origins) }
        }
    }

    func approvePrivateOrigins(_ origins: Set<String>, for origin: String) {
        guard let website = normalized(origin), let normalized = try? PageCastRequest.parsePrivateOrigins(Array(origins)) else { return }
        var saved = grants
        saved[website] = privateOrigins(for: website).union(normalized).sorted()
        defaults.set(saved, forKey: grantsKey)
        changed("approve", origin: website)
    }

    func revoke(_ origin: String) {
        guard let website = normalized(origin) else { return }
        defaults.set(approvedOrigins.filter { $0 != website }, forKey: approvalsKey)
        var saved = grants
        saved.removeValue(forKey: website)
        defaults.set(saved, forKey: grantsKey)
        changed("revoke", origin: website)
    }

    func clear() {
        defaults.removeObject(forKey: approvalsKey)
        defaults.removeObject(forKey: grantsKey)
        changed("clear")
    }

    func clearPrivateOrigins() {
        defaults.removeObject(forKey: grantsKey)
        changed("privateReset")
    }

    /// Generated once. Consent resets must not rotate it, or already-disclosed per-origin ids would change.
    func installSecret() -> Data {
        let key = "pageDestination.installSecret"
        if let existing = defaults.data(forKey: key), existing.count == 32 { return existing }
        var bytes = [UInt8](repeating: 0, count: 32)
        if SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) != errSecSuccess {
            for index in bytes.indices { bytes[index] = UInt8.random(in: 0...255) }
        }
        let created = Data(bytes)
        defaults.set(created, forKey: key)
        return created
    }

    private var grants: [String: [String]] { defaults.dictionary(forKey: grantsKey) as? [String: [String]] ?? [:] }
    private func changed(_ reason: String, origin: String? = nil) {
        var info = ["reason": reason]
        if let origin { info["origin"] = origin }
        NotificationCenter.default.post(name: .pageCastPermissionsChanged, object: self, userInfo: info)
    }
}

/// Page-facing destination identity. Same construction as Android PageDestinationPrivacy:
/// HMAC-SHA256(installSecret, origin || NUL || endpointKey). Raw endpoint keys are never returned.
enum PageDestinationPrivacy {
    static let localId = "this-device"
    static let unavailableId = "unavailable"

    static func id(secret: Data, origin: String, endpointKey: String) -> String {
        var input = Data(origin.utf8)
        input.append(0)
        input.append(contentsOf: endpointKey.utf8)
        let code = HMAC<SHA256>.authenticationCode(for: input, using: SymmetricKey(data: secret))
        return code.map { String(format: "%02x", Int($0)) }.joined()
    }

    static func project(_ raw: [String: Any], origin: String, approved: Bool, secret: Data) -> [String: Any] {
        let kind = raw["kind"] ?? "native"
        let connected = raw["connected"] ?? false
        guard approved, let rawId = raw["id"] as? String, !rawId.isEmpty else {
            return ["id": NSNull(), "name": NSNull(), "kind": kind, "connected": connected]
        }
        let name = raw["name"] ?? NSNull()
        if rawId == localId || rawId == unavailableId {
            return ["id": rawId, "name": name, "kind": kind, "connected": connected]
        }
        return ["id": id(secret: secret, origin: origin, endpointKey: rawId), "name": name, "kind": kind, "connected": connected]
    }

    /// Raw endpoint keys do not match. `this-device` is the only literal id a page may send.
    static func matches(pageId: String, rawId: String, origin: String, secret: Data) -> Bool {
        if pageId == localId { return rawId == localId }
        if rawId == localId || rawId == unavailableId || rawId.isEmpty { return false }
        return pageId == id(secret: secret, origin: origin, endpointKey: rawId)
    }
}
