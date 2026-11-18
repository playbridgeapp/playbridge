import Foundation

/// One playback report from whatever is playing: a PlayBridge TV, Google Cast, DLNA, the browser
/// receiver or AirPlay. All of them arrive as the coordinator's `TvPlaybackStatus`.
struct PlaybackSample: Equatable {
    enum State: Equatable { case playing, paused, buffering, stopped, error, idle }
    var state: State
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var isLive = false
    var title: String?
    var playlistIndex: Int?
    var playlistCount: Int?

    static func state(_ name: String) -> State {
        switch name {
        case "playing": return .playing
        case "paused": return .paused
        case "buffering", "loading": return .buffering
        case "stopped", "ended": return .stopped
        case "error": return .error
        default: return .idle
        }
    }
}

/// What a tracked cast has reached, as saved in its history entry.
struct CastProgress: Codable, Equatable {
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var positionReported = false
    var finished = false
    var live = false
    var liveWatchedMs: Int64 = 0
    var failed = false
    var failureReason: String?
    var playlistIndex: Int?
    var playlistCount: Int?
}

/// The cast playing on a receiver now, with its latest position.
struct NowPlaying: Equatable {
    let id: UUID
    let positionMs: Int64
    let durationMs: Int64
    let live: Bool
    let paused: Bool
}

/// Turns playback reports into progress for exactly one history entry. Port of Android's
/// `CastPositionTracker` (#545).
///
/// It attaches by the entry id given to `begin`, never by title. Reports about the cast that was
/// playing before keep arriving until the receiver says something new, so they're ignored until a
/// report shows the new cast. Pure and clock-free: callers pass the time.
struct CastPositionTracker {
    struct Write: Equatable {
        let id: UUID
        let progress: CastProgress
    }

    static let writeInterval: Int64 = 10_000
    static let staleGrace: Int64 = 8_000
    static let maxLiveGap: Int64 = 15_000
    static let failStoppedEarly = "stopped_before_playback"
    static let failReceiverError = "receiver_error"
    static let failNotSent = "not_sent"

    /// Within 5% of the end, or under 10 seconds left on anything a minute or longer.
    static func reachedEnd(positionMs: Int64, durationMs: Int64) -> Bool {
        durationMs > 0 && (positionMs * 100 >= durationMs * 95 || (durationMs >= 60_000 && durationMs - positionMs < 10_000))
    }

    private(set) var activeID: UUID?
    private var expectedTitle: String?
    private var beginAt: Int64 = 0
    private var baseline: PlaybackSample?
    private var awaitingFresh = false
    private var cleared = false
    private var lastSeen: PlaybackSample?
    private var progress = CastProgress()
    private var everPlayed = false
    private var lastPlayAt: Int64?
    private var lastWriteAt: Int64?
    private var lastWritten: CastProgress?
    private var lastWrittenState: PlaybackSample.State?
    private var lastState: PlaybackSample.State?
    /// A resumed cast's starting point: until the receiver reaches it (or 30 s pass), earlier
    /// positions are the receiver loading from 0 before it seeks, not where the user is.
    private var resumeFloor: Int64?
    static let resumeGrace: Int64 = 30_000

    /// Starts tracking `id`, returning the previous entry's unsaved progress so a quick second
    /// cast doesn't lose the first one's position.
    mutating func begin(_ id: UUID, at now: Int64, title: String?, playlistIndex: Int?, playlistCount: Int?,
                        resumeFrom: CastProgress? = nil) -> Write? {
        let pending = flushPending()
        activeID = id
        expectedTitle = title.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        beginAt = now
        baseline = lastSeen
        awaitingFresh = true
        cleared = false
        progress = CastProgress(playlistIndex: playlistIndex, playlistCount: playlistCount)
        resumeFloor = nil
        if let resumed = resumeFrom, resumed.positionMs > 0 {
            progress.positionMs = resumed.positionMs
            progress.durationMs = resumed.durationMs
            progress.positionReported = resumed.positionReported
            resumeFloor = resumed.positionMs
        }
        everPlayed = false
        lastPlayAt = nil
        lastWriteAt = nil
        lastWritten = progress
        lastWrittenState = nil
        lastState = nil
        return pending
    }

    /// The cast playing now, or nil when nothing is or its first report hasn't arrived.
    var nowPlaying: NowPlaying? {
        guard let id = activeID, !awaitingFresh, let state = lastState,
              [.playing, .paused, .buffering].contains(state) else { return nil }
        return NowPlaying(id: id, positionMs: progress.positionMs, durationMs: progress.durationMs,
                          live: progress.live, paused: state == .paused)
    }

    /// Stops tracking `id` (or whatever is tracked), returning its unsaved progress.
    mutating func detach(_ id: UUID? = nil) -> Write? {
        if let id, id != activeID { return nil }
        let pending = flushPending()
        activeID = nil
        return pending
    }

    /// The unsaved progress, for the app going to the background or a session ending.
    mutating func flush() -> Write? { flushPending() }

    private mutating func flushPending() -> Write? {
        guard let id = activeID, !awaitingFresh, progress != lastWritten else { return nil }
        lastWritten = progress
        return Write(id: id, progress: progress)
    }

    /// A nil `sample` means the report was cleared (idle, disconnected, link dropped).
    mutating func onSample(_ sample: PlaybackSample?, at now: Int64) -> Write? {
        if let sample { lastSeen = sample }
        guard let id = activeID else { return nil }
        if awaitingFresh {
            guard let sample else { cleared = true; return nil }
            guard isFresh(sample, now) else { return nil }
            awaitingFresh = false
        }
        apply(sample, now)
        let state = sample?.state
        let significant = state != lastWrittenState || progress.finished != lastWritten?.finished
            || progress.failed != lastWritten?.failed || progress.live != lastWritten?.live
            || progress.playlistIndex != lastWritten?.playlistIndex
            || progress.positionReported != lastWritten?.positionReported
            || state == nil || state == .stopped || state == .idle || state == .error
        let due = lastWriteAt.map { now - $0 >= Self.writeInterval } ?? true
        guard progress != lastWritten, significant || due else { return nil }
        lastWriteAt = now
        lastWritten = progress
        lastWrittenState = state
        return Write(id: id, progress: progress)
    }

    /// Whether a report's title would match the tracked cast (nil when there's nothing to compare).
    func titleMatches(_ title: String?) -> Bool? {
        guard let expected = expectedTitle, let title else { return nil }
        return title.caseInsensitiveCompare(expected) == .orderedSame
    }

    private func isFresh(_ sample: PlaybackSample, _ now: Int64) -> Bool {
        if let expected = expectedTitle, let title = sample.title, title.caseInsensitiveCompare(expected) != .orderedSame { return false }
        guard let base = baseline else { return true }
        let idle: [PlaybackSample.State] = [.stopped, .idle, .error]
        return cleared || sample.title != base.title || sample.durationMs != base.durationMs
            || (idle.contains(base.state) && !idle.contains(sample.state))
            || now - beginAt >= Self.staleGrace
    }

    private mutating func apply(_ sample: PlaybackSample?, _ now: Int64) {
        var p = progress
        if let sample, let index = sample.playlistIndex {
            if let current = p.playlistIndex, current != index {
                // A new item: its position and length are its own.
                p.positionMs = 0; p.durationMs = 0; p.finished = false
                lastPlayAt = nil
            }
            p.playlistIndex = index
            p.playlistCount = sample.playlistCount ?? p.playlistCount
        }
        if let sample {
            let playingLike = sample.state == .playing || sample.state == .paused
            if playingLike { everPlayed = true }
            let live = p.live || sample.isLive || (playingLike && sample.durationMs <= 0 && sample.positionMs > 0)
            p.live = live
            if live, sample.state == .playing, let last = lastPlayAt {
                p.liveWatchedMs += min(max(0, now - last), Self.maxLiveGap)
            }
            lastPlayAt = sample.state == .playing ? now : nil
            switch sample.state {
            case .playing, .paused, .buffering:
                if let floor = resumeFloor {
                    // Allow a little before the target: receivers land on a nearby keyframe.
                    if sample.positionMs >= floor - 15_000 || now - beginAt >= Self.resumeGrace { resumeFloor = nil }
                }
                if resumeFloor != nil {
                    if sample.durationMs > 0 { p.durationMs = sample.durationMs }
                } else if !live && (sample.positionMs > 0 || sample.durationMs > 0) {
                    p.positionMs = max(0, sample.positionMs)
                    if sample.durationMs > 0 { p.durationMs = sample.durationMs }
                    p.positionReported = p.positionReported || playingLike
                }
                if sample.state == .playing { p.failed = false; p.failureReason = nil }
            case .stopped, .idle:
                // Receivers reset the position when they stop; keep the last real one.
                if sample.durationMs > 0 && p.durationMs == 0 { p.durationMs = sample.durationMs }
                if sample.state == .stopped && !everPlayed && !p.positionReported {
                    p.failed = true; p.failureReason = Self.failStoppedEarly
                }
            case .error:
                p.failed = true; p.failureReason = Self.failReceiverError
            }
        }
        if sample?.state != .buffering { p.finished = Self.isFinished(p) }
        lastState = sample?.state
        progress = p
    }

    private static func isFinished(_ p: CastProgress) -> Bool {
        guard !p.live, p.positionReported, p.durationMs > 0 else { return false }
        if let index = p.playlistIndex, let count = p.playlistCount, index < count - 1 { return false }
        return reachedEnd(positionMs: p.positionMs, durationMs: p.durationMs)
    }
}
