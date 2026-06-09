import Foundation

/// Protocol-level constants, mirroring `protocol/constants/constants.go` and the shared
/// Kotlin `Config` / `NsdConstants`. These are the language-agnostic wire values; keep them
/// in sync with the other PlayBridge clients.
enum ProtocolConstants {
    static let defaultPort = 8765
    /// Mirrors `Config.MAX_RETRIES` (≈5 min at the 5s delay).
    static let maxRetries = 60
    static let retryDelay: TimeInterval = 5.0

    /// Bonjour service type. `NWBrowser` wants the type without the trailing dot that
    /// the Android/Go constant carries (`_playbridge._tcp.`).
    static let bonjourServiceType = "_playbridge._tcp"

    enum TXTKey {
        static let deviceName = "device_name"
        static let uuid = "uuid"
        static let customIP = "custom_ip"
        static let wssPort = "wss_port"
    }
}
