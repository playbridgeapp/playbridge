// SPDX-License-Identifier: MPL-2.0
import Foundation

@main struct BrowserStartupReadinessTests {
    @MainActor static func main() async {
        typealias Readiness = BrowserStartupReadiness
        for isApp in [false, true] {
            precondition(!Readiness.canLoad(isBridgedApp: isApp, baseline: .pending, fullRulesReady: false))
            precondition(Readiness.canLoad(isBridgedApp: isApp, baseline: .pending, fullRulesReady: true))
            for baseline in [Readiness.Baseline.empty, .installed] {
                precondition(Readiness.canLoad(isBridgedApp: isApp, baseline: baseline, fullRulesReady: false) == isApp)
                precondition(Readiness.canLoad(isBridgedApp: isApp, baseline: baseline, fullRulesReady: true))
            }
        }
        var didComplete = false
        let slow = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 600_000_000)
            didComplete = true
        }
        let start = ProcessInfo.processInfo.systemUptime
        let completed = await Readiness.wait(for: slow, timeoutNanoseconds: 60_000_000)
        let elapsed = ProcessInfo.processInfo.systemUptime - start
        precondition(!completed && !didComplete)
        precondition(elapsed >= 0.05 && elapsed < 0.4, "Timer must release without joining the slow task")
        await slow.value
        precondition(didComplete, "Compilation must continue after readiness releases")
        let fast = Task { @MainActor in }
        let fastCompleted = await Readiness.wait(for: fast, timeoutNanoseconds: 500_000_000)
        precondition(fastCompleted)
        // Exercise near-simultaneous completions: continuation must resume exactly once.
        for _ in 0..<20 {
            let task = Task<Void, Never> { @MainActor in try? await Task.sleep(nanoseconds: 1_000_000) }
            _ = await Readiness.wait(for: task, timeoutNanoseconds: 1_000_000)
            await task.value
        }
        print("PASS: startup readiness decisions, real timeout, background completion, exactly-once race")
        print("LAB: 60 ms readiness deadline / 600 ms compile released in \(Int(elapsed * 1000)) ms")
    }
}
