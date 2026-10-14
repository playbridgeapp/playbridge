import SwiftUI

/// Session-scoped track preferences shared by both engines, so a pick made on one
/// episode carries into the next — the player views (and their underlying players) are
/// recreated per item, losing any in-player selection state. AVPlayer matches by language
/// tag, MPV by track display name (stable across episodes of the same release).
/// Reset by the WebSocket server when a new cast session starts.
final class TrackPreferences {
    static let shared = TrackPreferences()

    /// Language tag of the picked track (AVPlayer; locale id or extended language tag).
    var audioLanguage: String?
    var subtitleLanguage: String?
    /// Display name of the picked track (MPV).
    var audioName: String?
    var subtitleName: String?
    /// Explicit "subtitles off" pick — also carried forward.
    var subtitlesOff = false

    func reset() {
        audioLanguage = nil
        subtitleLanguage = nil
        audioName = nil
        subtitleName = nil
        subtitlesOff = false
    }
}

struct PlayerView: View {
    let payload: Playbridge_PlayPayload
    let onDismiss: () -> Void
    @EnvironmentObject var historyStore: HistoryStore
    @EnvironmentObject var playlistStore: PlaylistStore
    @EnvironmentObject var server: WebSocketServer
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"
    // Engine chosen via the on-screen switch this session. Takes precedence over both the
    // phone's player_mode and the stored preference, but is not persisted.
    @State private var sessionEngine: PlaybackEngine? = nil
    @State private var resumeTime: Double = 0.0
    @State private var showPlaylist: Bool = false
    @State private var playlistHighlight: Int = 0
    @State private var latestPlaybackIsPlaying = false
    @State private var mediaGeneration = 0
    @State private var engineChrome = PlayerEngineChrome()
    @State private var exitGate = PlayerRemoteExitGate()
    @State private var settingsReporter = PlayerSettingsReporter(engine: "avplayer", generation: 0)
    @ObservedObject var stillWatching: StillWatchingController
    let isPreBuffering: Bool

    init(payload: Playbridge_PlayPayload, isPreBuffering: Bool,
         stillWatching: StillWatchingController, onDismiss: @escaping () -> Void) {
        self.payload = payload
        self.isPreBuffering = isPreBuffering
        self.stillWatching = stillWatching
        self.onDismiss = onDismiss
    }

    // 1. Define focus state
    @FocusState private var isPlayerFocused: Bool

    private func handleSwitch(to target: PlaybackEngine, currentTime: Double,
                              from source: PlaybackEngine, generation: Int) {
        guard isCurrentPlayback(source, generation: generation), target != source else { return }
        let sourceIdentity = PlayerReportIdentity(engine: source.rawValue, generation: generation)
        guard let frame = settingsReporter.switchEngine(from: sourceIdentity, to: target.rawValue) else { return }
        resumeTime = currentTime.isFinite && currentTime > 0 ? currentTime : 0
        sessionEngine = target
        syncExitFence()
        emit(frame, engine: target.rawValue)
    }

    private func reportIdentity(engine: PlaybackEngine, generation: Int) -> PlayerReportIdentity {
        PlayerReportIdentity(engine: engine.rawValue, generation: generation)
    }

    private func emit(_ frame: [String: Any], engine: String) {
        guard server.currentPlayRequest != nil else { return }
        server.setPlaybackEngine(engine)
        server.broadcast(frame)
    }

    private func emitCurrent(engine: PlaybackEngine, generation: Int) {
        let identity = reportIdentity(engine: engine, generation: generation)
        guard let frame = settingsReporter.resync(identity: identity) else { return }
        emit(frame, engine: identity.engine)
    }

    private func emitLoop(_ enabled: Bool, identity: PlayerReportIdentity) -> Bool {
        guard let frame = settingsReporter.setLoop(enabled, identity: identity) else { return false }
        emit(frame, engine: identity.engine)
        return true
    }

    private func emitEnd(_ identity: PlayerReportIdentity) -> PlayerEndDecision {
        let (decision, frame) = settingsReporter.endOfFile(identity: identity)
        if let frame { emit(frame, engine: identity.engine) }
        return decision
    }

    /// Engine for this item: a manual session switch wins; otherwise honor the phone's
    /// `player_mode` ("avplayer"/"mpv"); legacy "vlc" maps to MPV.
    /// "tv"/unset/unknown fall back to the stored default.
    private func effectiveEngine(for item: Playbridge_PlayPayload) -> PlaybackEngine {
        if let session = sessionEngine { return session }
        if item.hasPlayerMode, let requested = PlaybackEngine(command: item.playerMode) {
            return requested
        }
        return PlaybackEngine(command: preferredPlayer) ?? .avplayer
    }

    private func isCurrentPlayback(_ engine: PlaybackEngine, generation: Int) -> Bool {
        mediaGeneration == generation && effectiveEngine(for: playlistStore.currentItem ?? payload) == engine
    }

    /// Initial seek for `item` (seconds): an engine-switch resume wins; otherwise honor
    /// the phone's `start_position_ms` resume point seeded from its resume store.
    private func initialSeekTime(for item: Playbridge_PlayPayload) -> Double {
        if resumeTime > 0 { return resumeTime }
        if item.hasStartPositionMs, item.startPositionMs > 0 {
            return Double(item.startPositionMs) / 1000.0
        }
        return 0
    }

    /// Consume the start position of the item at `index` once we navigate away from it,
    /// so jumping back to it later starts from the beginning (matches the other receivers).
    private func consumeStartPosition(at index: Int) {
        guard index >= 0, index < playlistStore.items.count else { return }
        if playlistStore.items[index].hasStartPositionMs {
            playlistStore.items[index].clearStartPositionMs()
        }
    }

    private func handleNext() {
        server.progressWebhook.advance()
        stillWatching.reset()
        // A player may also advance after a stream error. Completion is inferred from
        // the last position instead of marking every advance as a successful finish.
        historyStore.flushProgress()
        consumeStartPosition(at: playlistStore.currentIndex)
        if let nextRequest = playlistStore.next(), let nextURL = nextRequest.validURL {
            // Only an actual next item needs a new decoder identity. Incrementing on
            // terminal EOF briefly reloaded the old payload while dismissal rendered.
            mediaGeneration &+= 1
            syncExitFence()
            noteCurrentItem(nextRequest)
            if !nextRequest.skipHistory { historyStore.addToHistory(url: nextURL, title: nextRequest.titleOrNil, headers: nextRequest.headersOrNil) }
            resumeTime = 0
        } else {
            onDismiss()
        }
    }

    private func handleJump(to index: Int) {
        server.progressWebhook.finish(clear: false)
        stillWatching.reset()
        mediaGeneration &+= 1
        syncExitFence()
        historyStore.flushProgress()
        consumeStartPosition(at: playlistStore.currentIndex)
        if let jumpRequest = playlistStore.jumpTo(index: index), let jumpURL = jumpRequest.validURL {
            if !jumpRequest.skipHistory { historyStore.addToHistory(url: jumpURL, title: jumpRequest.titleOrNil, headers: jumpRequest.headersOrNil) }
            resumeTime = 0
            withAnimation { showPlaylist = false }
        }
        noteCurrentItem(playlistStore.currentItem ?? payload)
    }

    /// The new item is current. Publish its engine and loop before a late callback
    /// from the item that just ended can report.
    private func noteCurrentItem(_ request: Playbridge_PlayPayload) {
        let engine = effectiveEngine(for: request)
        settingsReporter.note(engine: engine.rawValue, generation: mediaGeneration)
        emitCurrent(engine: engine, generation: mediaGeneration)
    }

    private func broadcast(_ json: [String: Any], for item: Playbridge_PlayPayload) {
        server.broadcast(json)
        if !isPreBuffering, !item.hasProgressIdentity { server.progressWebhook.finish(clear: false) }
        if !isPreBuffering, item.hasProgressIdentity,
           json["type"] as? String == "status",
           let position = json["position"] as? Int,
           let duration = json["duration"] as? Int {
            let identity = item.progressIdentity
            var content: [String: Any] = ["type": identity.type, "contentId": identity.contentID, "videoId": identity.videoID]
            if identity.hasSeason { content["season"] = identity.season }
            if identity.hasEpisode { content["episode"] = identity.episode }
            server.progressWebhook.sample(itemID: playlistStore.currentItemID ?? identity.videoID,
                                          content: content, state: json["state"] as? String ?? "",
                                          position: position, duration: duration)
        }
        guard !isPreBuffering, !item.skipHistory, let url = item.validURL,
              url == (playlistStore.currentItem ?? payload).validURL,
              json["type"] as? String == "status",
              let position = json["position"] as? Int,
              let duration = json["duration"] as? Int else { return }
        historyStore.updateProgress(url: url, positionMs: position, durationMs: duration)
    }

    private func syncExitFence() {
        let request = playlistStore.currentItem ?? payload
        exitGate.note(engine: effectiveEngine(for: request), generation: mediaGeneration)
    }

    /// Physical Menu and network Home/Back share this owner path. A second call
    /// while the screen is still up does not finish progress or broadcast idle again.
    private func exitPlayback() {
        guard exitGate.beginExit() else { return }
        let webhook = server.progressWebhook
        let history = historyStore
        let leave = onDismiss
        PlayerSessionExit.perform(
            finishProgress: { webhook.finish(clear: true) },
            flushHistory: { history.flushProgress() },
            leaveToIdle: leave
        )
    }

    private func handleRemoteNavigation(_ key: String) {
        let engine = exitGate.liveEngine
        let generation = exitGate.liveGeneration
        let chrome = engineChrome.snapshot().chrome(
            sessionMatches: true,
            stillWatching: stillWatching.isPrompting,
            playlist: showPlaylist
        )
        let effect = PlayerRemoteNavigation.effect(for: key, chrome: chrome)
        switch effect {
        case .ignored:
            break
        case .dismissStillWatching, .resumeStillWatching:
            stillWatching.continueWatching()
        case .dismissPlaylist:
            withAnimation { showPlaylist = false }
        case .exitPlayback:
            let webhook = server.progressWebhook
            let history = historyStore
            let leave = onDismiss
            exitGate.scheduleExit(engine: engine, generation: generation) {
                PlayerSessionExit.perform(
                    finishProgress: { webhook.finish(clear: true) },
                    flushHistory: { history.flushProgress() },
                    leaveToIdle: leave
                )
            }
        case .moveHighlight, .activateHighlight:
            if engineChrome.apply(effect) { break }
            applyPlaylistHighlight(effect)
        case .dismissSubtitleMenu, .dismissAudioMenu, .dismissEngineMenu,
             .dismissSettingsSheet, .dismissScrub, .dismissControls,
             .togglePlayback, .seekBackward, .seekForward,
             .scrubBackward, .scrubForward, .commitScrub:
            _ = engineChrome.apply(effect)
        }
    }

    private func applyPlaylistHighlight(_ effect: PlayerRemoteEffect) {
        let count = playlistStore.items.count
        switch effect {
        case .moveHighlight(let delta):
            playlistHighlight = PlayerHighlightList(count: count, index: playlistHighlight)
                .moving(delta).index
        case .activateHighlight:
            let index = PlayerHighlightList(count: count, index: playlistHighlight).moving(0).index
            guard playlistStore.items.indices.contains(index) else { return }
            playlistHighlight = index
            handleJump(to: index)
        default:
            break
        }
    }

    var body: some View {
        let fenceRequest = playlistStore.currentItem ?? payload
        let _ = exitGate.note(engine: effectiveEngine(for: fenceRequest), generation: mediaGeneration)
        let _ = settingsReporter.note(
            engine: effectiveEngine(for: fenceRequest).rawValue,
            generation: mediaGeneration)
        ZStack {
            Color.black.ignoresSafeArea()

            let currentRequest = playlistStore.currentItem ?? payload
            if let currentURL = currentRequest.validURL {
                let currentEngine = effectiveEngine(for: currentRequest)
                let currentGeneration = mediaGeneration
                if currentEngine == .mpv {
                    MPVPlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        subtitles: currentRequest.subtitlesOrNil,
                        initialTime: initialSeekTime(for: currentRequest),
                        mediaIdentity: mediaGeneration,
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        onDismiss: { if isCurrentPlayback(currentEngine, generation: currentGeneration) { handleNext() } },
                        onExit: { if isCurrentPlayback(currentEngine, generation: currentGeneration) { exitPlayback() } },
                        onSwitch: { handleSwitch(to: $0, currentTime: $1, from: currentEngine, generation: currentGeneration) },
                        onBroadcast: { if isCurrentPlayback(currentEngine, generation: currentGeneration) { broadcast($0, for: currentRequest) } },
                        onLoopChanged: { enabled, identity in emitLoop(enabled, identity: identity) },
                        onEndOfFile: { identity in emitEnd(identity) },
                        initialLoop: settingsReporter.loop,
                        engineChrome: engineChrome
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    // Stable identity: episode advances reuse the live mpv core
                    // (updateUIViewController issues `loadfile replace`) instead of
                    // paying a full mpv re-init per item. Switching engines still
                    // tears it down via the branch change.
                    .id("mpv-engine")
                } else {
                    NativePlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        subtitles: currentRequest.subtitlesOrNil,
                        initialTime: initialSeekTime(for: currentRequest),
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        onDismiss: { if isCurrentPlayback(currentEngine, generation: currentGeneration) { handleNext() } },
                        onExit: { if isCurrentPlayback(currentEngine, generation: currentGeneration) { exitPlayback() } },
                        onSwitch: { handleSwitch(to: $0, currentTime: $1, from: currentEngine, generation: currentGeneration) },
                        onBroadcast: { if isCurrentPlayback(currentEngine, generation: currentGeneration) { broadcast($0, for: currentRequest) } },
                        onLoopChanged: { enabled, identity in emitLoop(enabled, identity: identity) },
                        onEndOfFile: { identity in emitEnd(identity) },
                        initialLoop: settingsReporter.loop,
                        mediaIdentity: currentGeneration,
                        engineChrome: engineChrome
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    .id("avplayer-\(mediaGeneration)")
                    .onExitCommand { if isCurrentPlayback(currentEngine, generation: currentGeneration) { exitPlayback() } }
                }
            } else {
                // Unreachable in normal flow: WebSocketServer rejects payloads with invalid URLs
                // before publishing them, and playlist items go through the same gate. This
                // branch exists so a stray bad payload dismisses the player instead of crashing.
                let _ = print("PlayerView: invalid URL in payload, dismissing")
                Color.black.task { onDismiss() }
            }

            if showPlaylist {
                PlaylistOverlay(
                    highlightedIndex: $playlistHighlight,
                    onItemSelected: { index in
                        handleJump(to: index)
                    },
                    onDismiss: {
                        withAnimation { showPlaylist = false }
                    }
                )
                .zIndex(20)
            }

            if stillWatching.isPrompting {
                StillWatchingPrompt(
                    secondsRemaining: stillWatching.secondsRemaining,
                    title: (playlistStore.currentItem ?? payload).titleOrNil,
                    onContinue: stillWatching.continueWatching
                )
                .zIndex(100)
            }
        }
        .onAppear {
            isPlayerFocused = true
            syncExitFence()
            let request = playlistStore.currentItem ?? payload
            emitCurrent(engine: effectiveEngine(for: request), generation: mediaGeneration)
        }
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.resyncRequest)) { _ in
            let request = playlistStore.currentItem ?? payload
            emitCurrent(engine: effectiveEngine(for: request), generation: mediaGeneration)
        }
        .background(
            PlayerExitFenceSync(
                engine: effectiveEngine(for: playlistStore.currentItem ?? payload),
                generation: mediaGeneration,
                gate: exitGate
            )
        )
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.remoteKey)) { note in
            guard let key = note.userInfo?["key"] as? String else { return }
            handleRemoteNavigation(key)
        }
        .onDisappear {
            historyStore.flushProgress()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("TogglePlaylist"))) { _ in
            guard !stillWatching.isPrompting else { return }
            if !showPlaylist {
                playlistHighlight = playlistStore.currentIndex
            }
            withAnimation { showPlaylist.toggle() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .playBridgePlaybackActivity)) { note in
            guard let playing = note.userInfo?["isPlaying"] as? Bool else { return }
            latestPlaybackIsPlaying = playing
            stillWatching.playbackChanged(isPlaying: playing && !isPreBuffering)
        }
        .onChange(of: isPreBuffering) { wasPreBuffering, preBuffering in
            if wasPreBuffering && !preBuffering {
                stillWatching.playbackChanged(isPlaying: latestPlaybackIsPlaying)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .playBridgeUserActivity)) { _ in
            stillWatching.onUserActivity()
        }
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.controlCommand)) { note in
            guard stillWatching.isPrompting,
                  let command = note.userInfo?["command"] as? String else { return }
            switch command {
            case "play": stillWatching.continueWatching()
            case "stop": stillWatching.stopNow(onDismiss)
            default: break
            }
        }
        .onChange(of: stillWatching.didExpire) { _, expired in
            if expired { stillWatching.stopNow(onDismiss) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .playBridgeStillWatchingResume)) { _ in
            if stillWatching.isPrompting { stillWatching.continueWatching() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .playBridgeStillWatchingStop)) { _ in
            if stillWatching.isPrompting { stillWatching.stopNow(onDismiss) }
        }
    }
}

/// Writes the live engine and generation into the exit gate on each render so a
/// key queued for the previous item cannot close the item now on screen.
private struct PlayerExitFenceSync: View {
    let engine: PlaybackEngine
    let generation: Int
    let gate: PlayerRemoteExitGate

    var body: some View {
        Color.clear
            .onAppear { gate.note(engine: engine, generation: generation) }
            .onChange(of: engine) { _, newEngine in
                gate.note(engine: newEngine, generation: generation)
            }
            .onChange(of: generation) { _, newGeneration in
                gate.note(engine: engine, generation: newGeneration)
            }
    }
}
