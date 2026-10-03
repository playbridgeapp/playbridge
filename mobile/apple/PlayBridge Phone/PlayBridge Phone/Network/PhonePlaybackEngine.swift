import Foundation

enum PhonePlaybackEngineKind: String {
    case mpv = "mpv"
}

struct PhonePlaybackTrack: Equatable, Identifiable {
    let id: Int
    let label: String
    var language: String? = nil
}

struct PhonePlaybackState {
    var position: Double = 0
    var duration: Double = 0
    var paused = true
    var buffering = false
    var audioTracks: [PhonePlaybackTrack] = []
    var subtitleTracks: [PhonePlaybackTrack] = []
    var selectedAudio: Int?
    var selectedSubtitle: Int?
    var speed = 1.0
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
    var onState: ((PhonePlaybackState) -> Void)? { get set }
    var onEnd: (() -> Void)? { get set }
    var onFailure: ((Int32) -> Void)? { get set }
    func configure(_ options: PhonePlayerOptions)
    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool)
    func play()
    func pause()
    func seek(to seconds: Double)
    func selectAudio(_ id: Int?)
    func selectSubtitle(_ id: Int?)
    func close()
}

extension PhoneAlternativePlaybackEngine {
    var failureContext: String { "playback" }
    var networkIssue: PhonePlaybackNetworkIssue? { nil }
}
