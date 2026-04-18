//
//  PlayerOverlayView.swift
//  PlayBridge TV
//

import PlayBridgeProtocol
import SwiftUI

struct PlayerOverlayView: View {
    @Bindable var viewModel: PlayerViewModel
    let onSettingsToggle: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            // Top gradient — keeps title readable over bright video
            VStack {
                LinearGradient(
                    colors: [.black.opacity(0.75), .clear],
                    startPoint: .top, endPoint: .bottom
                )
                .frame(height: 260)
                .ignoresSafeArea()
                Spacer()
            }

            // Bottom gradient — backs the transport controls
            LinearGradient(
                colors: [.clear, .black.opacity(0.85)],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: 320)
            .ignoresSafeArea()

            // Content
            VStack(spacing: 0) {
                // ── Top Bar ──────────────────────────────────────────────────
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(viewModel.currentTitle ?? "Unknown Title")
                            .font(.system(size: 40, weight: .bold))
                            .foregroundColor(.white)
                            .shadow(color: .black.opacity(0.6), radius: 4)

                        if let sc = viewModel.currentSeriesContext {
                            Text(episodeLabel(sc))
                                .font(.title3)
                                .foregroundColor(.secondary)
                        }

                        if let engine = viewModel.engine {
                            HStack(spacing: 12) {
                                Text(engine is AVPlayerEngine ? "AVPlayer" : "VLC")
                                    .font(.caption)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 4)
                                    .background(Color.blue.opacity(0.4))
                                    .cornerRadius(6)

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
                        if viewModel.playlistItems.count > 1 {
                            Text("\(viewModel.currentIndex + 1) / \(viewModel.playlistItems.count)")
                                .font(.headline)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(Color.black.opacity(0.5))
                                .cornerRadius(10)
                        }

                        if let next = viewModel.nextEpisodeInfo {
                            NextUpTile(episode: next)
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                }
                .padding(.top, 60)
                .padding(.horizontal, 90)

                Spacer()

                // ── Bottom Bar ───────────────────────────────────────────────
                VStack(spacing: 16) {
                    // Scrub bar
                    if viewModel.duration > 0 {
                        VStack(spacing: 8) {
                            // Track bar — overlay avoids GeometryReader zero-width flash
                            Capsule()
                                .fill(Color.white.opacity(0.25))
                                .frame(height: 8)
                                .overlay(
                                    GeometryReader { geo in
                                        Capsule()
                                            .fill(Color.blue)
                                            .frame(
                                                width: max(
                                                    0,
                                                    geo.size.width
                                                        * CGFloat(
                                                            viewModel.position / viewModel.duration
                                                        )),
                                                height: 8)
                                    },
                                    alignment: .leading
                                )

                            HStack {
                                Text(formatTime(viewModel.position))
                                Spacer()
                                Text("-" + formatTime(viewModel.duration - viewModel.position))
                            }
                            .font(.system(.callout, design: .monospaced))
                            .foregroundColor(.gray)
                        }
                    }

                    // Transport buttons
                    HStack(spacing: 50) {
                        PlayerButton(systemName: "gobackward.10", size: 28) {
                            viewModel.skipBackward()
                        }

                        PlayerButton(
                            systemName: viewModel.state == .playing ? "pause.fill" : "play.fill",
                            size: 48
                        ) {
                            viewModel.state == .playing ? viewModel.pause() : viewModel.play()
                        }

                        PlayerButton(systemName: "goforward.10", size: 28) {
                            viewModel.skipForward()
                        }

                        PlayerButton(systemName: "slider.horizontal.3", size: 26) {
                            onSettingsToggle()
                        }
                    }
                }
                .padding(.bottom, 80)
                .padding(.horizontal, 90)
            }
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

// MARK: - Player Button (focus-aware)

struct PlayerButton: View {
    let systemName: String
    let size: CGFloat
    let action: () -> Void

    @FocusState private var isFocused: Bool

    init(systemName: String, size: CGFloat, action: @escaping () -> Void) {
        self.systemName = systemName
        self.size = size
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size, weight: .medium))
                .foregroundColor(.white)
                .frame(width: size * 2.4, height: size * 2.4)
                .background(
                    Circle()
                        .fill(isFocused ? Color.white.opacity(0.25) : Color.clear)
                        .animation(.easeOut(duration: 0.15), value: isFocused)
                )
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}

// MARK: - Next Up Tile

private struct NextUpTile: View {
    let episode: SeriesEpisodeRef

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text("UP NEXT")
                .font(.caption2.weight(.semibold))
                .foregroundColor(.gray)
                .textCase(.uppercase)

            Text("S\(String(format: "%02d", episode.season)) E\(String(format: "%02d", episode.episode))")
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
