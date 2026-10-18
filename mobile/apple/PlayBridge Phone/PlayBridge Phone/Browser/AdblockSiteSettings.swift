// SPDX-License-Identifier: MPL-2.0
import Foundation

/// Sites where the user turned ad blocking off. A site covers its subdomains, and a
/// leading `www.` is ignored, so `www.example.com` and `example.com` are the same site.
/// Off means no filter lists, scriptlets, procedural cosmetics or ad-redirect blocking;
/// the user's own element-picker rules still apply.
enum AdblockSiteSettings {
    static let changed = Notification.Name("PlayBridgeAdblockSitesChanged")
    static let key = "pb_adblock_disabled_sites"

    /// The site a page belongs to, or nil for non-web pages.
    static func site(for url: URL?) -> String? {
        guard let url, ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return site(host: url.host ?? "")
    }

    static func site(host: String) -> String? {
        var host = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        if host.hasPrefix("www.") { host.removeFirst(4) }
        return host.isEmpty ? nil : host
    }

    static func disabledSites(_ defaults: UserDefaults = .standard) -> [String] {
        (defaults.stringArray(forKey: key) ?? []).sorted()
    }

    static func isDisabled(host: String, defaults: UserDefaults = .standard) -> Bool {
        let host = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !host.isEmpty else { return false }
        return disabledSites(defaults).contains { host == $0 || host.hasSuffix("." + $0) }
    }

    static func isDisabled(_ url: URL?, defaults: UserDefaults = .standard) -> Bool {
        guard let host = url?.host else { return false }
        return isDisabled(host: host, defaults: defaults)
    }

    static func setDisabled(_ disabled: Bool, site: String, defaults: UserDefaults = .standard) {
        guard let site = self.site(host: site) else { return }
        var sites = Set(defaults.stringArray(forKey: key) ?? [])
        if disabled { sites.insert(site) } else { sites.remove(site) }
        defaults.set(sites.sorted(), forKey: key)
        NotificationCenter.default.post(name: changed, object: site)
    }
}
