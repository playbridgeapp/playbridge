import SwiftUI
import UIKit
import Combine

@MainActor enum StillWatchingGate {
    static var isPrompting = false
}

extension Notification.Name {
    static let playBridgePlaybackActivity = Notification.Name("PlayBridgePlaybackActivity")
    static let playBridgeStillWatchingPause = Notification.Name("PlayBridgeStillWatchingPause")
    static let playBridgeStillWatchingResume = Notification.Name("PlayBridgeStillWatchingResume")
    static let playBridgeStillWatchingStop = Notification.Name("PlayBridgeStillWatchingStop")
    static let playBridgeUserActivity = Notification.Name("PlayBridgeUserActivity")
}

@MainActor
final class StillWatchingController: ObservableObject {
    static let allowedThresholds = [30, 60, 90, 120, 180, 240]
    static let defaultThreshold = 90
    static let allowedResponseSeconds = [30, 60, 120, 300, 600]
    static let defaultResponseSeconds = 300

    @Published private(set) var isPrompting = false
    @Published private(set) var secondsRemaining = 300
    @Published private(set) var didExpire = false

    private var activeSeconds: TimeInterval = 0
    private var isPlaying = false
    private var isForeground = true
    private var lastTick = Date()
    private var timer: Timer?

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.tick() }
        }
    }

    deinit { timer?.invalidate() }

    var isEnabled: Bool { UserDefaults.standard.object(forKey: "still_watching_enabled") as? Bool ?? false }

    var thresholdMinutes: Int {
        let value = UserDefaults.standard.integer(forKey: "still_watching_threshold_min")
        return Self.allowedThresholds.contains(value) ? value : Self.defaultThreshold
    }

    var responseSeconds: Int {
        let value = UserDefaults.standard.integer(forKey: "still_watching_response_sec")
        return Self.allowedResponseSeconds.contains(value) ? value : Self.defaultResponseSeconds
    }

    func playbackChanged(isPlaying: Bool) {
        accumulate()
        self.isPlaying = isPlaying
    }

    func onUserActivity() {
        if isPrompting {
            continueWatching()
            return
        }
        accumulate()
        activeSeconds = 0
    }

    func foregroundChanged(_ foreground: Bool) {
        accumulate()
        isForeground = foreground
        UIApplication.shared.isIdleTimerDisabled = foreground && (isPlaying || isPrompting)
    }

    func settingsChanged() {
        accumulate()
        guard isEnabled else {
            if isPrompting { continueWatching() }
            activeSeconds = 0
            return
        }
        if activeSeconds >= TimeInterval(thresholdMinutes * 60), !isPrompting { beginPrompt() }
    }

    func continueWatching() {
        guard isPrompting else { return }
        isPrompting = false
        StillWatchingGate.isPrompting = false
        secondsRemaining = responseSeconds
        activeSeconds = 0
        lastTick = Date()
        NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
        UIApplication.shared.isIdleTimerDisabled = isForeground && isPlaying
    }

    func stopNow(_ stop: () -> Void) {
        guard isPrompting else { return }
        isPrompting = false
        StillWatchingGate.isPrompting = false
        isPlaying = false
        activeSeconds = 0
        secondsRemaining = responseSeconds
        didExpire = false
        UIApplication.shared.isIdleTimerDisabled = false
        stop()
    }

    func reset() {
        activeSeconds = 0
        isPlaying = false
        isPrompting = false
        StillWatchingGate.isPrompting = false
        secondsRemaining = responseSeconds
        didExpire = false
        lastTick = Date()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func accumulate(now: Date = Date()) {
        if isEnabled && isPlaying && isForeground && !isPrompting {
            activeSeconds += max(0, now.timeIntervalSince(lastTick))
        }
        lastTick = now
        UIApplication.shared.isIdleTimerDisabled = isForeground && (isPlaying || isPrompting)
    }

    private func tick() {
        accumulate()
        if isPrompting {
            secondsRemaining -= 1
            if secondsRemaining <= 0 { didExpire = true }
            return
        }
        if isEnabled && activeSeconds >= TimeInterval(thresholdMinutes * 60) { beginPrompt() }
    }

    private func beginPrompt() {
        isPrompting = true
        StillWatchingGate.isPrompting = true
        didExpire = false
        secondsRemaining = responseSeconds
        UIApplication.shared.isIdleTimerDisabled = isForeground
        NotificationCenter.default.post(name: .playBridgeStillWatchingPause, object: nil)
    }
}

struct StillWatchingPrompt: View {
    let secondsRemaining: Int
    let title: String?
    let onContinue: () -> Void
    @FocusState private var continueFocused: Bool

    var body: some View {
        ZStack {
            Color.black.opacity(0.72).ignoresSafeArea()
            VStack(spacing: 24) {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 64)).foregroundStyle(Theme.accent)
                Text("Are you still watching?")
                    .font(.system(size: 44, weight: .bold)).foregroundStyle(.white)
                if let title, !title.isEmpty {
                    Text(title).font(.title3).foregroundStyle(Theme.secondaryText).lineLimit(1)
                }
                Text("Stopping in \(max(0, secondsRemaining) / 60):\(String(format: "%02d", max(0, secondsRemaining) % 60))")
                    .font(.title2).foregroundStyle(Theme.secondaryText)
                Button("Continue watching", action: onContinue)
                    .focused($continueFocused)
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
            }
            .padding(60)
            .background(Color(white: 0.10), in: RoundedRectangle(cornerRadius: 28))
            .overlay(RoundedRectangle(cornerRadius: 28).stroke(Color.white.opacity(0.18)))
        }
        .onAppear { continueFocused = true }
        .onPlayPauseCommand { onContinue() }
        .onExitCommand { onContinue() }
    }
}
