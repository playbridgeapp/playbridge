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
        print("PASS: content blocker list migration")
    }
}
