//
//  PlayerControlView.swift
//  PlayBridge TV
//

import SwiftUI

struct PlayerControlView: View {
    @Bindable var viewModel: PlayerViewModel
    @State private var selectedTab: ControlTab = .tracks

    enum ControlTab { case tracks, speed, filters }

    var body: some View {
        VStack(spacing: 0) {
            // Tab bar
            HStack(spacing: 8) {
                ControlTabButton(title: "Tracks",        tab: .tracks,  selected: $selectedTab)
                ControlTabButton(title: "Speed",         tab: .speed,   selected: $selectedTab)
                ControlTabButton(title: "Filters",       tab: .filters, selected: $selectedTab)
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 16)

            Divider().background(Color.white.opacity(0.2))

            ScrollView {
                Group {
                    switch selectedTab {
                    case .tracks:  TrackSelectionView(viewModel: viewModel)
                    case .speed:   SpeedSelectionView(viewModel: viewModel)
                    case .filters: FilterSettingsView(viewModel: viewModel)
                    }
                }
                .padding(24)
            }
        }
        .frame(maxWidth: 860)
        .frame(minHeight: 460)
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(Color(white: 0.08))
                .overlay(
                    RoundedRectangle(cornerRadius: 24)
                        .strokeBorder(Color.white.opacity(0.1), lineWidth: 1)
                )
        )
        .padding(60)
    }
}

// MARK: - Tab Button

private struct ControlTabButton: View {
    let title: String
    let tab: PlayerControlView.ControlTab
    @Binding var selected: PlayerControlView.ControlTab

    @FocusState private var isFocused: Bool

    private var isSelected: Bool { selected == tab }

    var body: some View {
        Button { selected = tab } label: {
            Text(title)
                .font(.headline)
                .foregroundColor(isSelected || isFocused ? .white : .gray)
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(
                            isSelected
                                ? Color.blue.opacity(0.35)
                                : isFocused ? Color.white.opacity(0.12) : Color.clear
                        )
                )
        }
        .buttonStyle(.plain)
        .focused($isFocused)
        .animation(.easeOut(duration: 0.15), value: isFocused)
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
        VStack(alignment: .leading, spacing: 20) {
            if isLoading {
                HStack { Spacer(); ProgressView().tint(.white); Spacer() }
                    .padding(.vertical, 20)
            } else if audioTracks.isEmpty && subtitleTracks.isEmpty {
                Text("No embedded tracks available")
                    .foregroundColor(.gray)
                    .padding(.vertical, 20)
                    .frame(maxWidth: .infinity, alignment: .center)
            } else {
                if !audioTracks.isEmpty {
                    SectionLabel("Audio")
                    ForEach(audioTracks, id: \.id) { track in
                        TrackRow(name: track.name, isSelected: selectedAudio == track.id) {
                            selectedAudio = track.id
                            viewModel.engine?.setAudioTrack(track.id)
                        }
                    }
                }

                if !subtitleTracks.isEmpty {
                    SectionLabel("Subtitles").padding(.top, audioTracks.isEmpty ? 0 : 8)
                    TrackRow(name: "Off", isSelected: selectedSubtitle == nil) {
                        selectedSubtitle = nil
                        viewModel.engine?.setSubtitleTrack(nil)
                    }
                    ForEach(subtitleTracks, id: \.id) { track in
                        TrackRow(name: track.name, isSelected: selectedSubtitle == track.id) {
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
            async let subs  = viewModel.engine?.subtitleTracks() ?? []
            audioTracks    = await audio
            subtitleTracks = await subs
            isLoading = false
        }
    }
}

// MARK: - Speed Selection

struct SpeedSelectionView: View {
    @Bindable var viewModel: PlayerViewModel
    let speeds: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    var body: some View {
        VStack(spacing: 10) {
            ForEach(speeds, id: \.self) { speed in
                PickerRow(
                    label: String(format: "%.2f×", speed),
                    isSelected: viewModel.playbackRate == speed
                ) {
                    viewModel.playbackRate = speed
                }
            }
        }
    }
}

// MARK: - Filter Settings

struct FilterSettingsView: View {
    @Bindable var viewModel: PlayerViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            SectionLabel("Presets")

            HStack(spacing: 12) {
                ForEach(FilterPreset.allCases, id: \.self) { preset in
                    PresetButton(
                        title: preset.displayName,
                        isSelected: viewModel.filterPreset == preset
                    ) {
                        viewModel.filterPreset = preset
                    }
                }
            }

            if viewModel.filterPreset == .custom {
                Divider().background(Color.white.opacity(0.2)).padding(.vertical, 4)
                VStack(spacing: 20) {
                    FilterSlider(label: "Brightness",
                                 value: $viewModel.customFilterSettings.brightness,
                                 range: -1.0...1.0)
                    FilterSlider(label: "Contrast",
                                 value: $viewModel.customFilterSettings.contrast,
                                 range: 0.0...4.0)
                    FilterSlider(label: "Saturation",
                                 value: $viewModel.customFilterSettings.saturation,
                                 range: 0.0...2.0)
                }
            }
        }
    }
}

// MARK: - Reusable row components

private struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundColor(.secondary)
            .textCase(.uppercase)
            .tracking(1)
    }
}

private struct TrackRow: View {
    let name: String
    let isSelected: Bool
    let action: () -> Void
    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            HStack {
                Text(name).foregroundColor(.white)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.blue)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(rowBackground(selected: isSelected, focused: isFocused))
            )
        }
        .buttonStyle(.plain)
        .focused($isFocused)
        .animation(.easeOut(duration: 0.12), value: isFocused)
    }
}

private struct PickerRow: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void
    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            HStack {
                Text(label).foregroundColor(.white)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.blue)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(rowBackground(selected: isSelected, focused: isFocused))
            )
        }
        .buttonStyle(.plain)
        .focused($isFocused)
        .animation(.easeOut(duration: 0.12), value: isFocused)
    }
}

private struct PresetButton: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void
    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            Text(title)
                .foregroundColor(.white)
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(
                            isSelected
                                ? Color.blue.opacity(isFocused ? 0.7 : 0.5)
                                : Color.white.opacity(isFocused ? 0.18 : 0.08)
                        )
                )
        }
        .buttonStyle(.plain)
        .focused($isFocused)
        .animation(.easeOut(duration: 0.12), value: isFocused)
    }
}

// MARK: - Filter Slider

struct FilterSlider: View {
    let label: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let step: Float = 0.05

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(label).font(.callout).foregroundColor(.secondary)
                Spacer()
                Text(String(format: "%.2f", value))
                    .font(.system(.callout, design: .monospaced))
                    .foregroundColor(.white)
                    .frame(width: 56, alignment: .trailing)
            }

            HStack(spacing: 16) {
                SliderStepButton(systemName: "minus.circle.fill") {
                    value = max(range.lowerBound, value - step)
                }

                // Track — overlay keeps geo width stable inside ScrollView
                Capsule()
                    .fill(Color.white.opacity(0.15))
                    .frame(height: 6)
                    .overlay(
                        GeometryReader { geo in
                            Capsule()
                                .fill(Color.blue)
                                .frame(
                                    width: max(
                                        0,
                                        geo.size.width
                                            * CGFloat(
                                                (value - range.lowerBound)
                                                    / (range.upperBound - range.lowerBound))),
                                    height: 6)
                        },
                        alignment: .leading
                    )

                SliderStepButton(systemName: "plus.circle.fill") {
                    value = min(range.upperBound, value + step)
                }
            }
        }
    }
}

private struct SliderStepButton: View {
    let systemName: String
    let action: () -> Void
    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 28))
                .foregroundColor(isFocused ? .white : .gray)
                .scaleEffect(isFocused ? 1.15 : 1.0)
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}

// MARK: - Shared helper

private func rowBackground(selected: Bool, focused: Bool) -> Color {
    if selected { return Color.blue.opacity(focused ? 0.45 : 0.25) }
    return Color.white.opacity(focused ? 0.15 : 0.07)
}
