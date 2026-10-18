// SPDX-License-Identifier: MPL-2.0
import Foundation

enum ContentBlockerListSettings {
    static let key = "pb_adblock_filter_lists"
    static let disabledKey = "pb_adblock_disabled_lists"
    static let oldFanboyURL = "https://easylist.to/easylist/fanboy-annoyances.txt"
    static let fanboyURL = "https://easylist.to/easylist/fanboy-annoyance.txt"
    /// Formerly an always-on "built-in" list; now an ordinary default list.
    static let extraURL = "https://playbridge.app/filters/playbridge-extra.txt"
    /// The default set before the engine migration; used to recognise untouched settings.
    static let legacyDefaultURLs = [
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
        fanboyURL,
    ]
    static let defaultURLs = [extraURL] + legacyDefaultURLs + [
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&mimetype=plaintext",
        "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt"
    ]

    static func cacheFilename(for url: String) -> String {
        url.components(separatedBy: CharacterSet.alphanumerics.inverted).joined(separator: "_") + ".txt"
    }

    static func isDefault(_ url: String) -> Bool { defaultURLs.contains(url) }

    static func disabled(defaults: UserDefaults) -> Set<String> {
        Set(defaults.stringArray(forKey: disabledKey) ?? [])
    }

    static func setEnabled(_ enabled: Bool, url: String, defaults: UserDefaults) {
        var set = disabled(defaults: defaults)
        if enabled { set.remove(url) } else { set.insert(url) }
        defaults.set(set.sorted(), forKey: disabledKey)
    }

    /// Lists in the saved order that are switched on.
    static func enabled(defaults: UserDefaults, cacheDirectory: URL) -> [String] {
        let off = disabled(defaults: defaults)
        return load(defaults: defaults, cacheDirectory: cacheDirectory).filter { !off.contains($0) }
    }

    static func load(defaults: UserDefaults, cacheDirectory: URL) -> [String] {
        // Never reuse the old download under the corrected URL. Content-hash compiled
        // lists are retired by compileAll's existing desired-ID pruning.
        try? FileManager.default.removeItem(at: cacheDirectory.appendingPathComponent(cacheFilename(for: oldFanboyURL)))
        guard let saved = defaults.stringArray(forKey: key) else {
            defaults.set(true, forKey: "pb_adblock_engine_lists_v1")
            defaults.set(true, forKey: "pb_adblock_extra_in_lists_v1")
            return defaultURLs
        }
        var seen = Set<String>()
        var migrated = saved.map { $0 == oldFanboyURL ? fanboyURL : $0 }.filter { seen.insert($0).inserted }
        if !defaults.bool(forKey: "pb_adblock_engine_lists_v1") {
            // Upgrade the old complete default set, including users who added custom
            // lists. A deliberately reduced/empty set retains its exact choices.
            if legacyDefaultURLs.allSatisfy({ migrated.contains($0) }) {
                migrated += defaultURLs.filter { !seen.contains($0) }
                migrated.forEach { seen.insert($0) }
            }
            defaults.set(true, forKey: "pb_adblock_engine_lists_v1")
        }
        if !defaults.bool(forKey: "pb_adblock_extra_in_lists_v1") {
            // The PlayBridge list used to be always on outside this setting; keep it on.
            if !seen.contains(extraURL) { migrated.insert(extraURL, at: 0) }
            defaults.set(true, forKey: "pb_adblock_extra_in_lists_v1")
        }
        if migrated != saved { defaults.set(migrated, forKey: key) }
        return migrated
    }
}
