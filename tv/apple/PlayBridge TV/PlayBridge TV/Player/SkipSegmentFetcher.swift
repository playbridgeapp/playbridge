import Foundation
import CoreFoundation

nonisolated struct SkipSegment: Equatable, Sendable {
    var type: String
    var startMs: Int
    /// nil means the segment runs to the end of the media.
    var endMs: Int?

    var isValid: Bool {
        ["intro", "recap", "outro", "preview"].contains(type)
            && startMs >= 0 && (endMs.map { $0 > startMs } ?? true)
    }

    var buttonTitle: String {
        switch type {
        case "outro": return "Skip Credits"
        case "recap": return "Skip Recap"
        case "preview": return "Skip Preview"
        default: return "Skip Intro"
        }
    }

    func contains(seconds: Double) -> Bool {
        seconds.isFinite && seconds >= Double(startMs) / 1000
            && (endMs.map { seconds < Double($0) / 1000 } ?? true)
    }

    /// Like Android TV: skipping this segment finishes the item (it is open-ended or
    /// ends within a second of the duration), so the player advances instead of seeking.
    func endsPlayback(duration: Double) -> Bool {
        guard let endMs else { return true }
        return duration.isFinite && duration > 0 && Double(endMs) / 1000 >= duration - 1
    }

    func targetSeconds(duration: Double) -> Double? {
        let knownDuration = duration.isFinite && duration > 0
        if let endMs {
            let end = Double(endMs) / 1000
            return knownDuration ? min(end, duration) : end
        }
        return knownDuration ? duration : nil
    }
}

nonisolated struct SkipSegmentMetadata: Equatable, Sendable {
    var imdbID: String?
    var tmdbID: String?
    var season: Int?
    var episode: Int?
}

nonisolated enum SkipSegmentProvider: String, CaseIterable, Sendable {
    case both, introdb, theintrodb, disabled

    var title: String {
        switch self {
        case .both: return "Both providers"
        case .introdb: return "IntroDB"
        case .theintrodb: return "TheIntroDB"
        case .disabled: return "Off"
        }
    }

    static var preferred: Self {
        Self(rawValue: UserDefaults.standard.string(forKey: "skip_segments_provider") ?? "both") ?? .both
    }
}

nonisolated enum SkipSegmentFetcher {
    static let introDBBase = "https://api.introdb.app"
    static let theIntroDBBase = "https://api.theintrodb.org"

    private static func nonblank(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }

    static func introDBURL(_ metadata: SkipSegmentMetadata) -> URL? {
        guard let imdb = nonblank(metadata.imdbID), let season = metadata.season,
              let episode = metadata.episode else { return nil }
        var url = URLComponents(string: introDBBase + "/segments")!
        url.queryItems = [URLQueryItem(name: "imdb_id", value: imdb),
                          URLQueryItem(name: "season", value: String(season)),
                          URLQueryItem(name: "episode", value: String(episode))]
        return url.url
    }

    /// Like Android, prefer TMDB, reject partial episode coordinates, and preserve
    /// proxy paths/query values while replacing old API versions and identity fields.
    static func theIntroDBURL(_ metadata: SkipSegmentMetadata, baseURL: String = theIntroDBBase) -> URL? {
        guard (metadata.season == nil) == (metadata.episode == nil) else { return nil }
        let id: URLQueryItem
        if let tmdb = nonblank(metadata.tmdbID) { id = URLQueryItem(name: "tmdb_id", value: tmdb) }
        else if let imdb = nonblank(metadata.imdbID) { id = URLQueryItem(name: "imdb_id", value: imdb) }
        else { return nil }
        let base = nonblank(baseURL) ?? theIntroDBBase
        guard var url = URLComponents(string: base), ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else { return nil }
        var path = url.percentEncodedPath
        while path.hasSuffix("/") { path.removeLast() }
        path = path.replacingOccurrences(of: "/v[1-4]$", with: "", options: .regularExpression)
        url.percentEncodedPath = path + "/v4/media"
        url.fragment = nil
        let identityKeys = ["tvdb_id", "tmdb_id", "imdb_id", "season", "episode"]
        url.queryItems = (url.queryItems ?? []).filter { !identityKeys.contains($0.name) } + [id]
        if let season = metadata.season, let episode = metadata.episode {
            url.queryItems?.append(contentsOf: [URLQueryItem(name: "season", value: String(season)),
                                               URLQueryItem(name: "episode", value: String(episode))])
        }
        return url.url
    }

    private static func integer(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            // JSON integer values retain their exact representation.
            if let exact = Int(number.stringValue) { return exact }
            let value = number.doubleValue
            guard value.isFinite, value >= Double(Int.min), value < Double(Int.max) else { return nil }
            return Int(value)
        }
        if let value = value as? String { return Int(value) }
        return nil
    }

    private static func milliseconds(_ object: [String: Any], key: String) -> Int? {
        if let ms = integer(object[key + "_ms"]), ms != -1 { return ms }
        guard let value = object[key + "_sec"], !(value is NSNull) else { return nil }
        let number = (value as? NSNumber)?.doubleValue ?? (value as? String).flatMap(Double.init)
        if let boolean = value as? NSNumber, CFGetTypeID(boolean) == CFBooleanGetTypeID() { return nil }
        guard let seconds = number, seconds.isFinite, seconds >= 0,
              seconds * 1000 < Double(Int.max) else { return nil }
        return Int(seconds * 1000)
    }

    static func parseIntroDB(_ data: Data) -> [SkipSegment] {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        return ["intro", "recap", "outro"].compactMap { type in
            guard let range = json[type] as? [String: Any], let start = milliseconds(range, key: "start"),
                  let end = milliseconds(range, key: "end") else { return nil }
            let segment = SkipSegment(type: type, startMs: start, endMs: end)
            return segment.isValid ? segment : nil
        }
    }

    static func parseTheIntroDB(_ data: Data) -> [SkipSegment] {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        var segments: [SkipSegment] = []
        var seenTypes: Set<String> = []
        for (apiType, type) in [("intro", "intro"), ("recap", "recap"), ("outro", "outro"),
                                ("credits", "outro"), ("preview", "preview")] {
            guard let ranges = json[apiType] as? [Any], seenTypes.insert(type).inserted else { continue }
            for case let range as [String: Any] in ranges {
                guard let startValue = range["start_ms"], let endValue = range["end_ms"] else { continue }
                let start = startValue is NSNull && ["intro", "recap"].contains(type) ? 0 : integer(startValue)
                guard let start else { continue }
                let end = integer(endValue)
                guard endValue is NSNull || end != nil else { continue }
                let segment = SkipSegment(type: type, startMs: start, endMs: end)
                if segment.isValid { segments.append(segment) }
            }
        }
        return segments
    }

    static func merge(_ introDB: [SkipSegment], _ theIntroDB: [SkipSegment]) -> [SkipSegment] {
        let primary = introDB.filter(\.isValid)
        let covered = Set(primary.map(\.type))
        return Array((primary + theIntroDB.filter { $0.isValid && !covered.contains($0.type) })
            .sorted { $0.startMs < $1.startMs }.prefix(TimelineReport.maxSegments))
    }

    /// Runs in the loader's detached task. Failures are silent and independent per
    /// provider; no response, URL, headers, or error descriptions are logged or persisted.
    static func fetch(_ metadata: SkipSegmentMetadata, provider: SkipSegmentProvider,
                      configuration: URLSessionConfiguration = .ephemeral) async -> [SkipSegment] {
        guard provider != .disabled else { return [] }
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        func request(_ url: URL?) async -> Data? {
            guard let url, !Task.isCancelled else { return nil }
            var request = URLRequest(url: url)
            request.setValue("PlayBridgeTV/\(version) (+https://github.com/playbridgeapp/playbridge)", forHTTPHeaderField: "User-Agent")
            guard let (data, response) = try? await session.data(for: request),
                  let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  !Task.isCancelled else { return nil }
            return data
        }
        var primary: [SkipSegment] = []
        var secondary: [SkipSegment] = []
        if provider != .theintrodb, let data = await request(introDBURL(metadata)) { primary = parseIntroDB(data) }
        if provider != .introdb, let data = await request(theIntroDBURL(metadata)) { secondary = parseTheIntroDB(data) }
        return merge(primary, secondary)
    }
}

/// Main-thread state. A fresh request token fences even A → B → A and teardown;
/// cancellation alone cannot stop an already queued completion.
struct SkipSegmentResultState {
    private(set) var token = UUID()
    private(set) var segments: [SkipSegment] = []

    mutating func reset() -> UUID {
        token = UUID()
        segments = []
        return token
    }

    mutating func accept(_ segments: [SkipSegment], token: UUID) -> Bool {
        guard token == self.token else { return false }
        self.segments = SkipSegmentFetcher.merge(segments, [])
        return true
    }
}

/// Owned by one engine, called only on main. One fetch per opened item; repeat
/// playback and UI updates reuse the result. Network and parsing run off main.
@MainActor
final class SkipSegmentLoader {
    private var state = SkipSegmentResultState()
    private var task: Task<Void, Never>?
    var segments: [SkipSegment] { state.segments }

    func cancel() {
        task?.cancel()
        task = nil
        _ = state.reset()
    }

    func start(_ metadata: SkipSegmentMetadata, completion: @escaping @MainActor @Sendable () -> Void) {
        cancel()
        let token = state.token
        let provider = SkipSegmentProvider.preferred
        task = Task.detached(priority: .utility) { [weak self] in
            let segments = await SkipSegmentFetcher.fetch(metadata, provider: provider)
            guard !Task.isCancelled else { return }
            await MainActor.run { [weak self] in
                guard let self, self.state.accept(segments, token: token) else { return }
                completion()
            }
        }
    }

    deinit { task?.cancel() }
}
