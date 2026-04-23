import SwiftUI

struct PlayerView: View {
    let request: PlayRequest
    let onDismiss: () -> Void
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"
    @State private var resumeTime: Double = 0.0

    // 1. Define focus state
    @FocusState private var isPlayerFocused: Bool

    private func handleSwitch(currentTime: Double) {
        resumeTime = currentTime
        preferredPlayer = preferredPlayer == "vlc" ? "avplayer" : "vlc"
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if preferredPlayer == "vlc" {
                VLCPlayerView(
                    url: request.url,
                    headers: request.headers,
                    initialTime: resumeTime,
                    onDismiss: onDismiss,
                    onSwitch: handleSwitch
                )
                .ignoresSafeArea()
                .focused($isPlayerFocused)
            } else {
                NativePlayerView(
                    url: request.url,
                    headers: request.headers,
                    initialTime: resumeTime,
                    onDismiss: onDismiss,
                    onSwitch: handleSwitch
                )
                .ignoresSafeArea()
                .focused($isPlayerFocused)
                .onExitCommand { onDismiss() }
            }
        }
        .onAppear {
            isPlayerFocused = true
        }
    }
}
