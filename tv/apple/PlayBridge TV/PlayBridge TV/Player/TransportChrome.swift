import Foundation

/// Shared dimensions for the transport and the chapter canvas, in points.
enum TransportBarMetrics {
    static let horizontalInset = 80.0
    static let bottomInset = 60.0
    static let skipVisibleInset = 240.0
    static let skipHiddenInset = 80.0
    static let frameHeight = 14.0
    static let lineHeight = 6.0
    static let thumbDiameter = 16.0
    static let focusedThumbDiameter = 24.0
}

enum TransportTimeLabels {
    static func elapsed(_ seconds: Double) -> String {
        let seconds = PlaybackTime.seconds(seconds)
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let remainder = seconds % 60
        let tail = String(format: "%02d", remainder)
        return hours > 0
            ? "\(hours):\(String(format: "%02d", minutes)):\(tail)"
            : "\(minutes):\(tail)"
    }

    static func remaining(_ displayTime: Double, duration: Double) -> String {
        guard duration.isFinite, duration > 0 else { return "" }
        let display = displayTime.isFinite && displayTime > 0 ? displayTime : 0
        return "−" + elapsed(max(0, duration - display))
    }

    /// The title has its own line. Never repeat it as a metadata fallback.
    /// Optional coordinates preserve season/episode zero (specials).
    static func metadataSubtitle(title _: String? = nil, year: String? = nil,
                                 season: Int? = nil, episode: Int? = nil,
                                 episodeTitle: String? = nil) -> String {
        if let season, let episode {
            let coordinates = "S\(season) E\(episode)"
            guard let name = nonblank(episodeTitle) else { return coordinates }
            return coordinates + " · " + name
        }
        return nonblank(year) ?? ""
    }

    private static func nonblank(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }
}

enum TransportScrub {
    static let clickSkipSeconds = 10.0
    static let remoteSkipSeconds = 15.0
    static let holdThreshold = 0.5
    static let translationSpanPoints = 500.0
    static let minimumSpanSeconds = 60.0
    static let gainThresholdVelocity = 300.0
    static let maximumGainVelocity = 2000.0
    static let maximumGain = 4.0

    /// A proposed time only; the caller commits or cancels without seeking on
    /// every gesture update. Unknown/live duration disables touch scrubbing.
    static func proposal(origin: Double, translationX: Double, velocityX: Double, duration: Double) -> Double {
        let origin = origin.isFinite ? origin : 0
        guard duration.isFinite, duration > 0 else { return origin }
        let anchor = min(max(origin, 0), duration)
        guard translationX.isFinite else { return anchor }
        let velocity = velocityX.isFinite ? abs(velocityX) : 0
        let fraction = min(max((velocity - gainThresholdVelocity)
            / (maximumGainVelocity - gainThresholdVelocity), 0), 1)
        let gain = 1 + (maximumGain - 1) * fraction
        let span = max(duration, minimumSpanSeconds)
        let delta = (translationX / translationSpanPoints) * span * gain
        return min(max(anchor + delta, 0), duration)
    }
}

enum InfoPanelTab: String, CaseIterable {
    case info = "Info", chapters = "Chapters", audio = "Audio", subtitles = "Subtitles", settings = "Settings"
}

enum InfoPanelModel {
    /// Only tabs with something to show, as AVPlayer's info panel: Info needs
    /// metadata beyond the title, Audio a choice of two tracks, Subtitles any track.
    /// Never empty; a bare item still gets Info.
    static func visibleTabs(chapterCount: Int, settingsAvailable: Bool, hasInfo: Bool = true,
                            audioTrackCount: Int = 2, subtitleTrackCount: Int = 1) -> [InfoPanelTab] {
        var tabs: [InfoPanelTab] = []
        if hasInfo { tabs.append(.info) }
        if chapterCount >= 2 { tabs.append(.chapters) }
        if audioTrackCount >= 2 { tabs.append(.audio) }
        if subtitleTrackCount >= 1 { tabs.append(.subtitles) }
        if settingsAvailable { tabs.append(.settings) }
        return tabs.isEmpty ? [.info] : tabs
    }

    /// The tab to show: the current one while it is still visible, else the first.
    static func validTab(_ tab: InfoPanelTab, tabs: [InfoPanelTab]) -> InfoPanelTab {
        tabs.contains(tab) ? tab : (tabs.first ?? .info)
    }
}

/// Display-only metadata; generated protobufs stay outside the host-tested model.
struct TransportMetadata: Equatable {
    var subtitle = ""
    var year = ""
    var rating = ""
    var runtime = ""
    var overview = ""
    var genres: [String] = []

    /// Nothing for the Info tab to show besides the title.
    var isEmpty: Bool {
        [subtitle, year, rating, runtime, overview].allSatisfy(\.isEmpty) && genres.isEmpty
    }
}

struct InfoPanelRow: Equatable {
    var id: Int
    var title: String
    var selected: Bool = false
    var trailingText: String? = nil
    /// Header drawn above this row (the Settings tab's Speed / Scaling / Subtitle Sync blocks).
    var section: String? = nil
}

extension InfoPanelModel {
    static func chapterRows(chapters: [TimelineChapter], durationMs: Int, positionMs: Int) -> [InfoPanelRow] {
        let markers = ChapterNavigation.navigationMarkers(chapters: chapters, durationMs: durationMs)
        let current = markers.lastIndex { $0.startMs <= positionMs }
        return markers.enumerated().map { index, marker in
            // The cards show the title alone, as AVPlayer does ("Chapter N" when untitled).
            InfoPanelRow(id: marker.startMs, title: marker.title,
                selected: index == current,
                trailingText: TransportTimeLabels.elapsed(Double(marker.startMs) / 1000))
        }
    }

    static func initialHighlight(rows: [InfoPanelRow]) -> Int {
        rows.firstIndex { $0.selected } ?? 0
    }

    /// Physical Left/Right switches tabs, except on the chapter cards where
    /// it moves between cards (AVPlayer's chapter strip).
    static func horizontalMovesRows(tab: InfoPanelTab, tabBarFocused: Bool) -> Bool {
        tab == .chapters && !tabBarFocused
    }

    /// A second move command this soon after the last applied one is the same key
    /// press reported twice (Simulator keyboard), never a deliberate second press.
    static let moveEchoSeconds = 0.08

    static func isMoveEcho(focusMovedAt: Date, now: Date) -> Bool {
        let elapsed = now.timeIntervalSince(focusMovedAt)
        return elapsed >= 0 && elapsed < moveEchoSeconds
    }

    static func movingTab(_ tab: InfoPanelTab, delta: Int, tabs: [InfoPanelTab]) -> InfoPanelTab {
        guard !tabs.isEmpty else { return .info }
        let index = tabs.firstIndex(of: tab) ?? 0
        return tabs[PlayerHighlightList(count: tabs.count, index: index).moving(delta).index]
    }
}

/// Physical Up can reach the tabs; network directions stay within the rows.
/// Chapters are a horizontal card row: Up from any card reaches the tabs and
/// Down stays on the cards.
struct InfoPanelFocus: Equatable {
    var row = 0
    var tabBar = false

    func moving(_ delta: Int, rowCount: Int, physical: Bool, horizontalRows: Bool = false) -> Self {
        if physical && tabBar {
            return delta > 0 ? Self(row: row, tabBar: rowCount == 0) : self
        }
        if physical && horizontalRows { return delta < 0 ? Self(row: row, tabBar: true) : self }
        if physical && delta < 0 && row == 0 { return Self(row: 0, tabBar: true) }
        return Self(row: PlayerHighlightList(count: rowCount, index: row).moving(delta).index,
                    tabBar: rowCount == 0)
    }
}

enum TransportVisibility {
    static let autoHideSeconds = 5.0
    static let feedbackSeconds = 0.8

    static func canAutoHide(userPaused: Bool, scrubbing: Bool, panel: Bool,
                           engineMenu: Bool, playlist: Bool, prebuffering: Bool) -> Bool {
        !userPaused && !scrubbing && !panel && !engineMenu && !playlist && !prebuffering
    }
}

/// mpv sub-pos is a percentage of screen height (100 is its normal placement).
/// Keep the subtitle's lower edge above the entire visible chrome, including
/// the panel, without changing subtitle styles or the stored subtitle delay.
enum TransportSubtitlePlacement {
    static let normalPosition = 100.0
    static let minimumPosition = 5.0
    static let clearancePoints = 24.0

    static func position(occludedHeight: Double, viewportHeight: Double) -> Double {
        guard viewportHeight.isFinite, viewportHeight > 0,
              occludedHeight.isFinite, occludedHeight > 0 else { return normalPosition }
        let fraction = min((occludedHeight + clearancePoints) / viewportHeight, 1)
        return max(minimumPosition, normalPosition * (1 - fraction))
    }
}

enum PlaybackSurfaceState: String {
    case playing, paused, buffering

    var showsSpinner: Bool { self == .buffering }

    static func resolve(cacheStalled: Bool, userPaused: Bool, prebuffering: Bool,
                        enginePaused: Bool) -> Self {
        if prebuffering || userPaused { return .paused }
        if cacheStalled { return .buffering }
        return enginePaused ? .paused : .playing
    }
}

enum PlaybackFailureMessage {
    /// Stable libmpv client ABI values. Keep this model independent of Libmpv.
    /// Option (-5...-7) and property (-8...-11) failures can occur during init;
    /// -13 is MPV_ERROR_LOADING_FAILED. No decoder/error text is interpolated.
    static func sentence(forMpvCode code: Int32) -> String {
        switch code {
        case -13, -11 ... -5: return "The video couldn't be opened."
        default: return "This video couldn't be played."
        }
    }
}

enum PlaybackFailureAction: String {
    case tryAgain = "Try Again", next = "Next", close = "Close"
}

/// Policy only: the later error-screen PR owns rendering, timers and playback.
enum PlaybackFailureModel {
    static let autoCloseSeconds = 60.0
    static let defaultAction = PlaybackFailureAction.tryAgain

    static func actions(hasNextItem: Bool) -> [PlaybackFailureAction] {
        hasNextItem ? [.tryAgain, .next, .close] : [.tryAgain, .close]
    }

    static func shouldAutoClose(elapsedSeconds: Double) -> Bool {
        elapsedSeconds.isFinite && elapsedSeconds >= autoCloseSeconds
    }

    enum Event {
        case presented(mpvCode: Int32)
        case tryAgain, next, close, phoneStop, autoClose
    }

    enum PhoneReport: Equatable {
        case errorStatus(reason: String)
        case idleContext
    }

    /// Report failure synchronously on presentation, keeping the phone in the
    /// player context. Retry and Next continue playback, so neither sends idle.
    /// Close, phone Stop and timeout all close the error screen and return idle.
    static func phoneReport(for event: Event) -> PhoneReport? {
        switch event {
        case .presented(let code): return .errorStatus(reason: PlaybackFailureMessage.sentence(forMpvCode: code))
        case .close, .phoneStop, .autoClose: return .idleContext
        case .tryAgain, .next: return nil
        }
    }
}

/// Main-thread attempt fence shared by initialization, end-file callbacks and
/// the error-screen timeout. A retry can reuse an item generation, so it needs
/// a fresh token as well. A closed attempt cannot present another late error.
struct PlaybackFailureSession {
    private(set) var attempt = UUID()
    private(set) var sentence: String?
    private var closed = false

    mutating func beginAttempt() -> UUID {
        attempt = UUID()
        sentence = nil
        closed = false
        return attempt
    }

    func matches(_ token: UUID) -> Bool { token == attempt && !closed }

    mutating func present(code: Int32, attempt token: UUID) -> Bool {
        guard matches(token), sentence == nil,
              case .errorStatus(let reason) = PlaybackFailureModel.phoneReport(for: .presented(mpvCode: code)) else { return false }
        sentence = reason
        return true
    }

    mutating func close(attempt token: UUID) -> Bool {
        guard matches(token), sentence != nil else { return false }
        closed = true
        return true
    }
}

/// Physical transport focus order, matching AVPlayer's transport row (Loop,
/// Speed, Switch, Audio). Playlist is absent for a one-item queue.
enum TransportAction: CaseIterable {
    case loop, speed, switchEngine, audio, playlist

    static func available(hasPlaylist: Bool) -> [Self] {
        let actions: [Self] = [.loop, .speed, .switchEngine, .audio]
        return hasPlaylist ? actions + [.playlist] : actions
    }

    /// Switch, Speed and Audio open a pop-up menu above the row (AVPlayer's
    /// contextual menus); Loop and Playlist act directly.
    var menu: TransportMenu? {
        switch self {
        case .speed: return .speed
        case .switchEngine: return .engine
        case .audio: return .audio
        case .loop, .playlist: return nil
        }
    }

    func moving(_ delta: Int, hasPlaylist: Bool) -> Self {
        let actions = Self.available(hasPlaylist: hasPlaylist)
        let index = actions.firstIndex(of: self) ?? 0
        return actions[PlayerHighlightList(count: actions.count, index: index).moving(delta).index]
    }
}

enum TransportMenu: Equatable {
    case engine, speed, audio

    var title: String {
        switch self {
        case .engine: return "Switch Player"
        case .speed: return "Playback Speed"
        case .audio: return "Audio"
        }
    }

    var icon: String {
        switch self {
        case .engine: return "arrow.triangle.2.circlepath"
        case .speed: return "speedometer"
        case .audio: return "waveform"
        }
    }
}

struct TransportMenuRow: Equatable {
    var id: Int
    var title: String
    var selected = false
}

enum TransportMenuModel {
    /// Row ids index `PlayerSettingsItem.speeds`.
    static func speedRows(current: Double) -> [TransportMenuRow] {
        PlayerSettingsItem.speeds.enumerated().map { index, value in
            TransportMenuRow(id: index, title: PlayerSettingsItem.speedLabel(value), selected: value == current)
        }
    }

    static func speed(forRow id: Int) -> Double? {
        PlayerSettingsItem.speeds.indices.contains(id) ? PlayerSettingsItem.speeds[id] : nil
    }

    static func audioRows(tracks: [(id: Int, name: String)], current: Int) -> [TransportMenuRow] {
        tracks.map { TransportMenuRow(id: $0.id, title: $0.name, selected: $0.id == current) }
    }

    static func engineRows(current: PlaybackEngine) -> [TransportMenuRow] {
        PlaybackEngine.menuOrder(current: current).map {
            TransportMenuRow(id: $0.menuID, title: $0.name, selected: $0 == current)
        }
    }

    /// Opens on the selected row, like the Info panel.
    static func initialHighlight(rows: [TransportMenuRow]) -> Int {
        rows.firstIndex { $0.selected } ?? 0
    }
}

/// Physical-only context supplements the shared network chrome without adding
/// pause, gesture or focus state to the wire-key policy.
struct TransportChrome: Equatable {
    var navigation = PlayerChromeState()
    var userPaused = false
    var panelTabBarFocused = false
    var transportActionFocused = false
    var skipPillFocused = false
    var duration = 0.0
    var prebuffering = false
    var hasFailure = false
}

enum TransportKeyRoute: Equatable {
    case showTransport, openPanel, closePanel, moveInPanel
    case focusActions, focusScrubber
    /// Existing menus, the playlist and the Skip pill keep UIKit focus movement.
    case moveFocus

    /// Call after the controller's still-watching, pre-play and error guards.
    /// Down from the action row returns to the scrubber; elsewhere it opens
    /// Info. Explicit Up routing avoids relying on the above-right geometry.
    static func physicalVertical(up: Bool, chrome: TransportChrome) -> Self {
        let state = chrome.navigation
        if state.infoPanel {
            return up && chrome.panelTabBarFocused ? .closePanel : .moveInPanel
        }
        // An open list owns both directions; Down only opens Info outside lists.
        if state.engineMenu || state.settingsSheet || state.playlist {
            return .moveFocus
        }
        if state.controlsVisible && chrome.transportActionFocused {
            return up ? .focusActions : .focusScrubber
        }
        if !up { return .openPanel }
        guard state.controlsVisible else { return .showTransport }
        return chrome.skipPillFocused ? .moveFocus : .focusActions
    }

    /// Gate for the later touch recognizer. The proposal math also handles an
    /// unknown duration safely, even if a caller forgets this guard.
    static func canTouchScrub(chrome: TransportChrome) -> Bool {
        let state = chrome.navigation
        return chrome.userPaused && chrome.duration.isFinite && chrome.duration > 0
            && !chrome.prebuffering && !chrome.hasFailure && !state.stillWatching
            && !state.infoPanel && !state.engineMenu
            && !state.settingsSheet && !state.playlist && !state.scrubbing && !chrome.skipPillFocused
    }
}
