import PlayBridgeProtocol
import SwiftUI

struct PlayerOverlayView: View {
    @Bindable var viewModel: PlayerViewModel
    let onSettingsToggle: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // ── Top Bar ──────────────────────────────────────────────────────
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    // Title
                    Text(viewModel.currentTitle ?? "Unknown Title")
                        .font(.system(size: 40, weight: .bold))
                        .foregroundColor(.white)

                    // Season / episode label (when playing series content)
                    if let sc = viewModel.currentSeriesContext {
                        let epLabel = episodeLabel(sc)
                        Text(epLabel)
                            .font(.headline)
                            .foregroundColor(.secondary)
                    }

                    // Engine badge + duration
                    if let engine = viewModel.engine {
                        HStack(spacing: 12) {
                            Text(engine is AVPlayerEngine ? "AVPlayer" : "VLC")
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.blue.opacity(0.3))
                                .cornerRadius(4)

                            if viewModel.duration > 0 {
                                Text(formatTime(viewModel.duration))
                                    .font(.caption)
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 12) {
                    // Playlist index badge
                    if viewModel.playlistItems.count > 1 {
                        Text("\(viewModel.currentIndex + 1) / \(viewModel.playlistItems.count)")
                            .font(.headline)
                            .padding()
                            .background(Color.black.opacity(0.4))
                            .cornerRadius(10)
                    }

                    // "Next up" tile — appears 60 s before end for series content
                    if let next = viewModel.nextEpisodeInfo {
                        NextUpTile(episode: next)
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            .padding(.top, 60)
            .padding(.horizontal, 80)

            Spacer()

            // ── Bottom Bar ───────────────────────────────────────────────────
            VStack(spacing: 20) {
                // Scrub bar
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

                // Transport buttons
                HStack(spacing: 40) {
                    Button(action: { viewModel.skipBackward() }) {
                        Image(systemName: "gobackward.10").font(.title)
                    }
                    .buttonStyle(.plain)

                    Button(action: {
                        viewModel.state == .playing ? viewModel.pause() : viewModel.play()
                    }) {
                        Image(
                            systemName: viewModel.state == .playing ? "pause.fill" : "play.fill"
                        )
                        .font(.system(size: 50))
                    }
                    .buttonStyle(.plain)

                    Button(action: { viewModel.skipForward() }) {
                        Image(systemName: "goforward.10").font(.title)
                    }
                    .buttonStyle(.plain)

                    Button(action: onSettingsToggle) {
                        Image(systemName: "slider.horizontal.3").font(.title)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.bottom, 80)
            .padding(.horizontal, 80)
            .background(
                LinearGradient(
                    gradient: Gradient(colors: [.clear, .black.opacity(0.8)]),
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            )
        }
    }

    // MARK: - Helpers

    private func episodeLabel(_ sc: SeriesContext) -> String {
        let code = "S\(String(format: "%02d", sc.season)) E\(String(format: "%02d", sc.episode))"
        if let title = sc.episodeTitle, !title.isEmpty {
            return "\(code) – \"\(title)\""
        }
        return code
    }

    private func formatTime(_ seconds: TimeInterval) -> String {
        let h = Int(seconds) / 3600
        let m = (Int(seconds) % 3600) / 60
        let s = Int(seconds) % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }
}

// MARK: - Next Up Tile

private struct NextUpTile: View {
    let episode: SeriesEpisodeRef

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text("UP NEXT")
                .font(.caption2)
                .fontWeight(.semibold)
                .foregroundColor(.gray)
                .textCase(.uppercase)

            Text(
                "S\(String(format: "%02d", episode.season)) E\(String(format: "%02d", episode.episode))"
            )
            .font(.headline)
            .foregroundColor(.white)

            if let title = episode.title, !title.isEmpty {
                Text(title)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.black.opacity(0.7))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(Color.white.opacity(0.15), lineWidth: 1)
                )
        )
    }
}
