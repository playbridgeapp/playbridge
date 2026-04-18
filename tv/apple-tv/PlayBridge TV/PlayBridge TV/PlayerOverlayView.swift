import SwiftUI

struct PlayerOverlayView: View {
    @Bindable var viewModel: PlayerViewModel
    let onSettingsToggle: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Top Bar: Title & Stream Info
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(viewModel.currentTitle ?? "Unknown Title")
                        .font(.system(size: 40, weight: .bold))
                        .foregroundColor(.white)

                    if let engine = viewModel.engine {
                        HStack(spacing: 15) {
                            Text(engine is AVPlayerEngine ? "AVPlayer" : "VLC")
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.blue.opacity(0.3))
                                .cornerRadius(4)

                            if viewModel.duration > 0 {
                                Text("\(formatTime(viewModel.duration))")
                                    .font(.caption)
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                }

                Spacer()

                // Playlist Index
                if viewModel.playlistItems.count > 1 {
                    Text("\(viewModel.currentIndex + 1) / \(viewModel.playlistItems.count)")
                        .font(.headline)
                        .padding()
                        .background(Color.black.opacity(0.4))
                        .cornerRadius(10)
                }
            }
            .padding(.top, 60)
            .padding(.horizontal, 80)

            Spacer()

            // Bottom Bar: Scrubber & Controls
            VStack(spacing: 20) {
                // Scrubber
                if viewModel.duration > 0 {
                    VStack(spacing: 8) {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(Color.white.opacity(0.2))
                                    .frame(height: 10)

                                Capsule()
                                    .fill(Color.blue)
                                    .frame(
                                        width: geo.size.width
                                            * CGFloat(viewModel.position / viewModel.duration),
                                        height: 10)
                            }
                        }
                        .frame(height: 10)

                        HStack {
                            Text(formatTime(viewModel.position))
                            Spacer()
                            Text("-" + formatTime(viewModel.duration - viewModel.position))
                        }
                        .font(.system(.body, design: .monospaced))
                        .foregroundColor(.gray)
                    }
                }

                // Buttons
                HStack(spacing: 40) {
                    Button(action: { viewModel.skipBackward() }) {
                        Image(systemName: "gobackward.10")
                            .font(.title)
                    }
                    .buttonStyle(.plain)

                    Button(action: {
                        if viewModel.state == .playing {
                            viewModel.pause()
                        } else {
                            viewModel.play()
                        }
                    }) {
                        Image(systemName: viewModel.state == .playing ? "pause.fill" : "play.fill")
                            .font(.system(size: 50))
                    }
                    .buttonStyle(.plain)

                    Button(action: { viewModel.skipForward() }) {
                        Image(systemName: "goforward.10")
                            .font(.title)
                    }
                    .buttonStyle(.plain)

                    Button(action: onSettingsToggle) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.title)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.bottom, 80)
            .padding(.horizontal, 80)
            .background(
                LinearGradient(
                    gradient: Gradient(colors: [.clear, .black.opacity(0.8)]), startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            )
        }
    }

    private func formatTime(_ seconds: TimeInterval) -> String {
        let hours = Int(seconds) / 3600
        let minutes = (Int(seconds) % 3600) / 60
        let secs = Int(seconds) % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        } else {
            return String(format: "%d:%02d", minutes, secs)
        }
    }
}
