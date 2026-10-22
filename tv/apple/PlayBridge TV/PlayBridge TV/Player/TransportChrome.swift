import Foundation

/// Shared dimensions for the transport and the chapter canvas, in points.
enum TransportBarMetrics {
    static let horizontalInset = 80.0
    static let bottomInset = 60.0
    static let frameHeight = 14.0
    static let lineHeight = 6.0
    static let thumbDiameter = 16.0
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
    static func visibleTabs(chapterCount: Int, settingsAvailable: Bool) -> [InfoPanelTab] {
        var tabs: [InfoPanelTab] = [.info]
        if chapterCount >= 2 { tabs.append(.chapters) }
        tabs += [.audio, .subtitles]
        if settingsAvailable { tabs.append(.settings) }
        return tabs
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

/// Physical-only context supplements the shared network chrome without adding
/// pause, gesture or focus state to the wire-key policy.
struct TransportChrome: Equatable {
    var navigation = PlayerChromeState()
    var userPaused = false
    var panelTabBarFocused = false
    var skipPillFocused = false
    var duration = 0.0
    var prebuffering = false
    var hasFailure = false
}

enum TransportKeyRoute: Equatable {
    case showTransport, openPanel, closePanel, moveInPanel
    /// Existing menus, the playlist and the transport action row keep UIKit
    /// focus movement on Up. Down opens Info even when the Skip pill is focused.
    case moveFocus

    /// Call after the controller's still-watching, pre-play and error guards.
    /// Down always requests Info when closed. Chapter selection belongs in its
    /// tab; no seek-strip or hold-scrub chapter shortcut is modeled here.
    static func physicalVertical(up: Bool, chrome: TransportChrome) -> Self {
        let state = chrome.navigation
        if state.infoPanel {
            return up && chrome.panelTabBarFocused ? .closePanel : .moveInPanel
        }
        // An open list owns both directions; Down only opens Info outside lists.
        if state.subtitleMenu || state.audioMenu || state.engineMenu || state.settingsSheet || state.playlist {
            return .moveFocus
        }
        if !up { return .openPanel }
        return state.controlsVisible ? .moveFocus : .showTransport
    }

    /// Gate for the later touch recognizer. The proposal math also handles an
    /// unknown duration safely, even if a caller forgets this guard.
    static func canTouchScrub(chrome: TransportChrome) -> Bool {
        let state = chrome.navigation
        return chrome.userPaused && chrome.duration.isFinite && chrome.duration > 0
            && !chrome.prebuffering && !chrome.hasFailure && !state.stillWatching
            && !state.infoPanel && !state.subtitleMenu && !state.audioMenu && !state.engineMenu
            && !state.settingsSheet && !state.playlist && !state.scrubbing && !chrome.skipPillFocused
    }
}
