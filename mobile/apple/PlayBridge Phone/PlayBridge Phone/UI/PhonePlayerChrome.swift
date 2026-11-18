import Foundation

/// Text and numbers for the phone player's controls, kept out of the views so they can be tested.
enum PhonePlayerChrome {
    /// Tapping the end time switches it between the duration and the time left; the choice is remembered.
    static let showRemainingKey = "phonePlayer.showRemaining"

    /// The line under the title: the place in a queue, then the player engine ("3 of 12 · mpv").
    static func meta(queueIndex: Int, queueCount: Int, engine: String) -> String {
        var parts: [String] = []
        if queueCount > 1 {
            parts.append("\(min(max(queueIndex, 0), queueCount - 1) + 1) of \(queueCount)")
        }
        parts.append(engine)
        return parts.joined(separator: " · ")
    }

    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let value = Int(max(0, min(seconds, 360_000)))
        return value >= 3600
            ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
            : String(format: "%d:%02d", value / 60, value % 60)
    }

    /// The end of the seek bar: the duration, or the time left with a minus sign.
    static func endLabel(position: Double, duration: Double, remaining: Bool) -> String {
        guard duration.isFinite, duration > 0 else { return "--:--" }
        guard remaining else { return clock(duration) }
        let position = position.isFinite ? min(max(0, position), duration) : 0
        return "-" + clock(duration - position)
    }

    /// The time under a finger at `x` on a seek bar `width` points wide.
    static func seekTarget(x: Double, width: Double, duration: Double) -> Double {
        guard width > 0, duration.isFinite, duration > 0, x.isFinite else { return 0 }
        return min(1, max(0, x / width)) * duration
    }

    /// How far a scrub moves from where playback is ("+1:05", "-0:30").
    static func signedOffset(target: Double, from position: Double) -> String {
        let delta = target - position
        guard delta.isFinite else { return "+0:00" }
        return (delta < 0 ? "-" : "+") + clock(abs(delta))
    }

    static func speedLabel(_ speed: Double) -> String {
        guard speed.isFinite, speed > 0 else { return "1×" }
        return String(format: "%g×", speed)
    }
}
