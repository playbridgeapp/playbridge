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
    /// Chapter card frames keyed by chapter start (ms); missing ones show a placeholder.
    @Published var chapterThumbnails: [Int: UIImage] = [:]
    /// Skip segments drawn as bands on the seek bar.
    @Published var skipSegments: [SkipSegment] = []
    @Published var metadata = TransportMetadata()
    @Published var showInfoPanel = false
    @Published var panelTab = InfoPanelTab.info
    @Published var panelHighlight = 0
    @Published var panelTabBarFocused = false
    @Published var panelFocusRevision = 0
    /// When the panel last applied a move. The Simulator's keyboard can report
    /// one key press as two move commands.
    var panelMovedAt = Date.distantPast
    @Published var transportFocusRevision = 0
    @Published var feedbackSymbol: String?
    @Published var transportActionFocused = false
    @Published var transportActionHighlight = TransportAction.loop
    @Published var transportActionFocusRevision = 0
    @Published var skipPillFocused = false
    /// Mirror of `PlayerView`'s session tuning; the Settings tab only reads it.
    @Published var tuning = PlayerTuning()
    /// Settings tab rows. Speed is left out: the transport's Speed button owns it.
    let settingsAvailability = PlayerSettingsAvailability.forEngine(PlaybackEngine.mpv.rawValue,
        sidecarOverlayActive: false).withoutSpeed
    var menuOpen: Bool { showInfoPanel || showEngineMenu }

    var panelTabs: [InfoPanelTab] {
        InfoPanelModel.visibleTabs(chapterCount: chapters.count, settingsAvailable: settingsAvailability.any,
            hasInfo: !metadata.isEmpty)
    }

    /// The Subtitles button appears with the first embedded or sidecar track.
    var hasSubtitles: Bool { !subtitleTracks.isEmpty || !externalSubtitleTracks.isEmpty }

    /// The Audio button appears only when there is a track to switch to.
    var hasAudioChoice: Bool { audioTracks.count >= 2 }

    var availableActions: [TransportAction] {
        TransportAction.available(hasPlaylist: hasPlaylist, hasSubtitles: hasSubtitles, hasAudioChoice: hasAudioChoice)
    }

    /// Metadata line under the title, dropped when the title already carries it.
    var displaySubtitle: String { TransportTimeLabels.distinctSubtitle(metadata.subtitle, title: title) }

    var panelRows: [InfoPanelRow] {
        switch panelTab {
        case .info: return [InfoPanelRow(id: 0, title: "Play from Beginning")]
        case .chapters:
            return InfoPanelModel.chapterRows(chapters: chapters,
                durationMs: PlaybackTime.milliseconds(duration), positionMs: PlaybackTime.milliseconds(currentTime))
        case .settings: return PlayerSettingsItem.rows(tuning: tuning, availability: settingsAvailability)
        }
    }

    /// Highlights the tab's selected row. `keepTabBar` leaves the highlight on the
    /// tab bar while the user moves along the tabs.
    func focusPanelSelection(keepTabBar: Bool = false) {
        panelHighlight = InfoPanelModel.initialHighlight(rows: panelRows)
        panelTabBarFocused = keepTabBar && panelTabBarFocused
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
    /// A transport pop-up menu is open; `transportMenu` says which.
    @Published var showEngineMenu: Bool = false
    @Published var transportMenu = TransportMenu.engine

    func transportMenuRows(engine: PlaybackEngine) -> [TransportMenuRow] {
        switch transportMenu {
        case .engine: return TransportMenuModel.engineRows(current: engine)
        case .speed: return TransportMenuModel.speedRows(current: tuning.speed)
        case .subtitles:
            return TransportMenuModel.subtitleRows(tracks: subtitleTracks + externalSubtitleTracks,
                                                   current: currentSubtitleIndex)
        case .audio: return TransportMenuModel.audioRows(tracks: audioTracks, current: currentAudioIndex)
        }
    }

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
    let onOpenMenu: (TransportMenu) -> Void
    let onMenuSelect: (TransportMenu, Int) -> Void
    let onTogglePlaylist: () -> Void
    let onLoopToggle: () -> Void
    let onSkipSegment: () -> Void
    let onFailureAction: (PlaybackFailureAction) -> Void
    let onPanelActivate: () -> Void
    let onChromeActivity: () -> Void
    let onPanelDirection: (MoveCommandDirection) -> Void
    let onSubtitleClearanceChange: (Double) -> Void
    let engine: PlaybackEngine
    private enum Control: Hashable { case scrubber, loop, speed, engine, subtitles, audio, playlist }
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
                // Pop-up above the action row, as AVPlayer's contextual menus.
                let menu = data.transportMenu
                let rows = data.transportMenuRows(engine: engine)
                TrackMenuView(title: menu.title, icon: menu.icon,
                    tracks: rows.map { ($0.id, $0.title) },
                    currentId: rows.first(where: \.selected)?.id ?? Int.min, includeOff: false,
                    showCurrentLabel: menu == .engine,
                    highlightedIndex: $data.engineHighlight) { id in
                        data.showEngineMenu = false
                        onChromeActivity()
                        onMenuSelect(menu, id)
                    }
                    .id(menu)
                    .frame(width: 540)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(.trailing, TransportBarMetrics.horizontalInset)
                    .padding(.bottom, transportHeight + TransportBarMetrics.bottomInset - 40)
                    .transition(.opacity)
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
            focused = Self.control(for: data.transportActionHighlight)
            resetTransportFocus(in: transportActionsScope)
        }
        .onChange(of: focused) { _, value in
            data.transportActionFocused = value != nil && value != .scrubber
            if let action = TransportAction.allCases.first(where: { Self.control(for: $0) == value }) {
                data.transportActionHighlight = action
            }
            if value != nil { onChromeActivity() }
        }
        .allowsHitTesting(data.failureSentence != nil || data.showUI || data.menuOpen || data.activeSkipSegment != nil)
    }

    private var transport: some View {
        VStack(alignment: .leading, spacing: 10) {
            // With the panel open only the seek row stays, so the panel reads
            // as a light sheet under the timeline (AVPlayer's info layout).
            if !data.showInfoPanel { header }
            seekRow
        }
        .foregroundStyle(.white)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            // AVPlayer's title block: a large bold title with the action row level
            // with it. The Switch menu names the engine, so no engine badge here.
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(data.title).font(.system(size: 48, weight: .bold)).lineLimit(1)
                        .shadow(color: .black.opacity(0.4), radius: 8, y: 2)
                    if !data.displaySubtitle.isEmpty {
                        Text(data.displaySubtitle).font(.system(size: 28, weight: .medium))
                            .foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                    }
                    let caption = ChapterNavigation.timelineCaption(chapters: data.chapters, segments: data.skipSegments,
                        positionMs: PlaybackTime.milliseconds(displayTime), durationMs: durationMs) ?? ""
                    if !caption.isEmpty {
                        Text(caption).font(.system(size: 24, weight: .medium))
                            .foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                    }
                }
                .layoutPriority(1)
                Spacer(minLength: 32)
                HStack(spacing: 24) {
                    action("Loop", icon: "repeat", control: .loop, tint: data.isLooping ? Theme.accent : .white,
                           perform: onLoopToggle)
                    action("Speed", icon: TransportMenu.speed.icon, control: .speed) { onOpenMenu(.speed) }
                    action("Switch", icon: TransportMenu.engine.icon, control: .engine) { onOpenMenu(.engine) }
                    if data.hasSubtitles {
                        action("Subtitles", icon: TransportMenu.subtitles.icon, control: .subtitles) {
                            onOpenMenu(.subtitles)
                        }
                    }
                    if data.hasAudioChoice {
                        action("Audio", icon: TransportMenu.audio.icon, control: .audio) { onOpenMenu(.audio) }
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
        }
    }

    private var seekRow: some View {
            // AVPlayer's layout: a full-width bar, elapsed time riding under the
            // playhead, remaining time at the end; both dim when focus leaves the bar.
            Button(action: onTogglePlayPause) {
                let fraction = data.duration > 0 ? displayTime / data.duration : 0
                VStack(spacing: 12) {
                    SeekTrack(progress: fraction,
                        buffered: data.duration > 0 ? data.bufferedTime / data.duration : nil,
                        chapterFractions: ChapterNavigation.chapterFractions(chapters: data.chapters, durationMs: durationMs),
                        segmentRanges: ChapterNavigation.segmentRanges(segments: data.skipSegments, durationMs: durationMs),
                        focused: focused == .scrubber)
                        .frame(height: TransportBarMetrics.focusedThumbDiameter)
                    SeekTimeLabels(fraction: fraction,
                        elapsed: TransportTimeLabels.clock(displayTime),
                        remaining: TransportTimeLabels.clockRemaining(displayTime, duration: data.duration),
                        paused: data.userPaused && !data.isScrubbing)
                        .font(.system(size: 24, weight: .semibold).monospacedDigit())
                        .opacity(focused == .scrubber ? 0.9 : 0.55)
                }
                .padding(.vertical, 8)
                .animation(.easeOut(duration: 0.15), value: focused == .scrubber)
            }
            .buttonStyle(TransportSeekStyle())
            .focused($focused, equals: .scrubber)
            .accessibilityLabel("Playback, \(TransportTimeLabels.elapsed(displayTime)) elapsed, \(TransportTimeLabels.remaining(displayTime, duration: data.duration)) remaining")
            .onAppear {
                if !data.menuOpen && data.activeSkipSegment == nil { focused = .scrubber }
            }
    }

    private static func control(for action: TransportAction) -> Control {
        switch action {
        case .loop: return .loop
        case .speed: return .speed
        case .switchEngine: return .engine
        case .subtitles: return .subtitles
        case .audio: return .audio
        case .playlist: return .playlist
        }
    }

    private func isPreferredAction(_ control: Control) -> Bool {
        Self.control(for: data.transportActionHighlight) == control
    }

    private func action(_ title: String, icon: String, control: Control, tint: Color = .white,
                        perform: @escaping () -> Void) -> some View {
        // AVPlayer's transport actions: icon-only circles; the name is for VoiceOver.
        Button { perform(); onChromeActivity() } label: {
            Image(systemName: icon).font(.system(size: 28, weight: .medium))
                .frame(width: TransportActionStyle.diameter, height: TransportActionStyle.diameter)
                .foregroundStyle(control == .loop && data.isLooping ? Theme.accent : (focused == control ? .black : tint))
        }
        .buttonStyle(TransportActionStyle(focused: focused == control))
        .accessibilityLabel(title)
        .focused($focused, equals: control)
        .prefersDefaultFocus(data.transportActionFocused && isPreferredAction(control), in: transportActionsScope)
    }
}

/// One focusable surface. Tabs, rows and chapter cards are drawn from the model
/// highlight and every direction arrives through onMoveCommand, so UIKit focus
/// never moves inside the panel on its own (no double or reverted moves).
private struct PlaybackInfoPanel: View {
    @ObservedObject var data: PlayerControlsData
    let onActivate: () -> Void
    let onDirection: (MoveCommandDirection) -> Void
    @FocusState private var surfaceFocused: Bool

    private func highlighted(_ index: Int) -> Bool {
        !data.panelTabBarFocused && data.panelHighlight == index
    }

    var body: some View {
        Button(action: onActivate) { content }
            .buttonStyle(InfoPanelSurfaceStyle())
            .focused($surfaceFocused)
            .onMoveCommand(perform: onDirection)
            .onAppear { surfaceFocused = true }
            .onChange(of: data.panelFocusRevision) { _, _ in surfaceFocused = true }
            .onChange(of: data.panelRows.map(\.id)) { _, _ in data.focusPanelSelection(keepTabBar: true) }
            // Tracks arrive after the panel opens; a tab can appear or go away.
            .onChange(of: data.panelTabs) { _, tabs in
                let tab = InfoPanelModel.validTab(data.panelTab, tabs: tabs)
                guard tab != data.panelTab else { return }
                data.panelTab = tab
                data.focusPanelSelection(keepTabBar: true)
            }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 32) {
                ForEach(data.panelTabs, id: \.self) { tab in
                    Text(tab.rawValue)
                        .modifier(InfoPanelTabLook(selected: data.panelTab == tab,
                            focused: data.panelTabBarFocused && data.panelTab == tab))
                }
            }.font(.system(size: 28, weight: .semibold))
            Divider()
            ScrollViewReader { proxy in
                ScrollView(data.panelTab == .chapters ? .horizontal : .vertical, showsIndicators: false) {
                    if data.panelTab == .chapters {
                        chapterCards
                    } else {
                        VStack(alignment: .leading, spacing: 14) {
                            if data.panelTab == .info {
                                HStack(alignment: .bottom, spacing: 32) {
                                    poster
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
                }
                // A new tab (or a freshly opened panel) starts on its selected row.
                .id(data.panelTab)
                .onAppear { DispatchQueue.main.async { proxy.scrollTo(data.panelHighlight, anchor: .center) } }
                .onChange(of: data.panelFocusRevision) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(data.panelHighlight, anchor: .center)
                    }
                }
            }
        }
        .padding(.horizontal, 32).padding(.top, 24).padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Info fits its poster and synopsis; Chapters its cards.
        .frame(height: data.panelTab == .settings ? 380 : 420)
        .background(.ultraThinMaterial, in: UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .font(.system(size: 26)).foregroundStyle(.white)
    }

    private func panelRow(index: Int, row: InfoPanelRow) -> some View {
        let lit = highlighted(index)
        return HStack {
            Text(row.title).lineLimit(1)
            Spacer()
            if let trailingText = row.trailingText {
                Text(trailingText).monospacedDigit().opacity(0.8)
                Image(systemName: "checkmark")
                    .foregroundStyle(lit ? .black : Theme.accent)
                    .opacity(row.selected ? 1 : 0)
                    .frame(width: 28)
            } else if row.selected {
                Image(systemName: "checkmark").foregroundStyle(lit ? .black : Theme.accent)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
        .foregroundStyle(lit ? .black : .white)
        .background(RoundedRectangle(cornerRadius: 12).fill(lit ? Color.white : Color.white.opacity(0.06)))
        .scaleEffect(lit ? 1.02 : 1)
        .animation(.easeOut(duration: 0.15), value: lit)
        .id(index)
    }

    /// AVPlayer-style chapter strip: frame, "WATCHING" on the current chapter,
    /// title and start time. Left/Right move along the strip.
    private var chapterCards: some View {
        HStack(spacing: 40) {
            ForEach(Array(data.panelRows.enumerated()), id: \.offset) { index, row in
                ChapterCard(row: row, thumbnail: data.chapterThumbnails[row.id], focused: highlighted(index))
                    .id(index)
            }
        }
        .padding(.vertical, 24).padding(.horizontal, 24)
    }

    /// AVPlayer's Info artwork: the poster beside the title and synopsis.
    @ViewBuilder
    private var poster: some View {
        let urls = ArtworkURL.candidates(data.metadata.posterURL, kind: .poster)
        if !urls.isEmpty {
            ArtworkImage(urls: urls) { image in
                image.resizable().aspectRatio(2 / 3, contentMode: .fill)
            } placeholder: {
                Color.white.opacity(0.06)
            }
            .frame(width: 160, height: 240)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.4), radius: 12, y: 6)
        }
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(data.title).font(.system(size: 32, weight: .semibold)).lineLimit(2)
            if !data.displaySubtitle.isEmpty { Text(data.displaySubtitle).foregroundStyle(.white.opacity(0.72)) }
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

/// The panel surface draws no focus platter; its highlight is the lit row or tab.
private struct InfoPanelSurfaceStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.9 : 1)
    }
}

private struct ChapterCard: View {
    static let size = CGSize(width: 400, height: 225)
    let row: InfoPanelRow
    let thumbnail: UIImage?
    let focused: Bool

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let thumbnail {
                    Image(uiImage: thumbnail).resizable().aspectRatio(contentMode: .fill)
                } else {
                    LinearGradient(colors: [.white.opacity(0.18), .white.opacity(0.06)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                        .overlay(Image(systemName: "film").font(.system(size: 44)).opacity(0.35))
                }
            }
            .frame(width: Self.size.width, height: Self.size.height).clipped()
            LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 2) {
                if row.selected {
                    Text("WATCHING").font(.system(size: 16, weight: .semibold)).opacity(0.85)
                }
                Text(row.title).font(.system(size: 24, weight: .semibold)).lineLimit(1)
                if let time = row.trailingText {
                    Text(time).font(.system(size: 20).monospacedDigit()).opacity(0.75)
                }
            }
            .foregroundStyle(.white).padding(16)
        }
        .frame(width: Self.size.width, height: Self.size.height)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white, lineWidth: focused ? 4 : 0))
        .scaleEffect(focused ? 1.08 : 1)
        .shadow(color: .black.opacity(focused ? 0.5 : 0.2), radius: focused ? 18 : 6, y: focused ? 10 : 4)
        .animation(.easeOut(duration: 0.15), value: focused)
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
    static let diameter: CGFloat = 72
    let focused: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if focused { Circle().fill(Color.white) }
                else { Circle().fill(.ultraThinMaterial) }
            }
            .clipShape(Circle())
            .shadow(color: .black.opacity(focused ? 0.35 : 0), radius: 10, y: 4)
            .scaleEffect(configuration.isPressed ? 0.95 : (focused ? 1.12 : 1))
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// The selected tab keeps its pill while the rows are highlighted; a ring marks
/// the tab bar itself as the highlight.
private struct InfoPanelTabLook: ViewModifier {
    let selected: Bool
    let focused: Bool

    func body(content: Content) -> some View {
        content
            .foregroundStyle(selected ? Color.black : Color.white.opacity(0.7))
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(Capsule().fill(selected ? Color.white : Color.clear))
            .overlay(Capsule().stroke(Theme.accent, lineWidth: focused ? 3 : 0).padding(-4))
            .scaleEffect(focused ? 1.06 : 1)
            .animation(.easeOut(duration: 0.15), value: focused)
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
            let line = CGFloat(focused ? TransportBarMetrics.focusedLineHeight : TransportBarMetrics.lineHeight)
            func capsule(from start: Double, to end: Double, height: CGFloat) -> Path {
                let x0 = width * CGFloat(min(max(start, 0), 1))
                let x1 = width * CGFloat(min(max(end, 0), 1))
                let rect = CGRect(x: x0, y: midY - height / 2, width: max(x1 - x0, 0), height: height)
                return Path(roundedRect: rect, cornerRadius: height / 2)
            }

            for range in segmentRanges {
                context.fill(capsule(from: range.lowerBound, to: range.upperBound,
                                     height: max(CGFloat(TransportBarMetrics.frameHeight), line + 8)),
                             with: .color(Theme.segment.opacity(0.35)))
            }
            context.fill(capsule(from: 0, to: 1, height: line), with: .color(.white.opacity(focused ? 0.25 : 0.18)))
            if let buffered, buffered > progress {
                context.fill(capsule(from: 0, to: buffered, height: line), with: .color(.white.opacity(0.32)))
            }
            if progress > 0 {
                context.fill(capsule(from: 0, to: progress, height: line),
                             with: .color(.white.opacity(focused ? 0.85 : 0.6)))
            }

            context.blendMode = .clear
            for fraction in chapterFractions {
                let x = width * CGFloat(fraction)
                guard x > Self.gapWidth, x < width - Self.gapWidth else { continue }
                context.fill(Path(CGRect(x: x - Self.gapWidth / 2, y: 0, width: Self.gapWidth, height: size.height)),
                             with: .color(.black))
            }

            // Playhead tick, drawn last so a chapter gap never cuts it.
            context.blendMode = .normal
            let tickX = width * CGFloat(min(max(progress, 0), 1))
            let tickWidth = CGFloat(TransportBarMetrics.playheadWidth)
            let tickHeight = line + CGFloat(TransportBarMetrics.playheadOverhang) * 2
            context.fill(Path(roundedRect: CGRect(x: min(max(tickX - tickWidth / 2, 0), width - tickWidth),
                                                  y: midY - tickHeight / 2, width: tickWidth, height: tickHeight),
                              cornerRadius: tickWidth / 2),
                         with: .color(.white))
        }
        .compositingGroup()
        .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// AVPlayer's time row: elapsed under the playhead, remaining at the trailing end.
private struct SeekTimeLabels: View {
    let fraction: Double
    let elapsed: String
    let remaining: String
    let paused: Bool
    @State private var elapsedWidth: Double = 0
    @State private var remainingWidth: Double = 0

    var body: some View {
        GeometryReader { geometry in
            let layout = TransportTimeLabels.playheadLabelLayout(fraction: fraction,
                width: geometry.size.width, elapsedWidth: elapsedWidth, remainingWidth: remainingWidth)
            ZStack(alignment: .topLeading) {
                HStack(spacing: 10) {
                    Text(elapsed)
                    if paused { Image(systemName: "pause.circle") }
                }
                .fixedSize()
                .background(GeometryReader { Color.clear.preference(key: LabelWidth.self, value: $0.size.width) })
                .onPreferenceChange(LabelWidth.self) { elapsedWidth = Double($0) }
                .offset(x: layout.x)
                Text(remaining)
                    .fixedSize()
                    .background(GeometryReader { Color.clear.preference(key: RemainingWidth.self, value: $0.size.width) })
                    .onPreferenceChange(RemainingWidth.self) { remainingWidth = Double($0) }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .opacity(layout.showsRemaining ? 1 : 0)
            }
        }
        .frame(height: 30)
    }

    private struct LabelWidth: PreferenceKey {
        static let defaultValue: CGFloat = 0
        static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
    }

    private struct RemainingWidth: PreferenceKey {
        static let defaultValue: CGFloat = 0
        static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
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
