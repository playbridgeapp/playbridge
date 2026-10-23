// SPDX-License-Identifier: MPL-2.0
import Foundation

/// A bridged page needs the cached network baseline, not the full scriptlet engine.
enum BrowserStartupReadiness {
    enum Baseline { case pending, empty, installed }

    static func canLoad(isBridgedApp: Bool, baseline: Baseline, fullRulesReady: Bool) -> Bool {
        fullRulesReady || (isBridgedApp && baseline != .pending)
    }

    /// Unstructured observers deliberately do not join the compilation task. A task
    /// group would still wait for its non-cancellable child after the timer wins.
    @MainActor static func wait(for task: Task<Void, Never>, timeoutNanoseconds: UInt64) async -> Bool {
        await withCheckedContinuation { continuation in
            let race = ReadinessRace(continuation)
            race.timer = Task { @MainActor [weak race] in
                do { try await Task.sleep(nanoseconds: timeoutNanoseconds) }
                catch { return }
                race?.finish(completed: false)
            }
            Task { @MainActor in
                await task.value
                race.finish(completed: true)
            }
        }
    }
}

@MainActor private final class ReadinessRace {
    private var continuation: CheckedContinuation<Bool, Never>?
    var timer: Task<Void, Never>?

    init(_ continuation: CheckedContinuation<Bool, Never>) { self.continuation = continuation }

    func finish(completed: Bool) {
        guard let continuation else { return }
        self.continuation = nil
        timer?.cancel()
        timer = nil
        continuation.resume(returning: completed)
    }
}
