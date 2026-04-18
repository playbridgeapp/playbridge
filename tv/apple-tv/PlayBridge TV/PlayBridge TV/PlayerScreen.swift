import AVKit
import SwiftUI

struct PlayerScreen: View {
    let viewModel: PlayerViewModel
    @State private var showSettings = false
    @State private var showOverlay = true
    @State private var overlayTimer: Timer?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let engine = viewModel.engine as? AVPlayerEngine {
                AVPlayerViewControllerRepresentable(player: engine.player)
                    .ignoresSafeArea()
            } else if let engine = viewModel.engine as? VLCPlayerEngine {
                VLCPlayerView(mediaPlayer: engine.mediaPlayer)
                    .ignoresSafeArea()
                    .onMoveCommand { direction in
                        resetOverlayTimer()
                        switch direction {
                        case .left:
                            viewModel.skipBackward()
                        case .right:
                            viewModel.skipForward()
                        case .up:
                            showOverlay = true
                        default:
                            break
                        }
                    }
            }

            // Subtitles (always visible if present)
            VStack {
                Spacer()
                SubtitleOverlay(cues: viewModel.subtitleManager.activeCues)
            }
            .padding(.bottom, showOverlay ? 250 : 60)
            .animation(.easeInOut, value: showOverlay)

            // Loading/Buffering
            if viewModel.state == .buffering || viewModel.state == .loading {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(2)
            }

            // Info Overlay
            if showOverlay && !showSettings {
                PlayerOverlayView(viewModel: viewModel) {
                    showSettings = true
                }
                .transition(.opacity)
            }

            if showSettings {
                Color.black.opacity(0.3)
                    .ignoresSafeArea()
                    .onTapGesture {
                        showSettings = false
                        resetOverlayTimer()
                    }

                PlayerControlView(viewModel: viewModel)
            }
        }
        .onPlayPauseCommand {
            resetOverlayTimer()
            if viewModel.state == .playing {
                viewModel.pause()
            } else {
                viewModel.play()
            }
        }
        .onExitCommand {
            if showSettings {
                showSettings = false
                resetOverlayTimer()
            }
        }
        .onLongPressGesture {
            showSettings.toggle()
        }
        .onTapGesture {
            if !showOverlay {
                showOverlay = true
                resetOverlayTimer()
            } else if !showSettings {
                // Toggle play/pause on tap if overlay is already up
                if viewModel.state == .playing { viewModel.pause() } else { viewModel.play() }
                resetOverlayTimer()
            }
        }
        .task {
            resetOverlayTimer()
        }
    }

    private func resetOverlayTimer() {
        showOverlay = true
        overlayTimer?.invalidate()
        overlayTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { _ in
            withAnimation {
                if !showSettings && viewModel.state == .playing {
                    showOverlay = false
                }
            }
        }
    }
}
struct SubtitleOverlay: View {
    let cues: [SubtitleCue]

    var body: some View {
        VStack {
            ForEach(cues, id: \.startTime) { cue in
                Text(cue.text)
                    .font(.system(size: 44, weight: .medium))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .shadow(color: .black, radius: 4, x: 0, y: 2)
                    .padding(.horizontal, 40)
                    .padding(.bottom, 60)
            }
        }
    }
}

struct AVPlayerViewControllerRepresentable: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.showsPlaybackControls = true
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}
}
