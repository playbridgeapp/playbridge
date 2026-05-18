import SwiftUI

struct PlayerView: View {
    let payload: Playbridge_PlayPayload
    let onDismiss: () -> Void
    @EnvironmentObject var historyStore: HistoryStore
    @EnvironmentObject var playlistStore: PlaylistStore
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"
    @State private var resumeTime: Double = 0.0
    @State private var showPlaylist: Bool = false
    let isPreBuffering: Bool

    init(payload: Playbridge_PlayPayload, isPreBuffering: Bool, onDismiss: @escaping () -> Void) {
        self.payload = payload
        self.isPreBuffering = isPreBuffering
        self.onDismiss = onDismiss
    }

    // 1. Define focus state
    @FocusState private var isPlayerFocused: Bool

    private func handleSwitch(currentTime: Double) {
        resumeTime = currentTime
        preferredPlayer = preferredPlayer == "vlc" ? "avplayer" : "vlc"
    }

    private func handleNext() {
        if let nextRequest = playlistStore.next(), let nextURL = nextRequest.validURL {
            historyStore.addToHistory(url: nextURL, title: nextRequest.titleOrNil, headers: nextRequest.headersOrNil)
            resumeTime = 0
        } else {
            onDismiss()
        }
    }

    private func handleJump(to index: Int) {
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
                let _ = print("PlayerView: Rendering with URL: \(currentURL)")
                if preferredPlayer == "vlc" {
                    VLCPlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        subtitles: currentRequest.subtitlesOrNil,
                        initialTime: resumeTime,
                        isPreBuffering: isPreBuffering,
                        onDismiss: handleNext,  // end-of-video → try next item
                        onExit: onDismiss,      // back button → always go home
                        onSwitch: handleSwitch
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    .id(currentURL)
                } else {
                    NativePlayerView(
                        url: currentURL,
                        headers: currentRequest.headersOrNil,
                        initialTime: resumeTime,
                        isPreBuffering: isPreBuffering,
                        onDismiss: handleNext,  // end-of-video → try next item
                        onExit: onDismiss,      // back button → always go home
                        onSwitch: handleSwitch
                    )
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
                    .id(currentURL)
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
        }
        .onAppear {
            isPlayerFocused = true
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("TogglePlaylist"))) { _ in
            withAnimation { showPlaylist.toggle() }
        }
    }
}
