import Foundation

func debugLogNetworkRequest(
    _ source: String,
    url: URL,
    method: String = "GET",
    headers: [String: String]? = nil
) {
#if DEBUG
    print("[DebugNetwork][\(source)] request: \(method) \(url.absoluteString)")
    let fields = headers ?? [:]
    print("[DebugNetwork][\(source)] request headers (\(fields.count)):")
    for (name, value) in fields.sorted(by: { $0.key.lowercased() < $1.key.lowercased() }) {
        print("[DebugNetwork][\(source)]   \(name): \(value)")
    }
#endif
}

func debugLogNetworkResponse(
    _ source: String,
    url: URL?,
    statusCode: Int,
    headers: [AnyHashable: Any]
) {
#if DEBUG
    print("[DebugNetwork][\(source)] response: HTTP \(statusCode) \(url?.absoluteString ?? "?")")
    print("[DebugNetwork][\(source)] response headers (\(headers.count)):")
    let fields = headers.map { (String(describing: $0.key), String(describing: $0.value)) }
        .sorted { $0.0.lowercased() < $1.0.lowercased() }
    for (name, value) in fields {
        print("[DebugNetwork][\(source)]   \(name): \(value)")
    }
#endif
}

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
