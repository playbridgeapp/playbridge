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
    /// AVPlayer-style bar: thick and bright while focused, thin and dim otherwise,
    /// with a narrow playhead tick instead of a round thumb.
    static let focusedLineHeight = 12.0
    static let playheadWidth = 3.0
    static let playheadOverhang = 5.0
    static let timeLabelGap = 24.0
    /// The playlist sheet's height; subtitles clear it while it is open.
    static let playlistSheetHeight = 500.0
}

/// Text for a playlist card; payloads stay outside the host-tested model.
enum PlaylistCardText {
    /// Eyebrow ("S1 · E6") and title: the episode title for episodes, else the
    /// item title, else a numbered fallback.
    static func lines(title: String?, season: Int?, episode: Int?, episodeTitle: String?,
                      index: Int) -> (eyebrow: String, title: String) {
        func clean(_ text: String?) -> String? {
            guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
            return text
        }
        if let season, let episode {
            return ("S\(season) · E\(episode)", clean(episodeTitle) ?? clean(title) ?? "Episode \(episode)")
        }
        return ("", clean(title) ?? "Item \(index + 1)")
    }
}

enum TransportTimeLabels {
    /// The line under the title, unless the title already says it
    /// ("Below · S1E5 · Little Pot Rock" over "S1 E5 · Little Pot Rock").
    static func distinctSubtitle(_ subtitle: String, title: String) -> String {
        func compact(_ text: String) -> String {
            text.lowercased().filter { !$0.isWhitespace && $0 != "·" && $0 != "-" && $0 != ":" }
        }
        let line = compact(subtitle)
        guard !line.isEmpty else { return "" }
        return compact(title).contains(line) ? "" : subtitle
    }

    /// Clock format without a leading zero on the first field ("1:51", "1:02:03").
    static func clock(_ seconds: Double) -> String {
        let seconds = PlaybackTime.seconds(seconds)
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let rest = String(format: "%02d", seconds % 60)
        return hours > 0 ? "\(hours):\(String(format: "%02d", minutes)):\(rest)"
                         : "\(minutes):\(rest)"
    }

    static func clockRemaining(_ displayTime: Double, duration: Double) -> String {
        guard duration.isFinite, duration > 0 else { return "" }
        let display = displayTime.isFinite && displayTime > 0 ? displayTime : 0
        return "-" + clock(max(0, duration - display))
    }

    /// Leading x of the elapsed label, centred under the playhead and kept on the
    /// bar. The remaining label hides while the elapsed one would touch it.
    static func playheadLabelLayout(fraction: Double, width: Double, elapsedWidth: Double,
                                    remainingWidth: Double) -> (x: Double, showsRemaining: Bool) {
        guard width.isFinite, width > 0 else { return (0, true) }
        let progress = fraction.isFinite ? min(max(fraction, 0), 1) : 0
        let x = min(max(width * progress - elapsedWidth / 2, 0), max(width - elapsedWidth, 0))
        let showsRemaining = x + elapsedWidth + TransportBarMetrics.timeLabelGap <= width - remainingWidth
        return (x, showsRemaining)
    }

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
    /// A full-width swipe on the Siri Remote touchpad (~1920 pt of translation)
    /// covers the whole item, at most `maximumSpanSeconds`: fine control on long
    /// episodes and films instead of flying to either end.
    static let translationSpanPoints = 1920.0
    static let minimumSpanSeconds = 60.0
    static let maximumSpanSeconds = 600.0
    static let gainThresholdVelocity = 300.0
    static let maximumGainVelocity = 2000.0
    static let maximumGain = 2.0

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
        let span = min(max(duration, minimumSpanSeconds), maximumSpanSeconds)
        let delta = (translationX / translationSpanPoints) * span * gain
        return min(max(anchor + delta, 0), duration)
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
    /// Poster as sent (see `ArtworkURL`); the Info panel shows it beside the text.
    var posterURL = ""

    /// Nothing for the Info panel to show besides the title.
    var isEmpty: Bool {
        [subtitle, year, rating, runtime, overview, posterURL].allSatisfy(\.isEmpty) && genres.isEmpty
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
    enum Category: Equatable {
        case generic, unsupported, network, stall, decoder
        case http(Int)
    }

    static func sentence(for category: Category) -> String {
        switch category {
        case .unsupported: return "The link didn't return a playable video or the format isn't supported"
        case .network: return "The TV couldn't reach the video server"
        case .stall: return "The video stopped loading"
        case .decoder: return "The TV couldn't decode this video"
        case .http(let status) where status == 401 || status == 403: return "The link was refused (HTTP \(status))"
        case .http(let status) where status == 404 || status == 410: return "The video wasn't found (HTTP \(status))"
        case .http(let status) where (100...599).contains(status): return "The media couldn't be loaded (HTTP \(status))"
        default: return "The media couldn't be loaded"
        }
    }

    /// Stable libmpv ABI codes: -13 is generic loading failure, not evidence of an expired link.
    static func sentence(forMpvCode code: Int32, category: Category? = nil) -> String {
        if [-17, -16, -18].contains(code) { return sentence(for: .unsupported) }
        return sentence(for: category ?? .generic)
    }

    /// Raw native logs remain transient. Only this enum survives to END_FILE.
    static func category(prefix: String, text: String) -> Category? {
        guard prefix == "cplayer" || prefix == "ffmpeg" || prefix.hasPrefix("ffmpeg/") else { return nil }
        if let regex = try? NSRegularExpression(pattern: "\\bHTTP error ([1-5][0-9]{2})\\b"),
           let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
           let range = Range(match.range(at: 1), in: text), let status = Int(text[range]) { return .http(status) }
        if text.hasPrefix("Failed to recognize file format") || text.hasPrefix("Unrecognized file format") { return .unsupported }
        if ["Failed to resolve hostname", "Connection timed out", "TLS handshake failed", "Connection refused", "Network is unreachable"].contains(where: text.hasPrefix) { return .network }
        return nil
    }
}

/// A recovered subtitle/segment error must not label a later unrelated failure.
struct PlaybackFailureLogState {
    private(set) var category: PlaybackFailureMessage.Category?
    /// Sticky per loaded file/attempt so demuxer/HTTP truncation survives trailing buffered frames
    /// and distinguishes a dropped connection from a complete file shorter than its declared duration.
    private(set) var sawTruncationOrNetworkError = false
    private var lastPosition = 0.0

    mutating func record(prefix: String, level: Int, text: String) {
        guard (10...20).contains(level) else { return }
        if prefix == "cplayer" || prefix == "ffmpeg" || prefix.hasPrefix("ffmpeg/") {
            let markers = [
                "partial file",
                "Packet corrupt",
                "Error during demuxing",
                "Error when loading",
                "avformat_open_input() failed",
                "Input/output error",
            ]
            if markers.contains(where: { text.contains($0) }) {
                sawTruncationOrNetworkError = true
            }
        }
        guard let category = PlaybackFailureMessage.category(prefix: prefix, text: text) else { return }
        self.category = category
        self.sawTruncationOrNetworkError = true
    }

    mutating func beginFile() {
        playbackHealthy()
        sawTruncationOrNetworkError = false
        lastPosition = 0
    }

    mutating func playbackHealthy() { category = nil }

    mutating func positionChanged(_ seconds: Double) {
        guard seconds.isFinite else { return }
        if seconds > lastPosition { playbackHealthy() }
        lastPosition = seconds
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
        case presented(mpvCode: Int32, category: PlaybackFailureMessage.Category? = nil)
        case tryAgain, next, close, phoneStop, autoClose
        case stalled
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
        case .presented(let code, let category): return .errorStatus(reason: PlaybackFailureMessage.sentence(forMpvCode: code, category: category))
        case .close, .phoneStop, .autoClose: return .idleContext
        case .tryAgain, .next: return nil
        case .stalled: return .errorStatus(reason: PlaybackStallDetector.sentence)
        }
    }

    /// Party playback keeps its failure screen up until the user explicitly closes
    /// or backs out, or until the host loads a new video.
    static func shouldDismissFailure(event: Event, isWatchTogetherParty: Bool) -> Bool {
        switch event {
        case .autoClose, .phoneStop:
            return !isWatchTogetherParty
        case .close:
            return true
        case .presented, .tryAgain, .next, .stalled:
            return false
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

    mutating func present(code: Int32, category: PlaybackFailureMessage.Category? = nil, attempt token: UUID) -> Bool {
        guard matches(token), sentence == nil,
              case .errorStatus(let reason) = PlaybackFailureModel.phoneReport(for: .presented(mpvCode: code, category: category)) else { return false }
        sentence = reason
        return true
    }

    mutating func close(attempt token: UUID) -> Bool {
        guard matches(token), sentence != nil else { return false }
        closed = true
        return true
    }

    mutating func presentStall(attempt token: UUID) -> Bool {
        guard matches(token), sentence == nil,
              case .errorStatus(let reason) = PlaybackFailureModel.phoneReport(for: .stalled) else { return false }
        sentence = reason
        return true
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
    /// Gate for the touch scrub recognizer.
    static func canTouchScrub(chrome: TransportChrome) -> Bool {
        let state = chrome.navigation
        return chrome.userPaused && chrome.duration.isFinite && chrome.duration > 0
            && !chrome.prebuffering && !chrome.hasFailure && !state.stillWatching
            && !state.infoPanel && !state.engineMenu
            && !state.settingsSheet && !state.playlist && !state.scrubbing && !chrome.skipPillFocused
    }
}
