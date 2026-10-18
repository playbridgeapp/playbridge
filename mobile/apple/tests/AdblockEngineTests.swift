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
        print("PASS: adblock migration, profile/includes, native matching/types/source, cache rebuild, stable chunk JSON and popup subset")
    }
}
