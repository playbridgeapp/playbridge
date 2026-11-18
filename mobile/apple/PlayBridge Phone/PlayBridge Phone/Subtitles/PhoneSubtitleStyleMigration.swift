import Foundation

/// Carries the phone player's old subtitle look (text size and dark background, saved in
/// `PhonePlayerPreferences`) over to the shared subtitle style, once.
enum PhoneSubtitleStyleMigration {
    static let doneKey = "phone_subtitle_style_migrated_v1"

    static func migrateIfNeeded(_ defaults: UserDefaults = .standard) {
        guard !defaults.bool(forKey: doneKey) else { return }
        defaults.set(true, forKey: doneKey)
        // A style chosen since then wins.
        guard defaults.object(forKey: "subtitle_size") == nil,
              let data = defaults.data(forKey: PhonePlayerPreferences.key),
              let old = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        var style = SubtitleStylePreferences()
        if let scale = old["subtitleScale"] as? Double, scale.isFinite {
            style.size = SubtitleSizeOption.allCases.min { abs($0.scale - scale) < abs($1.scale - scale) } ?? .medium
        }
        // The old player drew a dark box unless it was turned off.
        if old["subtitleBackground"] as? Bool ?? true { style.background = .dim }
        style.save(to: defaults)
    }
}
