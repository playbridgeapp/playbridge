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
}

/// One audio/subtitle track the TV reports (the `tracks` message).
struct MediaTrack: Identifiable, Equatable {
    let id: String
    let name: String
    let selected: Bool
}

/// One entry in the TV's playlist (the `playlist_status` message).
struct PlaylistEpisode: Identifiable, Equatable {
    var id: Int { index }
    let index: Int
    let title: String
    var season: Int? = nil
    var episode: Int? = nil
    var imdbId: String? = nil
    var bingeGroup: String? = nil
}

struct PlaylistUiState: Equatable {
    var currentIndex: Int
    var totalCount: Int
    var items: [PlaylistEpisode]
}
