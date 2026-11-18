import Foundation

/// The phone player's decoder. mpv is the default for every session; AVPlayer is a
/// per-session choice for streams mpv handles badly (the player's More sheet or the failure screen).
enum PhonePlaybackEngineKind: String, CaseIterable, Identifiable {
    case mpv = "mpv"
    case avPlayer = "avplayer"

    var id: String { rawValue }
    var label: String { self == .mpv ? "mpv" : "AVPlayer" }
    var other: Self { self == .mpv ? .avPlayer : .mpv }
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
    func close()
}

extension PhoneAlternativePlaybackEngine {
    var failureContext: String { "playback" }
    var networkIssue: PhonePlaybackNetworkIssue? { nil }
    var failureMessage: String? { nil }
    func setSourceContentType(_ contentType: String?) {}
    func setSpeed(_ speed: Double) {}
    func addExternalSubtitles(_ subtitles: [PhoneExternalSubtitle]) {}
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
