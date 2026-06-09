import Foundation
import WebKit

/// Ad/tracker blocking for the browser via `WKContentRuleList` — the Safari content-blocker
/// mechanism, which is the WKWebView-native stand-in for the GeckoView uBlock the Android app uses
/// (WKWebView can't run WebExtensions). Blocks network requests to known ad/tracker hosts and
/// hides common ad containers cosmetically. Coverage is a curated list, not full EasyList.
enum ContentBlocker {
    /// Bump the version suffix whenever `blockedDomains`/`cosmeticSelector` change so the
    /// compiled rule list in the store is rebuilt.
    static let identifier = "playbridge-adblock-v1"
    private static let enabledKey = "pb_adblock_enabled"

    /// Persisted toggle. Defaults to on.
    static var isEnabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    /// Compile (or fetch the cached) rule list. Returns nil on failure.
    @MainActor
    static func compile() async -> WKContentRuleList? {
        let store = WKContentRuleListStore.default()
        if let cached = try? await lookup(store) { return cached }
        let json = makeRulesJSON()
        return try? await withCheckedThrowingContinuation { cont in
            store?.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                if let error { cont.resume(throwing: error) } else { cont.resume(returning: list) }
            }
        }
    }

    @MainActor
    private static func lookup(_ store: WKContentRuleListStore?) async throws -> WKContentRuleList? {
        try await withCheckedThrowingContinuation { cont in
            store?.lookUpContentRuleList(forIdentifier: identifier) { list, error in
                if let list { cont.resume(returning: list) }
                else if let error { cont.resume(throwing: error) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    // MARK: - Rules

    private static func makeRulesJSON() -> String {
        var rules: [[String: Any]] = blockedDomains.map { domain in
            let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
            return [
                "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)", "load-type": ["third-party"]],
                "action": ["type": "block"],
            ]
        }
        // Cosmetic: hide common ad containers on every page.
        rules.append([
            "trigger": ["url-filter": ".*"],
            "action": ["type": "css-display-none", "selector": cosmeticSelector],
        ])
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let json = String(data: data, encoding: .utf8) else { return "[]" }
        return json
    }

    /// Conservative cosmetic selectors — clear ad indicators only, to avoid breaking real content.
    private static let cosmeticSelector = [
        "ins.adsbygoogle", ".adsbygoogle", "#google_ads_iframe",
        "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication\"]",
        "iframe[src*=\"adservice\"]", "iframe[id^=\"google_ads\"]",
        "[id^=\"div-gpt-ad\"]", ".ad-slot", ".ad-banner", ".advertisement",
    ].joined(separator: ", ")

    /// Curated ad/tracker/analytics hosts (matches the host or any subdomain). Network rules only
    /// fire for third-party requests, so first-party site assets aren't affected.
    private static let blockedDomains: [String] = [
        // Google ads / measurement
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "2mdn.net",
        "app-measurement.com",
        // Amazon
        "amazon-adsystem.com", "assoc-amazon.com",
        // Meta tracking
        "connect.facebook.net",
        // SSPs / DSPs / exchanges
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "casalemedia.com", "contextweb.com", "indexww.com", "bidswitch.net",
        "smartadserver.com", "gumgum.com", "sharethrough.com", "teads.tv", "yieldmo.com",
        "districtm.io", "3lift.com", "sonobi.com", "lijit.com", "sovrn.com", "spotxchange.com",
        "spotx.tv", "adform.net", "mathtag.com", "bluekai.com", "rlcdn.com", "crwdcntrl.net",
        "agkn.com", "tapad.com", "adroll.com", "stickyadstv.com",
        // Native ads
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "zergnet.com",
        // Analytics / session-recording trackers
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "moatads.com", "hotjar.com",
        "mouseflow.com", "fullstory.com", "crazyegg.com", "mixpanel.com", "segment.com",
        "segment.io", "branch.io", "appsflyer.com", "adjust.com", "kochava.com", "chartbeat.com",
        "optimizely.com", "mparticle.com", "amplitude.com", "heapanalytics.com", "clarity.ms",
        "mc.yandex.ru",
        // Mobile/web ad networks
        "adcolony.com", "applovin.com", "inmobi.com", "mopub.com", "chartboost.com", "vungle.com",
        "startappservice.com", "propellerads.com", "popads.net", "exoclick.com", "trafficjunky.com",
        "adsterra.com", "hilltopads.net", "juicyads.com", "onclkds.com",
    ]
}
