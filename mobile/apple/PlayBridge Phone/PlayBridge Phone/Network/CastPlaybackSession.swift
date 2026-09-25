import Foundation

/// Receiver timing, independent of the silent audio used by the iOS renderer.
struct CastNowPlayingSnapshot: Equatable {
    var title: String
    var receiverName: String
    var position: TimeInterval
    var duration: TimeInterval
    var rate: Double
    var isLive: Bool
    var canSeek: Bool
    var isConnected: Bool
    var isAudio: Bool
}

enum CastRemoteAction {
    case play, pause, toggle, stop
    case seek(TimeInterval), skip(TimeInterval)
}

protocol CastPlaybackRendering: AnyObject {
    func render(_ snapshot: CastNowPlayingSnapshot, keepAlive: Bool)
    func clear()
}

/// Main-thread casting lifetime. Transport loss is not a media STOP. Time and
/// rendering are injected so expiry, seeking and stale-status handling can be tested.
final class CastPlaybackSession {
    static let reconnectGrace: TimeInterval = 120
    static let pausedAudioGrace: TimeInterval = 300
    static let statusGrace: TimeInterval = 120

    var onCommand: ((String) -> Bool)?
    var onReconnect: (() -> Void)?
    var onReleaseResources: (() -> Void)?
    private(set) var snapshot: CastNowPlayingSnapshot?
    private(set) var connected = false
    private let renderer: CastPlaybackRendering
    private let clock: () -> TimeInterval
    private let automaticallyTicks: Bool
    private var timer: Timer?
    private var lastStatusAt: TimeInterval = 0
    private var disconnectedAt: TimeInterval?
    private var pausedAt: TimeInterval?
    private var stoppedUntilIdle = false
    private var playbackState = ""
    private var playbackID: String?
    private var lastReconnectAt: TimeInterval = -.infinity
    private var newPlaybackGraceUntil: TimeInterval?
    private var pendingEnd = false

    init(renderer: CastPlaybackRendering, automaticallyTicks: Bool = true,
         clock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.renderer = renderer
        self.automaticallyTicks = automaticallyTicks
        self.clock = clock
    }

    var isActive: Bool { snapshot != nil }

    func connectionChanged(connected: Bool) {
        guard self.connected != connected else { return }
        self.connected = connected
        if !connected, snapshot != nil {
            if disconnectedAt == nil { disconnectedAt = clock() }
            let position = currentPosition
            snapshot?.position = position
            snapshot?.rate = 0
        }
        snapshot?.isConnected = connected
        publish()
    }

    /// An explicit new cast/play is allowed after a local STOP, even if the
    /// receiver has not yet reported its intervening idle status.
    func allowNewPlayback() { stoppedUntilIdle = false }

    /// Old idle/STOP reports can arrive while the receiver loads a replacement.
    /// Keep its new phone URLs alive until it has had a chance to report playback.
    func beginPlayback(title: String?, receiverName: String, mediaKind: String) {
        allowNewPlayback()
        newPlaybackGraceUntil = clock() + 10
        pendingEnd = false
        pausedAt = nil
        playbackState = ""
        receive(.init(state: "buffering", positionMs: 0, durationMs: 0,
                      title: title ?? "Casting to \(receiverName)"),
                receiverName: receiverName, mediaKind: mediaKind)
    }

    func receive(_ status: TvPlaybackStatus?, receiverName: String, mediaKind: String,
                 speed: Double = 1, isLive: Bool = false, canSeek: Bool = true) {
        guard connected else { return }
        guard let status else {
            if deferEndForNewPlayback() { return }
            stoppedUntilIdle = false
            end()
            return
        }
        let state = status.state.lowercased()
        if ["stopped", "idle", "ended", "finished", "error"].contains(state) {
            if deferEndForNewPlayback() { return }
            stoppedUntilIdle = false
            end()
            return
        }
        guard mediaKind != "image" else { end(releaseResources: false); return }
        guard ["playing", "paused", "buffering", "loading"].contains(state) else { return }
        if stoppedUntilIdle {
            // PlayBridge receivers can identify a genuinely different playback.
            guard let id = status.playbackId, let old = playbackID, id != old else { return }
            stoppedUntilIdle = false
        }
        let now = clock()
        pendingEnd = false
        disconnectedAt = nil
        if state == "paused" {
            if playbackState != "paused" { pausedAt = now }
        } else { pausedAt = nil }
        playbackState = state
        playbackID = status.playbackId
        let duration = max(0, Double(status.durationMs) / 1000)
        let position = max(0, Double(status.positionMs) / 1000)
        let title = status.title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayTitle = title.flatMap { value -> String? in
            guard !value.isEmpty, value.range(of: "https?://", options: [.regularExpression, .caseInsensitive]) == nil else { return nil }
            return String(value.prefix(512))
        }
        snapshot = CastNowPlayingSnapshot(
            title: displayTitle ?? (title == nil ? snapshot?.title : nil) ?? "Casting to \(receiverName)",
            receiverName: receiverName,
            position: duration > 0 ? min(position, duration) : position,
            duration: duration,
            rate: state == "playing" ? (speed.isFinite && speed > 0 ? speed : 1) : 0,
            isLive: isLive, canSeek: canSeek && duration > 0 && !isLive,
            isConnected: true, isAudio: mediaKind == "audio")
        lastStatusAt = now
        if automaticallyTicks, timer == nil {
            let timer = Timer(timeInterval: 5, repeats: true) { [weak self] _ in self?.tick() }
            self.timer = timer
            RunLoop.main.add(timer, forMode: .common)
        }
        publish()
    }

    /// Also called on foreground entry, because suspended timers can fire late.
    func tick() {
        guard snapshot != nil else { return }
        let now = clock()
        if pendingEnd, let deadline = newPlaybackGraceUntil, now >= deadline {
            stoppedUntilIdle = false
            end()
            return
        }
        if let disconnectedAt {
            guard now - disconnectedAt < Self.reconnectGrace else { end(); return }
            if now - lastReconnectAt >= 5 {
                lastReconnectAt = now
                onReconnect?()
            }
        } else if pausedAt == nil, now - lastStatusAt >= Self.statusGrace {
            // A connected socket without media status must not loop audio forever.
            end()
            return
        }
        // An intentional pause can last longer than the status grace. Keep its
        // URL leases for resume, while the renderer stops audio after pause grace.
        publish()
    }

    var currentPosition: TimeInterval {
        guard let snapshot else { return 0 }
        let position = snapshot.position + max(0, clock() - lastStatusAt) * snapshot.rate
        return snapshot.duration > 0 ? min(position, snapshot.duration) : position
    }

    @discardableResult
    func perform(_ action: CastRemoteAction) -> Bool {
        guard let snapshot, connected else { onReconnect?(); return false }
        let command: String
        switch action {
        case .play: command = "play"
        case .pause: command = "pause"
        case .toggle: command = playbackState == "paused" ? "play" : "pause"
        case .stop: command = "stop"
        case .seek(let seconds):
            guard let seek = seekCommand(seconds, snapshot: snapshot) else { return false }
            command = seek
        case .skip(let seconds):
            guard let seek = seekCommand(currentPosition + seconds, snapshot: snapshot) else { return false }
            command = seek
        }
        guard onCommand?(command) == true else { return false }
        return true
    }

    func stopLocally() {
        stoppedUntilIdle = true
        end()
    }

    func end(releaseResources: Bool = true) {
        let wasActive = snapshot != nil
        snapshot = nil
        disconnectedAt = nil
        pausedAt = nil
        playbackState = ""
        newPlaybackGraceUntil = nil
        pendingEnd = false
        timer?.invalidate()
        timer = nil
        renderer.clear()
        if releaseResources, wasActive { onReleaseResources?() }
    }

    private func publish() {
        guard var value = snapshot else { return }
        value.position = currentPosition
        let pausedTooLong = pausedAt.map { clock() - $0 >= Self.pausedAudioGrace } ?? false
        renderer.render(value, keepAlive: !pausedTooLong)
    }

    private func deferEndForNewPlayback() -> Bool {
        guard let deadline = newPlaybackGraceUntil, clock() < deadline else { return false }
        pendingEnd = true
        return true
    }

    private func seekCommand(_ seconds: TimeInterval, snapshot: CastNowPlayingSnapshot) -> String? {
        guard snapshot.canSeek, seconds.isFinite else { return nil }
        let clamped = max(0, min(seconds, snapshot.duration))
        guard let milliseconds = Int64(exactly: (clamped * 1000).rounded(.towardZero)) else { return nil }
        return "seek_to:\(milliseconds)"
    }

    deinit {
        timer?.invalidate()
        renderer.clear()
    }
}
