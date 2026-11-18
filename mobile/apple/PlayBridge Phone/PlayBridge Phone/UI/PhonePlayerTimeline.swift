import SwiftUI
import UIKit

/// The phone player's seek bar. It draws with the remote's timeline: it waves while playing,
/// is flat when paused or held, and shimmers while buffering. A drag or a tap moves to the
/// point under the finger; tapping the end time switches between the duration and the time left.
struct PhonePlayerTimeline: View {
    var position: Double
    var duration: Double
    var playing: Bool
    var buffering: Bool
    /// The time being dragged to, or nil when the drag ends.
    var onScrub: (Double?) -> Void
    var onSeek: (Double) -> Void
    var onSkip: (Double) -> Void

    @AppStorage(PhonePlayerChrome.showRemainingKey) private var showRemaining = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var target: Double?

    private var hasDuration: Bool { duration.isFinite && duration > 0 }
    private var shown: Double { target ?? position }
    private var held: Bool { target != nil }
    /// Playing grows the wave; pause, buffering and holding the thumb flatten it.
    private var waving: Bool { playing && !buffering && !held && !reduceMotion }
    /// The clock drifts the wave while playing and runs the shimmer while buffering.
    private var clockRuns: Bool { (playing || buffering) && !reduceMotion }

    var body: some View {
        VStack(spacing: 2) {
            GeometryReader { geometry in
                ZStack {
                    if hasDuration {
                        RemoteTimelineTrack(
                            fraction: min(1, max(0, shown / duration)), durationMs: duration * 1000, bufferedMs: nil,
                            chapters: [], segments: [],
                            amplitude: waving ? RemoteSeekStyle.amplitude : 0,
                            thumbRadius: held ? RemoteSeekStyle.thumbHeldRadius : RemoteSeekStyle.thumbRadius,
                            held: held, shimmer: buffering && clockRuns, clockRuns: clockRuns, played: Theme.primary
                        )
                        .animation(reduceMotion ? nil : .easeInOut(duration: waving ? RemoteSeekStyle.grow : RemoteSeekStyle.flatten), value: waving)
                        .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: held)
                        .frame(height: 4)
                    } else {
                        Capsule().fill(Color.white.opacity(0.18)).frame(height: 3)
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard hasDuration else { return }
                        if target == nil { UISelectionFeedbackGenerator().selectionChanged() }
                        let next = PhonePlayerChrome.seekTarget(x: value.location.x, width: geometry.size.width, duration: duration)
                        target = next
                        onScrub(next)
                    }
                    .onEnded { _ in
                        guard let target else { return }
                        self.target = nil
                        onScrub(nil)
                        onSeek(target)
                    })
            }
            .frame(height: 30)
            .accessibilityElement()
            .accessibilityLabel("Playback position")
            .accessibilityValue(hasDuration
                ? "\(PhonePlayerChrome.clock(position)) of \(PhonePlayerChrome.clock(duration))"
                : "Unknown length")
            .accessibilityAdjustableAction { direction in
                guard hasDuration else { return }
                onSkip(direction == .increment ? 10 : -10)
            }
            .accessibilityAction(named: "Back 10 seconds") { onSkip(-10) }
            .accessibilityAction(named: "Forward 10 seconds") { onSkip(10) }
            .accessibilityIdentifier("phone-player-timeline")

            HStack {
                Text(PhonePlayerChrome.clock(shown))
                    .accessibilityHidden(true)
                Spacer()
                Button { showRemaining.toggle() } label: {
                    Text(PhonePlayerChrome.endLabel(position: shown, duration: duration, remaining: showRemaining))
                        .padding(.vertical, 6).padding(.leading, 16)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!hasDuration)
                .accessibilityLabel(showRemaining ? "Time left" : "Duration")
                .accessibilityValue(PhonePlayerChrome.endLabel(position: shown, duration: duration, remaining: showRemaining))
                .accessibilityHint(showRemaining ? "Shows the duration instead" : "Shows the time left instead")
            }
            .font(Theme.font(size: 12)).monospacedDigit()
            .foregroundStyle(Theme.onSurfaceVariant)
        }
        .onChange(of: duration) { _ in if !hasDuration, target != nil { target = nil; onScrub(nil) } }
    }
}
