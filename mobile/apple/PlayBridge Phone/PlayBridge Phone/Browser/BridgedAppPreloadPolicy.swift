// SPDX-License-Identifier: MPL-2.0
import Foundation

/// What decides whether the most recently opened bridged app is preloaded at launch.
struct BridgedAppPreloadConditions {
    var enabled: Bool
    var lastOpenedAt: Date?
    var now: Date
    var alreadyLoaded: Bool
    var online: Bool
    var expensiveNetwork: Bool
    var lowDataMode: Bool
    var lowPowerMode: Bool
    var recentMemoryWarning: Bool
}

/// Preload one app, the most recently opened one, so opening it from the dashboard shows a
/// live page. Skipped whenever it would cost data, battery or memory the user asked to save,
/// or when the app hasn't been used recently.
enum BridgedAppPreloadPolicy {
    static let recentUse: TimeInterval = 14 * 24 * 60 * 60
    static let enabledKey = "pb_preload_recent_app"

    static func shouldPreload(_ c: BridgedAppPreloadConditions) -> Bool {
        guard let openedAt = c.lastOpenedAt else { return false }
        let age = c.now.timeIntervalSince(openedAt)
        return c.enabled && !c.alreadyLoaded && c.online && !c.expensiveNetwork && !c.lowDataMode
            && !c.lowPowerMode && !c.recentMemoryWarning && age >= 0 && age < recentUse
    }

    /// On by default; the user can turn it off in App Settings.
    static func isEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: enabledKey) as? Bool ?? true
    }
}
