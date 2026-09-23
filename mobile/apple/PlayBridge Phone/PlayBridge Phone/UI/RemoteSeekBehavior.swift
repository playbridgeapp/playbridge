import Foundation

/// The two seek-bar gestures used by the Android remote: swipe for a relative
/// adjustment, or hold before dragging to place the playhead under the finger.
enum RemoteSeekBehavior {
    enum Mode { case relative, absolute }

    static let holdThreshold: TimeInterval = 0.4

    static func mode(heldFor seconds: TimeInterval) -> Mode {
        seconds >= holdThreshold ? .absolute : .relative
    }

    static func target(
        currentMs: Double,
        durationMs: Double,
        width: Double,
        fingerX: Double,
        dragX: Double,
        mode: Mode
    ) -> Double {
        guard durationMs > 0, width > 0 else { return max(0, currentMs) }
        switch mode {
        case .relative:
            let rangeMs = min(600_000, max(120_000, durationMs / 10))
            return min(durationMs, max(0, currentMs + dragX / width * rangeMs))
        case .absolute:
            let inset = 52.0
            let span = max(1, width - inset * 2)
            return min(1, max(0, (fingerX - inset) / span)) * durationMs
        }
    }

    static func signedOffset(targetMs: Double, currentMs: Double) -> String {
        let delta = targetMs - currentMs
        let magnitude = Int64(min(abs(delta), Double(Int64.max / 2)))
        return "\(delta >= 0 ? "+" : "-")\(RemoteMode.time(magnitude))"
    }
}
