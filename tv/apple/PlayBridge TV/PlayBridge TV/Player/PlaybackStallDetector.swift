import Foundation

/// Call on the main thread with a monotonic timestamp, even while native callbacks are silent.
struct PlaybackStallDetector {
    static let startupSeconds = 30.0
    static let midPlaySeconds = 45.0
    static let sentence = "The video stopped loading"
    private var since: Double?
    private var lastPosition = 0.0
    private var lastBufferedAhead = 0.0
    private var reported = false

    mutating func reset() { since = nil; reported = false }

    mutating func sample(now: Double, buffering: Bool, playbackRequested: Bool, hasFirstFrame: Bool,
                         position: Double, bufferedAhead: Double, seeking: Bool = false) -> Bool {
        let progress = position.isFinite && position != lastPosition || bufferedAhead.isFinite && bufferedAhead > lastBufferedAhead
        lastPosition = position.isFinite ? position : 0
        lastBufferedAhead = bufferedAhead.isFinite ? bufferedAhead : 0
        if !buffering || !playbackRequested || seeking || progress { reset() }
        guard buffering, playbackRequested, !seeking, now.isFinite else { return false }
        if since == nil { since = now }
        let timeout = hasFirstFrame ? Self.midPlaySeconds : Self.startupSeconds
        if !reported, let since, now - since >= timeout { reported = true; return true }
        return false
    }
}
