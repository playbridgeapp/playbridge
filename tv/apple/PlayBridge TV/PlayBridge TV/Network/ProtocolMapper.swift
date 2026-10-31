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
    var musicMetadata: MusicPresentationMetadata {
        let metadata = visualMetadata
        return MusicPresentationMetadata(
            title: titleOrNil, metadataTitle: metadata.title,
            artist: metadata.hasArtist ? metadata.artist : nil, album: metadata.hasAlbum ? metadata.album : nil,
            artworkURL: metadata.hasArtworkURL ? metadata.artworkURL : nil,
            posterURL: metadata.hasPosterURL ? metadata.posterURL : nil,
            backdropURL: metadata.hasBackdropURL ? metadata.backdropURL : nil)
    }

    var resolvedMediaKind: MediaKind {
        MediaKind.resolve(explicit: hasMediaKind ? mediaKind : nil, url: url,
                          contentType: hasContentType ? contentType : nil)
    }

    /// Parsed `URL` from the proto's string `url` field. nil when malformed.
    var validURL: URL? {
        guard let parsed = URL(string: url), let scheme = parsed.scheme, !scheme.isEmpty else { return nil }
        if ["http", "https"].contains(scheme.lowercased()), parsed.host?.isEmpty != false { return nil }
        return parsed
    }

    /// Returns nil for empty headers, matching the legacy `[String: String]?` ergonomic.
    var headersOrNil: [String: String]? { headers.isEmpty ? nil : headers }

    /// The item's plain subtitle URLs and valid subtitle resources, or nil when it has none.
    var itemSubtitlesOrNil: [ItemSubtitle]? {
        let all = pageMediaSource.allSubtitles
        return all.isEmpty ? nil : all
    }

    /// Valid `subtitle_resources` with their own headers and labels.
    var itemSubtitleResources: [ItemSubtitle] {
        subtitleResources.filter(ExternalSubtitleDownload.isValid).map {
            ItemSubtitle(url: $0.url, headers: $0.headers,
                         label: $0.hasLabel && !$0.label.isEmpty ? $0.label : nil)
        }
    }

    /// Returns nil when the proto's optional title is unset/empty.
    var titleOrNil: String? { hasTitle ? title : nil }

    /// What the page-media router needs to route this item.
    var pageMediaSource: PageMediaSource {
        PageMediaSource(detectedBy: detectedBy, url: url, headers: headers,
                        contentType: hasContentType ? contentType : nil,
                        subtitles: subtitles, allowedPrivateOrigins: allowedPrivateOrigins,
                        subtitleResources: itemSubtitleResources)
    }

    /// Whether the website, not the user, chose this item's URL.
    var isPageControlled: Bool { PageMediaPolicy.isPageControlled(detectedBy: detectedBy) }

    /// History replays an item as a plain TV-initiated play, which would skip the
    /// proxy. A page-controlled URL is therefore never saved.
    var recordsHistory: Bool { !skipHistory && !isPageControlled }
}
