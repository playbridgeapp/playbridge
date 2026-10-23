// SPDX-License-Identifier: MPL-2.0
import Foundation

@main struct BridgedAppPreloadPolicyTests {
    static func main() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let ok = BridgedAppPreloadConditions(enabled: true, lastOpenedAt: now.addingTimeInterval(-60), now: now,
            alreadyLoaded: false, online: true, expensiveNetwork: false, lowDataMode: false,
            lowPowerMode: false, recentMemoryWarning: false)
        precondition(BridgedAppPreloadPolicy.shouldPreload(ok))
        var nearLimit = ok; nearLimit.lastOpenedAt = now.addingTimeInterval(-BridgedAppPreloadPolicy.recentUse + 1)
        precondition(BridgedAppPreloadPolicy.shouldPreload(nearLimit))
        let skips: [(String, (inout BridgedAppPreloadConditions) -> Void)] = [
            ("disabled", { $0.enabled = false }),
            ("never opened", { $0.lastOpenedAt = nil }),
            ("not used in 14 days", { $0.lastOpenedAt = now.addingTimeInterval(-BridgedAppPreloadPolicy.recentUse) }),
            ("future timestamp", { $0.lastOpenedAt = now.addingTimeInterval(1) }),
            ("already loaded", { $0.alreadyLoaded = true }),
            ("offline", { $0.online = false }),
            ("cellular or hotspot", { $0.expensiveNetwork = true }),
            ("Low Data Mode", { $0.lowDataMode = true }),
            ("Low Power Mode", { $0.lowPowerMode = true }),
            ("memory warning", { $0.recentMemoryWarning = true }),
        ]
        for (name, change) in skips {
            var conditions = ok; change(&conditions)
            precondition(!BridgedAppPreloadPolicy.shouldPreload(conditions), "Must skip: \(name)")
        }
        let defaults = UserDefaults(suiteName: "preload-policy-tests-\(UUID().uuidString)")!
        precondition(BridgedAppPreloadPolicy.isEnabled(defaults), "On by default")
        defaults.set(false, forKey: BridgedAppPreloadPolicy.enabledKey)
        precondition(!BridgedAppPreloadPolicy.isEnabled(defaults))
        print("BridgedAppPreloadPolicy checks passed")
    }
}
