import SwiftUI
import Combine

// MARK: - Side Panel & Overlay Models

enum PlayerSidePanel: Equatable {
    case none
    case subtitles
    case audio
    case more
    case chapters
    case queue
}

enum PlayerTransportBadge: Equatable {
    case none
    case playing
    case paused
}

struct PlayerNotice: Equatable {
    let text: String
    var emphasis: Bool = false
    var icon: String? = nil
}

struct PlayerQueueEntry: Equatable {
    let index: Int
    let title: String
}

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
    @Published var bufferedTime: Double = 0

    // Two-tier overlay state
    @Published var showUI: Bool = false
    @Published var isFullControlsVisible: Bool = false
    @Published var activeSidePanel: PlayerSidePanel = .none
    @Published var lastClosedSidePanel: PlayerSidePanel = .none
    @Published var pendingSeekTime: Double? = nil
    @Published var controlsFocusRevision: Int = 0

    // Notices & quiet transport badge
    @Published var transportBadge: PlayerTransportBadge = .none
    @Published var notice: PlayerNotice? = nil

    // Phone confirmations (Phase 3)
    @Published var senderName: String? = nil
    @Published var phonePaused: PhoneOrigin? = nil
    @Published var phoneSeek: PhoneSeek? = nil
    @Published var phoneCard: PhoneChangeCard? = nil

    @Published var isLooping: Bool = false
    @Published var hasPlaylist = false
    @Published var playlistItems: [PlayerQueueEntry] = []
    @Published var playlistIndex: Int = 0
    @Published var playlistVisible = false

    @Published var activeSkipSegment: SkipSegment?
    @Published var skipControlsEnabled = true
    @Published var skipPillFocused = false

    @Published var chapters: [TimelineChapter] = []
    @Published var skipSegments: [SkipSegment] = []
    @Published var metadata = TransportMetadata()
    @Published var tuning = PlayerTuning()
    @Published var hasSidecarSubtitleOverlay = false
    @Published var isBookmarked = false
    @Published var isWatchTogetherParty = false
    @Published var bookmarkPillText: String? = nil

    @Published var touchScrubTime: Double?
    @Published var isVirtualScrubbing: Bool = false
    @Published var scrubMultiplier: Int = 0
    @Published var virtualTime: Double = 0.0

    var isScrubbing: Bool { isVirtualScrubbing || touchScrubTime != nil || pendingSeekTime != nil }

    // Tracks
    @Published var subtitleTracks: [(id: Int, name: String)] = []
    @Published var externalSubtitleTracks: [(id: Int, name: String)] = []
    @Published var currentSubtitleIndex: Int = -1
    @Published var audioTracks: [(id: Int, name: String)] = []
    @Published var currentAudioIndex: Int = -1

    var hasSubtitles: Bool { !subtitleTracks.isEmpty || !externalSubtitleTracks.isEmpty }
    var hasAudioChoice: Bool { audioTracks.count >= 2 }

    var displaySubtitle: String {
        TransportTimeLabels.distinctSubtitle(metadata.subtitle, title: title)
    }

    var controlsSurface: PlayerControlsSurface {
        if activeSidePanel != .none { return .panel }
        if !showUI { return .nothing }
        return isFullControlsVisible ? .controls : .seekBar
    }
}

// MARK: - Main Overlay

struct PlayerControlsOverlay: View {
    @ObservedObject var data: PlayerControlsData
    @ObservedObject private var together = TvTogether.shared
    let engine: PlaybackEngine
    var failureRemote: AppRemoteFocusDriver? = nil
    let onTogglePlayPause: () -> Void
    let onSeekStep: (Double) -> Void
    let onOpenSidePanel: (PlayerSidePanel) -> Void
    let onCloseSidePanel: () -> Void
    let onSelectSubtitle: (Int) -> Void
    let onSelectAudio: (Int) -> Void
    let onSelectChapter: (TimelineChapter) -> Void
    let onSelectQueueItem: (Int) -> Void
    var onToggleBookmark: () -> Void = {}
    var onLeaveParty: () -> Void = {}
    let onLoopToggle: () -> Void
    let onStartOver: () -> Void
    let onSwitchEngine: () -> Void
    let onSkipSegment: () -> Void
    let onFailureAction: (PlaybackFailureAction) -> Void
    let onTuning: (PlayerTuning.Command) -> Void
    let onChromeActivity: () -> Void
    let onSubtitleClearanceChange: (Double) -> Void

    private enum ControlFocus: Hashable {
        case seekBar
        case back10
        case playPause
        case forward10
        case bookmark
        case subtitles
        case audio
        case queue
        case more
        case leaveParty
    }

    @FocusState private var focusedControl: ControlFocus?
    @State private var bottomBarHeight: CGFloat = 0

    private var displayTime: Double {
        data.touchScrubTime
            ?? data.pendingSeekTime
            ?? (data.isVirtualScrubbing ? data.virtualTime : data.currentTime)
    }

    private var durationMs: Int { PlaybackTime.milliseconds(data.duration) }

    private var focusedLabel: String {
        switch focusedControl {
        case .back10: return "Back 10"
        case .playPause: return data.isPlaying ? "Pause" : "Play"
        case .forward10: return "Forward 10"
        case .bookmark: return data.isBookmarked ? "Bookmarked" : "Bookmark"
        case .subtitles: return "Subtitles"
        case .audio: return "Audio"
        case .queue: return "Queue"
        case .more: return "More"
        case .leaveParty: return ""
        case .seekBar, .none: return ""
        }
    }

    var body: some View {
        TvSidePanelHost(isPresented: data.activeSidePanel != .none) {
            sidePanelView
        } content: {
            ZStack(alignment: .bottom) {
                if data.showUI && data.activeSidePanel == .none {
                    if data.isFullControlsVisible {
                        fullControlsLayer
                            .transition(.opacity)
                    } else {
                        seekStripLayer
                            .transition(.opacity)
                    }
                } else if data.activeSidePanel == .none && data.failureSentence == nil {
                    if let phoneSeek = data.phoneSeek {
                        phoneSeekStripLayer(phoneSeek)
                            .transition(.opacity)
                    } else if let phonePaused = data.phonePaused {
                        phonePausedBareLayer(phonePaused)
                            .transition(.opacity)
                    }
                }

                if data.activeSidePanel == .none && data.failureSentence == nil {
                    VStack {
                        HStack {
                            Spacer()
                            if let phoneCard = data.phoneCard {
                                PhoneChangeCardView(card: phoneCard)
                                    .transition(.opacity)
                            } else if data.showUI && data.isFullControlsVisible, let phonePaused = data.phonePaused {
                                PhonePausedPill(name: phonePaused.name, inverted: true)
                                    .transition(.opacity)
                            }
                        }
                        .padding(.horizontal, TvSpacing.screenGutter)
                        .padding(.top, TvScale.pt(40))
                        Spacer()
                    }
                    .allowsHitTesting(false)
                }

                if data.skipControlsEnabled,
                   let segment = data.activeSkipSegment,
                   !data.isScrubbing,
                   data.activeSidePanel == .none,
                   data.failureSentence == nil {
                    HStack {
                        Spacer()
                        TvPillButton(
                            title: segment.buttonTitle,
                            icon: "forward.end.fill",
                            style: .primary,
                            tone: .player,
                            height: TvScale.pt(68),
                            action: onSkipSegment
                        )
                    }
                    .padding(.horizontal, TvSpacing.screenGutter)
                    .padding(.bottom, data.showUI ? max(TvScale.pt(262), bottomBarHeight + TvScale.pt(24)) : TvSpacing.screenGutter)
                }

                if let pillText = data.bookmarkPillText, data.failureSentence == nil {
                    HStack {
                        Spacer()
                        BookmarkConfirmationPill(text: pillText)
                    }
                    .padding(.horizontal, TvSpacing.screenGutter)
                    .padding(.bottom, (data.showUI && data.isFullControlsVisible) ? TvScale.pt(262) : TvSpacing.screenGutter)
                    .allowsHitTesting(false)
                    .transition(.opacity)
                } else if let notice = data.notice, data.failureSentence == nil {
                    HStack {
                        Spacer()
                        PlayerNoticePill(notice: notice)
                    }
                    .padding(.horizontal, TvSpacing.screenGutter)
                    .padding(.bottom, (data.showUI && data.isFullControlsVisible) ? TvScale.pt(262) : TvSpacing.screenGutter)
                    .allowsHitTesting(false)
                    .transition(.opacity)
                }

                if !data.prebuffering {
                    if let sentence = data.failureSentence {
                        PlayerFailureOverlay(
                            title: data.title,
                            sentence: sentence,
                            hasNextItem: data.failureHasNextItem,
                            highlighted: $data.failureHighlight,
                            onAction: onFailureAction
                        )
                        .environment(\.appRemoteDriver, failureRemote)
                    } else {
                        centerStatusLayer
                    }
                }

                if data.isWatchTogetherParty, let party = together.currentParty {
                    TogetherPlayerChrome(
                        party: party,
                        showMemberChips: data.failureSentence != nil
                            || (data.showUI && data.isFullControlsVisible && data.activeSidePanel == .none)
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .ignoresSafeArea()
        }
        .animation(.easeOut(duration: 0.18), value: data.showUI)
        .animation(.easeOut(duration: 0.18), value: data.isFullControlsVisible)
        .animation(.easeOut(duration: 0.18), value: data.notice)
        .animation(.easeOut(duration: 0.15), value: data.transportBadge)
        .animation(.easeOut(duration: 0.18), value: data.phonePaused)
        .animation(.easeOut(duration: 0.18), value: data.phoneSeek)
        .animation(.easeOut(duration: 0.18), value: data.phoneCard)
        .onPreferenceChange(PlayerBottomHeightPreference.self) { bottomBarHeight = $0 }
        .onChange(of: data.showUI) { _, visible in
            updateSubtitleClearance(visible: visible)
            if !visible { focusedControl = nil }
        }
        .onChange(of: data.isFullControlsVisible) { _, full in
            updateSubtitleClearance(visible: data.showUI)
            if data.showUI && full && data.activeSidePanel == .none {
                restoreControlFocus()
            }
        }
        .onChange(of: data.activeSidePanel) { _, panel in
            updateSubtitleClearance(visible: data.showUI)
            if panel == .none && data.showUI && data.isFullControlsVisible {
                restoreControlFocus()
            }
        }
        .onChange(of: data.controlsFocusRevision) { _, _ in
            if data.showUI && data.isFullControlsVisible && data.activeSidePanel == .none {
                restoreControlFocus()
            }
        }
        .onChange(of: focusedControl) { _, newValue in
            if newValue != nil { onChromeActivity() }
        }
        .allowsHitTesting(data.failureSentence != nil || (data.showUI && data.isFullControlsVisible) || data.activeSidePanel != .none || data.activeSkipSegment != nil)
    }

    private func phonePausedBareLayer(_ origin: PhoneOrigin) -> some View {
        ZStack {
            VStack {
                HStack {
                    PhonePausedPill(name: origin.name, inverted: false)
                    Spacer()
                }
                .padding(.horizontal, TvSpacing.screenGutter)
                .padding(.top, TvScale.pt(40))
                Spacer()
            }
            .allowsHitTesting(false)

            VStack {
                Spacer()
                ZStack(alignment: .bottom) {
                    LinearGradient(
                        colors: [.clear, TvPlayerColors.background.opacity(0.72)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: TvScale.pt(140))
                    .ignoresSafeArea()

                    VStack(spacing: TvScale.pt(10)) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(data.title)
                                .font(.system(size: TvScale.pt(20), weight: .semibold))
                                .foregroundStyle(TvPlayerColors.onSurface)
                                .lineLimit(1)
                            Spacer()
                            Text("\(TransportTimeLabels.clock(displayTime)) / \(TransportTimeLabels.clock(data.duration))")
                                .font(.system(size: TvScale.pt(17), weight: .regular).monospacedDigit())
                                .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                        }
                        PlayerSeekBar(
                            progress: data.duration > 0 ? displayTime / data.duration : 0,
                            buffered: nil,
                            chapterFractions: [],
                            segmentRanges: [],
                            showThumb: false
                        )
                        .frame(height: TvScale.pt(8))
                    }
                    .padding(.horizontal, TvSpacing.screenGutter)
                    .padding(.bottom, TvScale.pt(40))
                }
            }
            .allowsHitTesting(false)
        }
    }

    private func phoneSeekStripLayer(_ seek: PhoneSeek) -> some View {
        let target = max(0, data.duration > 0 ? min(seek.targetSeconds, data.duration) : seek.targetSeconds)
        let targetMs = PlaybackTime.milliseconds(target)
        let timelineCaption = ChapterNavigation.captionAt(chapters: data.chapters, positionMs: targetMs)
        let caption = PhoneConfirmations.phoneSeekCaption(name: seek.name, timelineCaption: timelineCaption)
        return ZStack(alignment: .bottom) {
            LinearGradient(
                colors: [.clear, TvPlayerColors.background.opacity(0.72)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: TvScale.pt(170))
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: TvScale.pt(14)) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                        Text(TransportTimeLabels.clock(target))
                            .font(TvText.seekTime)
                            .foregroundStyle(TvPlayerColors.onSurface)
                        Text(caption)
                            .font(TvText.caption)
                            .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                            .lineLimit(1)
                    }
                    Spacer()
                    Text(TransportTimeLabels.clock(data.duration))
                        .font(.system(size: TvScale.pt(18), weight: .regular).monospacedDigit())
                        .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                }

                PlayerSeekBar(
                    progress: data.duration > 0 ? target / data.duration : 0,
                    buffered: data.duration > 0 ? data.bufferedTime / data.duration : nil,
                    chapterFractions: ChapterNavigation.chapterFractions(chapters: data.chapters, durationMs: durationMs),
                    segmentRanges: ChapterNavigation.segmentRanges(segments: data.skipSegments, durationMs: durationMs),
                    showThumb: true
                )
                .frame(height: TvSize.seekThumbSize)
            }
            .padding(.horizontal, TvSpacing.screenGutter)
            .padding(.bottom, TvScale.pt(44))
            .allowsHitTesting(false)
        }
    }

    // MARK: - Tier 1: Seek Strip

    private var seekStripLayer: some View {
        ZStack(alignment: .bottom) {
            LinearGradient(
                colors: [.clear, TvPlayerColors.background.opacity(0.72)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: TvScale.pt(170))
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: TvScale.pt(14)) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                        Text(TransportTimeLabels.clock(displayTime))
                            .font(TvText.seekTime)
                            .foregroundStyle(TvPlayerColors.onSurface)
                        if let chapterTitle = currentChapterTitle {
                            Text(chapterTitle)
                                .font(TvText.caption)
                                .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                                .lineLimit(1)
                        }
                    }
                    Spacer()
                    Text(TransportTimeLabels.clock(data.duration))
                        .font(.system(size: TvScale.pt(18), weight: .regular).monospacedDigit())
                        .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                }

                PlayerSeekBar(
                    progress: data.duration > 0 ? displayTime / data.duration : 0,
                    buffered: data.duration > 0 ? data.bufferedTime / data.duration : nil,
                    chapterFractions: ChapterNavigation.chapterFractions(chapters: data.chapters, durationMs: durationMs),
                    segmentRanges: ChapterNavigation.segmentRanges(segments: data.skipSegments, durationMs: durationMs),
                    showThumb: true
                )
                .frame(height: TvSize.seekThumbSize)

                HStack {
                    Text("Left and right to seek 10 seconds")
                    Spacer()
                    Text("Down for controls")
                }
                .font(TvText.caption)
                .foregroundStyle(TvPlayerColors.onSurfaceMuted)
            }
            .padding(.horizontal, TvSpacing.screenGutter)
            .padding(.bottom, TvScale.pt(44))
            .background(GeometryReader { geo in
                Color.clear.preference(key: PlayerBottomHeightPreference.self, value: geo.size.height)
            })
        }
    }

    // MARK: - Tier 2: Full Controls

    private var fullControlsLayer: some View {
        ZStack {
            VStack(spacing: 0) {
                LinearGradient(
                    colors: [TvPlayerColors.background.opacity(TvPlayerColors.scrimTopAlpha), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: TvScale.pt(130))
                Spacer()
                LinearGradient(
                    colors: [.clear, TvPlayerColors.background.opacity(TvPlayerColors.scrimBottomAlpha)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: TvScale.pt(250))
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(alignment: .leading, spacing: 0) {
                headerBlock
                    .padding(.horizontal, TvSpacing.screenGutter)
                    .padding(.top, TvScale.pt(40))

                Spacer()

                VStack(spacing: TvScale.pt(18)) {
                    seekBarControl
                    controlRow
                }
                .padding(.horizontal, TvSpacing.screenGutter)
                .padding(.bottom, TvScale.pt(40))
                .background(GeometryReader { geo in
                    Color.clear.preference(key: PlayerBottomHeightPreference.self, value: geo.size.height)
                })
            }
        }
    }

    private var headerBlock: some View {
        VStack(alignment: .leading, spacing: TvScale.pt(4)) {
            Text(data.title)
                .font(TvText.panelTitle)
                .foregroundStyle(TvPlayerColors.onSurface)
                .lineLimit(1)
            let subtitleParts = [
                data.isWatchTogetherParty ? (together.currentParty?.toPartyUi().partyName ?? "Watch party") : nil,
                data.displaySubtitle.isEmpty ? nil : data.displaySubtitle,
                PhoneConfirmations.castOriginText(data.senderName),
                data.userPaused ? "Paused" : nil,
                data.hasPlaylist ? "\(data.playlistIndex + 1) of \(data.playlistItems.count) in the queue" : nil
            ].compactMap { $0 }
            if !subtitleParts.isEmpty {
                Text(subtitleParts.joined(separator: " · "))
                    .font(TvText.caption)
                    .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                    .lineLimit(1)
            }
        }
    }

    private var seekBarControl: some View {
        let fraction = data.duration > 0 ? displayTime / data.duration : 0
        return Button(action: onTogglePlayPause) {
            VStack(spacing: TvSpacing.s) {
                PlayerSeekBar(
                    progress: fraction,
                    buffered: data.duration > 0 ? data.bufferedTime / data.duration : nil,
                    chapterFractions: ChapterNavigation.chapterFractions(chapters: data.chapters, durationMs: durationMs),
                    segmentRanges: ChapterNavigation.segmentRanges(segments: data.skipSegments, durationMs: durationMs),
                    showThumb: focusedControl == .seekBar
                )
                .frame(height: TvSize.seekThumbSize)

                HStack {
                    Text(TransportTimeLabels.clock(displayTime))
                    Spacer()
                    Text(TransportTimeLabels.clock(data.duration))
                }
                .font(.system(size: TvScale.pt(17), weight: .regular).monospacedDigit())
                .foregroundStyle(TvPlayerColors.onSurfaceMuted)
            }
        }
        .buttonStyle(PlainSeekButtonStyle())
        .focused($focusedControl, equals: .seekBar)
        .onMoveCommand { direction in
            switch direction {
            case .left:
                onSeekStep(-PlayerKeyMap.seekStepSeconds)
                onChromeActivity()
            case .right:
                onSeekStep(PlayerKeyMap.seekStepSeconds)
                onChromeActivity()
            case .down:
                focusedControl = .playPause
                onChromeActivity()
            default:
                break
            }
        }
    }

    private var controlRow: some View {
        HStack {
            HStack(spacing: TvScale.pt(14)) {
                TvIconCircleButton(
                    systemImage: "gobackward.10",
                    accessibilityLabel: "Back 10 seconds",
                    diameter: TvSize.iconButton
                ) {
                    onSeekStep(-10)
                    onChromeActivity()
                }
                .focused($focusedControl, equals: .back10)

                TvIconCircleButton(
                    systemImage: data.isPlaying ? "pause.fill" : "play.fill",
                    accessibilityLabel: data.isPlaying ? "Pause" : "Play",
                    diameter: TvSize.iconButtonLarge,
                    iconSize: TvScale.pt(30)
                ) {
                    onTogglePlayPause()
                    onChromeActivity()
                }
                .focused($focusedControl, equals: .playPause)

                TvIconCircleButton(
                    systemImage: "goforward.10",
                    accessibilityLabel: "Forward 10 seconds",
                    diameter: TvSize.iconButton
                ) {
                    onSeekStep(10)
                    onChromeActivity()
                }
                .focused($focusedControl, equals: .forward10)

                if data.isWatchTogetherParty {
                    Text("Play, pause and seek apply to everyone")
                        .font(TvText.caption)
                        .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: TvSpacing.l)

            HStack(spacing: TvSpacing.xl) {
                Text(focusedLabel)
                    .font(TvText.button)
                    .foregroundStyle(TvPlayerColors.onSurface)
                    .lineLimit(1)
                    .frame(width: TvSize.controlLabelSlot, alignment: .trailing)

                if !data.isWatchTogetherParty {
                    TvIconCircleButton(
                        systemImage: data.isBookmarked ? "bookmark.fill" : "bookmark",
                        accessibilityLabel: data.isBookmarked ? "Bookmarked" : "Bookmark",
                        diameter: TvSize.iconButton
                    ) {
                        onToggleBookmark()
                        onChromeActivity()
                    }
                    .focused($focusedControl, equals: .bookmark)
                }

                if data.hasSubtitles {
                    TvIconCircleButton(
                        systemImage: "captions.bubble",
                        accessibilityLabel: "Subtitles",
                        diameter: TvSize.iconButton
                    ) {
                        onOpenSidePanel(.subtitles)
                    }
                    .focused($focusedControl, equals: .subtitles)
                }

                if data.hasAudioChoice {
                    TvIconCircleButton(
                        systemImage: "speaker.wave.2",
                        accessibilityLabel: "Audio",
                        diameter: TvSize.iconButton
                    ) {
                        onOpenSidePanel(.audio)
                    }
                    .focused($focusedControl, equals: .audio)
                }

                if data.hasPlaylist {
                    TvIconCircleButton(
                        systemImage: "list.bullet",
                        accessibilityLabel: "Queue",
                        diameter: TvSize.iconButton
                    ) {
                        onOpenSidePanel(.queue)
                    }
                    .focused($focusedControl, equals: .queue)
                }

                TvIconCircleButton(
                    systemImage: "ellipsis",
                    accessibilityLabel: "More",
                    diameter: TvSize.iconButton
                ) {
                    onOpenSidePanel(.more)
                }
                .focused($focusedControl, equals: .more)

                if data.isWatchTogetherParty {
                    TvPillButton(
                        title: "Leave party",
                        icon: "rectangle.portrait.and.arrow.right",
                        style: .secondary,
                        tone: .player,
                        height: TvSize.pillHeightCompact,
                        action: onLeaveParty
                    )
                    .focused($focusedControl, equals: .leaveParty)
                }
            }
        }
    }

    // MARK: - Side Panels

    @ViewBuilder
    private var sidePanelView: some View {
        switch data.activeSidePanel {
        case .none:
            EmptyView()
        case .subtitles:
            let availability = PlayerSettingsAvailability.forEngine(
                engine.rawValue,
                sidecarOverlayActive: data.hasSidecarSubtitleOverlay
            )
            SubtitlesSidePanel(
                subtitleTracks: data.subtitleTracks,
                externalTracks: data.externalSubtitleTracks,
                currentId: data.currentSubtitleIndex,
                offsetMs: data.tuning.subtitleOffsetMs,
                subtitleSyncAvailable: availability.subtitleOffset,
                onSelect: onSelectSubtitle,
                onTuning: onTuning,
                onClose: onCloseSidePanel
            )
        case .audio:
            AudioSidePanel(
                tracks: data.audioTracks,
                currentId: data.currentAudioIndex,
                onSelect: onSelectAudio,
                onClose: onCloseSidePanel
            )
        case .more:
            let availability = PlayerSettingsAvailability.forEngine(
                engine.rawValue,
                sidecarOverlayActive: data.hasSidecarSubtitleOverlay
            )
            MoreSidePanel(
                tuning: data.tuning,
                availability: availability,
                chapterCount: data.chapters.count,
                isLooping: data.isLooping,
                engineName: engine.name,
                onTuning: onTuning,
                onOpenChapters: { onOpenSidePanel(.chapters) },
                onToggleRepeat: onLoopToggle,
                onStartOver: onStartOver,
                onSwitchEngine: onSwitchEngine,
                onClose: onCloseSidePanel
            )
        case .chapters:
            ChaptersSidePanel(
                chapters: data.chapters,
                currentTime: displayTime,
                duration: data.duration,
                onSelect: onSelectChapter,
                onClose: onCloseSidePanel
            )
        case .queue:
            QueueSidePanel(
                items: data.playlistItems,
                currentIndex: data.playlistIndex,
                onSelect: onSelectQueueItem,
                onClose: onCloseSidePanel
            )
        }
    }

    // MARK: - Center Status

    private var centerStatusLayer: some View {
        Group {
            if data.surfaceState.showsSpinner {
                ProgressView()
                    .tint(TvPlayerColors.onSurface)
                    .scaleEffect(2)
                    .frame(width: TvScale.pt(64), height: TvScale.pt(64))
                    .accessibilityLabel("Buffering")
            } else if data.isVirtualScrubbing {
                Text("\(abs(data.scrubMultiplier))×")
                    .font(.system(size: TvScale.pt(64), weight: .semibold))
                    .foregroundStyle(TvPlayerColors.onSurface)
            } else if data.transportBadge != .none {
                PlayerTransportBadgeView(badge: data.transportBadge)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .allowsHitTesting(false)
    }

    // MARK: - Helpers

    private var currentChapterTitle: String? {
        let posMs = PlaybackTime.milliseconds(displayTime)
        guard let idx = data.chapters.lastIndex(where: { $0.startMs <= posMs }),
              data.chapters.indices.contains(idx) else { return nil }
        return data.chapters[idx].title
    }

    private func restoreControlFocus() {
        let target: ControlFocus = switch data.lastClosedSidePanel {
        case .subtitles: data.hasSubtitles ? .subtitles : .playPause
        case .audio: data.hasAudioChoice ? .audio : .playPause
        case .queue: data.hasPlaylist ? .queue : .playPause
        case .more, .chapters: .more
        case .none: .playPause
        }
        DispatchQueue.main.async {
            focusedControl = target
        }
    }

    private func updateSubtitleClearance(visible: Bool) {
        if !visible || data.activeSidePanel != .none {
            onSubtitleClearanceChange(0)
        } else {
            onSubtitleClearanceChange(Double(TvScale.pt(160)))
        }
    }
}

// MARK: - Subviews

private struct PlayerSeekBar: View {
    let progress: Double
    let buffered: Double?
    let chapterFractions: [Double]
    let segmentRanges: [ClosedRange<Double>]
    let showThumb: Bool

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let midY = size.height / 2
            let h = TvSize.seekBarHeight

            func barRect(from start: Double, to end: Double, height: CGFloat) -> Path {
                let x0 = w * CGFloat(min(max(start, 0), 1))
                let x1 = w * CGFloat(min(max(end, 0), 1))
                let rect = CGRect(x: x0, y: midY - height / 2, width: max(x1 - x0, 0), height: height)
                return Path(roundedRect: rect, cornerRadius: height / 2)
            }

            for range in segmentRanges {
                context.fill(barRect(from: range.lowerBound, to: range.upperBound, height: h + 6),
                             with: .color(Theme.segment.opacity(0.35)))
            }
            context.fill(barRect(from: 0, to: 1, height: h), with: .color(TvPlayerColors.track))
            if let buffered, buffered > progress {
                context.fill(barRect(from: 0, to: buffered, height: h), with: .color(TvPlayerColors.buffer))
            }
            if progress > 0 {
                context.fill(barRect(from: 0, to: progress, height: h), with: .color(TvPlayerColors.accent))
            }

            let gapWidth: CGFloat = TvScale.pt(3)
            for fraction in chapterFractions {
                let x = w * CGFloat(fraction)
                guard x > gapWidth, x < w - gapWidth else { continue }
                context.fill(
                    Path(CGRect(x: x - gapWidth / 2, y: midY - h / 2 - 2, width: gapWidth, height: h + 4)),
                    with: .color(TvPlayerColors.background)
                )
            }

            if showThumb {
                let thumbSize = TvSize.seekThumbSize
                let cx = min(max(w * CGFloat(min(max(progress, 0), 1)), thumbSize / 2), w - thumbSize / 2)
                let thumbRect = CGRect(x: cx - thumbSize / 2, y: midY - thumbSize / 2, width: thumbSize, height: thumbSize)
                context.fill(Path(ellipseIn: thumbRect), with: .color(TvPlayerColors.onSurface))
            }
        }
    }
}

private struct PlainSeekButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}

private struct PlayerNoticePill: View {
    let notice: PlayerNotice

    var body: some View {
        let bg = notice.emphasis ? TvColors.dark.onSurface : TvPlayerColors.panel
        let fg = notice.emphasis ? TvColors.dark.surface : TvPlayerColors.onSurface
        HStack(spacing: TvScale.pt(10)) {
            if let icon = notice.icon {
                Image(systemName: icon)
                    .font(.system(size: TvScale.pt(18), weight: .semibold))
            }
            Text(notice.text)
                .font(TvText.value)
                .lineLimit(1)
        }
        .foregroundStyle(fg)
        .padding(.horizontal, TvScale.pt(18))
        .frame(height: TvScale.pt(46))
        .background(bg, in: Capsule())
    }
}

private struct PlayerTransportBadgeView: View {
    let badge: PlayerTransportBadge

    var body: some View {
        let icon = badge == .paused ? "pause.fill" : "play.fill"
        Image(systemName: icon)
            .font(.system(size: TvScale.pt(28), weight: .semibold))
            .foregroundStyle(TvPlayerColors.onSurface)
            .frame(width: TvScale.pt(56), height: TvScale.pt(56))
            .background(TvPlayerColors.background.opacity(0.72), in: Circle())
    }
}

// MARK: - Side Panel Implementations

private struct SubtitlesSidePanel: View {
    let subtitleTracks: [(id: Int, name: String)]
    let externalTracks: [(id: Int, name: String)]
    let currentId: Int
    let offsetMs: Int
    let subtitleSyncAvailable: Bool
    let onSelect: (Int) -> Void
    let onTuning: (PlayerTuning.Command) -> Void
    let onClose: () -> Void

    @FocusState private var focusedRow: Int?

    private var formattedOffset: String {
        if offsetMs == 0 { return "In sync" }
        let sec = Double(offsetMs) / 1000.0
        return String(format: "%+.1f s", sec)
    }

    var body: some View {
        TvSidePanel(title: "Subtitles", tone: .player) {
            TvListRow(
                title: "Off",
                selected: currentId == -1,
                showCheckmark: true,
                tone: .player
            ) { onSelect(-1) }
                .focused($focusedRow, equals: -1)

            ForEach(subtitleTracks, id: \.id) { track in
                TvListRow(
                    title: track.name,
                    selected: currentId == track.id,
                    showCheckmark: true,
                    tone: .player
                ) { onSelect(track.id) }
                    .focused($focusedRow, equals: track.id)
            }

            ForEach(externalTracks, id: \.id) { track in
                TvListRow(
                    title: track.name,
                    trailingText: "Online",
                    selected: currentId == track.id,
                    showCheckmark: true,
                    tone: .player
                ) { onSelect(track.id) }
                    .focused($focusedRow, equals: track.id)
            }

            if subtitleSyncAvailable {
                VStack(alignment: .leading, spacing: TvScale.pt(10)) {
                    Text("Out of sync? Shift the timing")
                        .font(TvText.caption)
                        .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                        .padding(.top, TvScale.pt(10))

                    HStack(spacing: TvScale.pt(10)) {
                        TvPillButton(
                            title: "Earlier",
                            style: .secondary,
                            tone: .player,
                            height: TvSize.pillHeight
                        ) { onTuning(.subtitleOffsetDelta(-100)) }

                        Text(formattedOffset)
                            .font(.system(size: TvScale.pt(20), weight: .semibold).monospacedDigit())
                            .foregroundStyle(TvPlayerColors.onSurface)
                            .frame(width: TvScale.pt(110), height: TvSize.pillHeight)

                        TvPillButton(
                            title: "Later",
                            style: .secondary,
                            tone: .player,
                            height: TvSize.pillHeight
                        ) { onTuning(.subtitleOffsetDelta(100)) }
                    }

                    if offsetMs != 0 {
                        TvPillButton(
                            title: "Reset timing",
                            style: .subtle,
                            tone: .player,
                            height: TvScale.pt(52)
                        ) { onTuning(.subtitleOffsetDelta(-offsetMs)) }
                    }
                }
            }
        }
        .onAppear {
            DispatchQueue.main.async { focusedRow = currentId }
        }
        .onExitCommand(perform: onClose)
    }
}

private struct AudioSidePanel: View {
    let tracks: [(id: Int, name: String)]
    let currentId: Int
    let onSelect: (Int) -> Void
    let onClose: () -> Void

    @FocusState private var focusedRow: Int?

    var body: some View {
        TvSidePanel(title: "Audio", tone: .player) {
            ForEach(tracks, id: \.id) { track in
                TvListRow(
                    title: track.name,
                    selected: currentId == track.id,
                    showCheckmark: true,
                    tone: .player
                ) { onSelect(track.id) }
                    .focused($focusedRow, equals: track.id)
            }
        }
        .onAppear {
            DispatchQueue.main.async { focusedRow = currentId }
        }
        .onExitCommand(perform: onClose)
    }
}

private struct MoreSidePanel: View {
    private enum Subview { case root, speed, scaling }

    let tuning: PlayerTuning
    let availability: PlayerSettingsAvailability
    let chapterCount: Int
    let isLooping: Bool
    let engineName: String
    let onTuning: (PlayerTuning.Command) -> Void
    let onOpenChapters: () -> Void
    let onToggleRepeat: () -> Void
    let onStartOver: () -> Void
    let onSwitchEngine: () -> Void
    let onClose: () -> Void

    @State private var subview: Subview = .root
    @FocusState private var focusedKey: String?

    private func scalingTitle(_ mode: PlayerScaling) -> String {
        switch mode {
        case .fit: return "Fit screen"
        case .zoom: return "Zoom"
        case .fill: return "Fill screen"
        }
    }

    private var panelTitle: String {
        switch subview {
        case .root: return "More"
        case .speed: return "Speed"
        case .scaling: return "Picture size"
        }
    }

    var body: some View {
        TvSidePanel(title: panelTitle, tone: .player) {
            switch subview {
            case .root:
                if availability.speed {
                    TvListRow(
                        title: "Speed",
                        trailingText: abs(tuning.speed - 1.0) < 0.01 ? "Normal" : PlayerSettingsItem.speedLabel(tuning.speed),
                        tone: .player
                    ) {
                        subview = .speed
                        DispatchQueue.main.async {
                            focusedKey = PlayerSettingsItem.speed(tuning.speed).id
                        }
                    }
                    .focused($focusedKey, equals: "speed")
                }
                if availability.scaling {
                    TvListRow(
                        title: "Picture size",
                        trailingText: scalingTitle(tuning.scaling),
                        tone: .player
                    ) {
                        subview = .scaling
                        DispatchQueue.main.async {
                            focusedKey = PlayerSettingsItem.scaling(tuning.scaling).id
                        }
                    }
                    .focused($focusedKey, equals: "scaling")
                }
                if chapterCount >= 2 {
                    TvListRow(title: "Chapters", trailingText: "\(chapterCount)", tone: .player, action: onOpenChapters)
                        .focused($focusedKey, equals: "chapters")
                }
                TvListRow(title: "Repeat", trailingText: isLooping ? "On" : "Off", tone: .player, action: onToggleRepeat)
                    .focused($focusedKey, equals: "repeat")
                TvListRow(title: "Start over", tone: .player, action: onStartOver)
                    .focused($focusedKey, equals: "start_over")
                TvListRow(title: "Player", trailingText: engineName, tone: .player, action: onSwitchEngine)
                    .focused($focusedKey, equals: "player")

            case .speed:
                ForEach(PlayerSettingsItem.items(in: .speed), id: \.id) { item in
                    TvListRow(
                        title: item.title,
                        selected: item.isSelected(tuning),
                        showCheckmark: true,
                        tone: .player
                    ) {
                        onTuning(item.command(currentOffsetMs: tuning.subtitleOffsetMs))
                        subview = .root
                        DispatchQueue.main.async { focusedKey = "speed" }
                    }
                    .focused($focusedKey, equals: item.id)
                }

            case .scaling:
                ForEach(PlayerSettingsItem.items(in: .scaling), id: \.id) { item in
                    let label = if case .scaling(let mode) = item { scalingTitle(mode) } else { item.title }
                    TvListRow(
                        title: label,
                        selected: item.isSelected(tuning),
                        showCheckmark: true,
                        tone: .player
                    ) {
                        onTuning(item.command(currentOffsetMs: tuning.subtitleOffsetMs))
                        subview = .root
                        DispatchQueue.main.async { focusedKey = "scaling" }
                    }
                    .focused($focusedKey, equals: item.id)
                }
            }
        }
        .onAppear {
            DispatchQueue.main.async {
                focusedKey = availability.speed ? "speed" : (availability.scaling ? "scaling" : "repeat")
            }
        }
        .onExitCommand {
            if subview != .root {
                let target = subview == .speed ? "speed" : "scaling"
                subview = .root
                DispatchQueue.main.async { focusedKey = target }
            } else {
                onClose()
            }
        }
    }
}

private struct ChaptersSidePanel: View {
    let chapters: [TimelineChapter]
    let currentTime: Double
    let duration: Double
    let onSelect: (TimelineChapter) -> Void
    let onClose: () -> Void

    @FocusState private var focusedIndex: Int?

    var body: some View {
        let posMs = PlaybackTime.milliseconds(currentTime)
        let currentIdx = chapters.lastIndex(where: { $0.startMs <= posMs })

        TvSidePanel(title: "Chapters", tone: .player) {
            ForEach(Array(chapters.enumerated()), id: \.offset) { index, chapter in
                let isCurrent = index == currentIdx
                TvListRow(
                    title: chapter.title ?? "Chapter \(index + 1)",
                    leadingText: TransportTimeLabels.clock(Double(chapter.startMs) / 1000.0),
                    trailingText: isCurrent ? "Now" : nil,
                    activeFill: isCurrent,
                    height: TvSize.chapterRow,
                    tone: .player
                ) { onSelect(chapter) }
                    .focused($focusedIndex, equals: index)
            }
        }
        .onAppear {
            DispatchQueue.main.async { focusedIndex = currentIdx ?? 0 }
        }
        .onExitCommand(perform: onClose)
    }
}

private struct QueueSidePanel: View {
    let items: [PlayerQueueEntry]
    let currentIndex: Int
    let onSelect: (Int) -> Void
    let onClose: () -> Void

    @FocusState private var focusedIndex: Int?

    var body: some View {
        TvSidePanel(
            title: "Queue",
            trailingHeader: "\(currentIndex + 1) of \(items.count)",
            footerText: "Add or reorder videos from your phone.",
            tone: .player
        ) {
            ForEach(items, id: \.index) { item in
                let isCurrent = item.index == currentIndex
                let subtitle: String? = isCurrent ? "Playing now" : (item.index < currentIndex ? "Watched" : nil)
                TvListRow(
                    title: "\(item.index + 1). \(item.title)",
                    subtitle: subtitle,
                    activeFill: isCurrent,
                    height: TvSize.queueRow,
                    tone: .player
                ) { onSelect(item.index) }
                    .focused($focusedIndex, equals: item.index)
            }
        }
        .onAppear {
            DispatchQueue.main.async { focusedIndex = currentIndex }
        }
        .onExitCommand(perform: onClose)
    }
}

// MARK: - Failure Overlay

private struct PlayerFailureOverlay: View {
    let title: String
    let sentence: String
    let hasNextItem: Bool
    @Binding var highlighted: PlaybackFailureAction
    let onAction: (PlaybackFailureAction) -> Void

    @Environment(\.appRemoteDriver) private var appRemoteDriver
    @State private var focusIDs: [PlaybackFailureAction: AppFocusID] = Dictionary(
        uniqueKeysWithValues: PlaybackFailureModel.actions(hasNextItem: true).map { ($0, AppFocusID()) }
    )

    private var defaultFocusID: AppFocusID? {
        focusIDs[PlaybackFailureModel.defaultAction]
    }

    private static func icon(_ action: PlaybackFailureAction) -> String {
        switch action {
        case .tryAgain: return "arrow.clockwise"
        case .next: return "forward.end.fill"
        case .close: return "xmark"
        }
    }

    var body: some View {
        ZStack {
            TvColors.dark.surface.ignoresSafeArea()
            VStack(spacing: TvSpacing.xxl) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: TvScale.pt(48)))
                    .foregroundStyle(TvColors.dark.error)
                if !title.isEmpty {
                    Text(title)
                        .font(TvText.panelTitle)
                        .foregroundStyle(TvColors.dark.onSurface)
                        .lineLimit(1)
                }
                Text(sentence)
                    .font(TvText.row)
                    .foregroundStyle(TvColors.dark.onSurfaceMuted)
                    .multilineTextAlignment(.center)
                HStack(spacing: TvSpacing.xl) {
                    ForEach(PlaybackFailureModel.actions(hasNextItem: hasNextItem), id: \.self) { action in
                        TvPillButton(
                            title: action.rawValue,
                            icon: Self.icon(action),
                            style: action == .tryAgain ? .primary : .secondary,
                            tone: .app,
                            focusID: focusIDs[action]
                        ) { onAction(action) }
                    }
                }
                .padding(.top, TvSpacing.m)
            }
            .padding(.horizontal, TvScale.pt(80))
        }
        .appRemoteScope(
            back: { onAction(.close) },
            home: { onAction(.close) },
            initialFocus: defaultFocusID
        )
        .onAppear {
            if let id = focusIDs[highlighted] ?? defaultFocusID {
                DispatchQueue.main.async { appRemoteDriver?.focus(id) }
            }
        }
        .onChange(of: hasNextItem) { _, canAdvance in
            if !canAdvance, highlighted == .next {
                highlighted = PlaybackFailureModel.defaultAction
                if let id = defaultFocusID {
                    appRemoteDriver?.focus(id)
                }
            }
        }
    }
}

private struct PhonePausedPill: View {
    let name: String?
    let inverted: Bool

    var body: some View {
        HStack(spacing: TvScale.pt(14)) {
            Image(systemName: "pause.fill")
                .font(.system(size: TvScale.pt(24), weight: .bold))
            Text(PhoneConfirmations.pausedFromText(name))
                .font(.system(size: TvScale.pt(27), weight: .medium))
                .lineLimit(1)
        }
        .foregroundStyle(inverted ? TvPlayerColors.onAccentContainer : TvPlayerColors.onSurface)
        .padding(.leading, TvScale.pt(26))
        .padding(.trailing, TvScale.pt(32))
        .frame(height: TvScale.pt(76))
        .background(
            Capsule()
                .fill(inverted ? TvPlayerColors.accentContainer : TvPlayerColors.panel)
        )
    }
}

private struct PhoneChangeCardView: View {
    let card: PhoneChangeCard

    private var iconName: String {
        switch card.kind {
        case .subtitles: return "captions.bubble"
        case .audio: return "speaker.wave.2"
        case .speed: return "gauge.with.needle"
        case .queue: return "list.bullet"
        }
    }

    var body: some View {
        HStack(spacing: TvScale.pt(22)) {
            ZStack {
                Circle()
                    .fill(TvPlayerColors.control)
                    .frame(width: TvScale.pt(72), height: TvScale.pt(72))
                Image(systemName: iconName)
                    .font(.system(size: TvScale.pt(28), weight: .semibold))
                    .foregroundStyle(TvPlayerColors.onSurface)
            }
            VStack(alignment: .leading, spacing: TvScale.pt(4)) {
                Text(card.title)
                    .font(.system(size: TvScale.pt(28), weight: .semibold))
                    .foregroundStyle(TvPlayerColors.onSurface)
                    .lineLimit(1)
                Text(card.detail)
                    .font(.system(size: TvScale.pt(24), weight: .regular))
                    .foregroundStyle(TvPlayerColors.onSurfaceMuted)
                    .lineLimit(1)
            }
        }
        .padding(.leading, TvScale.pt(26))
        .padding(.trailing, TvScale.pt(34))
        .padding(.vertical, TvScale.pt(22))
        .background(
            RoundedRectangle(cornerRadius: TvScale.pt(27), style: .continuous)
                .fill(TvPlayerColors.panel)
        )
    }
}

private struct BookmarkConfirmationPill: View {
    let text: String

    var body: some View {
        HStack(spacing: TvScale.pt(10)) {
            Image(systemName: "bookmark.fill")
                .font(.system(size: TvScale.pt(22), weight: .semibold))
            Text(text)
                .font(.system(size: TvScale.pt(24), weight: .medium))
                .lineLimit(1)
        }
        .foregroundStyle(TvPlayerColors.onAccentContainer)
        .padding(.horizontal, TvScale.pt(22))
        .frame(height: TvScale.pt(68))
        .background(
            Capsule()
                .fill(TvPlayerColors.accentContainer)
        )
    }
}

private struct PlayerBottomHeightPreference: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
