import Combine
import MediaPlayer

/// Lock-screen and Control Center controls for a phone-player video that keeps playing in the
/// background (Settings › Phone player › When you leave the player › Keep playing). While local
/// playback owns the audio session, the TV cast's own controls are cleared and refuse commands,
/// so these are the only targets.
@MainActor final class PhoneNowPlaying {
    static let shared = PhoneNowPlaying()
    private weak var session: PlaybackSession?
    private var targets: [(MPRemoteCommand, Any)] = []
    private var updates: AnyCancellable?

    func attach(_ session: PlaybackSession) {
        guard self.session !== session else { return }
        detach()
        self.session = session
        install()
        updates = session.objectWillChange
            .throttle(for: .milliseconds(500), scheduler: DispatchQueue.main, latest: true)
            .sink { [weak self] _ in DispatchQueue.main.async { MainActor.assumeIsolated { self?.publish() } } }
        publish()
    }

    /// Only the attached session (or anyone, with nil) can take the controls away.
    func detach(_ session: PlaybackSession? = nil) {
        guard session == nil || session === self.session else { return }
        updates = nil
        for (command, target) in targets { command.removeTarget(target) }
        targets.removeAll()
        if self.session != nil { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil }
        self.session = nil
    }

    private func publish() {
        guard let session else { return }
        let state = session.mpvState
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: session.title.isEmpty ? "PlayBridge" : session.title,
            MPMediaItemPropertyArtist: "Playing on this phone",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: state.position,
            MPNowPlayingInfoPropertyPlaybackRate: session.isPlaying ? state.speed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: (session.isAudio ? MPNowPlayingInfoMediaType.audio : .video).rawValue,
        ]
        if #available(iOS 18.0, *) { info[MPNowPlayingInfoPropertyExcludeFromSuggestions] = true }
        if state.duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = state.duration }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        let center = MPRemoteCommandCenter.shared()
        center.changePlaybackPositionCommand.isEnabled = state.duration > 0
        center.skipBackwardCommand.isEnabled = state.duration > 0
        center.skipForwardCommand.isEnabled = state.duration > 0
        let queue = session.websiteQueueTitles
        center.previousTrackCommand.isEnabled = !queue.isEmpty && session.websiteQueueIndex > 0
        center.nextTrackCommand.isEnabled = session.websiteQueueIndex + 1 < queue.count
    }

    private func install() {
        let center = MPRemoteCommandCenter.shared()
        center.skipBackwardCommand.preferredIntervals = [10]
        center.skipForwardCommand.preferredIntervals = [10]
        add(center.playCommand) { session, _ in session.play() }
        add(center.pauseCommand) { session, _ in session.pause() }
        add(center.togglePlayPauseCommand) { session, _ in session.isPlaying ? session.pause() : session.play() }
        add(center.skipBackwardCommand) { session, event in session.skip(by: -((event as? MPSkipIntervalCommandEvent)?.interval ?? 10)) }
        add(center.skipForwardCommand) { session, event in session.skip(by: (event as? MPSkipIntervalCommandEvent)?.interval ?? 10) }
        add(center.changePlaybackPositionCommand) { session, event in
            if let event = event as? MPChangePlaybackPositionCommandEvent { session.seek(to: event.positionTime) }
        }
        add(center.previousTrackCommand) { session, _ in session.onWebsiteJump?(session.websiteQueueIndex - 1) }
        add(center.nextTrackCommand) { session, _ in session.onWebsiteJump?(session.websiteQueueIndex + 1) }
    }

    private func add(_ command: MPRemoteCommand, _ action: @escaping @MainActor (PlaybackSession, MPRemoteCommandEvent) -> Void) {
        command.isEnabled = true
        let target = command.addTarget { [weak self] event in
            let handle: () -> MPRemoteCommandHandlerStatus = {
                MainActor.assumeIsolated {
                    guard let session = self?.session else { return .commandFailed }
                    action(session, event)
                    self?.publish()
                    return .success
                }
            }
            return Thread.isMainThread ? handle() : DispatchQueue.main.sync(execute: handle)
        }
        targets.append((command, target))
    }
}
