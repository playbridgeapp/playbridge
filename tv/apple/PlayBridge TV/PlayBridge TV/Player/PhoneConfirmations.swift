import Foundation

/// Identifies the phone that originated a remote action. `name` is nil when the
/// connection has no known device name.
struct PhoneOrigin: Equatable {
    let name: String?
}

/// A phone is moving the playback position to `targetSeconds`.
struct PhoneSeek: Equatable {
    let name: String?
    let targetSeconds: Double
}

enum PhoneChangeKind: Equatable {
    case subtitles
    case audio
    case speed
    case queue
}

/// Small confirmation card for a setting or queue change initiated from a phone.
struct PhoneChangeCard: Equatable {
    let kind: PhoneChangeKind
    let title: String
    let detail: String
}

enum PhoneConfirmationTiming {
    /// Duration a phone seek strip or change card stays visible after the last event.
    static let durationSeconds: TimeInterval = 3.0
}

enum PhoneConfirmations {
    static func phoneLabel(_ name: String?) -> String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed?.isEmpty == false) ? trimmed! : "your phone"
    }

    /// "Paused from Pixel 9" / "Paused from your phone".
    static func pausedFromText(_ name: String?) -> String {
        "Paused from \(phoneLabel(name))"
    }

    /// "Pixel 9 is seeking" / "Your phone is seeking".
    static func seekingText(_ name: String?) -> String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return "\(trimmed) is seeking"
        }
        return "Your phone is seeking"
    }

    /// "Changed from Pixel 9" / "Changed from your phone".
    static func changedFromText(_ name: String?) -> String {
        "Changed from \(phoneLabel(name))"
    }

    /// "From Pixel 9" / nil when sender name is unknown.
    static func castOriginText(_ name: String?) -> String? {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else { return nil }
        return "From \(trimmed)"
    }

    /// Seek strip caption: who is seeking, plus chapter or segment title when present.
    static func phoneSeekCaption(name: String?, timelineCaption: String?) -> String {
        let base = seekingText(name)
        let extra = timelineCaption?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let extra, !extra.isEmpty {
            return "\(base) · \(extra)"
        }
        return base
    }

    static func subtitleChangeTitle(trackName: String?, off: Bool) -> String {
        if off { return "Subtitles: Off" }
        let trimmed = trackName?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return "Subtitles: \(trimmed)"
        }
        return "Subtitles changed"
    }

    static func audioChangeTitle(trackName: String?) -> String {
        let trimmed = trackName?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return "Audio: \(trimmed)"
        }
        return "Audio track changed"
    }

    static func speedChangeTitle(_ speed: Double) -> String {
        if abs(speed - 1.0) < 0.01 { return "Speed: Normal" }
        let formatted = String(format: "%.2f", speed)
            .replacingOccurrences(of: #"0+$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\.$"#, with: "", options: .regularExpression)
        return "Speed: \(formatted)×"
    }

    static func subtitleOffsetTitle(offsetMs: Int) -> String {
        if offsetMs == 0 { return "Subtitles in sync" }
        let absMs = abs(offsetMs)
        let whole = absMs / 1000
        let fracRaw = String(format: "%03d", absMs % 1000)
        let frac = fracRaw.replacingOccurrences(of: #"0+$"#, with: "", options: .regularExpression)
        let seconds = frac.isEmpty ? "\(whole) s" : "\(whole).\(frac) s"
        return "Subtitles \(seconds) \(offsetMs > 0 ? "later" : "earlier")"
    }

    static func queueAddedTitle(itemTitle: String?) -> String {
        let trimmed = itemTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return "Added to queue: \(trimmed)"
        }
        return "Added to queue"
    }

    static func queueNextTitle(itemTitle: String?) -> String {
        let trimmed = itemTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return "Playing next: \(trimmed)"
        }
        return "Playing next"
    }

    static func queuePositionTitle(itemTitle: String?, index: Int, count: Int) -> String {
        let trimmed = itemTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return "Now playing: \(trimmed)"
        }
        return "Now playing: item \(index + 1) of \(max(count, 1))"
    }

    static func card(kind: PhoneChangeKind, title: String, name: String?) -> PhoneChangeCard {
        PhoneChangeCard(kind: kind, title: title, detail: changedFromText(name))
    }
}
