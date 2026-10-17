import Foundation
#if canImport(UIKit)
import UIKit
#endif

enum RemoteHapticLevel: String, CaseIterable {
    case light, medium, strong
    enum Impact { case light, medium, heavy }
    var impact: Impact {
        switch self {
        case .light: return .light
        case .medium: return .medium
        case .strong: return .heavy
        }
    }
    var title: String { rawValue.capitalized }
}

enum RemoteFeedback: CaseIterable {
    case padStep, seekBound, playPause, volume, scrub, axisLock, holdStart

    static func key(_ key: String) -> Self? {
        switch key {
        case "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home": return .padStep
        case "volume_up", "volume_down", "mute": return .volume
        default: return nil
        }
    }
}

struct RemoteHapticPreferences: Equatable {
    static let enabledKey = "remote.haptics.enabled"
    static let levelKey = "remote.haptics.level"
    var enabled = true
    var level: RemoteHapticLevel = .light

    static func load(from store: UserDefaults) -> Self {
        Self(enabled: store.object(forKey: enabledKey) as? Bool ?? true,
             level: RemoteHapticLevel(rawValue: store.string(forKey: levelKey) ?? "") ?? .light)
    }
    func save(to store: UserDefaults) {
        store.set(enabled, forKey: Self.enabledKey)
        store.set(level.rawValue, forKey: Self.levelKey)
    }
}

/// One allow-list and preference gate for every remote feedback source.
/// UIKit retains control of system haptics suppression; no vibration fallback.
@MainActor final class RemoteHaptics {
    static let shared = RemoteHaptics()
    static let allowed: Set<RemoteFeedback> = [.padStep, .seekBound, .playPause, .volume, .scrub]
    private let store: UserDefaults
    private let emit: (RemoteHapticLevel) -> Void
    private let prepareGenerator: (RemoteHapticLevel) -> Void

    init(store: UserDefaults = .standard, emit: ((RemoteHapticLevel) -> Void)? = nil) {
        self.store = store
#if canImport(UIKit)
        let generators = PreparedRemoteHapticGenerators()
        self.emit = emit ?? { generators.emit($0) }
        prepareGenerator = { generators.prepare($0) }
#else
        self.emit = emit ?? { _ in }
        prepareGenerator = { _ in }
#endif
    }

    func prepare() {
        let preferences = RemoteHapticPreferences.load(from: store)
        if preferences.enabled { prepareGenerator(preferences.level) }
    }

    @discardableResult func perform(_ feedback: RemoteFeedback?, actionAllowed: Bool = true,
                                    levelOverride: RemoteHapticLevel? = nil) -> Bool {
        let preferences = RemoteHapticPreferences.load(from: store)
        guard actionAllowed, preferences.enabled, let feedback, Self.allowed.contains(feedback) else { return false }
        emit(levelOverride ?? preferences.level)
        return true
    }

    /// A rejected/stale/failed command must never click. No cross-channel retry.
    func afterSending(_ feedback: RemoteFeedback?, send: () async throws -> Void) async throws {
        try await send()
        perform(feedback)
    }
}

/// Android's ten-second scrub intervals, with one tick per event, 40 ms spacing,
/// and bounds taking priority. A fresh gesture passes a nil lastTick.
enum RemoteScrubFeedback {
    static func feedback(previous: Double, next: Double, duration: Double, started: Bool,
                         now: TimeInterval, lastTick: TimeInterval?) -> RemoteFeedback? {
        guard duration > 0, previous.isFinite, next.isFinite else { return nil }
        if next <= 0 && previous > 0 || next >= duration && previous < duration { return .seekBound }
        guard started || floor(max(0, previous) / 10_000) != floor(max(0, next) / 10_000),
              lastTick.map({ now - $0 >= 0.040 }) ?? true else { return nil }
        return .scrub
    }
}

#if canImport(UIKit)
@MainActor private final class PreparedRemoteHapticGenerators {
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let medium = UIImpactFeedbackGenerator(style: .medium)
    private let heavy = UIImpactFeedbackGenerator(style: .heavy)
    private func generator(_ level: RemoteHapticLevel) -> UIImpactFeedbackGenerator {
        switch level.impact {
        case .light: return light
        case .medium: return medium
        case .heavy: return heavy
        }
    }
    func prepare(_ level: RemoteHapticLevel) { generator(level).prepare() }
    func emit(_ level: RemoteHapticLevel) {
        let feedback = generator(level)
        feedback.impactOccurred()
        feedback.prepare()
    }
}
#endif
