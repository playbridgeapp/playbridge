// SPDX-License-Identifier: MPL-2.0
import Foundation

@main struct ContentBlockerListSettingsTests {
    static func main() throws {
        let suite = "ContentBlockerListSettingsTests.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: directory)
        }
        typealias Settings = ContentBlockerListSettings
        assert(Settings.load(defaults: defaults, cacheDirectory: directory) == Settings.defaultURLs)
        assert(defaults.object(forKey: Settings.key) == nil)
        // New installs: only EasyList, EasyPrivacy and Adblock Warning Removal are on.
        assert(Set(Settings.enabled(defaults: defaults, cacheDirectory: directory)) == Settings.enabledByDefaultURLs)
        assert(Settings.disabled(defaults: defaults) == Set(Settings.defaultOffURLs))
        // The switch-over runs once; a list the user turns back on stays on.
        Settings.setEnabled(true, url: Settings.extraURL, defaults: defaults)
        _ = Settings.load(defaults: defaults, cacheDirectory: directory)
        assert(!Settings.disabled(defaults: defaults).contains(Settings.extraURL))
        let custom = "https://example.test/custom.txt"
        for enabled in [false, true] {
            defaults.set(enabled, forKey: "pb_adblock_enabled")
            defaults.set([custom, Settings.oldFanboyURL, Settings.fanboyURL], forKey: Settings.key)
            let oldCache = directory.appendingPathComponent(Settings.cacheFilename(for: Settings.oldFanboyURL))
            try Data("old cached rules".utf8).write(to: oldCache)
            let expected = [custom, Settings.fanboyURL]
            assert(Settings.load(defaults: defaults, cacheDirectory: directory) == expected)
            assert(defaults.stringArray(forKey: Settings.key) == expected)
            assert(defaults.bool(forKey: "pb_adblock_enabled") == enabled)
            assert(!FileManager.default.fileExists(atPath: oldCache.path))
            assert(Settings.load(defaults: defaults, cacheDirectory: directory) == expected)
        }
        defaults.set([String](), forKey: Settings.key)
        assert(Settings.load(defaults: defaults, cacheDirectory: directory).isEmpty)
        defaults.set([custom], forKey: Settings.key)
        assert(Settings.load(defaults: defaults, cacheDirectory: directory) == [custom])
        // Existing installs switch to the same defaults; custom lists keep their state.
        let existing = UserDefaults(suiteName: suite + ".existing")!
        defer { existing.removePersistentDomain(forName: suite + ".existing") }
        existing.set(Settings.defaultURLs + [custom], forKey: Settings.key)
        existing.set(true, forKey: "pb_adblock_engine_lists_v1")
        existing.set(true, forKey: "pb_adblock_extra_in_lists_v1")
        let enabledAfter = Settings.enabled(defaults: existing, cacheDirectory: directory)
        assert(Set(enabledAfter) == Settings.enabledByDefaultURLs.union([custom]))
        print("PASS: content blocker list migration")
    }
}
