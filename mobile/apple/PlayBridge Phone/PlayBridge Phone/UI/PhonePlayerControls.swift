import Foundation
import Combine

/// Shared chrome state for the toolbar and transport controls. No view owns an
/// independent hide timer. All delayed work is cancelled on dismissal/activity.
@MainActor final class PhonePlayerControls: ObservableObject {
    @Published private(set) var visible = true
    @Published private(set) var locked = false
    @Published private(set) var unlockVisible = false
    private var playing = false
    private var buffering = false
    private var voiceOver = false
    private var suspended = false
    private var scrubbing = false
    private var sheetPresented = false
    private var hideTask: Task<Void, Never>?
    private let delay: UInt64
    /// Increments only when a reveal/schedule is requested. Surface gestures must not touch it.
    private(set) var hideGeneration = 0

    enum ChromeSource { case slider, transport, surfaceGesture
        var revealsChrome: Bool { self != .surfaceGesture }
    }

    init(hideDelayNanoseconds: UInt64 = 3_000_000_000) { delay = hideDelayNanoseconds }
    func update(playing: Bool, buffering: Bool, voiceOver: Bool) {
        let changed = self.playing != playing || self.buffering != buffering || self.voiceOver != voiceOver
        self.playing = playing; self.buffering = buffering; self.voiceOver = voiceOver
        guard changed else { return }
        if !locked && (!playing || buffering || voiceOver) { visible = true }
        schedule()
    }
    func reveal() {
        if locked { unlockVisible = true } else { visible = true }
        schedule()
    }
    /// Transport buttons and the bottom slider keep the bars alive. A swipe-scrub or
    /// double-tap seek must not reveal, hide, or extend them.
    func touchChrome(from source: ChromeSource) { if source.revealsChrome { reveal() } }
    func tap() {
        if locked { reveal() }
        else if voiceOver { reveal() }
        else { visible.toggle(); schedule() }
    }
    func lock() {
        locked = true; visible = false; unlockVisible = true
        schedule()
    }
    func unlock() {
        locked = false; unlockVisible = false; visible = true
        schedule()
    }
    func setScrubbing(_ value: Bool, source: ChromeSource = .slider) {
        guard source.revealsChrome else { return }
        scrubbing = value; if value { reveal() }; schedule()
    }
    func setSheetPresented(_ value: Bool) { sheetPresented = value; if value { reveal() }; schedule() }
    func setSuspended(_ value: Bool) { suspended = value; schedule() }
    func stop() {
        hideTask?.cancel(); hideTask = nil
        suspended = true; scrubbing = false; sheetPresented = false
    }
    private func schedule() {
        hideGeneration += 1
        hideTask?.cancel(); hideTask = nil
        guard !suspended, !voiceOver, !scrubbing, !sheetPresented,
              (locked && unlockVisible) || (!locked && visible && playing && !buffering) else { return }
        hideTask = Task { [weak self, delay] in
            do { try await Task.sleep(nanoseconds: delay) } catch { return }
            guard let self, !Task.isCancelled else { return }
            if locked { unlockVisible = false } else { visible = false }
        }
    }
    deinit { hideTask?.cancel() }
}
