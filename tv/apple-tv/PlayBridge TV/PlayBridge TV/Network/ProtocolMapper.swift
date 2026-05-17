import Foundation

/// Convenience accessors over the Wire-generated proto types so the rest of the app
/// doesn't have to parse strings or unwrap optionals at every consumer.
extension Playbridge_PlayPayload {
    /// Parsed `URL` from the proto's string `url` field. nil when malformed.
    var validURL: URL? { URL(string: url) }

    /// Returns nil for empty headers, matching the legacy `[String: String]?` ergonomic.
    var headersOrNil: [String: String]? { headers.isEmpty ? nil : headers }

    /// Returns nil for empty subtitles list.
    var subtitlesOrNil: [String]? { subtitles.isEmpty ? nil : subtitles }

    /// Returns nil when the proto's optional title is unset/empty.
    var titleOrNil: String? { hasTitle ? title : nil }
}
