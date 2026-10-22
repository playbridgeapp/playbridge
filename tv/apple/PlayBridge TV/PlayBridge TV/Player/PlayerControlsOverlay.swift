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
    @Published var activeSkipSegment: SkipSegment?
    @Published var skipControlsEnabled = true
    /// Sorted chapter markers of the open item (`ChapterNavigation.sorted`).
    @Published var chapters: [TimelineChapter] = []
    /// Skip segments drawn as bands on the seek bar.
    @Published var skipSegments: [SkipSegment] = []
    /// Jump toast ("Chapter 3 · The Heist", "Last chapter"); replaces the caption briefly.
    @Published var chapterToast: String?
    /// The HUD is showing because of a Left/Right seek or a chapter jump. Up/Down
    /// jump chapters only then (and during a scrub).
    @Published var seekStripActive = false

    var menuOpen: Bool { showSubtitleMenu || showAudioMenu || showEngineMenu }

    /// Up/Down jump chapters (or move the scrub ghost to one) right now.
    var chapterKeysActive: Bool {
        guard ChapterNavigation.canJump(chapters), !menuOpen else { return false }
        return isVirtualScrubbing || (seekStripActive && showUI && !userPaused)
    }
    
    // Virtual Scrubbing Ghost HUD
    @Published var isVirtualScrubbing: Bool = false
    @Published var scrubMultiplier: Int = 0
    @Published var virtualTime: Double = 0.0

    // Subtitles
    @Published var subtitleTracks: [(id: Int, name: String)] = []
    @Published var externalSubtitleTracks: [(id: Int, name: String)] = []
    @Published var currentSubtitleIndex: Int = -1
    @Published var showSubtitleMenu: Bool = false

    // Audio Tracks
    @Published var audioTracks: [(id: Int, name: String)] = []
    @Published var currentAudioIndex: Int = -1
    @Published var showAudioMenu: Bool = false
    @Published var showEngineMenu: Bool = false

    /// Row index inside the open menu. The network remote and the focus ring share it.
    @Published var subtitleHighlight: Int = 0
    @Published var audioHighlight: Int = 0
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
    /// Active playback engine, also shown in the controls badge.
    let engine: PlaybackEngine

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

                // Top bar: title (leading) + active-player badge (trailing)
                VStack {
                    HStack(alignment: .top, spacing: 24) {
                        if !data.title.isEmpty {
                            Text(data.title)
                                .font(.system(size: 32, weight: .semibold))
                                .foregroundColor(.white)
                                .lineLimit(2)
                                .shadow(color: .black.opacity(0.5), radius: 6, y: 2)
                        }
                        Spacer(minLength: 0)
                        playerBadge
                    }
                    .padding(.top, 50)
                    .padding(.horizontal, 60)
                    Spacer()
                }
                .transition(.opacity)

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
                        let displayTime = data.isVirtualScrubbing ? data.virtualTime : data.currentTime
                        VStack(spacing: 10) {
                            // Progress bar
                            HStack(spacing: 16) {
                                Text(formatTime(displayTime))
                                    .font(.system(size: 22, weight: .medium, design: .monospaced))
                                    .foregroundColor(.white.opacity(0.8))

                                SeekTrack(
                                    progress: data.duration > 0 ? displayTime / data.duration : 0,
                                    buffered: data.duration > 0 && data.bufferedTime > displayTime
                                        ? min(data.bufferedTime / data.duration, 1.0) : nil,
                                    chapterFractions: ChapterNavigation.chapterFractions(
                                        chapters: data.chapters, durationMs: durationMs),
                                    segmentRanges: ChapterNavigation.segmentRanges(
                                        segments: data.skipSegments, durationMs: durationMs))
                                    .frame(height: SeekTrack.height)

                                Text(formatTime(data.duration))
                                    .font(.system(size: 22, weight: .medium, design: .monospaced))
                                    .foregroundColor(.white.opacity(0.8))
                            }

                            if hasCaptionRow {
                                HStack(spacing: 16) {
                                    if let toast = data.chapterToast {
                                        Text(toast)
                                            .font(.system(size: 22, weight: .semibold))
                                            .foregroundColor(.white)
                                            .lineLimit(1)
                                    } else if let caption = caption(at: displayTime) {
                                        Text(caption)
                                            .font(.system(size: 20, weight: .medium))
                                            .foregroundColor(.white.opacity(0.75))
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                    if data.chapterKeysActive {
                                        Text(ChapterNavigation.hint)
                                            .font(.system(size: 18, weight: .medium))
                                            .foregroundColor(.white.opacity(0.6))
                                    }
                                }
                                .frame(height: Self.captionRowHeight)
                            }
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
                                if !data.subtitleTracks.isEmpty || !data.externalSubtitleTracks.isEmpty {
                                    Button(action: {
                                        data.subtitleHighlight = subtitleRowIndex
                                        data.showSubtitleMenu.toggle()
                                        data.showAudioMenu = false
                                        data.showEngineMenu = false
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
                                        data.audioHighlight = audioRowIndex
                                        data.showAudioMenu.toggle()
                                        data.showSubtitleMenu = false
                                        data.showEngineMenu = false
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
                                Button(action: onLoopToggle) {
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
                                Button(action: {
                                    data.engineHighlight = engineRowIndex
                                    data.showEngineMenu.toggle()
                                    data.showSubtitleMenu = false
                                    data.showAudioMenu = false
                                }) {
                                    VStack(spacing: 6) {
                                        Image(systemName: "arrow.triangle.2.circlepath")
                                            .font(.system(size: 26))
                                        Text("Switch")
                                            .font(.system(size: 16, weight: .medium))
                                    }
                                    .frame(width: 100, height: 70)
                                }
                                .buttonStyle(.card)
                                
                                if data.hasPlaylist {
                                    // Playlist button
                                    Button(action: { onTogglePlaylist() }) {
                                        VStack(spacing: 6) {
                                            Image(systemName: "list.bullet")
                                                .font(.system(size: 26))
                                            Text("Playlist")
                                                .font(.system(size: 16, weight: .medium))
                                        }
                                        .frame(width: 100, height: 70)
                                    }
                                    .buttonStyle(.card)
                                }
                            }
                            .disabled(data.showSubtitleMenu || data.showAudioMenu || data.showEngineMenu)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .padding(.bottom, 60)
                }
                .transition(.opacity)
            }

            if data.skipControlsEnabled, let segment = data.activeSkipSegment,
               !data.isVirtualScrubbing, !data.showSubtitleMenu, !data.showAudioMenu, !data.showEngineMenu {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        SkipSegmentButton(
                            title: segment.buttonTitle, claimsFocus: !data.userPaused,
                            action: onSkipSegment)
                    }
                    .padding(.horizontal, 80)
                    .padding(.bottom, skipButtonBottomPadding)
                }
            }

            // MARK: - Track Picker Popups
            if data.showSubtitleMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                    .transition(.opacity)

                TrackMenuView(
                    title: "Subtitles",
                    icon: "captions.bubble.fill",
                    tracks: data.subtitleTracks + data.externalSubtitleTracks,
                    currentId: data.currentSubtitleIndex,
                    includeOff: true,
                    highlightedIndex: $data.subtitleHighlight
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
                    includeOff: false,
                    highlightedIndex: $data.audioHighlight
                ) { id in
                    onSelectAudio(id)
                    data.showAudioMenu = false
                }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
            }

            if data.showEngineMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                    .transition(.opacity)

                TrackMenuView(
                    title: "Switch Player",
                    icon: "arrow.triangle.2.circlepath",
                    tracks: PlaybackEngine.menuOrder(current: engine).map { ($0.menuID, $0.name) },
                    currentId: engine.menuID,
                    includeOff: false,
                    showCurrentLabel: true,
                    highlightedIndex: $data.engineHighlight
                ) { id in
                    data.showEngineMenu = false
                    if let target = PlaybackEngine.allCases.first(where: { $0.menuID == id }), target != engine {
                        onSwitchEngine(target)
                    }
                }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
            }

            if !data.prebuffering {
                if let sentence = data.failureSentence {
                    PlaybackFailureOverlay(title: data.title, sentence: sentence,
                        hasNextItem: data.failureHasNextItem, highlighted: $data.failureHighlight,
                        onAction: onFailureAction)
                } else if data.surfaceState.showsSpinner {
                    ProgressView()
                        .tint(.white)
                        .scaleEffect(2)
                        .frame(width: 64, height: 64)
                        .accessibilityLabel("Buffering")
                        .allowsHitTesting(false)
                }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: data.showUI)
        .animation(.easeInOut(duration: 0.3), value: data.userPaused)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showSubtitleMenu)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showAudioMenu)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showEngineMenu)
        .allowsHitTesting(data.failureSentence != nil || data.showUI || data.showSubtitleMenu || data.showAudioMenu || data.showEngineMenu)
    }

    private static let captionRowHeight: CGFloat = 28

    private var durationMs: Int { PlaybackTime.milliseconds(data.duration) }

    private func caption(at seconds: Double) -> String? {
        ChapterNavigation.timelineCaption(
            chapters: data.chapters, segments: data.skipSegments,
            positionMs: PlaybackTime.milliseconds(seconds), durationMs: durationMs)
    }

    /// The row under the bar holds the jump toast, the playhead caption, or the hint.
    private var hasCaptionRow: Bool {
        !data.chapters.isEmpty || !data.skipSegments.isEmpty || data.chapterToast != nil
    }

    /// Keeps the Skip pill clear of the bottom bar, which grows by the caption row.
    private var skipButtonBottomPadding: CGFloat {
        guard data.showUI else { return 80 }
        let base: CGFloat = data.userPaused ? 300 : 140
        return hasCaptionRow ? base + Self.captionRowHeight + 10 : base
    }

    private var subtitleRowIndex: Int {
        let ids = PlayerRemoteNavigation.menuRowIDs(
            trackIDs: data.subtitleTracks.map(\.id) + data.externalSubtitleTracks.map(\.id),
            includeOff: true
        )
        return ids.firstIndex(of: data.currentSubtitleIndex) ?? 0
    }

    private var audioRowIndex: Int {
        let ids = PlayerRemoteNavigation.menuRowIDs(trackIDs: data.audioTracks.map(\.id), includeOff: false)
        return ids.firstIndex(of: data.currentAudioIndex) ?? 0
    }

    private var engineRowIndex: Int {
        let ids = PlayerRemoteNavigation.menuRowIDs(
            trackIDs: PlaybackEngine.menuOrder(current: engine).map(\.menuID),
            includeOff: false
        )
        return ids.firstIndex(of: engine.menuID) ?? 0
    }

    private var playerBadge: some View {
        HStack(spacing: 8) {
            Image(systemName: "play.rectangle.fill")
                .font(.system(size: 18))
            Text(engine.name)
                .font(.system(size: 18, weight: .semibold))
        }
        .foregroundColor(.white.opacity(0.9))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
        .clipShape(Capsule())
        .overlay(
            Capsule().stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
    }

    private func formatTime(_ seconds: Double) -> String {
        let sec = PlaybackTime.seconds(seconds)
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
    static let height: CGFloat = 16
    static let lineHeight: CGFloat = 8
    static let gapWidth: CGFloat = 4

    let progress: Double
    let buffered: Double?
    let chapterFractions: [Double]
    let segmentRanges: [ClosedRange<Double>]

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
                context.fill(capsule(from: range.lowerBound, to: range.upperBound, height: size.height),
                             with: .color(Theme.segment.opacity(0.35)))
            }
            context.fill(capsule(from: 0, to: 1, height: Self.lineHeight), with: .color(.white.opacity(0.2)))
            if let buffered, buffered > progress {
                context.fill(capsule(from: 0, to: buffered, height: Self.lineHeight),
                             with: .color(.white.opacity(0.35)))
            }
            if progress > 0 {
                let played = capsule(from: 0, to: progress, height: Self.lineHeight)
                context.fill(played, with: .linearGradient(
                    Gradient(colors: [Theme.accent, Theme.accent.opacity(0.7)]),
                    startPoint: CGPoint(x: 0, y: midY),
                    endPoint: CGPoint(x: width * CGFloat(min(progress, 1)), y: midY)))
            }

            context.blendMode = .clear
            for fraction in chapterFractions {
                let x = width * CGFloat(fraction)
                guard x > Self.gapWidth, x < width - Self.gapWidth else { continue }
                context.fill(Path(CGRect(x: x - Self.gapWidth / 2, y: 0, width: Self.gapWidth, height: size.height)),
                             with: .color(.black))
            }
        }
        .compositingGroup()
    }
}

/// Skip Intro / Credits pill. Bright when focused so Select's target is obvious; while
/// playing it takes focus as it appears (the overlay has no other focusable control).
private struct SkipSegmentButton: View {
    let title: String
    let claimsFocus: Bool
    let action: () -> Void
    @FocusState private var focused: Bool

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
        .focused($focused)
        .onAppear { if claimsFocus { focused = true } }
    }
}

private struct SkipPillStyle: ButtonStyle {
    let focused: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(focused ? .black : .white)
            .padding(.horizontal, 32)
            .padding(.vertical, 18)
            .background(Capsule().fill(focused ? Color.white : Color.black.opacity(0.55)))
            .overlay(Capsule().stroke(Color.white.opacity(focused ? 0 : 0.35), lineWidth: 2))
            .scaleEffect(configuration.isPressed ? 0.97 : (focused ? 1.08 : 1.0))
            .shadow(color: .black.opacity(0.4), radius: focused ? 16 : 6, y: 6)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}
