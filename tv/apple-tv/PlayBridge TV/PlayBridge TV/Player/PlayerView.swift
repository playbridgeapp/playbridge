import SwiftUI

struct PlayerView: View {
    let request: PlayRequest
    let onDismiss: () -> Void
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"

    // 1. Define focus state
    @FocusState private var isPlayerFocused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if preferredPlayer == "vlc" {
                VLCPlayerView(url: request.url, headers: request.headers, onDismiss: onDismiss)
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
            } else {
                NativePlayerView(url: request.url, headers: request.headers, onDismiss: onDismiss)
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
