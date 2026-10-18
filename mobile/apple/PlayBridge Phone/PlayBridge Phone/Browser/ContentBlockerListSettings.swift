// SPDX-License-Identifier: MPL-2.0
import Foundation

enum ContentBlockerListSettings {
    static let key = "pb_adblock_filter_lists"
    static let oldFanboyURL = "https://easylist.to/easylist/fanboy-annoyances.txt"
    static let fanboyURL = "https://easylist.to/easylist/fanboy-annoyance.txt"
    static let defaultURLs = [
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
        fanboyURL
    ]

    static func cacheFilename(for url: String) -> String {
        url.components(separatedBy: CharacterSet.alphanumerics.inverted).joined(separator: "_") + ".txt"
    }

    static func load(defaults: UserDefaults, cacheDirectory: URL) -> [String] {
        // Never reuse the old download under the corrected URL. Content-hash compiled
        // lists are retired by compileAll's existing desired-ID pruning.
        try? FileManager.default.removeItem(at: cacheDirectory.appendingPathComponent(cacheFilename(for: oldFanboyURL)))
        guard let saved = defaults.stringArray(forKey: key) else { return defaultURLs }
        guard saved.contains(oldFanboyURL) else { return saved }
        var seen = Set<String>()
        let migrated = saved.map { $0 == oldFanboyURL ? fanboyURL : $0 }.filter { seen.insert($0).inserted }
        defaults.set(migrated, forKey: key)
        return migrated
    }
}
