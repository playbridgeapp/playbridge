// SPDX-License-Identifier: MPL-2.0
import Foundation

enum AdblockSiteSettingsModelTests {
    static func run() throws {
        let suite = "AdblockSiteSettingsModelTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        precondition(AdblockSiteSettings.site(for: URL(string: "https://WWW.Example.com/a")) == "example.com")
        precondition(AdblockSiteSettings.site(for: URL(string: "http://news.example.com.")) == "news.example.com")
        precondition(AdblockSiteSettings.site(for: URL(string: "about:blank")) == nil)
        precondition(AdblockSiteSettings.site(for: URL(string: "file:///tmp/x")) == nil)

        AdblockSiteSettings.setDisabled(true, site: "www.example.com", defaults: defaults)
        precondition(AdblockSiteSettings.disabledSites(defaults) == ["example.com"])
        precondition(AdblockSiteSettings.isDisabled(host: "example.com", defaults: defaults))
        precondition(AdblockSiteSettings.isDisabled(host: "www.example.com", defaults: defaults))
        precondition(AdblockSiteSettings.isDisabled(host: "video.example.com", defaults: defaults))
        precondition(!AdblockSiteSettings.isDisabled(host: "badexample.com", defaults: defaults), "Suffix must match on a dot boundary")
        precondition(!AdblockSiteSettings.isDisabled(host: "example.com.evil.test", defaults: defaults))
        precondition(AdblockSiteSettings.isDisabled(URL(string: "https://example.com/watch"), defaults: defaults))

        // Turning a subdomain off does not turn off its parent site.
        AdblockSiteSettings.setDisabled(true, site: "news.other.test", defaults: defaults)
        precondition(!AdblockSiteSettings.isDisabled(host: "other.test", defaults: defaults))
        precondition(AdblockSiteSettings.isDisabled(host: "news.other.test", defaults: defaults))

        AdblockSiteSettings.setDisabled(false, site: "example.com", defaults: defaults)
        precondition(!AdblockSiteSettings.isDisabled(host: "example.com", defaults: defaults))
        precondition(AdblockSiteSettings.disabledSites(defaults) == ["news.other.test"])
        print("CHECK: per-site ad blocking switch matching, www/subdomain handling and persistence")
    }
}
