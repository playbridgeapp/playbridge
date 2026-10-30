import Foundation

/// Casting restrictions never prevent ordinary browsing or in-page playback.
enum BrowserCastingPolicy {
    static let message = "This site doesn't allow casting."
    static let blockedHosts = [
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com",
        "netflix.com", "nflxvideo.net", "primevideo.com", "disneyplus.com",
        "hulu.com", "max.com", "hbomax.com", "tv.apple.com", "paramountplus.com",
        "peacocktv.com", "spotify.com", "music.apple.com",
    ]

    static func blocks(_ url: URL?) -> Bool {
        guard let host = url?.host?.lowercased() else { return false }
        let canonical = host.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return blockedHosts.contains { canonical == $0 || canonical.hasSuffix("." + $0) }
    }

    static func allows(pageURL: URL?, mediaURL: URL? = nil) -> Bool {
        !blocks(pageURL) && !blocks(mediaURL)
    }

    /// The detector receives the same list as the native casting boundary.
    static var hostsJSON: String {
        String(decoding: try! JSONSerialization.data(withJSONObject: blockedHosts), as: UTF8.self)
    }
}
