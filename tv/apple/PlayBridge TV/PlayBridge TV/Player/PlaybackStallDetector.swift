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

/// FFmpeg HTTP reconnect options applied to both direct streams (`stream-lavf-o`)
/// and child HTTP contexts inside DASH/HLS demuxers (`demuxer-lavf-o`).
enum MPVLavfReconnectOptions {
    /// Deliberately omits `reconnect_at_eof` so clean EOF at the end of finite VOD streams ends normally.
    /// `reconnect_delay_max=5` + `reconnect_max_retries=3` caps each FFmpeg outage to 3 retries
    /// (`0 s + 1 s + 3 s = 4 s` total backoff sleep before failing, since `1 + 2*3 = 7 > 5`).
    static let value = "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_on_http_error=\"429,5xx\",reconnect_delay_max=5,reconnect_max_retries=3"
}

/// Detects silent mpv hangs (including frozen `time-pos` when `paused-for-cache` and `core-idle`
/// stay false) and drives bounded recovery reloads within `PlaybackStallDetector`'s timeout budget.
struct PlaybackFreezeRecovery {
    enum SampleAction: Equatable {
        case none
        case reloadAt(Double)
        case presentStall
    }

    enum EndOfFileDecision: Equatable {
        case naturalEOF
        case reloadAt(Double)
        case presentFailure
    }

    static let frozenTicksThreshold = 2
    static let stallReloadDelaySeconds = 15.0
    static let eofFreezeReloadDelaySeconds = 3.0
    static let maxRecoveriesWithoutProgress = 2
    static let maxTotalRecoveries = 6
    static let minProgressAdvanceSeconds = 2.0
    static let prematureEOFMinDurationSeconds = 15.0
    static let prematureEOFMinRemainingSeconds = 10.0
    static let prematureEOFMaxProgressRatio = 0.92

    private var stallDetector = PlaybackStallDetector()
    private(set) var lastGoodPosition = 0.0
    private var lastSampledPosition: Double?
    private var lastSampledCacheAhead: Double?
    private var frozenTicks = 0
    private var stuckSince: Double?
    private var recoveryCountAtSpot = 0
    private(set) var totalRecoveries = 0
    private var recoveryAnchorPosition: Double?
    private var episodeHadFirstFrame = false

    mutating func resetForNewItem(initialPosition: Double = 0) {
        totalRecoveries = 0
        resetForPositionChange(initialPosition: initialPosition)
    }

    /// Resets per-episode tracking on seek or pause/unpause without clearing the per-item reload cap.
    mutating func resetForPositionChange(initialPosition: Double = 0) {
        stallDetector.reset()
        lastGoodPosition = initialPosition.isFinite && initialPosition > 0 ? initialPosition : 0
        lastSampledPosition = nil
        lastSampledCacheAhead = nil
        frozenTicks = 0
        stuckSince = nil
        recoveryCountAtSpot = 0
        recoveryAnchorPosition = nil
        episodeHadFirstFrame = false
    }

    mutating func sample(
        now: Double,
        buffering: Bool,
        playbackRequested: Bool,
        hasFirstFrame: Bool,
        eofReached: Bool,
        position: Double,
        bufferedAhead: Double,
        seeking: Bool = false
    ) -> SampleAction {
        let validPos = position.isFinite && position >= 0 ? position : 0
        let inRecovery = recoveryAnchorPosition != nil
        let effectiveHasFirstFrame = hasFirstFrame || episodeHadFirstFrame

        if !playbackRequested || seeking || !now.isFinite {
            frozenTicks = 0
            lastSampledPosition = nil
            lastSampledCacheAhead = nil
            if !inRecovery {
                stuckSince = nil
            }
            _ = stallDetector.sample(
                now: now, buffering: buffering, playbackRequested: playbackRequested,
                hasFirstFrame: effectiveHasFirstFrame, position: validPos,
                bufferedAhead: bufferedAhead, seeking: seeking
            )
            return .none
        }

        if hasFirstFrame {
            episodeHadFirstFrame = true
        }

        let advancedSinceLastTick = lastSampledPosition.map { (validPos - $0) >= 0.05 } ?? false
        lastSampledPosition = validPos

        if let anchor = recoveryAnchorPosition {
            if !buffering, !eofReached, validPos >= anchor + Self.minProgressAdvanceSeconds, advancedSinceLastTick {
                lastGoodPosition = validPos
                recoveryAnchorPosition = nil
                recoveryCountAtSpot = 0
                stuckSince = nil
                frozenTicks = 0
                stallDetector.reset()
            }
        } else if hasFirstFrame && (advancedSinceLastTick || validPos > lastGoodPosition) {
            lastGoodPosition = validPos
        }

        let stillInRecovery = recoveryAnchorPosition != nil
        if advancedSinceLastTick && !buffering && !eofReached && !stillInRecovery {
            frozenTicks = 0
            stuckSince = nil
        } else if !buffering && hasFirstFrame {
            if !advancedSinceLastTick {
                frozenTicks += 1
            } else if !stillInRecovery {
                frozenTicks = 0
            }
        } else if buffering {
            frozenTicks = 0
        }

        let frozenWithoutStatus = !buffering && hasFirstFrame && frozenTicks >= Self.frozenTicksThreshold
        let cacheGrowing = !stillInRecovery && !eofReached && !frozenWithoutStatus
            && bufferedAhead.isFinite
            && (lastSampledCacheAhead.map { bufferedAhead > $0 } ?? false)
        lastSampledCacheAhead = bufferedAhead.isFinite ? bufferedAhead : nil

        let effectiveBuffering = buffering || eofReached || frozenWithoutStatus || stillInRecovery
        let reallyStuck = frozenWithoutStatus || eofReached || stillInRecovery || (buffering && !cacheGrowing)
        if reallyStuck {
            if stuckSince == nil { stuckSince = now }
        } else {
            stuckSince = nil
        }

        // Hold detector position steady during reload so 0 -> anchor seek hops do not reset the stall budget.
        let detectorPosition = stillInRecovery ? (recoveryAnchorPosition ?? lastGoodPosition) : validPos
        let detectorBuffered = stillInRecovery ? 0.0 : bufferedAhead
        if stallDetector.sample(
            now: now, buffering: effectiveBuffering, playbackRequested: playbackRequested,
            hasFirstFrame: effectiveHasFirstFrame, position: detectorPosition,
            bufferedAhead: detectorBuffered, seeking: seeking
        ) {
            return .presentStall
        }

        if reallyStuck, let since = stuckSince {
            let reloadDelay = (eofReached && hasFirstFrame) ? Self.eofFreezeReloadDelaySeconds : Self.stallReloadDelaySeconds
            if now - since >= reloadDelay * Double(recoveryCountAtSpot + 1) {
                if recoveryCountAtSpot < Self.maxRecoveriesWithoutProgress,
                   totalRecoveries < Self.maxTotalRecoveries {
                    let target = max(lastGoodPosition, validPos)
                    recoveryCountAtSpot += 1
                    totalRecoveries += 1
                    recoveryAnchorPosition = target
                    lastGoodPosition = target
                    lastSampledPosition = nil
                    lastSampledCacheAhead = nil
                    frozenTicks = 0
                    return .reloadAt(target)
                }
                if totalRecoveries >= Self.maxTotalRecoveries {
                    return .presentStall
                }
            }
        }

        return .none
    }

    mutating func evaluateEndOfFile(
        reasonIsEOF: Bool,
        duration: Double,
        position: Double,
        hadTruncationOrNetworkError: Bool = true
    ) -> EndOfFileDecision {
        guard reasonIsEOF,
              hadTruncationOrNetworkError,
              duration.isFinite, duration >= Self.prematureEOFMinDurationSeconds,
              position.isFinite, position >= 0 else {
            return .naturalEOF
        }
        let target = max(lastGoodPosition, position)
        let remaining = duration - target
        guard remaining > Self.prematureEOFMinRemainingSeconds,
              target < duration * Self.prematureEOFMaxProgressRatio else {
            return .naturalEOF
        }
        if let anchor = recoveryAnchorPosition, target >= anchor + Self.minProgressAdvanceSeconds {
            recoveryCountAtSpot = 0
        }
        guard recoveryCountAtSpot < Self.maxRecoveriesWithoutProgress,
              totalRecoveries < Self.maxTotalRecoveries else {
            return .presentFailure
        }
        recoveryCountAtSpot += 1
        totalRecoveries += 1
        recoveryAnchorPosition = target
        lastGoodPosition = target
        lastSampledPosition = nil
        frozenTicks = 0
        return .reloadAt(target)
    }
}
