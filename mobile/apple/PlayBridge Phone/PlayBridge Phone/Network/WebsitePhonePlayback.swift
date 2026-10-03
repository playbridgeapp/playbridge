import AVFoundation
import Foundation

/// The native player keeps playing when the website unlinks. Only the website's
/// authority and callbacks are detached; closing the player saves its final state.
@MainActor final class WebsitePhonePlayback {
    let session: PlaybackSession
    private(set) var items: [[String: Any]]
    private(set) var index: Int
    private var event: ((String, [String: Any]) -> Void)?
    private var tickTimer: Timer?
    private var advanceTask: Task<Void, Never>?
    private var subtitleTask: Task<Void, Never>?
    private var closed = false
    private var ended = false
    private var endOfList = false
    private var changingItem = false
    private var finalState: [String: Any]?
    private var cues: [(start: Double, end: Double, text: String)] = []
    private let route: StreamRoute
    private let configuration: RemoteProxyConfiguration

    private init(request: PageCastRequest, media: RoutedStream, route: StreamRoute,
                 configuration: RemoteProxyConfiguration, alternativeFactory: (() -> PhoneAlternativePlaybackEngine?)?,
                 event: @escaping (String, [String: Any]) -> Void) {
        items = request.items; index = request.startIndex; self.event = event
        self.route = route; self.configuration = configuration
        let item = request.items[request.startIndex]
        session = PlaybackSession(media: media, route: route, contentType: item["contentType"] as? String, alternativeFactory: alternativeFactory, prepare: {
            try await Self.prepare(item, route: route, configuration: configuration)
        })
        session.onWebsiteClose = { [weak self] in self?.close() }
        session.onWebsiteSubtitleSelection = { [weak self] in self?.selectSubtitle($0) }
        session.onSubtitleTimingChange = { [weak self] in self?.tick() }
        session.onWebsiteJump = { [weak self] in self?.navigate(to: $0) }
        updateTracks()
        updateQueue()
        tickTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        session.onPlaybackEnd = { [weak self] in
            guard let self, !self.closed else { return }
            self.ended = true
            self.updateQueue()
            self.event?("statechange", self.snapshot())
            self.advanceIfAvailable()
        }
    }

    static func start(_ request: PageCastRequest, alternativeFactory: (() -> PhoneAlternativePlaybackEngine?)? = nil,
                      event: @escaping (String, [String: Any]) -> Void) async throws -> WebsitePhonePlayback {
        let route = StreamRoute(rawValue: UserDefaults.standard.string(forKey: "stream_route_default") ?? "direct") ?? .direct
        let configuration = StreamProxySettingsStore.load()
        let item = request.items[request.startIndex]
        let media = try await prepare(item, route: route, configuration: configuration)
        try Task.checkCancellation()
        let playback = WebsitePhonePlayback(request: request, media: media, route: route, configuration: configuration,
                                            alternativeFactory: alternativeFactory, event: event)
        await playback.session.replaceWebsiteMedia(media, title: item["title"] as? String ?? "", resumeMs: item["start_position_ms"] as? Int ?? 0, contentType: item["contentType"] as? String, autoplay: false)
        do { try Task.checkCancellation() }
        catch { playback.session.close(); throw error }
        return playback
    }

    private static func prepare(_ item: [String: Any], route: StreamRoute, configuration: RemoteProxyConfiguration) async throws -> RoutedStream {
        guard (item["mediaKind"] as? String ?? "video") != "image" else { throw PageCastError(code: "unsupported_target") }
        return try await StreamRouteService.localPlayback.prepare(url: item["url"] as! String,
            headers: item["headers"] as? [String: String] ?? [:], contentType: item["contentType"] as? String,
            route: route, configuration: configuration)
    }

    func snapshot() -> [String: Any] {
        if let finalState { return finalState }
        let position = session.positionSeconds
        let duration = session.durationSeconds
        return ["currentIndex": index, "totalCount": items.count, "closed": closed, "finished": ended && endOfList && index == items.count - 1,
                "title": items[index]["title"] as? String ?? "",
                "state": ended ? "ended" : (session.isPlaying ? "playing" : "paused"),
                "positionMs": position.isFinite ? Int64(max(0, min(position, Double(Int64.max / 2000))) * 1000) : 0,
                "durationMs": duration.isFinite ? Int64(max(0, min(duration, Double(Int64.max / 2000))) * 1000) : 0]
    }
    private func tick() {
        guard !closed, !changingItem else { return }
        let caption = WebsiteCaptionParser.caption(cues, position: session.positionSeconds, delay: session.subtitleDelay)
        if caption != session.websiteCaption { session.websiteCaption = caption }
    }
    func append(_ supplied: [[String: Any]], endOfList: Bool) {
        guard !closed else { return }
        items += supplied; self.endOfList = endOfList
        updateQueue(); advanceIfAvailable()
    }
    private func updateQueue() {
        session.websiteQueueTitles = items.enumerated().map { $0.element["title"] as? String ?? "Episode \($0.offset + 1)" }
        session.websiteQueueIndex = index
        session.websiteQueueChangingItem = changingItem
        session.websiteWaitingForNext = !closed && ended && !endOfList && index + 1 >= items.count
    }
    func jump(_ selected: Int) async throws {
        guard !closed, items.indices.contains(selected), !changingItem else { throw PageCastError(code: "stale_request") }
        changingItem = true; session.websiteQueueError = nil; updateQueue()
        defer { changingItem = false; updateQueue() }
        let item = items[selected]
        let media = try await Self.prepare(item, route: route, configuration: configuration)
        try Task.checkCancellation()
        guard !closed else { throw PageCastError(code: "session_ended") }
        // Native next/previous bypass the page jump operation. Flush the old
        // identity and position before switching, just as EOF does.
        if !ended { event?("statechange", snapshot()) }
        guard !closed else { throw PageCastError(code: "session_ended") }
        index = selected; ended = false; updateTracks(); updateQueue()
        let route = route; let configuration = configuration
        await session.replaceWebsiteMedia(media, title: item["title"] as? String ?? "", resumeMs: item["start_position_ms"] as? Int ?? 0,
            contentType: item["contentType"] as? String,
            prepare: { try await Self.prepare(item, route: route, configuration: configuration) })
    }
    private func advanceIfAvailable() {
        guard ended, index + 1 < items.count else { return }
        navigate(to: index + 1)
    }
    private func navigate(to selected: Int) {
        guard !closed, !changingItem, advanceTask == nil, items.indices.contains(selected), selected != index else { return }
        advanceTask = Task { [weak self] in
            guard let self else { return }
            defer { advanceTask = nil }
            do { try await jump(selected) }
            catch {
                if !Task.isCancelled && !closed {
                    session.websiteQueueError = "Couldn’t load this episode. Choose it again in the queue to retry."
                }
            }
        }
    }
    func detach() {
        event = nil
        // Delivered items remain navigable, but there is no page authority to resolve more.
        endOfList = true; updateQueue()
    }
    private func close() {
        guard !closed else { return }
        var state = snapshot(); state["closed"] = true
        if !ended { state["state"] = "stopped" }
        finalState = state; closed = true
        updateQueue(); session.onWebsiteJump = nil
        event?("statechange", state)
        advanceTask?.cancel(); subtitleTask?.cancel()
        tickTimer?.invalidate(); tickTimer = nil
    }
    private func updateTracks() {
        subtitleTask?.cancel(); cues = []; session.websiteCaption = ""; session.websiteSubtitleIndex = nil
        let item = items[index]
        let resources = item["subtitleResources"] as? [[String: Any]] ?? []
        var urls = item["subtitles"] as? [String] ?? []
        for resource in resources { if let url = resource["url"] as? String, !urls.contains(url) { urls.append(url) } }
        session.websiteSubtitleLanguages = urls.map { url in resources.first { $0["url"] as? String == url }?["language"] as? String }
        session.websiteSubtitleTracks = urls.enumerated().map { offset, url in
            let resource = resources.first { $0["url"] as? String == url }
            return (url, resource?["label"] as? String ?? resource?["language"] as? String ?? "Subtitle \(offset + 1)")
        }
        session.applyPreferredTracks()
    }
    private func selectSubtitle(_ selected: Int?) {
        if selected != nil { session.alternativeEngine?.selectSubtitle(nil) }
        subtitleTask?.cancel(); cues = []; session.websiteCaption = ""; session.websiteSubtitleError = nil
        session.websiteSubtitleIndex = selected
        guard let selected, session.websiteSubtitleTracks.indices.contains(selected) else { return }
        let raw = session.websiteSubtitleTracks[selected].url
        let resource = (items[index]["subtitleResources"] as? [[String: Any]] ?? []).first { $0["url"] as? String == raw }
        let headers = resource?["headers"] as? [String: String] ?? [:]
        subtitleTask = Task { [weak self] in
            guard let self else { return }
            let client = URLSession(configuration: .ephemeral, delegate: WebsiteCaptionRedirectPolicy(), delegateQueue: nil)
            defer { client.invalidateAndCancel() }
            do {
                var request = URLRequest(url: URL(string: raw)!); request.allHTTPHeaderFields = headers; request.timeoutInterval = 30
                let (bytes, response) = try await client.bytes(for: request)
                guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode) else { throw PageCastError(code: "network_unavailable") }
                var data = Data()
                for try await byte in bytes {
                    guard data.count < 8 * 1024 * 1024 else { throw PageCastError(code: "resource_limit") }
                    data.append(byte)
                }
                try Task.checkCancellation()
                let parsed = WebsiteCaptionParser.parse(String(data: data, encoding: .utf8) ?? "")
                guard !parsed.isEmpty else { throw PageCastError(code: "invalid_request") }
                guard !closed else { return }
                cues = parsed; tick()
            } catch {
                if !Task.isCancelled { session.websiteSubtitleError = "Couldn’t load this subtitle. Choose another track." }
            }
        }
    }
    deinit {
        advanceTask?.cancel(); subtitleTask?.cancel()
        tickTimer?.invalidate()
    }
}

/// Credentialed caption requests never follow an unvalidated redirect.
private final class WebsiteCaptionRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
