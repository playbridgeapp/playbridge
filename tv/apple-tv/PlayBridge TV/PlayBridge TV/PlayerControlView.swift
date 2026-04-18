import SwiftUI

struct PlayerControlView: View {
    @Bindable var viewModel: PlayerViewModel
    @State private var selectedTab: ControlTab = .tracks

    enum ControlTab { case tracks, speed, filters }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 40) {
                ControlTabButton(title: "Tracks", isSelected: selectedTab == .tracks) {
                    selectedTab = .tracks
                }
                ControlTabButton(title: "Playback Speed", isSelected: selectedTab == .speed) {
                    selectedTab = .speed
                }
                ControlTabButton(title: "Filters", isSelected: selectedTab == .filters) {
                    selectedTab = .filters
                }
            }
            .padding(.top, 40)
            .padding(.bottom, 20)

            Divider().background(Color.white.opacity(0.3))

            ScrollView {
                switch selectedTab {
                case .tracks:  TrackSelectionView(viewModel: viewModel)
                case .speed:   SpeedSelectionView(viewModel: viewModel)
                case .filters: FilterSettingsView(viewModel: viewModel)
                }
            }
            .padding()
        }
        .frame(maxWidth: 800)
        .background(Color.black.opacity(0.8))
        .cornerRadius(20)
        .padding(60)
    }
}

// MARK: - Tab Button

struct ControlTabButton: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .foregroundColor(isSelected ? .white : .gray)
                .padding(.bottom, 8)
                .overlay(
                    Rectangle()
                        .frame(height: 2)
                        .foregroundColor(isSelected ? .white : .clear),
                    alignment: .bottom
                )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Track Selection

struct TrackSelectionView: View {
    let viewModel: PlayerViewModel
    @State private var audioTracks: [(id: String, name: String)] = []
    @State private var subtitleTracks: [(id: String, name: String)] = []
    @State private var selectedAudio: String? = nil
    @State private var selectedSubtitle: String? = nil
    @State private var isLoading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            if isLoading {
                HStack {
                    Spacer()
                    ProgressView().tint(.white)
                    Spacer()
                }
                .padding(.vertical, 20)
            } else if audioTracks.isEmpty && subtitleTracks.isEmpty {
                Text("No embedded tracks available")
                    .foregroundColor(.gray)
                    .padding(.vertical, 20)
            } else {
                // Audio tracks
                if !audioTracks.isEmpty {
                    Text("Audio")
                        .font(.headline)
                        .foregroundColor(.white)

                    ForEach(audioTracks, id: \.id) { track in
                        TrackRow(
                            name: track.name,
                            isSelected: selectedAudio == track.id
                        ) {
                            selectedAudio = track.id
                            viewModel.engine?.setAudioTrack(track.id)
                        }
                    }
                }

                // Subtitle tracks
                if !subtitleTracks.isEmpty {
                    Text("Subtitles")
                        .font(.headline)
                        .foregroundColor(.white)
                        .padding(.top, audioTracks.isEmpty ? 0 : 12)

                    // Off option
                    TrackRow(name: "Off", isSelected: selectedSubtitle == nil) {
                        selectedSubtitle = nil
                        viewModel.engine?.setSubtitleTrack(nil)
                    }

                    ForEach(subtitleTracks, id: \.id) { track in
                        TrackRow(
                            name: track.name,
                            isSelected: selectedSubtitle == track.id
                        ) {
                            selectedSubtitle = track.id
                            viewModel.engine?.setSubtitleTrack(track.id)
                        }
                    }
                }
            }
        }
        .task {
            isLoading = true
            async let audio = viewModel.engine?.audioTracks() ?? []
            async let subs = viewModel.engine?.subtitleTracks() ?? []
            audioTracks = await audio
            subtitleTracks = await subs
            isLoading = false
        }
    }
}

private struct TrackRow: View {
    let name: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(name)
                    .foregroundColor(.white)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundColor(.blue)
                }
            }
            .padding()
            .background(isSelected ? Color.blue.opacity(0.3) : Color.white.opacity(0.1))
            .cornerRadius(10)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Speed Selection

struct SpeedSelectionView: View {
    @Bindable var viewModel: PlayerViewModel
    let speeds: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    var body: some View {
        VStack(spacing: 10) {
            ForEach(speeds, id: \.self) { speed in
                Button(action: { viewModel.playbackRate = speed }) {
                    HStack {
                        Text(String(format: "%.2fx", speed))
                            .foregroundColor(.white)
                        Spacer()
                        if viewModel.playbackRate == speed {
                            Image(systemName: "checkmark").foregroundColor(.blue)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(
                        viewModel.playbackRate == speed
                            ? Color.blue.opacity(0.3) : Color.white.opacity(0.1)
                    )
                    .cornerRadius(10)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

// MARK: - Filter Settings

struct FilterSettingsView: View {
    @Bindable var viewModel: PlayerViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            Text("Presets").font(.headline).foregroundColor(.white)
            HStack(spacing: 20) {
                ForEach(FilterPreset.allCases, id: \.self) { preset in
                    Button(action: { viewModel.filterPreset = preset }) {
                        Text(preset.displayName)
                            .foregroundColor(.white)
                            .padding()
                            .background(
                                viewModel.filterPreset == preset
                                    ? Color.blue : Color.white.opacity(0.1)
                            )
                            .cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                }
            }

            if viewModel.filterPreset == .custom {
                Divider().background(Color.white.opacity(0.3))
                VStack(spacing: 20) {
                    FilterSlider(
                        label: "Brightness",
                        value: $viewModel.customFilterSettings.brightness,
                        range: -1.0...1.0)
                    FilterSlider(
                        label: "Contrast",
                        value: $viewModel.customFilterSettings.contrast,
                        range: 0.0...4.0)
                    FilterSlider(
                        label: "Saturation",
                        value: $viewModel.customFilterSettings.saturation,
                        range: 0.0...2.0)
                }
            }
        }
    }
}

struct FilterSlider: View {
    let label: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let step: Float = 0.05

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(label).font(.caption).foregroundColor(.gray)
            HStack(spacing: 20) {
                Button(action: { value = max(range.lowerBound, value - step) }) {
                    Image(systemName: "minus.circle")
                }.buttonStyle(.plain)

                Text(String(format: "%.2f", value))
                    .font(.system(.body, design: .monospaced))
                    .foregroundColor(.white)
                    .frame(width: 80)

                Button(action: { value = min(range.upperBound, value + step) }) {
                    Image(systemName: "plus.circle")
                }.buttonStyle(.plain)

                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.1)).frame(height: 4)
                        Capsule()
                            .fill(Color.blue)
                            .frame(
                                width: geo.size.width
                                    * CGFloat(
                                        (value - range.lowerBound)
                                            / (range.upperBound - range.lowerBound)),
                                height: 4)
                    }
                    .frame(maxHeight: .infinity)
                }
                .frame(height: 4)
            }
        }
    }
}
