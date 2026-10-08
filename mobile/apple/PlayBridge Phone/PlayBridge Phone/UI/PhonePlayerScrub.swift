import CoreGraphics
import Foundation

/// Pure swipe-to-scrub rules for the phone player. A full-width drag seeks
/// `min(duration, fullWidthSeconds)`, clamped to the media. UIKit only asks
/// this type whether a drag may begin, what it previews, and whether release
/// should seek.
struct PhonePlayerScrub {
    static let minimumDistance: CGFloat = 16
    static let edgeMargin: CGFloat = 32
    static let homeIndicatorMargin: CGFloat = 44
    static let fullWidthSeconds: Double = 100
    static let tickStep: Double = 2

    struct Gate: Equatable {
        var duration: Double
        var locked: Bool
        var voiceOver: Bool
    }

    struct Preview: Equatable {
        var offset: Double
        var target: Double
        var offsetLabel: String
        var timeLabel: String
    }

    enum Decision: Equatable {
        case ignore
        case pending
        case scrub(Preview)
    }

    static func isEnabled(_ gate: Gate) -> Bool {
        gate.duration > 0 && gate.duration.isFinite && !gate.locked && !gate.voiceOver
    }

    /// Left/right edges and the bottom home-indicator band must not start a scrub.
    static func ignoredStart(_ start: CGPoint, in size: CGSize) -> Bool {
        guard size.width > 1, size.height > 1, start.x.isFinite, start.y.isFinite else { return true }
        if start.x < edgeMargin || start.x > size.width - edgeMargin { return true }
        if start.y < 0 || start.y > size.height - homeIndicatorMargin { return true }
        return false
    }

    static func span(duration: Double) -> Double {
        guard duration.isFinite, duration > 0 else { return 0 }
        return min(duration, fullWidthSeconds)
    }

    /// Signed seconds for a horizontal drag. Positive moves forward.
    static func offset(translationX: CGFloat, viewWidth: CGFloat, duration: Double) -> Double {
        guard translationX.isFinite, viewWidth > 1, duration > 0, duration.isFinite else { return 0 }
        return Double(translationX / viewWidth) * span(duration: duration)
    }

    static func clampedTarget(position: Double, offset: Double, duration: Double) -> Double {
        guard duration > 0, duration.isFinite else { return 0 }
        let origin = position.isFinite ? min(max(0, position), duration) : 0
        let delta = offset.isFinite ? offset : 0
        return min(max(0, origin + delta), duration)
    }

    static func preview(translationX: CGFloat, viewWidth: CGFloat, position: Double, duration: Double) -> Preview? {
        guard duration > 0, duration.isFinite, viewWidth > 1, translationX.isFinite else { return nil }
        let origin = position.isFinite ? min(max(0, position), duration) : 0
        let target = clampedTarget(position: origin, offset: offset(translationX: translationX, viewWidth: viewWidth, duration: duration), duration: duration)
        let applied = target - origin
        return Preview(offset: applied, target: target, offsetLabel: formatSigned(applied), timeLabel: "\(formatClock(target)) / \(formatClock(duration))")
    }

    /// `ignore` fails the gesture (no seek). `pending` has not won horizontal dominance yet.
    static func decide(start: CGPoint, translation: CGSize, viewSize: CGSize, position: Double, gate: Gate) -> Decision {
        guard translation.width.isFinite, translation.height.isFinite else { return .ignore }
        guard isEnabled(gate), !ignoredStart(start, in: viewSize) else { return .ignore }
        let dx = abs(translation.width), dy = abs(translation.height)
        if dy >= minimumDistance && dy > dx { return .ignore }
        guard dx >= minimumDistance, dx > dy else { return .pending }
        guard let preview = preview(translationX: translation.width, viewWidth: viewSize.width, position: position, duration: gate.duration) else { return .ignore }
        return .scrub(preview)
    }

    /// Release seeks only when the drag had already qualified. Cancellation does not.
    static func commit(_ preview: Preview?, ended: Bool) -> Preview? { ended ? preview : nil }

    enum ClampEdge: Equatable { case start, end }

    /// 0 while |offset| < 2, then ±1, ±2, … Truncates toward zero so a reversal reticks at the same boundaries.
    static func tickBucket(offset: Double) -> Int {
        guard offset.isFinite, tickStep > 0 else { return 0 }
        return Int((offset / tickStep).rounded(.towardZero))
    }

    static func clampEdge(target: Double, duration: Double) -> ClampEdge? {
        guard duration > 0, duration.isFinite, target.isFinite else { return nil }
        if target <= 0 { return .start }
        if target >= duration { return .end }
        return nil
    }

    /// Non-nil only on the sample that first arrives at 0 or duration. Stays nil while pinned, and again after leaving until the next arrival.
    static func clampEdgeArrival(target: Double, duration: Double, held: ClampEdge?) -> ClampEdge? {
        guard let edge = clampEdge(target: target, duration: duration), edge != held else { return nil }
        return edge
    }

    /// Selection ticks for boundaries crossed since the last sample. Zero while held on a clamp, even if a later sample would otherwise move buckets.
    static func hapticTicks(previousBucket: Int, offset: Double, target: Double, duration: Double, heldClamp: ClampEdge?) -> Int {
        if let edge = clampEdge(target: target, duration: duration), edge == heldClamp { return 0 }
        return abs(tickBucket(offset: offset) - previousBucket)
    }

    static func formatClock(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let value = Int(max(0, min(seconds, 360_000)))
        return value >= 3600
            ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
            : String(format: "%d:%02d", value / 60, value % 60)
    }

    static func formatSigned(_ seconds: Double) -> String {
        let whole = Int(seconds.isFinite ? seconds : 0)
        return (whole < 0 ? "-" : "+") + formatClock(Double(abs(whole)))
    }
}
