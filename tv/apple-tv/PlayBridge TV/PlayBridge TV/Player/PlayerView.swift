import SwiftUI

struct PlayerView: View {
    let request: PlayRequest
    let onDismiss: () -> Void
    @EnvironmentObject var historyStore: HistoryStore
    @EnvironmentObject var playlistStore: PlaylistStore
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"
    @State private var resumeTime: Double = 0.0
    @State private var showPlaylist: Bool = false
    let isPreBuffering: Bool

    init(request: PlayRequest, isPreBuffering: Bool, onDismiss: @escaping () -> Void) {
        self.request = request
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
        if let nextRequest = playlistStore.next() {
            historyStore.addToHistory(url: nextRequest.url, title: nextRequest.title, headers: nextRequest.headers)
            resumeTime = 0
        } else {
            onDismiss()
        }
    }
    
    private func handleJump(to index: Int) {
        if let jumpRequest = playlistStore.jumpTo(index: index) {
            historyStore.addToHistory(url: jumpRequest.url, title: jumpRequest.title, headers: jumpRequest.headers)
            resumeTime = 0
            withAnimation { showPlaylist = false }
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            let currentRequest = playlistStore.currentItem ?? request
            let _ = print("PlayerView: Rendering with URL: \(currentRequest.url)")
            if preferredPlayer == "vlc" {
                VLCPlayerView(
                    url: currentRequest.url,
                    headers: currentRequest.headers,
                    subtitles: currentRequest.subtitles,
                    initialTime: resumeTime,
                    isPreBuffering: isPreBuffering,
                    onDismiss: handleNext,  // end-of-video → try next item
                    onExit: onDismiss,      // back button → always go home
                    onSwitch: handleSwitch
                )
                .ignoresSafeArea()
                .focused($isPlayerFocused)
                .id(currentRequest.url)
            } else {
                NativePlayerView(
                    url: currentRequest.url,
                    headers: currentRequest.headers,
                    initialTime: resumeTime,
                    isPreBuffering: isPreBuffering,
                    onDismiss: handleNext,  // end-of-video → try next item
                    onExit: onDismiss,      // back button → always go home
                    onSwitch: handleSwitch
                )
                .ignoresSafeArea()
                .focused($isPlayerFocused)
                .id(currentRequest.url)
                .onExitCommand { onDismiss() }
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
