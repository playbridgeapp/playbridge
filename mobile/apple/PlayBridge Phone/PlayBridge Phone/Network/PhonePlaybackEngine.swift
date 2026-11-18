import Foundation
import CoreGraphics

/// The phone player's decoder. Which one a video starts on, and whether it may move to the
/// other once, follows `PhoneEngineMode`; the player's More sheet or the failure screen switch
/// it by hand.
enum PhonePlaybackEngineKind: String, CaseIterable, Identifiable {
    case mpv = "mpv"
    case avPlayer = "avplayer"

    var id: String { rawValue }
    var label: String { self == .mpv ? "mpv" : "AVPlayer" }
    var other: Self { self == .mpv ? .avPlayer : .mpv }
}

/// Settings › Phone player › Player. The second player takes a video over once, and only one
/// that it can play (`PhoneEngineFallback`).
enum PhoneEngineMode: String, CaseIterable, Identifiable {
    /// Everything starts on mpv; AVPlayer takes over HLS, MP4, MOV and audio mpv can't play.
    case mpvFirst = "mpv_first"
    /// HLS, MP4, MOV and audio start on AVPlayer, everything else on mpv; mpv takes over any
    /// video AVPlayer can't play.
    case avPlayerFirst = "avplayer_first"
    /// mpv, never switching by itself.
    case mpvOnly = "mpv_only"

    static let key = "phone_player_engine_mode"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .mpvFirst: return "mpv first"
        case .avPlayerFirst: return "AVPlayer first"
        case .mpvOnly: return "mpv only"
        }
    }

    static func load(from store: UserDefaults = .standard) -> Self {
        store.string(forKey: key).flatMap(Self.init(rawValue:)) ?? .mpvFirst
    }

    /// The player a new video starts on.
    func startingEngine(url: URL, contentType: String?) -> PhonePlaybackEngineKind {
        self == .avPlayerFirst && PhoneEngineFallback.avPlayerPlays(url: url, contentType: contentType) ? .avPlayer : .mpv
    }
}

/// When a video moves to the other player: only to one that plays its format (mpv plays all
/// of AVPlayer's), and only for a failure that player could get past. Network, HTTP, TLS and
/// certificate failures would fail there too, so they never switch. Once per video.
enum PhoneEngineFallback {
    /// HLS, MP4/MOV and plain audio. MKV, WebM, DASH, raw TS and unknown types never switch.
    static func avPlayerPlays(url: URL, contentType: String?) -> Bool {
        let mime = contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        let refused = ["video/x-matroska", "video/matroska", "audio/x-matroska", "video/webm", "audio/webm",
                       "application/dash+xml", "video/mp2t"]
        if refused.contains(mime) { return false }
        let playable = ["application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
                        "video/mp4", "video/quicktime", "video/x-m4v", "audio/mp4", "audio/mpeg", "audio/aac", "audio/x-m4a"]
        if playable.contains(mime) { return true }
        return ["m3u8", "mp4", "m4v", "mov", "mp3", "m4a", "aac"].contains(url.pathExtension.lowercased())
    }

    /// The failed player's error is one the other could get past. From mpv: it couldn't open or
    /// decode the file (loading error -13 only without a network, HTTP or TLS cause; mpv's own
    /// messages never switch). From AVPlayer: a format or decoder failure (AVFoundation and
    /// Core Media codes), or the phone proxy being unavailable, since mpv reads more formats and
    /// needs no proxy. URL loading errors (-2...-3100, such as -1102 when the server refuses the
    /// file) are the source's problem and would fail on mpv too.
    static func allowsSwitch(from engine: PhonePlaybackEngineKind, code: Int32,
                             networkIssue: PhonePlaybackNetworkIssue?, engineMessage: String?) -> Bool {
        guard networkIssue == nil else { return false }
        switch engine {
        case .mpv: return engineMessage == nil && [-13, -17, -18].contains(code)
        case .avPlayer: return !(-3100 ... -2).contains(code)
        }
    }

    /// Sound with no picture for this many seconds of playback.
    static let noPictureSeconds = 5.0

    /// Watches for a video that plays with no picture (audio only, or a black surface).
    struct NoPictureWatch: Equatable {
        private var since: Double?

        /// True once playback has run for `noPictureSeconds` without a frame.
        mutating func sample(_ state: PhonePlaybackState, isAudio: Bool, active: Bool) -> Bool {
            guard !isAudio, active, state.loaded, !state.paused, !state.buffering, state.videoWidth == 0 else {
                if state.videoWidth > 0 || !state.loaded { since = nil }
                return false
            }
            guard let since else { self.since = state.position; return false }
            if state.position < since { self.since = state.position; return false }
            return state.position - since >= PhoneEngineFallback.noPictureSeconds
        }

        mutating func reset() { since = nil }
    }
}

/// A video moved to the other player by itself: what to say, and which player Switch back returns to.
struct PhoneEngineNotice: Equatable {
    let message: String
    let back: PhonePlaybackEngineKind
}

struct PhonePlaybackTrack: Equatable, Identifiable {
    let id: Int
    let label: String
    var language: String? = nil
    /// The address an external subtitle was added from; nil for tracks in the file.
    var externalURL: String? = nil
}

/// A subtitle ticked in the cast sheet, added to the player beside the video's own tracks.
/// Normally a local file the phone downloaded (`PhoneSubtitleDownload`).
struct PhoneExternalSubtitle: Equatable {
    let url: URL
    let title: String
    var language: String? = nil

    /// What mpv is given, and reports back as the track's `external-filename`.
    var mpvPath: String { url.isFileURL ? url.path : url.absoluteString }
}

struct PhonePlaybackState: Equatable {
    var position: Double = 0
    var duration: Double = 0
    var paused = true
    var buffering = false
    var loaded = false
    var audioTracks: [PhonePlaybackTrack] = []
    var subtitleTracks: [PhonePlaybackTrack] = []
    var selectedAudio: Int?
    var selectedSubtitle: Int?
    var speed = 1.0
    /// The picture's display size once the first frame is decoded (rotation applied);
    /// zero before that and for audio. Decides landscape vs vertical and hides the poster.
    var videoWidth = 0
    var videoHeight = 0
    /// Subtitle text the engine wants drawn over the video (AVPlayer's sidecar subtitles);
    /// empty when the engine renders subtitles itself.
    var overlayCaption = ""
}

/// Keep only fixed categories from decoder logs. Raw FFmpeg messages can include
/// authenticated URLs and headers and must never enter copied diagnostics.
enum PhonePlaybackNetworkIssue: Equatable {
    case certificate, tls, http(Int), dns, timeout, connection

    static func classify(_ message: String) -> Self? {
        let text = message.lowercased()
        if text.contains("certificate") && ["failed", "not trusted", "untrusted", "unable to", "not verified", "verify error"].contains(where: text.contains) {
            return .certificate
        }
        if text.contains("http error "), let range = text.range(of: "http error "),
           let code = Int(text[range.upperBound...].prefix(3)), (400...599).contains(code) { return .http(code) }
        if text.contains("resolve hostname") || text.contains("name or service not known") || text.contains("nodename nor servname") { return .dns }
        if text.contains("timed out") || text.contains("timeout") { return .timeout }
        if (text.contains("tls") || text.contains("ssl")) && (text.contains("failed") || text.contains("error")) { return .tls }
        if text.contains("connection refused") || text.contains("network is unreachable") || text.contains("connection reset") { return .connection }
        return nil
    }

    var summary: String {
        switch self {
        case .certificate: return "TLS certificate verification failed"
        case .tls: return "TLS handshake failed"
        case .http(let code): return "HTTP \(code)"
        case .dns: return "Hostname resolution failed"
        case .timeout: return "Network request timed out"
        case .connection: return "Network connection failed"
        }
    }
}

/// The website queue and progress tracker consume the session, never a specific
/// decoder. Injectable here so mpv lifecycle and multi-format queues can be tested.
@MainActor protocol PhoneAlternativePlaybackEngine: AnyObject {
    var failureContext: String { get }
    var networkIssue: PhonePlaybackNetworkIssue? { get }
    /// A ready-made failure message; nil lets the session describe the numeric code as an mpv error.
    var failureMessage: String? { get }
    var onState: ((PhonePlaybackState) -> Void)? { get set }
    var onEnd: (() -> Void)? { get set }
    var onFailure: ((Int32) -> Void)? { get set }
    func configure(_ options: PhonePlayerOptions)
    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool)
    func play()
    func pause()
    func seek(to seconds: Double)
    func setSpeed(_ speed: Double)
    func selectAudio(_ id: Int?)
    func selectSubtitle(_ id: Int?)
    /// Subtitle files to add whenever a file loads, including after a retry.
    func addExternalSubtitles(_ subtitles: [PhoneExternalSubtitle])
    /// The source's MIME type, for an engine that registers the stream with the loopback proxy itself.
    func setSourceContentType(_ contentType: String?)
    /// A tiny copy of the current frame for ambient mode, or nil when there is none.
    func ambientFrame(_ completion: @escaping @MainActor (CGImage?) -> Void)
    func close()
}

extension PhoneAlternativePlaybackEngine {
    var failureContext: String { "playback" }
    var networkIssue: PhonePlaybackNetworkIssue? { nil }
    var failureMessage: String? { nil }
    func setSourceContentType(_ contentType: String?) {}
    func setSpeed(_ speed: Double) {}
    func addExternalSubtitles(_ subtitles: [PhoneExternalSubtitle]) {}
    func ambientFrame(_ completion: @escaping @MainActor (CGImage?) -> Void) { completion(nil) }
}

/// Filters captured browser transport artifacts out of `mpv`'s `http-header-fields`
/// (especially `Range: bytes=0-`, which would otherwise pin seeks back to byte 0) while
/// rejecting control characters and routing `User-Agent` separately.
enum PhoneMPVHTTPHeaders {
    private static let excludedNames: Set<String> = [
        "user-agent",
        "range",
        "if-range",
        "host",
        "connection",
        "accept-encoding",
        "content-length",
        "transfer-encoding",
        "te",
        "upgrade-insecure-requests",
        "priority",
        "pragma",
    ]

    private static func hasControlCharacter(_ text: String) -> Bool {
        text.unicodeScalars.contains { $0 == "\r" || $0 == "\n" || $0 == "\0" }
    }

    static func filteredEntries(from headers: [String: String]) -> [String]? {
        var result: [String] = []
        for (name, value) in headers.sorted(by: { $0.key < $1.key }) {
            let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedName.isEmpty else { continue }
            guard !hasControlCharacter(name), !hasControlCharacter(value) else {
                return nil
            }
            let lower = trimmedName.lowercased()
            if excludedNames.contains(lower) || lower.hasPrefix("sec-") {
                continue
            }
            result.append("\(trimmedName): \(value)")
        }
        return result
    }

    static func userAgent(from headers: [String: String], default defaultUA: String = "PlayBridge") -> String {
        let candidate = headers.first { $0.key.lowercased() == "user-agent" }?.value ?? defaultUA
        if hasControlCharacter(candidate) || candidate.isEmpty {
            return defaultUA
        }
        return candidate
    }
}

/// Tracks an in-flight mpv seek until playback restarts or times out so stale
/// pre-seek `time-pos` readings and paused cache states do not cause re-seek loops.
struct PhoneMPVSeekState: Equatable {
    static let unseekableDropSeconds: TimeInterval = 0.75
    static let maxSeekDurationSeconds: TimeInterval = 15.0

    private(set) var targetSeconds: Double?
    private(set) var mpvSeeking = false
    private var issuedAt: TimeInterval?

    var isSeeking: Bool { targetSeconds != nil || mpvSeeking }

    mutating func beginSeek(to seconds: Double, now: TimeInterval) {
        targetSeconds = max(0, seconds)
        mpvSeeking = false
        issuedAt = now
    }

    mutating func markMPVSeekingEvent(now: TimeInterval? = nil) {
        mpvSeeking = true
        if issuedAt == nil, let now {
            issuedAt = now
        }
    }

    mutating func finishSeek() {
        targetSeconds = nil
        mpvSeeking = false
        issuedAt = nil
    }

    mutating func reset() {
        finishSeek()
    }

    mutating func sample(
        mpvSeeking: Bool,
        timePos: Double?,
        paused: Bool,
        pausedForCache: Bool,
        now: TimeInterval
    ) -> (position: Double?, buffering: Bool) {
        self.mpvSeeking = mpvSeeking
        if mpvSeeking && issuedAt == nil {
            issuedAt = now
        }
        if let issuedAt, now - issuedAt >= Self.maxSeekDurationSeconds {
            finishSeek()
        } else if targetSeconds != nil, !mpvSeeking, let issuedAt, now - issuedAt >= Self.unseekableDropSeconds {
            targetSeconds = nil
            self.issuedAt = nil
        }
        let active = targetSeconds != nil || self.mpvSeeking
        let resolvedPosition = targetSeconds ?? timePos
        let resolvedBuffering = active || (!paused && pausedForCache)
        return (resolvedPosition, resolvedBuffering)
    }
}
