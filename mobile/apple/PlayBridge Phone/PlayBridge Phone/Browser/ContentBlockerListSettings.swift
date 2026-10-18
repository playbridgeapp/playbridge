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

    /// The only default lists switched on: ads, trackers and "turn off your ad blocker"
    /// walls. The other defaults stay listed but off; users switch them on as needed.
    static let enabledByDefaultURLs: Set<String> = [
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
    ]
    static var defaultOffURLs: [String] { defaultURLs.filter { !enabledByDefaultURLs.contains($0) } }
    static let defaultSwitchesKey = "pb_adblock_default_switches_v2"

    /// One-time, for new and existing installs: switch off every default list except the
    /// three above. Custom lists keep their state.
    static func applyDefaultSwitches(defaults: UserDefaults) {
        guard !defaults.bool(forKey: defaultSwitchesKey) else { return }
        defaults.set(disabled(defaults: defaults).union(defaultOffURLs).sorted(), forKey: disabledKey)
        defaults.set(true, forKey: defaultSwitchesKey)
    }

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
        // load() may apply the one-time default switches, so read the off set after it.
        let lists = load(defaults: defaults, cacheDirectory: cacheDirectory)
        let off = disabled(defaults: defaults)
        return lists.filter { !off.contains($0) }
    }

    static func load(defaults: UserDefaults, cacheDirectory: URL) -> [String] {
        // Never reuse the old download under the corrected URL. Content-hash compiled
        // lists are retired by compileAll's existing desired-ID pruning.
        try? FileManager.default.removeItem(at: cacheDirectory.appendingPathComponent(cacheFilename(for: oldFanboyURL)))
        applyDefaultSwitches(defaults: defaults)
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
