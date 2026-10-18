// SPDX-License-Identifier: MPL-2.0
import Foundation

@main struct AdblockEngineTests {
    static func main() async throws {
        let suite = "AdblockEngineTests.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: directory) }
        typealias Settings = ContentBlockerListSettings
        let oldSet = Settings.legacyDefaultURLs.map { $0 == Settings.fanboyURL ? Settings.oldFanboyURL : $0 }
        let custom = "https://example.test/custom.txt"
        defaults.set(false, forKey: "pb_adblock_enabled")
        defaults.set(oldSet + [custom, Settings.fanboyURL], forKey: Settings.key)
        let oldCache = directory.appendingPathComponent(Settings.cacheFilename(for: Settings.oldFanboyURL))
        try Data("old download".utf8).write(to: oldCache)
        let migrated = Settings.load(defaults: defaults, cacheDirectory: directory)
        precondition(Set(migrated) == Set(Settings.defaultURLs + [custom]))
        precondition(migrated.count == Settings.defaultURLs.count + 1 && !defaults.bool(forKey: "pb_adblock_enabled"))
        precondition(!FileManager.default.fileExists(atPath: oldCache.path))
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == migrated)
        defaults.set([], forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory).isEmpty)
        defaults.removeObject(forKey: "pb_adblock_engine_lists_v1")
        defaults.set([custom], forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [custom])
        defaults.removeObject(forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == Settings.defaultURLs)
        defaults.set(Array(Settings.defaultURLs.dropLast()), forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == Array(Settings.defaultURLs.dropLast()))

        // The formerly always-on PlayBridge list joins saved sets once, at the front,
        // including deliberately reduced and empty sets; it is never duplicated.
        defaults.removeObject(forKey: "pb_adblock_extra_in_lists_v1")
        defaults.set([custom], forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [Settings.extraURL, custom])
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [Settings.extraURL, custom])
        defaults.removeObject(forKey: "pb_adblock_extra_in_lists_v1")
        defaults.set([custom, Settings.extraURL], forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [custom, Settings.extraURL])
        defaults.removeObject(forKey: "pb_adblock_extra_in_lists_v1")
        defaults.set([], forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [Settings.extraURL])
        // A user who removed it after the migration keeps it removed.
        defaults.set([custom], forKey: Settings.key)
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [custom])
        // Upgrading the old default set yields the full defaults plus custom lists, extra once.
        defaults.removeObject(forKey: "pb_adblock_engine_lists_v1")
        defaults.removeObject(forKey: "pb_adblock_extra_in_lists_v1")
        defaults.set(Settings.legacyDefaultURLs + [custom], forKey: Settings.key)
        let upgraded = Settings.load(defaults: defaults, cacheDirectory: directory)
        precondition(Set(upgraded) == Set(Settings.defaultURLs + [custom]) && upgraded.count == Settings.defaultURLs.count + 1)
        precondition(Settings.isDefault(Settings.extraURL) && !Settings.isDefault(custom))

        // Switching a list off keeps it saved but out of the enabled set; on restores it.
        defaults.set([Settings.extraURL, custom], forKey: Settings.key)
        precondition(Settings.enabled(defaults: defaults, cacheDirectory: directory) == [Settings.extraURL, custom])
        Settings.setEnabled(false, url: Settings.extraURL, defaults: defaults)
        precondition(Settings.enabled(defaults: defaults, cacheDirectory: directory) == [custom])
        precondition(Settings.load(defaults: defaults, cacheDirectory: directory) == [Settings.extraURL, custom])
        Settings.setEnabled(false, url: Settings.extraURL, defaults: defaults)
        precondition(Settings.disabled(defaults: defaults) == [Settings.extraURL])
        Settings.setEnabled(true, url: Settings.extraURL, defaults: defaults)
        precondition(Settings.enabled(defaults: defaults, cacheDirectory: directory) == [Settings.extraURL, custom])
        print("CHECK: list settings migration, PlayBridge list as a normal list and per-list on/off")

        let lines = AdblockListPreparser.activeLines("""
        !#if env_safari
        safari
        !#if !env_mobile
        desktop
        !#else
        mobile
        !#endif
        !#else
        chromium
        !#endif
        !#if future_unknown
        unknown-a
        !#else
        unknown-b
        !#endif
        """)
        precondition(lines == ["safari", "mobile", "unknown-a", "unknown-b"])
        precondition(AdblockListPreparser.evaluate("ext_ublock && env_mobile") == true)
        precondition(AdblockListPreparser.evaluate("cap_html_filtering || env_firefox") == false)
        let base = URL(string: "https://filters.test/root.txt")!
        let expanded = try await AdblockListPreparser.expand(text: "!#include child.txt", url: base) { url in
            precondition(url == URL(string: "https://filters.test/child.txt")); return "||ad.test^"
        }
        precondition(expanded == "||ad.test^")
        do {
            _ = try await AdblockListPreparser.expand(text: "!#include https://evil.test/x", url: base) { _ in preconditionFailure() }
            preconditionFailure("cross-origin include accepted")
        } catch AdblockListPreparser.Failure.invalidInclude {}
        do {
            _ = try await AdblockListPreparser.expand(text: "!#include root.txt", url: base) { _ in preconditionFailure() }
            preconditionFailure("cycle accepted")
        } catch AdblockListPreparser.Failure.includeLimit {}

        let text = "||cdn.test/ads/$script\n||media.test^$media,third-party\n@@||media.test/safe$media\n||doc.test^$document"
        let engine = try NativeAdblockEngine.load(lists: [text], cached: nil)
        precondition(!engine.matches(url: "https://cdn.test/movie.mp4", source: "https://page.test", type: "media"))
        precondition(engine.matches(url: "https://media.test/movie.mp4", source: "https://page.test", type: "media"))
        precondition(!engine.matches(url: "https://media.test/safe", source: "https://page.test", type: "media"))
        precondition(!engine.matches(url: "https://media.test/movie.mp4", source: "https://media.test", type: "media"))
        precondition(engine.matches(url: "https://doc.test/", source: "https://page.test", type: "document"))
        let cached = try engine.serialize()
        let restored = try NativeAdblockEngine.load(lists: [text], cached: cached)
        precondition(restored.matches(url: "https://doc.test/", source: "https://page.test", type: "document"))
        let changed = try NativeAdblockEngine.load(lists: ["||new.test^$document"], cached: cached)
        precondition(changed.matches(url: "https://new.test/", source: "https://page.test", type: "document"))
        precondition(!changed.matches(url: "https://doc.test/", source: "https://page.test", type: "document"))
        let corrupt = try NativeAdblockEngine.load(lists: [text], cached: Data("corrupt".utf8))
        precondition(corrupt.matches(url: "https://doc.test/", source: "https://page.test", type: "document"))
        let chunks = try NativeAdblockEngine.chunks(lists: [text])
        let repeated = try NativeAdblockEngine.chunks(lists: [text])
        precondition(chunks == repeated)
        let popup = NavigationAdRules(text: "||pop.test^$popup,domain=page.test|~safe.page.test\n||off.test^$popup\n||off.test^$popup,badfilter\n||doc.test^$document")
        precondition(popup.decision(url: URL(string: "https://pop.test")!, source: URL(string: "https://page.test"), popup: true) == true)
        precondition(popup.decision(url: URL(string: "https://pop.test")!, source: URL(string: "https://safe.page.test"), popup: true) == nil)
        precondition(popup.decision(url: URL(string: "https://off.test")!, source: nil, popup: true) == nil)
        precondition(popup.decision(url: URL(string: "https://doc.test")!, source: nil, popup: false) == nil)

        let stage2Rules = [
            "example.test##+js(aopr, adsbygoogle)",
            "example.test##+js(aopw, __ad_write)",
            "example.test##+js(acs, eval, /adNeedle/)",
            "example.test##+js(acis, atob, inlineNeedle)",
            "example.test##+js(set, adConfig.enabled, false)",
            "example.test##+js(aeld, click, popMagic)",
            "example.test##+js(nostif, badTimer, 1000)",
            "example.test##+js(nosiif, badInterval, 500)",
            "example.test##+js(nowoif, /popup\\.test/)",
            "example.test##+js(json-prune, adSlots.primary, data)",
            "example.test##+js(ra, oncontextmenu, body)",
            "example.test##+js(rc, ad-locked, .article)",
            "example.test##+js(no-fetch-if, /ads-api/, emptyObj)",
            "example.test##+js(no-xhr-if, /xhr-ad/, emptyArr)",
            "example.test##.card > span:has-text(Sponsored):upward(2)",
            "example.test##body.locked:style(overflow: auto !important)",
            "example.test##.ad-box:remove()",
            "sub.example.test#@#+js(aopr, adsbygoogle)",
            "sub.example.test#@#.card > span:has-text(Sponsored):upward(2)",
            "exceptall.example.test#@#+js()",
            "youtube.com##+js(set, ytAd, false)",
            "youtube.com##.yt-ad:has-text(Ad)"
        ].joined(separator: "\n")
        let stage2Engine = try NativeAdblockEngine.load(lists: [stage2Rules], cached: nil)
        AdblockSnapshot.shared.publish(stage2Engine)
        let cosmetic = stage2Engine.cosmetic(url: "https://example.test/watch")
        precondition(cosmetic.injectedScript.contains("abortOnPropertyRead(\"adsbygoogle\")"))
        precondition(cosmetic.injectedScript.contains("abortOnPropertyWrite(\"__ad_write\")"))
        precondition(cosmetic.injectedScript.contains("abortCurrentScript(\"eval\", \"/adNeedle/\")"))
        precondition(cosmetic.injectedScript.contains("abortCurrentScript(\"atob\", \"inlineNeedle\")"))
        precondition(cosmetic.injectedScript.contains("setConstant(\"adConfig.enabled\", \"false\")"))
        precondition(cosmetic.injectedScript.contains("addEventListenerDefuser(\"click\", \"popMagic\")"))
        precondition(cosmetic.injectedScript.contains("noSetTimeoutIf(\"badTimer\", \"1000\")"))
        precondition(cosmetic.injectedScript.contains("noSetIntervalIf(\"badInterval\", \"500\")"))
        precondition(cosmetic.injectedScript.contains("noWindowOpenIf(\"/popup\\\\.test/\")"))
        precondition(cosmetic.injectedScript.contains("jsonPrune(\"adSlots.primary\", \"data\")"))
        precondition(cosmetic.injectedScript.contains("removeAttr(\"oncontextmenu\", \"body\")"))
        precondition(cosmetic.injectedScript.contains("removeClass(\"ad-locked\", \".article\")"))
        precondition(cosmetic.injectedScript.contains("preventFetch(\"/ads-api/\", \"emptyObj\")"))
        precondition(cosmetic.injectedScript.contains("preventXhr(\"/xhr-ad/\", \"emptyArr\")"))
        precondition(cosmetic.proceduralActions.count == 3)

        let subCosmetic = stage2Engine.cosmetic(url: "https://sub.example.test/watch")
        precondition(!subCosmetic.injectedScript.contains("abortOnPropertyRead(\"adsbygoogle\")"))
        precondition(subCosmetic.injectedScript.contains("setConstant(\"adConfig.enabled\", \"false\")"))
        precondition(subCosmetic.proceduralActions.count == 2)
        precondition(stage2Engine.cosmetic(url: "https://exceptall.example.test/watch").injectedScript.isEmpty)
        precondition(stage2Engine.cosmetic(url: "https://unmatched.test/watch").injectedScript.isEmpty)

        // Cached deserialize preserves scriptlet resources and procedural actions.
        let stage2Cached = try stage2Engine.serialize()
        let stage2Restored = try NativeAdblockEngine.load(lists: [stage2Rules], cached: stage2Cached)
        let restoredCosmetic = stage2Restored.cosmetic(url: "https://example.test/watch")
        precondition(restoredCosmetic.injectedScript.count == cosmetic.injectedScript.count)
        precondition(restoredCosmetic.proceduralActions == cosmetic.proceduralActions)

        // Site scripts respect global enable, bridged-app exclusion, and YouTube exemption.
        let activeScripts = AdblockSnapshot.shared.siteScripts(for: URL(string: "https://example.test/watch"), enabled: true, isBridgedApp: false)
        precondition(activeScripts.scriptlet != nil && activeScripts.cosmetic != nil)
        precondition(AdblockSnapshot.shared.siteScripts(for: URL(string: "https://example.test/watch"), enabled: false, isBridgedApp: false) == .empty)
        precondition(AdblockSnapshot.shared.siteScripts(for: URL(string: "https://example.test/watch"), enabled: true, isBridgedApp: true) == .empty)
        precondition(AdblockSnapshot.shared.siteScripts(for: URL(string: "https://www.youtube.com/watch?v=1"), enabled: true, isBridgedApp: false) == .empty)
        precondition(AdblockSnapshot.shared.siteScripts(for: URL(string: "https://unmatched.test/watch"), enabled: true, isBridgedApp: false) == .empty)

        print("PASS: adblock migration, profile/includes, native matching/types/source, cache rebuild, stable chunk JSON and popup subset")
    }
}
