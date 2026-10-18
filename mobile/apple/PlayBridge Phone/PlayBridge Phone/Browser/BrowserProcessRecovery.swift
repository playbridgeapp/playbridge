// SPDX-License-Identifier: MPL-2.0
import Foundation

struct BrowserProcessRecovery {
    private(set) var needsReload = false
    private var lastAutomaticReload: TimeInterval?
    mutating func markTerminated() { needsReload = true }
    mutating func cancelPendingReload() { needsReload = false }
    /// nil: nothing pending; true: reload; false: a recent automatic retry already failed.
    mutating func takeRecovery(now: TimeInterval) -> Bool? {
        guard needsReload else { return nil }
        needsReload = false
        if let lastAutomaticReload, now - lastAutomaticReload < 10 { return false }
        lastAutomaticReload = now
        return true
    }
}
