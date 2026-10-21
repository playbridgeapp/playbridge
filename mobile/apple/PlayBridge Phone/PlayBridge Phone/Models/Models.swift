import Foundation

/// A receiver found on the LAN via Bonjour (or entered manually).
/// Mirrors `NsdHelper.DiscoveredDevice` on Android.
struct DiscoveredDevice: Identifiable, Equatable {
    var id: String { "\(ip):\(port)" }
    let ip: String
    let port: Int
    let name: String
    var uuid: String = ""
    /// Port of the receiver's `wss://` listener (from the `wss_port` TXT attr).
    /// `nil` means the receiver only serves plaintext `ws://`.
    var wssPort: Int? = nil
}

/// A receiver we've paired with. Persisted (token + pin in the Keychain, the rest in
/// UserDefaults). Mirrors `TvDevice` on Android.
struct PairedDevice: Codable, Equatable {
    var ip: String
    var port: Int
    var name: String
    var uuid: String = ""
    var wssPort: Int? = nil
    /// Auth token issued by the receiver at pairing. Stored in the Keychain with the rest
    /// of this record.
    var token: String? = nil
    /// SPKI pin (`sha256/<base64>`) captured at pairing; validated on every wss connection.
    var certFingerprint: String? = nil
    /// player_mode / browser_mode ids the receiver reported at the last auth.
    var players: [String] = []
    var browsers: [String] = []
    var mediaKinds: [String]? = nil
    var features: [String]? = nil
    /// Bounded authenticated v1 metadata; refreshed on every session, never used as live authority before auth.
    var receiverCapabilitiesData: Data? = nil
    var lastConnected: Date = Date()
}

/// Connection lifecycle, ported from `WebSocketClient.ConnectionState` (Kotlin).
enum ConnectionState: Equatable {
    case disconnected
    case connecting
    case connected(serverName: String, secure: Bool)
    /// Pairing request sent — waiting for the TV user to tap Allow.
    case waitingForApproval(serverName: String)
    /// SAS handshake: the TV challenged us and is displaying the 6-digit code. The user
    /// types it here. [attemptsLeft]/[lastCodeWrong] drive the inline retry hint.
    case waitingForCodeInput(serverName: String, attemptsLeft: Int, lastCodeWrong: Bool)
    /// Correct code entered; confirmation MAC sent, awaiting the receiver's approval.
    case verifyingCode(serverName: String)
    /// TV user tapped Deny, or the 60s timeout elapsed.
    case pairingDenied(serverName: String)
    case retrying(attempt: Int, maxAttempts: Int, nextRetrySeconds: Int)
    case error(message: String)
    /// Stale token rejected by the TV — wipe token and re-pair.
    case authFailed
    /// Presented TLS cert didn't match the pinned fingerprint — possible MITM.
    case pinMismatch(serverName: String)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}

/// Now-playing snapshot the TV pushes (the `status` message).
struct TvPlaybackStatus: Equatable {
    var state: String          // "playing" | "paused" | …
    var positionMs: Int64
    var durationMs: Int64
    var title: String?
    var playbackId: String? = nil
    var currentItemId: String? = nil
    /// Absolute position (ms) the receiver has buffered up to (`status.buffered`).
    var bufferedMs: Int64? = nil
}

/// A chapter marker from the receiver's `timeline` frame.
struct RemoteChapter: Equatable {
    let startMs: Int64
    var title: String? = nil
}

/// A skip segment (intro, recap, outro, preview); a nil end runs to the end of the media.
struct RemoteSegment: Equatable {
    let type: String
    let startMs: Int64
    let endMs: Int64?
}

/// The receiver's `timeline` frame: chapters and skip segments of one item.
struct TvTimeline: Equatable {
    var playbackId: String? = nil
    var currentItemId: String? = nil
    var chapters: [RemoteChapter] = []
    var segments: [RemoteSegment] = []

    static let maxChapters = 256
    static let maxSegments = 16
    static let maxTitleLength = 200
    static let segmentTypes: Set<String> = ["intro", "recap", "outro", "preview"]

    /// Bounded, sorted parse that drops invalid entries, matching the Android remote.
    static func parse(_ json: [String: Any]) -> TvTimeline {
        func id(_ key: String) -> String? { (json[key] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        func ms(_ value: Any?) -> Int64? {
            guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            let double = number.doubleValue
            guard double.isFinite, double >= 0, double < 9.2e18 else { return nil }
            return Int64(double)
        }
        var chapters: [RemoteChapter] = []
        for entry in (json["chapters"] as? [Any] ?? []).prefix(maxChapters) {
            guard let object = entry as? [String: Any], let start = ms(object["startMs"]) else { continue }
            let title = (object["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            chapters.append(RemoteChapter(startMs: start,
                title: title.flatMap { $0.isEmpty ? nil : String($0.prefix(maxTitleLength)) }))
        }
        var seen = Set<Int64>()
        chapters = chapters.sorted { $0.startMs < $1.startMs }.filter { seen.insert($0.startMs).inserted }
        var segments: [RemoteSegment] = []
        for entry in (json["segments"] as? [Any] ?? []).prefix(maxSegments) {
            guard let object = entry as? [String: Any],
                  let type = object["type"] as? String, segmentTypes.contains(type),
                  let start = ms(object["startMs"]) else { continue }
            let rawEnd = object["endMs"]
            let end: Int64?
            if rawEnd == nil || rawEnd is NSNull { end = nil } else {
                guard let value = ms(rawEnd), value > start else { continue }
                end = value
            }
            segments.append(RemoteSegment(type: type, startMs: start, endMs: end))
        }
        return TvTimeline(playbackId: id("playbackId"), currentItemId: id("currentItemId"),
                          chapters: chapters, segments: segments.sorted { $0.startMs < $1.startMs })
    }

    /// The marks to draw for the queue's current item; a timeline for another item is ignored.
    func marks(forQueueItem queueItemId: String?) -> TvTimeline? {
        currentItemId == nil || queueItemId == nil || currentItemId == queueItemId ? self : nil
    }
}

/// One audio/subtitle track the TV reports (the `tracks` message).
struct MediaTrack: Identifiable, Equatable {
    let id: String
    let name: String
    let selected: Bool
    var type: String? = nil
}

/// One entry in the TV's playlist (the `playlist_status` message).
struct PlaylistEpisode: Identifiable, Equatable {
    var id: String { itemId ?? "legacy-\(index)" }
    let index: Int
    let title: String
    var itemId: String? = nil
    var season: Int? = nil
    var episode: Int? = nil
    var imdbId: String? = nil
    var tmdbId: String? = nil
    var bingeGroup: String? = nil
}

struct PlaylistUiState: Equatable {
    var currentIndex: Int
    var totalCount: Int
    var items: [PlaylistEpisode]
    var playbackId: String? = nil
    var currentItemId: String? = nil
    var queueRevision: UInt64 = 0
}

struct QueueCommandResult: Equatable {
    let requestId: String
    let ok: Bool
    let error: String?
    let playbackId: String?
    let queueRevision: UInt64?
}
