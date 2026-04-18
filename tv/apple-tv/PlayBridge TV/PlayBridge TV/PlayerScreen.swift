import AVKit
import SwiftUI

struct PlayerScreen: View {
    let viewModel: PlayerViewModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let engine = viewModel.engine as? AVPlayerEngine {
                AVPlayerViewControllerRepresentable(player: engine.player)
                    .ignoresSafeArea()
            } else if let engine = viewModel.engine as? VLCPlayerEngine {
                VLCPlayerView(mediaPlayer: engine.mediaPlayer)
                    .ignoresSafeArea()
            }

            // Simple overlay for now
            VStack {
                if let title = viewModel.currentTitle {
                    Text(title)
                        .font(.headline)
                        .padding()
                        .background(Color.black.opacity(0.5))
                        .cornerRadius(10)
                        .padding(.top, 40)
                }

                Spacer()

                if viewModel.state == .buffering || viewModel.state == .loading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        .scaleEffect(2)
                }

                Spacer()
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
