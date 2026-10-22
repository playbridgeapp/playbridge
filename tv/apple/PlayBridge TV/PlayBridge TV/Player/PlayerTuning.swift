import Foundation

enum PlayerScaling: String {
    case fit = "Fit", zoom = "Zoom", fill = "Fill"
}

/// Session values shared by both engines. Positive subtitle delay shows cues later.
struct PlayerTuning: Equatable {
    static let speedRange = 0.25...2.0
    static let subtitleOffsetRange = -120_000...120_000
    var speed = 1.0
    var subtitleOffsetMs = 0
    var scaling = PlayerScaling.fit

    enum Command: Equatable {
        case speed(Double), subtitleOffsetDelta(Int), scaling(PlayerScaling)
    }

    static func parse(_ command: String) -> Command? {
        if command.hasPrefix("speed:"), let value = Double(command.dropFirst("speed:".count)), value.isFinite {
            return .speed(value)
        }
        if command.hasPrefix("sub_offset:"), let delta = Int(command.dropFirst("sub_offset:".count)) {
            return .subtitleOffsetDelta(delta)
        }
        if command.hasPrefix("scaling:"), let mode = PlayerScaling(rawValue: String(command.dropFirst("scaling:".count))) {
            return .scaling(mode)
        }
        return nil
    }

    @discardableResult
    mutating func apply(_ command: Command) -> Bool {
        let previous = self
        switch command {
        case .speed(let value):
            guard value.isFinite else { return false }
            speed = min(max(value, Self.speedRange.lowerBound), Self.speedRange.upperBound)
        case .subtitleOffsetDelta(let delta):
            let (sum, overflow) = subtitleOffsetMs.addingReportingOverflow(delta)
            let saturated = overflow ? (delta > 0 ? Int.max : Int.min) : sum
            subtitleOffsetMs = min(max(saturated, Self.subtitleOffsetRange.lowerBound), Self.subtitleOffsetRange.upperBound)
        case .scaling(let value):
            scaling = value
        }
        return self != previous
    }

    /// Swift's numeric description is locale independent and round-trips the value.
    static func mpvNumber(_ value: Double) -> String { String(value) }
    static func mpvSubDelay(ms: Int) -> String { mpvNumber(Double(ms) / 1000) }

    static func mpvScalingProperties(_ scaling: PlayerScaling) -> [(String, String)] {
        [("video-unscaled", "no"), ("keepaspect", scaling == .fill ? "no" : "yes"),
         ("panscan", scaling == .zoom ? "1.0" : "0")]
    }

    static func overlayCueTime(position: Double, offsetMs: Int) -> Double {
        position - Double(offsetMs) / 1000
    }
}

struct PlayerSettingsAvailability: Equatable {
    var speed = false
    var scaling = false
    var subtitleOffset = false
    static let none = Self()

    static func forEngine(_ engine: String, sidecarOverlayActive: Bool) -> Self {
        switch engine {
        case "mpv": return Self(speed: true, scaling: true, subtitleOffset: true)
        case "avplayer": return Self(speed: true, scaling: true, subtitleOffset: sidecarOverlayActive)
        default: return .none
        }
    }

    func allows(_ command: PlayerTuning.Command) -> Bool {
        switch command {
        case .speed: return speed
        case .scaling: return scaling
        case .subtitleOffsetDelta: return subtitleOffset
        }
    }
}
