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
    @AppStorage("preferredPlayer") var preferredPlayer: String = PlaybackEngine.defaultEngine.rawValue
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
    @State private var tuning = PlayerTuning()
    @State private var settingsReporter = PlayerSettingsReporter(engine: PlaybackEngine.defaultEngine.rawValue, generation: 0)
    /// App volume for this playback session. A new player screen starts at full
    /// level, unmuted. Engine switches and playlist moves keep the stored level.
    @State private var appVolume = PlayerAppVolumeController(engine: PlaybackEngine.defaultEngine.rawValue, generation: 0)
    @State private var appVolumeBanner: String?
    @State private var appVolumeShownLevel = PlayerAppVolume.defaultLevel
    @State private var appVolumeBannerID = 0
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
        appVolume.note(engine: target.rawValue, generation: generation)
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
        if (playlistStore.currentItem ?? payload).resolvedMediaKind == .image {
            emit(PlayerPlaybackReport.imageSettings(engine: engine.rawValue), engine: engine.rawValue)
            return
        }
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

    private func applyTuning(_ command: PlayerTuning.Command) {
        guard (playlistStore.currentItem ?? payload).resolvedMediaKind != .image else { return }
        let identity = liveVolumeIdentity()
        guard let frame = settingsReporter.apply(command, identity: identity) else { return }
        tuning = settingsReporter.tuning
        emit(frame, engine: identity.engine)
    }

    private func liveVolumeIdentity() -> PlayerReportIdentity {
        PlayerReportIdentity(engine: exitGate.liveEngine.rawValue, generation: exitGate.liveGeneration)
    }

    /// Initial sync and `context_query` resync. A mismatched engine or item emits nothing.
    private func emitAppVolume(engine: PlaybackEngine, generation: Int) {
        let identity = reportIdentity(engine: engine, generation: generation)
        guard let frame = appVolume.resync(identity: identity) else { return }
        guard server.currentPlayRequest != nil else { return }
        server.broadcast(frame)
    }

    /// An accepted step, absolute level, or mute toggle. The on-screen label follows.
    private func publishAppVolume(_ frame: [String: Any]?) {
        guard let frame, server.currentPlayRequest != nil else { return }
        server.broadcast(frame)
        appVolumeShownLevel = appVolume.level
        appVolumeBanner = PlayerAppVolume.indicatorText(level: appVolume.level, muted: appVolume.muted)
        appVolumeBannerID &+= 1
    }

    /// Engine for this item: a manual session switch wins; otherwise honor the phone's
    /// `player_mode` ("avplayer"/"mpv"); legacy "vlc" maps to MPV.
    /// "tv"/unset/unknown fall back to the stored default.
    private func effectiveEngine(for item: Playbridge_PlayPayload) -> PlaybackEngine {
        if let session = sessionEngine { return session }
        if item.hasPlayerMode, let requested = PlaybackEngine(command: item.playerMode) {
            return requested
        }
        return PlaybackEngine(command: preferredPlayer) ?? .defaultEngine
    }

    private func isCurrentPlayback(_ engine: PlaybackEngine, generation: Int) -> Bool {
        server.currentPlayRequest != nil && playlistStore.currentItem != nil &&
            mediaGeneration == generation && effectiveEngine(for: playlistStore.currentItem ?? payload) == engine
    }

    /// Preserve protobuf presence: season/episode zero can identify specials.
    private func skipMetadata(for item: Playbridge_PlayPayload) -> SkipSegmentMetadata {
        let metadata = item.visualMetadata
        return SkipSegmentMetadata(
            imdbID: metadata.hasImdbID ? metadata.imdbID : nil,
            tmdbID: metadata.hasTmdbID ? metadata.tmdbID : nil,
            season: metadata.hasSeason ? Int(metadata.season) : nil,
            episode: metadata.hasEpisode ? Int(metadata.episode) : nil)
    }

    /// Initial seek: an engine-switch resume wins over the sender's start position.
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
        appVolume.note(engine: engine.rawValue, generation: mediaGeneration)
        emitCurrent(engine: engine, generation: mediaGeneration)
    }

    private func broadcast(_ json: [String: Any], for item: Playbridge_PlayPayload) {
        guard server.currentPlayRequest != nil, item.itemID == playlistStore.currentItemID else { return }
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
        guard !isPreBuffering, !item.skipHistory, item.resolvedMediaKind != .image, let url = item.validURL,
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
        if key == "home" { exitPlayback(); return }
        if (playlistStore.currentItem ?? payload).resolvedMediaKind == .image,
           !stillWatching.isPrompting, !showPlaylist {
            switch key {
            case "back": exitPlayback()
            case "dpad_left": if playlistStore.hasPrevious { handleJump(to: playlistStore.currentIndex - 1) }
            case "dpad_right": handleNext()
            case "dpad_center":
                NotificationCenter.default.post(name: WebSocketServer.controlCommand, object: nil, userInfo: ["command": "toggle"])
            default: break
            }
            return
        }
        if !stillWatching.isPrompting, !isPreBuffering, engineChrome.handleFailureKey(key) { return }
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
        case .dismissInfoPanel, .dismissEngineMenu,
             .dismissSettingsSheet, .dismissScrub, .dismissControls,
             .togglePlayback, .seekBackward, .seekForward,
             .scrubBackward, .scrubForward, .commitScrub:
            _ = engineChrome.apply(effect)
        case .adjustAppVolume(let steps):
            publishAppVolume(appVolume.step(steps, identity: liveVolumeIdentity()))
        case .toggleAppMute:
            publishAppVolume(appVolume.toggleMute(identity: liveVolumeIdentity()))
        }
    }

    private func transportMetadata(for payload: Playbridge_PlayPayload) -> TransportMetadata {
        guard payload.hasVisualMetadata else { return TransportMetadata() }
        let metadata = payload.visualMetadata
        return TransportMetadata(subtitle: TransportTimeLabels.metadataSubtitle(
            year: metadata.hasYear ? metadata.year : nil,
            season: metadata.hasSeason ? Int(metadata.season) : nil,
            episode: metadata.hasEpisode ? Int(metadata.episode) : nil,
            episodeTitle: metadata.hasEpisodeTitle ? metadata.episodeTitle : nil),
            year: metadata.year, rating: metadata.rating, runtime: metadata.runtime,
            overview: metadata.overview, genres: metadata.genres,
            posterURL: metadata.hasPosterURL ? metadata.posterURL : "")
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
        let _ = appVolume.note(
            engine: effectiveEngine(for: fenceRequest).rawValue,
            generation: mediaGeneration)
        ZStack {
            Color.black.ignoresSafeArea()

            let currentRequest = playlistStore.currentItem ?? payload
            if let currentURL = currentRequest.validURL {
                let currentEngine = effectiveEngine(for: currentRequest)
                let currentGeneration = mediaGeneration
                let mediaIdentity = PlayerMediaIdentity(playbackID: playlistStore.playbackID,
                                                        itemID: playlistStore.currentItemID)
                let isCurrentItem = {
                    isCurrentPlayback(currentEngine, generation: currentGeneration) &&
                        mediaIdentity.matches(playbackID: playlistStore.playbackID,
                                              itemID: playlistStore.currentItemID,
                                              active: server.currentPlayRequest != nil)
                }
                if currentRequest.resolvedMediaKind == .image {
                    ImagePresentationView(
                        payload: currentRequest, engine: currentEngine.rawValue,
                        prompting: stillWatching.isPrompting,
                        onBroadcast: { frame in
                            guard isCurrentItem() else { return }
                            broadcast(frame, for: currentRequest)
                        },
                        onNavigate: { delta in
                            guard isCurrentItem() else { return }
                            if delta > 0 { handleNext() }
                            else if playlistStore.hasPrevious { handleJump(to: playlistStore.currentIndex - 1) }
                        },
                        onExit: { if isCurrentItem() { exitPlayback() } }
                    )
                    .focused($isPlayerFocused)
                    .id("image-\(playlistStore.currentItemID ?? currentRequest.url)-\(currentGeneration)")
                } else if currentEngine == .mpv {
                    MPVPlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        subtitles: currentRequest.subtitlesOrNil,
                        skipMetadata: skipMetadata(for: currentRequest),
                        initialTime: initialSeekTime(for: currentRequest),
                        mediaIdentity: mediaGeneration,
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        musicPayload: currentRequest.resolvedMediaKind == .audio ? currentRequest : nil,
                        onDismiss: { if isCurrentItem() { handleNext() } },
                        onExit: { if isCurrentItem() { exitPlayback() } },
                        onBack: { if isCurrentItem() { handleRemoteNavigation("back") } },
                        onSwitch: { if isCurrentItem() { handleSwitch(to: $0, currentTime: $1, from: currentEngine, generation: currentGeneration) } },
                        onBroadcast: { frame in
                            guard isCurrentItem() else { return }
                            if frame["state"] as? String == "error" { showPlaylist = false }
                            broadcast(frame, for: currentRequest)
                        },
                        onLoopChanged: { enabled, identity in isCurrentItem() && emitLoop(enabled, identity: identity) },
                        onEndOfFile: { identity in isCurrentItem() ? emitEnd(identity) : .ignored },
                        initialLoop: settingsReporter.loop,
                        appLevel: appVolume.level,
                        appMuted: appVolume.muted,
                        engineChrome: engineChrome,
                        hasPlaylist: playlistStore.items.count > 1,
                        playbackSpeed: tuning.speed,
                        subtitleOffsetMs: tuning.subtitleOffsetMs,
                        scaling: tuning.scaling,
                        hasNextItem: playlistStore.hasNext,
                        metadata: transportMetadata(for: currentRequest),
                        playlistVisible: showPlaylist,
                        onLocalTuning: { if isCurrentItem() { applyTuning($0) } }
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
                        skipMetadata: skipMetadata(for: currentRequest),
                        initialTime: initialSeekTime(for: currentRequest),
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        musicPayload: currentRequest.resolvedMediaKind == .audio ? currentRequest : nil,
                        onDismiss: { if isCurrentItem() { handleNext() } },
                        onExit: { if isCurrentItem() { exitPlayback() } },
                        onSwitch: { if isCurrentItem() { handleSwitch(to: $0, currentTime: $1, from: currentEngine, generation: currentGeneration) } },
                        onBroadcast: { if isCurrentItem() { broadcast($0, for: currentRequest) } },
                        onLoopChanged: { enabled, identity in isCurrentItem() && emitLoop(enabled, identity: identity) },
                        onEndOfFile: { identity in isCurrentItem() ? emitEnd(identity) : .ignored },
                        initialLoop: settingsReporter.loop,
                        appLevel: appVolume.level,
                        appMuted: appVolume.muted,
                        mediaIdentity: currentGeneration,
                        engineChrome: engineChrome,
                        hasPlaylist: playlistStore.items.count > 1,
                        playbackSpeed: tuning.speed,
                        subtitleOffsetMs: tuning.subtitleOffsetMs,
                        scaling: tuning.scaling,
                        onSidecarOverlayChanged: { active, identity in
                            if isCurrentItem(), let frame = settingsReporter.setSidecarOverlay(active, identity: identity) {
                                emit(frame, engine: identity.engine)
                            }
                        },
                        onLocalTuning: { if isCurrentItem() { applyTuning($0) } }
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    .id("avplayer-\(mediaGeneration)")
                    .onExitCommand { if isCurrentItem() { exitPlayback() } }
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

            if let appVolumeBanner {
                VStack {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(appVolumeBanner)
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.white)
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.28))
                            Capsule()
                                .fill(.white)
                                .frame(width: max(0, 320 * appVolumeShownLevel))
                        }
                        .frame(width: 320, height: 10)
                    }
                    .padding(.horizontal, 28)
                    .padding(.vertical, 18)
                    .background(.black.opacity(0.78))
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(appVolumeBanner)
                    Spacer()
                }
                .padding(.top, 48)
                .allowsHitTesting(false)
                .zIndex(40)
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
            let engine = effectiveEngine(for: request)
            emitCurrent(engine: engine, generation: mediaGeneration)
            emitAppVolume(engine: engine, generation: mediaGeneration)
        }
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.resyncRequest)) { _ in
            let request = playlistStore.currentItem ?? payload
            let engine = effectiveEngine(for: request)
            emitCurrent(engine: engine, generation: mediaGeneration)
            emitAppVolume(engine: engine, generation: mediaGeneration)
        }
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.appVolumeCommand)) { note in
            guard let level = note.userInfo?["level"] as? Double else { return }
            publishAppVolume(appVolume.setAbsolute(level, identity: liveVolumeIdentity()))
        }
        .task(id: appVolumeBannerID) {
            guard appVolumeBanner != nil else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            appVolumeBanner = nil
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
                _ = engineChrome.apply(.dismissInfoPanel)
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
            if let command = note.userInfo?["command"] as? String, let parsed = PlayerTuning.parse(command) {
                applyTuning(parsed)
                return
            }
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
