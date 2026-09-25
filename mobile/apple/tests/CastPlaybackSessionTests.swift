import Foundation

private final class Renderer: CastPlaybackRendering {
    var snapshot: CastNowPlayingSnapshot?
    var keepAlive = false
    func render(_ snapshot: CastNowPlayingSnapshot, keepAlive: Bool) {
        self.snapshot = snapshot
        self.keepAlive = keepAlive
    }
    func clear() { snapshot = nil; keepAlive = false }
}

@main struct CastPlaybackSessionChecks {
    static func main() {
        var now: TimeInterval = 0
        let renderer = Renderer()
        let session = CastPlaybackSession(renderer: renderer, automaticallyTicks: false, clock: { now })
        var released = 0
        var retries = 0
        var commands: [String] = []
        session.onReleaseResources = { released += 1 }
        session.onReconnect = { retries += 1 }
        session.onCommand = { commands.append($0); return true }
        func receive(_ state: String = "playing", position: Int64 = 10_000, duration: Int64 = 60_000,
                     id: String = "one", mediaKind: String = "video", speed: Double = 1, live: Bool = false) {
            session.receive(.init(state: state, positionMs: position, durationMs: duration, title: "Fixture", playbackId: id),
                            receiverName: "Living room", mediaKind: mediaKind, speed: speed, isLive: live)
        }
        session.connectionChanged(connected: true)
        receive(speed: 1.5)
        now = 4
        precondition(session.currentPosition == 16)
        precondition(session.perform(.skip(15)) && commands.last == "seek_to:31000")
        precondition(session.perform(.seek(-3)) && commands.last == "seek_to:0")
        precondition(session.perform(.seek(500)) && commands.last == "seek_to:60000")
        precondition(!session.perform(.seek(.nan)))
        precondition(!session.perform(.seek(.infinity)))
        precondition(session.perform(.toggle) && commands.last == "pause")
        precondition(renderer.keepAlive)

        // Transport loss freezes the displayed clock and retains phone resources.
        session.connectionChanged(connected: false)
        now = 8
        session.receive(nil, receiverName: "Living room", mediaKind: "video")
        session.tick()
        precondition(released == 0 && session.isActive && renderer.keepAlive)
        precondition(renderer.snapshot?.position == 16 && renderer.snapshot?.rate == 0)
        precondition(retries == 1)
        precondition(!session.perform(.play), "Never queue a remote command to an unknown session")
        session.connectionChanged(connected: true)
        receive(position: 30_000)
        now = 10
        precondition(session.currentPosition == 32 && released == 0)

        // Only real status resets the disconnect grace, not repeated socket readiness.
        session.connectionChanged(connected: false)
        now = 125
        session.connectionChanged(connected: true)
        session.connectionChanged(connected: false)
        now = 130
        session.tick()
        precondition(!session.isActive && !renderer.keepAlive && released == 1)

        session.connectionChanged(connected: true)
        receive()
        session.stopLocally()
        let afterStop = released
        receive()
        precondition(!session.isActive && released == afterStop, "Late status must not restart a stopped loop")
        receive(id: "two")
        precondition(session.isActive, "A different receiver playback ID may start a new session")
        session.stopLocally()
        session.allowNewPlayback()
        receive(id: "two")
        precondition(session.isActive, "Explicit new cast/play must not be fenced by the old STOP")

        // Paused status polling must not extend silent playback indefinitely.
        receive("paused")
        let pausedAt = now
        for step in 1...6 {
            now = pausedAt + Double(step * 50)
            receive("paused")
        }
        precondition(session.isActive && !renderer.keepAlive && renderer.snapshot?.rate == 0)
        let beforeLongPause = released
        now += 900
        session.tick()
        precondition(session.isActive && released == beforeLongPause,
                     "Intentional suspension after pausing must not revoke resume URLs")
        precondition(session.perform(.toggle) && commands.last == "play")
        receive()
        precondition(renderer.keepAlive)
        receive("paused")
        session.connectionChanged(connected: false)
        let releasesBeforePausedDisconnect = released
        now += CastPlaybackSession.reconnectGrace
        session.tick()
        precondition(!session.isActive && released == releasesBeforePausedDisconnect + 1,
                     "A paused cast must release its URLs after reconnect expiry")
        session.connectionChanged(connected: true)
        session.end()
        receive("paused")
        precondition(renderer.keepAlive, "A new paused session gets its own grace period")

        receive(duration: 0, mediaKind: "audio", live: true)
        precondition(renderer.snapshot?.isAudio == true && renderer.snapshot?.isLive == true)
        precondition(!session.perform(.seek(10)) && !session.perform(.skip(15)))
        let beforeImage = released
        receive(mediaKind: "image")
        precondition(!session.isActive && !renderer.keepAlive && released == beforeImage,
                     "Images must not loop audio or revoke the image's phone URL")

        receive()
        now += CastPlaybackSession.statusGrace
        session.tick()
        precondition(!session.isActive && !renderer.keepAlive, "Missing status must eventually expire")
        session.allowNewPlayback()
        receive()
        receive("stopped")
        precondition(!session.isActive && !renderer.keepAlive)

        session.beginPlayback(title: "Replacement", receiverName: "TV", mediaKind: "video")
        let beforeReplacement = released
        receive("stopped") // the previous item's delayed status
        precondition(session.isActive && released == beforeReplacement)
        now += 2
        receive(id: "replacement")
        now += 10
        session.tick()
        precondition(session.isActive && released == beforeReplacement)
        session.beginPlayback(title: "Failed load", receiverName: "TV", mediaKind: "video")
        receive("stopped")
        now += 10
        session.tick()
        precondition(!session.isActive && released == beforeReplacement + 1,
                     "A failed replacement must eventually release its resources")
        session.allowNewPlayback()
        session.receive(.init(state: "playing", positionMs: 0, durationMs: 1000,
                              title: "https://example.test/video?token=private"),
                        receiverName: "TV", mediaKind: "video")
        precondition(renderer.snapshot?.title == "Casting to TV", "URLs must not become system metadata titles")
        print("Cast session timing, controls, disconnect retention/expiry, STOP fencing, pause grace, live media and cleanup checks passed")
    }
}
