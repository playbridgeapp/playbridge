import SwiftUI
import Combine

// MARK: - Player UI Models & Overlay

class PlayerControlsData: ObservableObject {
    @Published var isPlaying: Bool = false
    @Published var cacheStalled = false
    @Published var prebuffering = false
    @Published var failureSentence: String?
    @Published var failureHasNextItem = false
    @Published var failureHighlight = PlaybackFailureModel.defaultAction

    var surfaceState: PlaybackSurfaceState {
        .resolve(cacheStalled: cacheStalled, userPaused: userPaused,
                 prebuffering: prebuffering, enginePaused: !isPlaying)
    }
    @Published var userPaused: Bool = false
    @Published var title: String = ""
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    /// Absolute time (seconds) up to which media is buffered ahead. Drives the seek-bar
    /// buffer indicator. 0 hides it.
    @Published var bufferedTime: Double = 0
    @Published var showUI: Bool = true
    @Published var isLooping: Bool = false
    @Published var hasPlaylist = false
    @Published var playlistVisible = false
    @Published var activeSkipSegment: SkipSegment?
    @Published var skipControlsEnabled = true
    /// Sorted chapter markers of the open item (`ChapterNavigation.sorted`).
    @Published var chapters: [TimelineChapter] = []
    /// Skip segments drawn as bands on the seek bar.
    @Published var skipSegments: [SkipSegment] = []
    @Published var metadata = TransportMetadata()
    @Published var showInfoPanel = false
    @Published var panelTab = InfoPanelTab.info
    @Published var panelHighlight = 0
    @Published var panelTabBarFocused = false
    @Published var panelFocusRevision = 0
    @Published var transportFocusRevision = 0
    @Published var feedbackSymbol: String?
    @Published var transportActionFocused = false
    @Published var transportActionHighlight = TransportAction.loop
    @Published var transportActionFocusRevision = 0
    @Published var skipPillFocused = false
    /// Mirror of `PlayerView`'s session tuning; the Settings tab only reads it.
    @Published var tuning = PlayerTuning()
    let settingsAvailability = PlayerSettingsAvailability.forEngine(PlaybackEngine.mpv.rawValue,
        sidecarOverlayActive: false)
    var menuOpen: Bool { showInfoPanel || showEngineMenu }

    var panelTabs: [InfoPanelTab] {
        InfoPanelModel.visibleTabs(chapterCount: chapters.count, settingsAvailable: settingsAvailability.any)
    }

    var panelRows: [InfoPanelRow] {
        switch panelTab {
        case .info: return [InfoPanelRow(id: 0, title: "Play from Beginning")]
        case .chapters:
            return InfoPanelModel.chapterRows(chapters: chapters,
                durationMs: PlaybackTime.milliseconds(duration), positionMs: PlaybackTime.milliseconds(currentTime))
        case .audio:
            return audioTracks.map { InfoPanelRow(id: $0.id, title: $0.name, selected: $0.id == currentAudioIndex) }
        case .subtitles:
            return [InfoPanelRow(id: -1, title: "Off", selected: currentSubtitleIndex == -1)]
                + (subtitleTracks + externalSubtitleTracks).map {
                    InfoPanelRow(id: $0.id, title: $0.name, selected: $0.id == currentSubtitleIndex)
                }
        case .settings: return PlayerSettingsItem.rows(tuning: tuning, availability: settingsAvailability)
        }
    }

    func focusPanelSelection() {
        panelHighlight = InfoPanelModel.initialHighlight(rows: panelRows)
        panelTabBarFocused = false
        panelFocusRevision += 1
    }

    /// Paused touch proposal. Actual currentTime remains the reported playhead.
    @Published var touchScrubTime: Double?
    var isScrubbing: Bool { isVirtualScrubbing || touchScrubTime != nil }

    // Virtual Scrubbing Ghost HUD
    @Published var isVirtualScrubbing: Bool = false
    @Published var scrubMultiplier: Int = 0
    @Published var virtualTime: Double = 0.0

    // Subtitles
    @Published var subtitleTracks: [(id: Int, name: String)] = []
    @Published var externalSubtitleTracks: [(id: Int, name: String)] = []
    @Published var currentSubtitleIndex: Int = -1

    // Audio Tracks
    @Published var audioTracks: [(id: Int, name: String)] = []
    @Published var currentAudioIndex: Int = -1
    @Published var showEngineMenu: Bool = false

    /// Row index inside the open menu. The network remote and the focus ring share it.
    @Published var engineHighlight: Int = 0
}

struct TrackMenuView: View {
    let title: String
    let icon: String
    let tracks: [(id: Int, name: String)]
    let currentId: Int
    let includeOff: Bool
    var showCurrentLabel: Bool = false
    @Binding var highlightedIndex: Int
    let onSelect: (Int) -> Void

    @FocusState private var focusedId: Int?

    private var rowIDs: [Int] {
        PlayerRemoteNavigation.menuRowIDs(trackIDs: tracks.map(\.id), includeOff: includeOff)
    }

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
        .onChange(of: highlightedIndex) { _, index in
            let ids = rowIDs
            guard ids.indices.contains(index), focusedId != ids[index] else { return }
            focusedId = ids[index]
        }
        .onChange(of: focusedId) { _, id in
            guard let id, let index = rowIDs.firstIndex(of: id), highlightedIndex != index else { return }
            highlightedIndex = index
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
                    if showCurrentLabel {
                        Text("Current")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(Theme.accent)
                    }
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

struct PlayerControlsOverlay: View {
    @ObservedObject var data: PlayerControlsData
    let onSelectSubtitle: (Int) -> Void
    let onSelectAudio: (Int) -> Void
    let onTogglePlayPause: () -> Void
    let onSwitchEngine: (PlaybackEngine) -> Void
    let onTogglePlaylist: () -> Void
    let onLoopToggle: () -> Void
    let onSkipSegment: () -> Void
    let onFailureAction: (PlaybackFailureAction) -> Void
    let onPanelActivate: () -> Void
    let onChromeActivity: () -> Void
    let onPanelDirection: (MoveCommandDirection) -> Void
    let onSubtitleClearanceChange: (Double) -> Void
    let engine: PlaybackEngine
    private enum Control: Hashable { case scrubber, loop, engine, playlist }
    @FocusState private var focused: Control?
    @Namespace private var transportActionsScope
    @Environment(\.resetFocus) private var resetTransportFocus
    @State private var transportHeight: CGFloat = 0

    private var displayTime: Double { data.touchScrubTime ?? (data.isVirtualScrubbing ? data.virtualTime : data.currentTime) }
    private var durationMs: Int { PlaybackTime.milliseconds(data.duration) }

    var body: some View {
        ZStack(alignment: .bottom) {
            if data.showUI {
                LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 420).ignoresSafeArea().allowsHitTesting(false)
                VStack(spacing: 24) {
                    transport
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: TransportHeightPreference.self, value: geometry.size.height)
                        })
                        .disabled(data.menuOpen || data.playlistVisible)
                    if data.showInfoPanel {
                        PlaybackInfoPanel(data: data, onActivate: onPanelActivate, onDirection: onPanelDirection)
                    }
                }
                .padding(.horizontal, TransportBarMetrics.horizontalInset)
                .padding(.bottom, TransportBarMetrics.bottomInset)
                .background(GeometryReader { geometry in
                    Color.clear.preference(key: SubtitleClearancePreference.self, value: geometry.size.height)
                })
                .transition(.opacity)
            }

            if data.skipControlsEnabled, let segment = data.activeSkipSegment,
               !data.isScrubbing, !data.menuOpen, !data.playlistVisible {
                HStack {
                    Spacer()
                    SkipSegmentButton(title: segment.buttonTitle,
                        claimsFocus: !data.transportActionFocused,
                        focused: $data.skipPillFocused, action: onSkipSegment)
                }
                .padding(.horizontal, TransportBarMetrics.horizontalInset)
                .padding(.bottom, data.showUI
                    ? max(TransportBarMetrics.skipVisibleInset,
                          transportHeight + TransportBarMetrics.bottomInset + 24)
                    : TransportBarMetrics.skipHiddenInset)
            }

            if data.showEngineMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                TrackMenuView(title: "Switch Player", icon: "arrow.triangle.2.circlepath",
                    tracks: PlaybackEngine.menuOrder(current: engine).map { ($0.menuID, $0.name) },
                    currentId: engine.menuID, includeOff: false, showCurrentLabel: true,
                    highlightedIndex: $data.engineHighlight) { id in
                        data.showEngineMenu = false
                        onChromeActivity()
                        if let target = PlaybackEngine.allCases.first(where: { $0.menuID == id }), target != engine {
                            onSwitchEngine(target)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if !data.prebuffering {
                if let sentence = data.failureSentence {
                    PlaybackFailureOverlay(title: data.title, sentence: sentence,
                        hasNextItem: data.failureHasNextItem, highlighted: $data.failureHighlight,
                        onAction: onFailureAction)
                } else {
                    Group {
                        if data.surfaceState.showsSpinner {
                            ProgressView().tint(.white).scaleEffect(2).frame(width: 64, height: 64)
                                .accessibilityLabel("Buffering")
                        } else if data.isVirtualScrubbing {
                            Text("\(abs(data.scrubMultiplier))×")
                                .font(.system(size: 64, weight: .semibold))
                        } else if let symbol = data.feedbackSymbol {
                            Image(systemName: symbol).font(.system(size: 64, weight: .semibold)).transition(.opacity)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .foregroundStyle(.white).allowsHitTesting(false)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.25), value: data.showUI)
        .animation(.easeInOut(duration: 0.25), value: data.showInfoPanel)
        .animation(.easeOut(duration: 0.2), value: data.feedbackSymbol)
        .onPreferenceChange(SubtitleClearancePreference.self) { onSubtitleClearanceChange(Double($0)) }
        .onPreferenceChange(TransportHeightPreference.self) { transportHeight = $0 }
        .onChange(of: data.transportFocusRevision) { _, _ in focused = .scrubber }
        .onChange(of: data.transportActionFocusRevision) { _, _ in
            switch data.transportActionHighlight {
            case .loop: focused = .loop
            case .switchEngine: focused = .engine
            case .playlist: focused = .playlist
            }
            resetTransportFocus(in: transportActionsScope)
        }
        .onChange(of: focused) { _, value in
            data.transportActionFocused = value != nil && value != .scrubber
            switch value {
            case .loop: data.transportActionHighlight = .loop
            case .engine: data.transportActionHighlight = .switchEngine
            case .playlist: data.transportActionHighlight = .playlist
            default: break
            }
            if value != nil { onChromeActivity() }
        }
        .allowsHitTesting(data.failureSentence != nil || data.showUI || data.menuOpen || data.activeSkipSegment != nil)
    }

    private var transport: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(data.title).font(.system(size: 38, weight: .semibold)).lineLimit(1)
                Spacer()
                Text(engine.name).font(.system(size: 18, weight: .semibold))
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(.ultraThinMaterial, in: Capsule())
            }
            if !data.metadata.subtitle.isEmpty {
                Text(data.metadata.subtitle).font(.system(size: 29)).foregroundStyle(.white.opacity(0.72)).lineLimit(1)
            }
            HStack(alignment: .bottom) {
                Text(ChapterNavigation.timelineCaption(chapters: data.chapters, segments: data.skipSegments,
                    positionMs: PlaybackTime.milliseconds(displayTime), durationMs: durationMs) ?? "")
                    .font(.system(size: 24, weight: .medium)).lineLimit(1).frame(height: 32)
                Spacer()
                HStack(spacing: 24) {
                    action("Loop", icon: "repeat", control: .loop, tint: data.isLooping ? Theme.accent : .white,
                           perform: onLoopToggle)
                    action("Switch", icon: "arrow.triangle.2.circlepath", control: .engine) {
                        data.showInfoPanel = false
                        data.engineHighlight = PlaybackEngine.menuOrder(current: engine).firstIndex(of: engine) ?? 0
                        data.showEngineMenu = true
                        onChromeActivity()
                    }
                    if data.hasPlaylist {
                        action("Playlist", icon: "list.bullet", control: .playlist) {
                            data.showInfoPanel = false
                            onTogglePlaylist()
                        }
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .focusScope(transportActionsScope)
            }
            // Include the leading Spacer in the focus section so UIKit's Up
            // search can reach the trailing buttons from the whole seek bar.
            .focusSection()
            // Reserve space for the complete action labels and focus expansion
            // above the scrubber, independently of the seek button's hit area.
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 12)
            .padding(.bottom, 24)
            Button(action: onTogglePlayPause) {
                HStack(spacing: 24) {
                    Text(TransportTimeLabels.elapsed(displayTime))
                    SeekTrack(progress: data.duration > 0 ? displayTime / data.duration : 0,
                        buffered: data.duration > 0 ? data.bufferedTime / data.duration : nil,
                        chapterFractions: ChapterNavigation.chapterFractions(chapters: data.chapters, durationMs: durationMs),
                        segmentRanges: ChapterNavigation.segmentRanges(segments: data.skipSegments, durationMs: durationMs),
                        focused: focused == .scrubber)
                        .frame(height: TransportBarMetrics.focusedThumbDiameter)
                    Text(TransportTimeLabels.remaining(displayTime, duration: data.duration))
                }
                .font(.system(size: 29, weight: .medium, design: .monospaced))
                .padding(.vertical, 12)
            }
            .buttonStyle(TransportSeekStyle())
            .focused($focused, equals: .scrubber)
            .accessibilityLabel("Playback, \(TransportTimeLabels.elapsed(displayTime)) elapsed, \(TransportTimeLabels.remaining(displayTime, duration: data.duration)) remaining")
            .onAppear {
                if !data.menuOpen && data.activeSkipSegment == nil { focused = .scrubber }
            }
        }
        .foregroundStyle(.white)
    }

    private func isPreferredAction(_ control: Control) -> Bool {
        switch data.transportActionHighlight {
        case .loop: return control == .loop
        case .switchEngine: return control == .engine
        case .playlist: return control == .playlist
        }
    }

    private func action(_ title: String, icon: String, control: Control, tint: Color = .white,
                        perform: @escaping () -> Void) -> some View {
        Button { perform(); onChromeActivity() } label: {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 26)).frame(width: 66, height: 56)
                Text(title).font(.system(size: 22))
            }.foregroundStyle(control == .loop && data.isLooping ? Theme.accent : (focused == control ? .black : tint))
        }
        .buttonStyle(TransportActionStyle(focused: focused == control))
        .focused($focused, equals: control)
        .prefersDefaultFocus(data.transportActionFocused && isPreferredAction(control), in: transportActionsScope)
    }
}

private struct PlaybackInfoPanel: View {
    @ObservedObject var data: PlayerControlsData
    let onActivate: () -> Void
    let onDirection: (MoveCommandDirection) -> Void
    private enum Target: Hashable { case tab(InfoPanelTab), row(Int) }
    @FocusState private var focused: Target?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 32) {
                ForEach(data.panelTabs, id: \.self) { tab in
                    Button(tab.rawValue) { selectTab(tab) }
                        .buttonStyle(InfoPanelTabStyle(selected: data.panelTab == tab,
                            focused: focused == .tab(tab)))
                        .focused($focused, equals: .tab(tab))
                }
            }.font(.system(size: 28, weight: .semibold))
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if data.panelTab == .info {
                            HStack(alignment: .bottom, spacing: 32) {
                                info.frame(maxWidth: .infinity, alignment: .leading)
                                panelRow(index: 0, row: data.panelRows[0]).frame(width: 340)
                            }
                        } else {
                            if data.panelRows.isEmpty {
                                Text("No tracks available").foregroundStyle(.white.opacity(0.7))
                            }
                            ForEach(Array(data.panelRows.enumerated()), id: \.offset) { index, row in
                                if let section = row.section {
                                    Text(section).font(.system(size: 22, weight: .semibold))
                                        .foregroundStyle(.white.opacity(0.6))
                                        .padding(.top, index == 0 ? 0 : 12).padding(.leading, 20)
                                }
                                panelRow(index: index, row: row)
                            }
                        }
                    }.padding(12)
                }
                .onChange(of: data.panelFocusRevision) { _, _ in
                    focused = data.panelTabBarFocused || data.panelRows.isEmpty
                        ? .tab(data.panelTab) : .row(data.panelHighlight)
                    proxy.scrollTo(data.panelHighlight, anchor: .center)
                }
            }
        }
        .padding(32).frame(height: 520)
        .background(.ultraThinMaterial, in: UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .font(.system(size: 26)).foregroundStyle(.white)
        .onChange(of: data.panelRows.map(\.id)) { _, _ in data.focusPanelSelection() }
        .onMoveCommand(perform: onDirection)
        .onAppear { focused = data.panelRows.isEmpty ? .tab(data.panelTab) : .row(data.panelHighlight) }
        .onChange(of: focused) { _, target in
            switch target {
            case .tab(let tab):
                data.panelTabBarFocused = true
                if data.panelTab != tab { data.panelTab = tab }
            case .row(let index):
                data.panelTabBarFocused = false
                data.panelHighlight = index
            case nil: break
            }
        }
    }

    private func panelRow(index: Int, row: InfoPanelRow) -> some View {
        Button {
            data.panelHighlight = index
            onActivate()
        } label: {
            HStack {
                Text(row.title).lineLimit(1)
                Spacer()
                if let trailingText = row.trailingText {
                    Text(trailingText).monospacedDigit().opacity(0.8)
                    Image(systemName: "checkmark")
                        .foregroundStyle(Theme.accent)
                        .opacity(row.selected ? 1 : 0)
                        .frame(width: 28)
                } else if row.selected {
                    Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .foregroundStyle(focused == .row(index) ? .black : .white)
        }
        .buttonStyle(.card).focused($focused, equals: .row(index)).id(index)
    }

    private func selectTab(_ tab: InfoPanelTab) {
        data.panelTab = tab
        data.focusPanelSelection()
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(data.title).font(.system(size: 32, weight: .semibold)).lineLimit(1)
            if !data.metadata.subtitle.isEmpty { Text(data.metadata.subtitle).foregroundStyle(.white.opacity(0.72)) }
            HStack(spacing: 16) {
                ForEach([data.metadata.year, data.metadata.rating, data.metadata.runtime].filter { !$0.isEmpty }, id: \.self) { text in
                    Text(text).font(.system(size: 22)).padding(8).background(.white.opacity(0.1), in: Capsule())
                }
            }
            if !data.metadata.overview.isEmpty { Text(data.metadata.overview).lineLimit(4).font(.system(size: 24)) }
            if !data.metadata.genres.isEmpty {
                Text(data.metadata.genres.joined(separator: " · ")).font(.system(size: 22)).foregroundStyle(.white.opacity(0.7))
            }
        }
    }
}

/// Unlike tvOS's plain style, this never supplies a focus platter. The track
/// and thumb draw the seek control's focus indication inside its own bounds.
private struct TransportSeekStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

private struct TransportActionStyle: ButtonStyle {
    let focused: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 16)
                .fill(focused ? Color.white : Color.white.opacity(0.12)))
            .scaleEffect(configuration.isPressed ? 0.97 : (focused ? 1.06 : 1))
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// The selected-tab pill remains visible while focus moves into that tab's
/// rows. UIKit focus memory must not leave a separate pill on an old tab.
private struct InfoPanelTabStyle: ButtonStyle {
    let selected: Bool
    let focused: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(selected ? Color.black : Color.white.opacity(0.7))
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(Capsule().fill(selected ? Color.white : Color.clear))
            .overlay(Capsule().stroke(Color.white.opacity(selected && focused ? 0.9 : 0), lineWidth: 2))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// The named Skip inset is a minimum: metadata can make the cluster taller.
/// Measure it so the pill also clears large text and the action focus platter.
private struct SubtitleClearancePreference: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct TransportHeightPreference: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct PlaybackFailureOverlay: View {
    let title: String
    let sentence: String
    let hasNextItem: Bool
    @Binding var highlighted: PlaybackFailureAction
    let onAction: (PlaybackFailureAction) -> Void
    @FocusState private var focused: PlaybackFailureAction?

    private static func icon(_ action: PlaybackFailureAction) -> String {
        switch action {
        case .tryAgain: return "arrow.clockwise"
        case .next: return "forward.end.fill"
        case .close: return "xmark"
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 32) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 64))
                    .foregroundColor(.white.opacity(0.8))
                if !title.isEmpty {
                    Text(title).font(.system(size: 36, weight: .semibold)).lineLimit(1)
                }
                Text(sentence).font(.system(size: 28)).lineLimit(1)
                HStack(spacing: 32) {
                    ForEach(PlaybackFailureModel.actions(hasNextItem: hasNextItem), id: \.self) { action in
                        Button { onAction(action) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: Self.icon(action))
                                    .font(.system(size: 24, weight: .semibold))
                                Text(action.rawValue)
                                    .font(.system(size: 26, weight: .semibold))
                                    .lineLimit(1)
                                    .fixedSize()
                            }
                        }
                        // Same pill as Skip Intro: no tvOS card platter around a tight label.
                        .buttonStyle(SkipPillStyle(focused: focused == action))
                        .focused($focused, equals: action)
                    }
                }
                .padding(.top, 16)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 80)
        }
        .onAppear { focused = PlaybackFailureModel.defaultAction }
        .onChange(of: highlighted) { _, value in focused = value }
        .onChange(of: focused) { _, value in
            if let value { highlighted = value }
        }
    }
}

/// The seek bar: skip-segment bands under the track, then the track, the
/// buffered range, and the played range, with chapter gaps cleared through all
/// of them (the phone remote's draw order, scaled for the TV).
private struct SeekTrack: View {
    static let lineHeight: CGFloat = TransportBarMetrics.lineHeight
    static let gapWidth: CGFloat = 4

    let progress: Double
    let buffered: Double?
    let chapterFractions: [Double]
    let segmentRanges: [ClosedRange<Double>]
    let focused: Bool

    var body: some View {
        Canvas { context, size in
            let width = size.width
            let midY = size.height / 2
            func capsule(from start: Double, to end: Double, height: CGFloat) -> Path {
                let x0 = width * CGFloat(min(max(start, 0), 1))
                let x1 = width * CGFloat(min(max(end, 0), 1))
                let rect = CGRect(x: x0, y: midY - height / 2, width: max(x1 - x0, 0), height: height)
                return Path(roundedRect: rect, cornerRadius: height / 2)
            }

            for range in segmentRanges {
                context.fill(capsule(from: range.lowerBound, to: range.upperBound, height: TransportBarMetrics.frameHeight),
                             with: .color(Theme.segment.opacity(0.35)))
            }
            context.fill(capsule(from: 0, to: 1, height: Self.lineHeight), with: .color(.white.opacity(focused ? 0.3 : 0.2)))
            if let buffered, buffered > progress {
                context.fill(capsule(from: 0, to: buffered, height: Self.lineHeight),
                             with: .color(.white.opacity(0.35)))
            }
            if progress > 0 {
                let played = capsule(from: 0, to: progress, height: Self.lineHeight)
                context.fill(played, with: .color(.white))
            }

            let thumbX = width * CGFloat(min(max(progress, 0), 1))
            let diameter = focused ? TransportBarMetrics.focusedThumbDiameter : TransportBarMetrics.thumbDiameter
            context.fill(Path(ellipseIn: CGRect(x: thumbX - diameter / 2, y: midY - diameter / 2,
                width: diameter, height: diameter)), with: .color(.white))

            context.blendMode = .clear
            for fraction in chapterFractions {
                let x = width * CGFloat(fraction)
                guard x > Self.gapWidth, x < width - Self.gapWidth else { continue }
                context.fill(Path(CGRect(x: x - Self.gapWidth / 2, y: 0, width: Self.gapWidth, height: size.height)),
                             with: .color(.black))
            }
        }
        .compositingGroup()
        .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// Skip Intro / Credits pill. It takes focus as it appears unless the transport
/// actions already own focus, keeping Select on the active Skip action.
private struct SkipSegmentButton: View {
    let title: String
    let claimsFocus: Bool
    @Binding var focused: Bool
    let action: () -> Void
    @FocusState private var hasFocus: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "forward.end.fill")
                    .font(.system(size: 24, weight: .semibold))
                Text(title)
                    .font(.system(size: 26, weight: .semibold))
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        // A custom style replaces tvOS's plain-button focus platter, which would
        // otherwise draw a second rounded outline around the pill.
        .buttonStyle(SkipPillStyle(focused: focused))
        .focused($hasFocus)
        .onAppear { if claimsFocus { hasFocus = true } }
        .onChange(of: hasFocus) { _, value in focused = value }
        .onDisappear { focused = false }
    }
}

private struct SkipPillStyle: ButtonStyle {
    let focused: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(focused ? .black : .white)
            .padding(.horizontal, 32)
            .padding(.vertical, 18)
            .background(Capsule().fill(focused ? Theme.accent : Color.black.opacity(0.55)))
            .overlay(Capsule().stroke(Color.white.opacity(focused ? 0 : 0.35), lineWidth: 2))
            .scaleEffect(configuration.isPressed ? 0.97 : (focused ? 1.08 : 1.0))
            .shadow(color: .black.opacity(0.4), radius: focused ? 16 : 6, y: 6)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}
