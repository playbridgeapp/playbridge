import Foundation

/// A registration on one proxied URL. Dropping the grant ends the registration.
nonisolated protocol PageMediaGrant: AnyObject, Sendable {
    var url: URL { get }
}

/// The embedded Rust proxy as the router sees it. The proxy, not the player,
/// resolves DNS and follows redirects under the page-cast network policy.
nonisolated protocol PageMediaProxying: AnyObject, Sendable {
    /// Starts the proxy if needed. Throws when it cannot run.
    func start() async throws
    func register(url: String, headers: [String: String], contentType: String?,
                  allowedPrivateOrigins: [String]) async throws -> any PageMediaGrant
}

nonisolated enum PageMediaError: Error, Equatable {
    /// The proxy library is missing or could not start.
    case unavailable
    /// The proxy or the router refused this media.
    case refused
}

/// The command-result code for a page item that cannot be routed.
nonisolated let pageMediaUnavailableError = "page_media_unavailable"

/// Page-controlled media: the website, not the user, chose the URL.
nonisolated enum PageMediaPolicy {
    static let pageDetectors: Set<String> = ["page_cast", "linked_page"]

    static func isPageControlled(detectedBy: String) -> Bool {
        pageDetectors.contains(detectedBy)
    }

    /// The proxy accepts only these request headers for page media.
    private static let proxyHeaderNames: Set<String> = [
        "authorization", "cookie", "referer", "origin", "user-agent", "accept", "accept-language",
    ]
    /// Subtitles may sit on another host: never carry credentials to it.
    private static let contextHeaderNames: Set<String> = ["origin", "user-agent", "accept-language"]
    static let maximumHeaders = 16

    static func proxyHeaders(_ headers: [String: String]) -> [String: String] {
        filter(headers, allowed: proxyHeaderNames)
    }

    static func subtitleContextHeaders(_ headers: [String: String]) -> [String: String] {
        filter(headers, allowed: contextHeaderNames)
    }

    private static func filter(_ headers: [String: String], allowed: Set<String>) -> [String: String] {
        var result: [String: String] = [:]
        var seen = Set<String>()
        for (name, value) in headers.sorted(by: { $0.key < $1.key }) {
            let lower = name.lowercased()
            guard allowed.contains(lower), seen.insert(lower).inserted,
                  !value.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }),
                  result.count < maximumHeaders else { continue }
            result[name] = value
        }
        return result
    }

    /// A subtitle the viewer adds during playback is their choice, not the page's:
    /// its own origin is approved on top of the item's grants. The proxy still
    /// never reaches loopback or other forbidden addresses.
    static func withUserChosenOrigin(_ origins: [String], subtitle: String) -> [String] {
        guard let url = URL(string: subtitle), let scheme = url.scheme?.lowercased(),
              let host = url.host?.lowercased(), !host.isEmpty else { return origins }
        let authority = host.contains(":") ? "[\(host)]" : host
        let defaultPort = scheme == "https" ? 443 : 80
        let port = url.port.flatMap { $0 == defaultPort ? nil : ":\($0)" } ?? ""
        let origin = "\(scheme)://\(authority)\(port)"
        return origins.contains(origin) ? origins : origins + [origin]
    }

    static func isRemoteHTTP(_ string: String) -> Bool {
        guard let url = URL(string: string), let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme), url.host?.isEmpty == false,
              url.user == nil, url.password == nil else { return false }
        return true
    }
}

/// One subtitle a cast item carries. A plain `subtitles` URL has no headers or
/// label; a sender's `subtitle_resources` entry brings its own request headers
/// and menu label.
nonisolated struct ItemSubtitle: Hashable, Sendable {
    var url: String
    /// nil for a plain URL, which keeps loading as it always has.
    var headers: [String: String]? = nil
    var label: String? = nil
}

/// What the router needs to know about one queue item. The wire payload stays
/// out of this type so the routing decision runs without the protobuf package.
nonisolated struct PageMediaSource: Hashable {
    var detectedBy: String
    var url: String
    var headers: [String: String]
    var contentType: String?
    var subtitles: [String]
    var allowedPrivateOrigins: [String]
    /// The item's `subtitle_resources`, each with its own headers.
    var subtitleResources: [ItemSubtitle] = []

    /// Every subtitle in order: plain URLs first, then resources not already
    /// listed. A resource's headers and label win for a URL sent both ways.
    var allSubtitles: [ItemSubtitle] {
        let resources = Dictionary(subtitleResources.map { ($0.url, $0) }, uniquingKeysWith: { first, _ in first })
        var seen = Set<String>()
        var result: [ItemSubtitle] = []
        for url in subtitles + subtitleResources.map(\.url) where seen.insert(url).inserted {
            result.append(resources[url] ?? ItemSubtitle(url: url))
        }
        return result
    }

    var isPageControlled: Bool { PageMediaPolicy.isPageControlled(detectedBy: detectedBy) }
}

/// What the player is handed for one item.
nonisolated struct PageRoutedMedia: @unchecked Sendable {
    let url: URL
    /// nil for direct media, which keeps its own headers.
    let headers: [String: String]?
    let subtitles: [ItemSubtitle]
    let isProxied: Bool
    /// Registrations that keep the proxied URLs alive.
    fileprivate let grants: [any PageMediaGrant]
}

nonisolated enum PageMediaRouteState {
    /// Not page-controlled: play the original URL.
    case direct
    /// Page-controlled and not routed yet.
    case pending
    case ready(PageRoutedMedia)
    case failed
}

/// What a player may load for one item.
nonisolated struct PageMediaPlayable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [ItemSubtitle]?
}

extension PageMediaRouteState {
    /// The original values for cast-sheet media, the proxy URL once a page item is
    /// routed, and nothing while it is pending or refused: a player is never
    /// handed a page item's original URL.
    nonisolated func playable(url: URL, headers: [String: String]?, subtitles: [ItemSubtitle]?) -> PageMediaPlayable? {
        switch self {
        case .direct: return PageMediaPlayable(url: url, headers: headers, subtitles: subtitles)
        case .ready(let media):
            return PageMediaPlayable(url: media.url, headers: media.headers,
                                     subtitles: media.subtitles.isEmpty ? nil : media.subtitles)
        case .pending, .failed: return nil
        }
    }
}

/// Decides, per item, whether media plays directly (cast sheet) or only through
/// the proxy (page cast, linked page). Never falls back from one to the other.
nonisolated final class PageMediaRouter: @unchecked Sendable {
    private let proxy: PageMediaProxying?
    private let lock = NSLock()
    private var routed: [PageMediaSource: PageRoutedMedia] = [:]
    private var inFlight: [PageMediaSource: Task<PageRoutedMedia, Error>] = [:]
    private var failed = Set<PageMediaSource>()
    private var lateGrants: [PageMediaSource: [any PageMediaGrant]] = [:]

    /// `proxy` is nil when the proxy library is not part of this build.
    init(proxy: PageMediaProxying?) { self.proxy = proxy }

    /// True while any page item holds a registration.
    var hasRegistrations: Bool {
        lock.lock(); defer { lock.unlock() }
        return !routed.isEmpty || !inFlight.isEmpty || !lateGrants.isEmpty
    }

    func state(for source: PageMediaSource) -> PageMediaRouteState {
        guard source.isPageControlled else { return .direct }
        lock.lock(); defer { lock.unlock() }
        if let media = routed[source] { return .ready(media) }
        return failed.contains(source) ? .failed : .pending
    }

    /// Cheap checks a command can make for items that will be routed later
    /// (queue_add, tail of a playlist). Registration happens when they come up.
    func preflight(_ source: PageMediaSource) async throws {
        guard source.isPageControlled else { return }
        guard let proxy else { throw PageMediaError.unavailable }
        guard PageMediaPolicy.isRemoteHTTP(source.url),
              source.allowedPrivateOrigins.count <= 16 else { throw PageMediaError.refused }
        try await proxy.start()
    }

    /// Registers the media and its subtitles for a page item; non-page items
    /// pass through unchanged and never touch the proxy.
    func route(_ source: PageMediaSource) async throws -> PageRoutedMedia {
        guard source.isPageControlled else {
            guard let url = URL(string: source.url) else { throw PageMediaError.refused }
            return PageRoutedMedia(url: url, headers: nil, subtitles: source.allSubtitles,
                                   isProxied: false, grants: [])
        }
        let (cached, task) = join(source)
        if let cached { return cached }
        do {
            let media = try await task!.value
            finish(source, media: media)
            return media
        } catch {
            finish(source, media: nil)
            throw error
        }
    }

    /// The cached route, or the in-flight registration for `source` (creating it).
    private func join(_ source: PageMediaSource) -> (PageRoutedMedia?, Task<PageRoutedMedia, Error>?) {
        lock.withLock {
            if let media = routed[source] { return (media, nil) }
            if let existing = inFlight[source] { return (nil, existing) }
            let created = Task { try await self.register(source) }
            inFlight[source] = created
            return (nil, created)
        }
    }

    private func finish(_ source: PageMediaSource, media: PageRoutedMedia?) {
        lock.withLock {
            inFlight[source] = nil
            if let media {
                routed[source] = media
                failed.remove(source)
            } else {
                failed.insert(source)
            }
        }
    }

    private func register(_ source: PageMediaSource) async throws -> PageRoutedMedia {
        guard let proxy else { throw PageMediaError.unavailable }
        guard PageMediaPolicy.isRemoteHTTP(source.url) else { throw PageMediaError.refused }
        let media = try await proxy.register(
            url: source.url, headers: PageMediaPolicy.proxyHeaders(source.headers),
            contentType: source.contentType, allowedPrivateOrigins: source.allowedPrivateOrigins)
        var grants: [any PageMediaGrant] = [media]
        var subtitles: [ItemSubtitle] = []
        let context = PageMediaPolicy.subtitleContextHeaders(source.headers)
        for subtitle in source.allSubtitles {
            // A plain URL gets only context headers; a resource sends its own.
            // A subtitle the proxy refuses is dropped, never fetched directly.
            let headers = subtitle.headers.map(PageMediaPolicy.proxyHeaders) ?? context
            guard PageMediaPolicy.isRemoteHTTP(subtitle.url),
                  let grant = try? await proxy.register(
                      url: subtitle.url, headers: headers, contentType: nil,
                      allowedPrivateOrigins: source.allowedPrivateOrigins) else { continue }
            grants.append(grant)
            subtitles.append(ItemSubtitle(url: grant.url.absoluteString, label: subtitle.label))
        }
        return PageRoutedMedia(url: media.url, headers: nil, subtitles: subtitles,
                               isProxied: true, grants: grants)
    }

    /// A subtitle the viewer added while a page item plays. The result is the URL
    /// to download; a page item never gets its original subtitle URL back.
    func lateSubtitle(url: String, headers: [String: String],
                      for source: PageMediaSource) async throws -> URL {
        guard source.isPageControlled else {
            guard let direct = URL(string: url) else { throw PageMediaError.refused }
            return direct
        }
        guard let proxy else { throw PageMediaError.unavailable }
        guard PageMediaPolicy.isRemoteHTTP(url) else { throw PageMediaError.refused }
        let grant = try await proxy.register(
            url: url, headers: PageMediaPolicy.proxyHeaders(headers), contentType: nil,
            allowedPrivateOrigins: PageMediaPolicy.withUserChosenOrigin(
                source.allowedPrivateOrigins, subtitle: url))
        lock.withLock { lateGrants[source, default: []].append(grant) }
        return grant.url
    }

    /// Keeps registrations only for items still in the queue.
    func retain(only sources: Set<PageMediaSource>) {
        lock.lock()
        let dropped = routed.filter { !sources.contains($0.key) }.map(\.key)
        for key in dropped { routed[key] = nil }
        for key in inFlight.keys where !sources.contains(key) {
            inFlight[key]?.cancel()
            inFlight[key] = nil
        }
        for key in lateGrants.keys where !sources.contains(key) { lateGrants[key] = nil }
        failed = failed.intersection(sources)
        lock.unlock()
    }

    func releaseAll() { retain(only: []) }
}
