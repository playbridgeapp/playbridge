import Foundation

/// Media engines report NaN/infinity while duration is unknown. Swift's direct
/// floating-point-to-Int conversion traps for those values and for overflow.
enum PlaybackTime {
    static func milliseconds(_ seconds: Double) -> Int {
        let value = seconds * 1000
        guard value.isFinite, value > 0 else { return 0 }
        return Int(exactly: value.rounded(.towardZero)) ?? 0
    }

    static func seconds(_ value: Double) -> Int {
        guard value.isFinite, value > 0 else { return 0 }
        return Int(exactly: value.rounded(.towardZero)) ?? 0
    }
}
