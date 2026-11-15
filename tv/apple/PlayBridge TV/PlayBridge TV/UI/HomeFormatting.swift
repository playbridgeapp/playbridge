import Foundation

/// Pure-Swift formatting helpers for Home, Pre-play, and Pairing corner card.
enum HomeFormatting {
    static func formatShortTime(_ ms: Int64) -> String {
        let totalSeconds = max(0, Int(ms / 1000))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    static func formatMinutes(_ ms: Int64) -> Int {
        max(1, Int((max(0, ms) + 30_000) / 60_000))
    }

    static func bookmarkSubtitle(
        positionMs: Int64?,
        durationMs: Int64?,
        resumePositionMs: Int64?
    ) -> String? {
        if let resumeMs = resumePositionMs, let durationMs, durationMs > 0 {
            let remainingMin = formatMinutes(max(0, durationMs - resumeMs))
            return "Resume at \(formatShortTime(resumeMs)) · \(remainingMin) min left"
        }
        if let durationMs, durationMs > 0 {
            return "From the start · \(formatMinutes(durationMs)) min"
        }
        if let resumeMs = resumePositionMs {
            return "Resume at \(formatShortTime(resumeMs))"
        }
        return nil
    }

    static func isFirstRun(pairedCount: Int, bookmarkCount: Int) -> Bool {
        pairedCount == 0 && bookmarkCount == 0
    }

    static func connectionSummary(connectedCount: Int, connectedNames: [String]) -> String? {
        guard connectedCount > 0 else { return nil }
        if connectedCount == 1, let first = connectedNames.first, !first.isEmpty {
            return "\(first) connected"
        }
        return connectedCount == 1 ? "1 phone connected" : "\(connectedCount) phones connected"
    }

    static func prePlayResumeInfo(
        positionMs: Int64?,
        durationMs: Int64?
    ) -> (fraction: Double, stoppedText: String, buttonTitle: String)? {
        guard let positionMs, let durationMs, durationMs > 0,
              positionMs >= 30_000, Double(positionMs) / Double(durationMs) < 0.95 else {
            return nil
        }
        let fraction = min(1.0, max(0.0, Double(positionMs) / Double(durationMs)))
        let posText = formatShortTime(positionMs)
        let durText = formatShortTime(durationMs)
        return (
            fraction: fraction,
            stoppedText: "You stopped at \(posText) of \(durText)",
            buttonTitle: "Resume from \(posText)"
        )
    }

    static func formattedSASCode(_ sas: String) -> String {
        guard sas.count == 6 else { return sas }
        let idx = sas.index(sas.startIndex, offsetBy: 3)
        return "\(sas.prefix(upTo: idx)) \(sas.suffix(from: idx))"
    }

    static func pairingExpiryText(secondsRemaining: Int) -> String {
        let clamped = max(0, secondsRemaining)
        return clamped == 1 ? "Expires in 1 second" : "Expires in \(clamped) seconds"
    }
}
