import Foundation

/// Playback status message (TV -> Phone)
public struct StatusMessage: Codable {
    public let type: String
    public let state: String
    public let position: Int64
    public let duration: Int64
    public let title: String?

    public init(state: String, position: Int64, duration: Int64, title: String? = nil) {
        self.type = "status"
        self.state = state
        self.position = position
        self.duration = duration
        self.title = title
    }
}

/// Context response from TV
public struct ContextMessage: Codable {
    public let type: String
    public let active: String  // "player", "browser", or "idle"

    public init(active: String) {
        self.type = "context"
        self.active = active
    }
}

/// Playlist item info for status messages
public struct PlaylistItemInfo: Codable {
    public let index: Int
    public let title: String

    public init(index: Int, title: String) {
        self.index = index
        self.title = title
    }
}

/// Playlist status message (TV -> Phone)
public struct PlaylistStatusMessage: Codable {
    public let type: String
    public let items: [PlaylistItemInfo]
    public let currentIndex: Int
    public let totalCount: Int

    public init(items: [PlaylistItemInfo], currentIndex: Int, totalCount: Int) {
        self.type = "playlist_status"
        self.items = items
        self.currentIndex = currentIndex
        self.totalCount = totalCount
    }
}

/// Authentication message
public struct AuthMessage: Codable {
    public let type: String
    public let token: String?
    public let pin: String?

    public init(token: String? = nil, pin: String? = nil) {
        self.type = "auth"
        self.token = token
        self.pin = pin
    }
}

/// Authentication response
public struct AuthResponse: Codable {
    public let type: String
    public let success: Bool
    public let token: String?

    public init(success: Bool, token: String? = nil) {
        self.type = "auth_response"
        self.success = success
        self.token = token
    }
}

/// Heartbeat messages
public struct PingMessage: Codable {
    public let type: String
    public init() { self.type = "ping" }
}

public struct PongMessage: Codable {
    public let type: String
    public init() { self.type = "pong" }
}
