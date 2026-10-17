import Foundation

enum RemoteHapticsTests {
    @MainActor static func run() async throws {
        let suite = "PlayBridge.RemoteHapticsTests.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suite)!
        defer { store.removePersistentDomain(forName: suite) }
        precondition(RemoteHapticPreferences.load(from: store) == .init())
        precondition(RemoteHapticPreferences().enabled && RemoteHapticPreferences().level == .light)
        precondition(RemoteHapticLevel.light.impact == .light)
        precondition(RemoteHapticLevel.medium.impact == .medium)
        precondition(RemoteHapticLevel.strong.impact == .heavy)

        var emitted: [RemoteHapticLevel] = []
        let haptics = RemoteHaptics(store: store, emit: { emitted.append($0) })
        precondition(RemoteHaptics.allowed == [.padStep, .seekBound, .playPause, .volume, .scrub])
        RemoteFeedback.allCases.forEach { haptics.perform($0) }
        precondition(emitted == Array(repeating: .light, count: 5))
        for key in ["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home"] {
            precondition(RemoteFeedback.key(key) == .padStep)
        }
        for key in ["volume_down", "volume_up", "mute"] { precondition(RemoteFeedback.key(key) == .volume) }
        for key in ["key_enter", "key_delete", "text:private", "menu", "power", "unknown"] {
            precondition(RemoteFeedback.key(key) == nil)
        }
        emitted.removeAll()
        RemoteHapticPreferences(enabled: true, level: .strong).save(to: store)
        precondition(RemoteHapticPreferences.load(from: store).level == .strong)
        haptics.perform(.volume)
        haptics.perform(.padStep, levelOverride: .light)
        precondition(emitted == [.strong, .light])
        precondition(!haptics.perform(.volume, actionAllowed: false))
        precondition(!haptics.perform(nil))
        precondition(emitted.count == 2)

        enum SendError: Error { case rejected, stale, failed }
        for error in [SendError.rejected, .stale, .failed] {
            do {
                try await haptics.afterSending(.padStep) { throw error }
                preconditionFailure("Failed send accepted")
            } catch {}
            precondition(emitted.count == 2)
        }
        var sent = false
        let ordered = RemoteHaptics(store: store, emit: { _ in precondition(sent) })
        try await ordered.afterSending(.padStep) { sent = true }

        RemoteHapticPreferences(enabled: false, level: .medium).save(to: store)
        precondition(RemoteHapticPreferences.load(from: store) == .init(enabled: false, level: .medium))
        // A new instance sees persisted preferences; the existing instance also reads live changes.
        let reloaded = RemoteHaptics(store: store, emit: { emitted.append($0) })
        for feedback in RemoteFeedback.allCases {
            precondition(!haptics.perform(feedback))
            precondition(!reloaded.perform(feedback, levelOverride: .light))
        }
        try await haptics.afterSending(.playPause) {}
        precondition(emitted.count == 2)
        store.set(true, forKey: RemoteHapticPreferences.enabledKey)
        store.set("invalid", forKey: RemoteHapticPreferences.levelKey)
        precondition(RemoteHapticPreferences.load(from: store).level == .light)
        reloaded.perform(.playPause)
        precondition(emitted == [.strong, .light, .light])

        func scrub(_ from: Double, _ to: Double, duration: Double = 100_000,
                   started: Bool = false, now: Double = 1, last: Double? = nil) -> RemoteFeedback? {
            RemoteScrubFeedback.feedback(previous: from, next: to, duration: duration,
                started: started, now: now, lastTick: last)
        }
        precondition(scrub(1_000, 2_000, started: true) == .scrub)
        precondition(scrub(1_000, 2_000) == nil)
        precondition(scrub(9_999, 10_000) == .scrub && scrub(10_000, 9_999) == .scrub)
        precondition(scrub(10_001, 19_999) == nil)
        precondition(scrub(9_000, 11_000, now: 0.139, last: 0.1) == nil)
        precondition(scrub(9_000, 11_000, now: 0.140, last: 0.1) == .scrub)
        precondition(scrub(9_000, 0, started: true, now: 0.101, last: 0.1) == .seekBound)
        precondition(scrub(99_000, 100_000) == .seekBound)
        precondition(scrub(0, 0) == nil && scrub(100_000, 100_000) == nil)
        precondition(scrub(5, 0, duration: 0, started: true) == nil)
        var previous = 0.0, last: Double?
        var ticks = 0
        for index in 0...8 {
            let next = Double(index + 1) * 50_000, now = Double(index) * 0.01
            if scrub(previous, next, duration: 1_000_000, started: index == 0, now: now, last: last) != nil {
                ticks += 1; last = now
            }
            previous = next
        }
        precondition(ticks == 3, "Fast scrubs emit at most once per event and per 40 ms")
        print("PASS: remote haptics allow-list, levels, persisted defaults, disabled/failed sends and scrub ticks")
    }
}
