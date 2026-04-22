import SwiftUI
import Combine

// MARK: - VLC UI Models & Overlay

class VLCPlaybackData: ObservableObject {
    @Published var isPlaying: Bool = false
    @Published var userPaused: Bool = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    @Published var showUI: Bool = true
    @Published var isLooping: Bool = false
    
    // Virtual Scrubbing Ghost HUD
    @Published var isVirtualScrubbing: Bool = false
    @Published var scrubMultiplier: Int = 0
    @Published var virtualTime: Double = 0.0

    // Subtitles
    @Published var subtitleTracks: [(id: Int, name: String)] = []
    @Published var currentSubtitleIndex: Int = -1
    @Published var showSubtitleMenu: Bool = false

    // Audio Tracks
    @Published var audioTracks: [(id: Int, name: String)] = []
    @Published var currentAudioIndex: Int = -1
    @Published var showAudioMenu: Bool = false
}

struct TrackMenuView: View {
    let title: String
    let icon: String
    let tracks: [(id: Int, name: String)]
    let currentId: Int
    let includeOff: Bool
    let onSelect: (Int) -> Void

    @FocusState private var focusedId: Int?

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(Theme.accent)
                Text(title)
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
            }
            .padding(.horizontal, 36)
            .padding(.top, 32)
            .padding(.bottom, 20)

            Divider().background(Color.white.opacity(0.15))

            ScrollView {
                VStack(spacing: 4) {
                    if includeOff {
                        trackRow(id: -1, name: "Off")
                    }
                    ForEach(tracks, id: \.id) { track in
                        trackRow(id: track.id, name: track.name)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
        }
        .onAppear {
            // Slight delay ensures the layout is ready before forcefully moving focus
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                focusedId = currentId
            }
        }
        .frame(width: 540, height: min(CGFloat(tracks.count + (includeOff ? 1 : 0)) * 80 + 120, 620))
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func trackRow(id: Int, name: String) -> some View {
        Button(action: { onSelect(id) }) {
            HStack {
                Text(name)
                    .font(.system(size: 22))
                    .lineLimit(1)
                Spacer()
                if currentId == id {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 22))
                        .foregroundColor(Theme.accent)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .buttonStyle(.plain)
        .focused($focusedId, equals: id)
    }
}

struct VLCControlsOverlay: View {
    @ObservedObject var data: VLCPlaybackData
    let onSelectSubtitle: (Int) -> Void
    let onSelectAudio: (Int) -> Void
    let onTogglePlayPause: () -> Void
    let onSwitchEngine: () -> Void

    var body: some View {
        ZStack {
            // MARK: - Main Controls HUD
            if data.showUI {
                // Full-screen dim when user paused
                if data.userPaused {
                    Color.black.opacity(0.5)
                        .ignoresSafeArea()
                        .transition(.opacity)
                }

                VStack(spacing: 0) {
                    Spacer()

                    // Center action indicator (pause icon or fast-forward scrub indicator)
                    if data.isVirtualScrubbing {
                        HStack(spacing: 12) {
                            Image(systemName: data.scrubMultiplier > 0 ? "forward.fill" : "backward.fill")
                            Text("\(abs(data.scrubMultiplier))x")
                        }
                        .font(.system(size: 72, weight: .bold))
                        .foregroundColor(.white)
                        .transition(.scale.combined(with: .opacity))
                        .padding(.bottom, 60)
                    } else if data.userPaused {
                        Image(systemName: "pause.fill")
                            .font(.system(size: 72, weight: .medium))
                            .foregroundColor(.white.opacity(0.6))
                            .transition(.scale.combined(with: .opacity))
                            .padding(.bottom, 60)
                    }

                    Spacer()

                    // Bottom control bar
                    VStack(spacing: 24) {
                        // Progress bar
                        HStack(spacing: 16) {
                            let displayTime = data.isVirtualScrubbing ? data.virtualTime : data.currentTime
                            Text(formatTime(displayTime))
                                .font(.system(size: 22, weight: .medium, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))

                            GeometryReader { proxy in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color.white.opacity(0.2))
                                    Capsule()
                                        .fill(
                                            LinearGradient(
                                                colors: [Theme.accent, Theme.accent.opacity(0.7)],
                                                startPoint: .leading, endPoint: .trailing)
                                        )
                                        .frame(
                                            width: proxy.size.width
                                                * CGFloat(
                                                    data.duration > 0
                                                        ? displayTime / data.duration : 0))
                                }
                            }.frame(height: 8)

                            Text(formatTime(data.duration))
                                .font(.system(size: 22, weight: .medium, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                        }
                        .padding(.horizontal, 80)

                        // Action buttons row (only when user explicitly paused)
                        if data.userPaused {
                            HStack(spacing: 40) {
                                // Play/Resume button (center, prominent)
                                Button(action: { onTogglePlayPause() }) {
                                    HStack(spacing: 12) {
                                        Image(systemName: "play.fill")
                                            .font(.system(size: 26))
                                        Text("Resume")
                                            .font(.system(size: 22, weight: .semibold))
                                    }
                                    .padding(.horizontal, 36)
                                    .padding(.vertical, 16)
                                }
                                .buttonStyle(.card)

                                // Subtitles button
                                if !data.subtitleTracks.isEmpty {
                                    Button(action: {
                                        data.showSubtitleMenu.toggle()
                                        data.showAudioMenu = false
                                    }) {
                                        VStack(spacing: 6) {
                                            Image(systemName: "captions.bubble.fill")
                                                .font(.system(size: 26))
                                            Text("Subtitles")
                                                .font(.system(size: 16, weight: .medium))
                                        }
                                        .frame(width: 100, height: 70)
                                    }
                                    .buttonStyle(.card)
                                }

                                // Audio tracks button
                                if data.audioTracks.count > 1 {
                                    Button(action: {
                                        data.showAudioMenu.toggle()
                                        data.showSubtitleMenu = false
                                    }) {
                                        VStack(spacing: 6) {
                                            Image(systemName: "waveform")
                                                .font(.system(size: 26))
                                            Text("Audio")
                                                .font(.system(size: 16, weight: .medium))
                                        }
                                        .frame(width: 100, height: 70)
                                    }
                                    .buttonStyle(.card)
                                }

                                // Loop button
                                Button(action: { data.isLooping.toggle() }) {
                                    VStack(spacing: 6) {
                                        Image(systemName: "repeat")
                                            .font(.system(size: 26))
                                            .foregroundColor(data.isLooping ? Theme.accent : .white)
                                        Text("Loop")
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundColor(data.isLooping ? Theme.accent : .white)
                                    }
                                    .frame(width: 100, height: 70)
                                }
                                .buttonStyle(.card)
                                
                                // Switch Engine button
                                Button(action: { onSwitchEngine() }) {
                                    VStack(spacing: 6) {
                                        Image(systemName: "arrow.triangle.2.circlepath")
                                            .font(.system(size: 26))
                                        Text("Switch")
                                            .font(.system(size: 16, weight: .medium))
                                    }
                                    .frame(width: 100, height: 70)
                                }
                                .buttonStyle(.card)
                            }
                            .disabled(data.showSubtitleMenu || data.showAudioMenu)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .padding(.bottom, 60)
                }
                .transition(.opacity)
            }

            // MARK: - Track Picker Popups
            if data.showSubtitleMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                    .transition(.opacity)

                TrackMenuView(
                    title: "Subtitles",
                    icon: "captions.bubble.fill",
                    tracks: data.subtitleTracks,
                    currentId: data.currentSubtitleIndex,
                    includeOff: true
                ) { id in
                    onSelectSubtitle(id)
                    data.showSubtitleMenu = false
                }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
            }

            if data.showAudioMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                    .transition(.opacity)

                TrackMenuView(
                    title: "Audio Track",
                    icon: "waveform",
                    tracks: data.audioTracks,
                    currentId: data.currentAudioIndex,
                    includeOff: false
                ) { id in
                    onSelectAudio(id)
                    data.showAudioMenu = false
                }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.3), value: data.showUI)
        .animation(.easeInOut(duration: 0.3), value: data.userPaused)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showSubtitleMenu)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showAudioMenu)
        .allowsHitTesting(data.showUI || data.showSubtitleMenu || data.showAudioMenu)
    }

    private func formatTime(_ seconds: Double) -> String {
        let sec = Int(seconds)
        let h = sec / 3600
        let m = (sec % 3600) / 60
        let s = sec % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        } else {
            return String(format: "%d:%02d", m, s)
        }
    }
}
