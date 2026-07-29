import SwiftUI

/// Session-scoped track preferences shared by all three engines, so a pick made on one
/// episode carries into the next — the player views (and their underlying players) are
/// recreated per item, losing any in-player selection state. AVPlayer matches by language
/// tag, VLC/MPV by track display name (stable across episodes of the same release).
/// Reset by the WebSocket server when a new cast session starts.
final class TrackPreferences {
    static let shared = TrackPreferences()

    /// Language tag of the picked track (AVPlayer; locale id or extended language tag).
    var audioLanguage: String?
    var subtitleLanguage: String?
    /// Display name of the picked track (VLC/MPV).
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
    @State private var sessionEngine: String? = nil
    @State private var resumeTime: Double = 0.0
    @State private var showPlaylist: Bool = false
    @State private var latestPlaybackIsPlaying = false
    @State private var mediaGeneration = 0
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

    private func handleSwitch(currentTime: Double) {
        resumeTime = currentTime
        let current = effectiveEngine(for: playlistStore.currentItem ?? payload)
        // Cycle AVPlayer → VLC → MPV → AVPlayer.
        switch current {
        case "avplayer": sessionEngine = "vlc"
        case "vlc":      sessionEngine = "mpv"
        default:         sessionEngine = "avplayer"
        }
    }

    /// Engine for this item: a manual session switch wins; otherwise honor the phone's
    /// `player_mode` ("avplayer"/"vlc"/"mpv"); "tv"/unset/unknown fall back to the stored default.
    private func effectiveEngine(for item: Playbridge_PlayPayload) -> String {
        if let session = sessionEngine { return session }
        if item.hasPlayerMode {
            switch item.playerMode {
            case "avplayer", "native": return "avplayer"
            case "vlc": return "vlc"
            case "mpv": return "mpv"
            default: break
            }
        }
        return preferredPlayer
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
        stillWatching.reset()
        mediaGeneration &+= 1
        consumeStartPosition(at: playlistStore.currentIndex)
        if let nextRequest = playlistStore.next(), let nextURL = nextRequest.validURL {
            historyStore.addToHistory(url: nextURL, title: nextRequest.titleOrNil, headers: nextRequest.headersOrNil)
            resumeTime = 0
        } else {
            onDismiss()
        }
    }

    private func handleJump(to index: Int) {
        stillWatching.reset()
        mediaGeneration &+= 1
        consumeStartPosition(at: playlistStore.currentIndex)
        if let jumpRequest = playlistStore.jumpTo(index: index), let jumpURL = jumpRequest.validURL {
            historyStore.addToHistory(url: jumpURL, title: jumpRequest.titleOrNil, headers: jumpRequest.headersOrNil)
            resumeTime = 0
            withAnimation { showPlaylist = false }
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            let currentRequest = playlistStore.currentItem ?? payload
            if let currentURL = currentRequest.validURL {
                if effectiveEngine(for: currentRequest) == "vlc" {
                    VLCPlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        subtitles: currentRequest.subtitlesOrNil,
                        initialTime: initialSeekTime(for: currentRequest),
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        onDismiss: handleNext,
                        onExit: onDismiss,
                        onSwitch: handleSwitch,
                        onBroadcast: { server.broadcast($0) }
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    .id("vlc-\(mediaGeneration)")
                } else if effectiveEngine(for: currentRequest) == "mpv" {
                    MPVPlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        subtitles: currentRequest.subtitlesOrNil,
                        initialTime: initialSeekTime(for: currentRequest),
                        mediaIdentity: mediaGeneration,
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        onDismiss: handleNext,
                        onExit: onDismiss,
                        onSwitch: handleSwitch,
                        onBroadcast: { server.broadcast($0) }
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
                        initialTime: initialSeekTime(for: currentRequest),
                        isPreBuffering: isPreBuffering,
                        title: currentRequest.titleOrNil,
                        onDismiss: handleNext,  // end-of-video → try next item
                        onExit: onDismiss,      // back button → always go home
                        onSwitch: handleSwitch,
                        onBroadcast: { server.broadcast($0) }
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    .id("avplayer-\(mediaGeneration)")
                    .onExitCommand { onDismiss() }
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
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("TogglePlaylist"))) { _ in
            guard !stillWatching.isPrompting else { return }
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
