import Foundation
import Combine
#if canImport(UIKit)
import UIKit
#endif

/// Receives detection messages bridged from `DetectionScript` and maintains the per-tab list of
/// playable streams. Port of the native side of `cast/VideoDetector.kt` (dedup, classification,
/// `mediaHeaders`). One instance per browser tab.
final class VideoDetector: ObservableObject {
    @Published private(set) var videos: [DetectedVideo] = []
    @Published private(set) var subtitlePreviews: [String: SubtitlePreviewState] = [:]
    
    @Published private(set) var qualities: [String: [VideoQuality]] = [:]
    @Published private(set) var thumbnails: [String: UIImage] = [:]
    @Published private(set) var thumbnailStates: [String: StreamThumbnailState] = [:]

    @Published private(set) var manifests: [String: StreamManifestInfo] = [:]
#if DEBUG
    private var playbackDiagnostics: [String: String] = [:]

    func recordPlaybackDiagnostics(_ report: String, for id: String) {
        guard videos.contains(where: { $0.id == id }) else { return }
        playbackDiagnostics[id] = report
    }

    private var debugTraces: [String: [String: StreamDebugTrace]] = [:]

    func debugReport(for video: DetectedVideo) -> String {
        let bundle = Bundle.main
        var lines = ["PlayBridge stream diagnostics", "App: \(bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") ?? "unknown") (\(bundle.object(forInfoDictionaryKey: "CFBundleVersion") ?? "unknown"))",
                     "OS: \(ProcessInfo.processInfo.operatingSystemVersionString)",
                     "Stream: \(StreamDebugTrace.safeURL(video.url))",
                     "Kind: \(video.kind.rawValue); detected by: \(video.detectedBy)",
                     "Content type: \(video.contentType ?? "unknown")",
                     "Lifecycle: \(video.lifecycleIndex); first seen: \(video.timestamp); last seen: \(video.lastSeen)",
                     "Thumbnail: \(thumbnailStates[video.id].map { String(describing: $0) } ?? "not started")",
                     "Manifest: \(manifests[video.id].map { String(describing: $0.validation) } ?? "not checked"); qualities: \(qualities[video.id]?.count ?? 0)"]
        for (name, value) in Self.mediaHeaders(for: video).sorted(by: { $0.key < $1.key }) {
            let lower = name.lowercased()
            let safe = lower == "user-agent" ? value : ["origin", "referer"].contains(lower) ? StreamDebugTrace.safeURL(value) : "[redacted]"
            lines.append("Header \(name): \(safe)")
        }
        for (stage, trace) in (debugTraces[video.id] ?? [:]).sorted(by: { $0.key < $1.key }) {
            lines.append("--- \(stage) ---")
            lines.append(trace.text.isEmpty ? "Queued / running" : trace.text)
        }
        if let playback = playbackDiagnostics[video.id] {
            lines.append("--- playback / AirPlay ---")
            lines.append(playback)
        }
        lines.append("Signed URL parameters and sensitive header values are redacted.")
        return lines.joined(separator: "\n")
    }
#endif
    private var lifecycleIndex = 0
    private let now: () -> Int64
    private var seen = Set<String>()
    private enum Work { case qualities, thumbnail }
    private struct Job {
        let id = UUID()
        let video: DetectedVideo
        let revision: UUID
        let work: Work
    }
    private var pending: [Job] = []
    private var running: [UUID: Task<Void, Never>] = [:]
    private var revisions: [String: UUID] = [:]
    private let loadQualities: (DetectedVideo) async -> StreamManifestInfo
    private let loadThumbnail: (DetectedVideo) async -> UIImage?

    init(
        loadQualities: @escaping (DetectedVideo) async -> StreamManifestInfo = { video in
            let headers = VideoDetector.mediaHeaders(for: video)
            if video.kind == .hls {
                return await HLSParser.inspect(masterURL: video.url, headers: headers)
            }
            let qualities = await DASHParser.variants(mpdURL: video.url, headers: headers)
            return StreamManifestInfo(qualities: qualities, validation: qualities.isEmpty ? .pending : .verified)
        },
        loadThumbnail: @escaping (DetectedVideo) async -> UIImage? = { video in
            await Thumbnailer.thumbnail(url: video.url, headers: VideoDetector.mediaHeaders(for: video), isHLS: video.kind == .hls)
        },
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    ) {
        self.now = now
        self.loadQualities = loadQualities
        self.loadThumbnail = loadThumbnail
    }

    deinit {
        for task in running.values { task.cancel() }
    }

    /// Called by WebKit ingestion on the main thread, independently of any sheet.
    private func enrich(_ video: DetectedVideo) {
        let revision = UUID()
        revisions[video.id] = revision
#if DEBUG
        debugTraces[video.id] = [:]
#endif
        pending.removeAll { $0.video.id == video.id }
        qualities[video.id] = nil
        manifests[video.id] = nil
        thumbnails[video.id] = nil
        thumbnailStates[video.id] = nil
        if video.isSubtitle || video.isAudio || video.isImage {
            subtitlePreviews[video.id] = nil
            return
        }
        if video.kind == .hls || video.kind == .dash {
            pending.append(Job(video: video, revision: revision, work: .qualities))
        }
        thumbnailStates[video.id] = .loading
        pending.append(Job(video: video, revision: revision, work: .thumbnail))
        startPendingWork()
    }

    @MainActor
    func loadSubtitlePreview(for video: DetectedVideo) async {
        guard video.isSubtitle, subtitlePreviews[video.id] == nil else { return }
        let revision = revisions[video.id]
        subtitlePreviews[video.id] = .loading
        let sample = await SubtitlePreview.fetch(url: video.url, headers: Self.mediaHeaders(for: video))
        let language = if let sample {
            await Task.detached(priority: .utility) {
                SubtitleLanguageDetector.detect(sample.languageText)
            }.value
        } else {
            nil as String?
        }
        guard revisions[video.id] == revision else { return }
        if Task.isCancelled {
            subtitlePreviews[video.id] = nil
        } else if let sample {
            subtitlePreviews[video.id] = .ready(preview: sample.preview, language: language)
        } else {
            subtitlePreviews[video.id] = .unavailable
        }
    }

    private func startPendingWork() {
        // Keep speculative network/media work bounded per tab. Qualities and
        // thumbnails are separate jobs, so slow previews do not gate manifests.
        while running.count < 3, !pending.isEmpty {
            let job = pending.removeFirst()
            let qualityLoader = loadQualities
            let thumbnailLoader = loadThumbnail
            let trace = StreamDebugTrace()
#if DEBUG
            debugTraces[job.video.id, default: [:]][String(describing: job.work)] = trace
#endif
            running[job.id] = Task { @MainActor [weak self] in
                guard !Task.isCancelled else { return }
                switch job.work {
                case .qualities:
                    let result = await StreamDebugTrace.$current.withValue(trace) {
                        let result = await qualityLoader(job.video)
                        StreamDebugTrace.record("Manifest result: \(result.validation), role: \(result.hlsRole), qualities: \(result.qualities.count)")
                        return result
                    }
                    if !Task.isCancelled, self?.revisions[job.video.id] == job.revision {
                        self?.qualities[job.video.id] = result.qualities
                        self?.manifests[job.video.id] = result
                    }
                case .thumbnail:
                    let result = await StreamDebugTrace.$current.withValue(trace) {
                        let result = await thumbnailLoader(job.video)
                        StreamDebugTrace.record(result == nil ? "Thumbnail unavailable" : "Thumbnail ready")
                        return result
                    }
                    if !Task.isCancelled, self?.revisions[job.video.id] == job.revision {
                        self?.thumbnails[job.video.id] = result
                        self?.thumbnailStates[job.video.id] = result == nil ? .unavailable : .ready
                    }
                }
                self?.running[job.id] = nil
                self?.startPendingWork()
            }
        }
    }
    
    /// Headers the receiver's player can't use / shouldn't be forwarded (port of the Kotlin
    /// PLAYER_SKIP_HEADERS intent).
    private static let skipHeaders: Set<String> = [
        "host", "connection", "accept-encoding", "content-length",
        "upgrade-insecure-requests", "range",
    ]
    private static let fallbackUA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    
    /// Ingest one `{type:'video', url, contentType, detectedBy, originUrl, ua}` message.
    func ingest(_ body: [String: Any]) {
        guard let url = body["url"] as? String, !url.isEmpty else { return }

        if ContentBlocker.shouldBlock(urlString: url) {
            return
        }

        let contentType = (body["contentType"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let explicitKind = (body["mediaKind"] as? String).flatMap(StreamKind.init(rawValue:))
        let kind = explicitKind ?? DetectedVideo.classify(url: url, contentType: contentType)

        let detectedBy = (body["detectedBy"] as? String) ?? "unknown"
        let originUrl = body["originUrl"] as? String
        let requestHeaders = Self.requestHeaders(
            originUrl: originUrl,
            userAgent: body["ua"] as? String,
            // Subtitle body/disposition detections also come from fetch/XHR.
            // DOM media loads do not carry an Origin.
            includeOrigin: detectedBy.hasPrefix("fetch") || detectedBy.hasPrefix("xhr") ||
                detectedBy == "body_content_subtitle" || detectedBy == "subtitle_disposition"
        )
        if seen.contains(url), let idx = videos.firstIndex(where: { $0.url == url }) {
            var updated = videos[idx]
            updated.lastSeen = now()
            let strongerEvidence = CastStreamRanking.evidenceScore(detectedBy) > CastStreamRanking.evidenceScore(updated.detectedBy)
            if strongerEvidence {
                updated.detectedBy = detectedBy
            }
            let oldKind = updated.kind
            let oldHeaders = updated.headers
            if !requestHeaders.isEmpty && (strongerEvidence || (oldHeaders["Origin"] == nil && requestHeaders["Origin"] != nil)) {
                updated.originUrl = originUrl
                updated.headers = requestHeaders
            }
            if let explicitKind, explicitKind != updated.kind {
                updated.contentType = contentType ?? updated.contentType
                updated.kind = explicitKind
            } else if let ct = contentType, updated.contentType == nil {
                updated.contentType = ct
                updated.kind = DetectedVideo.classify(url: url, contentType: ct)
            }
            videos[idx] = updated
            if oldKind != updated.kind || oldHeaders != updated.headers { enrich(updated) }
            return
        }
        seen.insert(url)
        let video = DetectedVideo(
            url: url,
            contentType: contentType,
            detectedBy: detectedBy,
            originUrl: originUrl,
            headers: requestHeaders,
            kind: kind,
            timestamp: now(),
            lastSeen: now(),
            lifecycleIndex: lifecycleIndex
        )
        videos.append(video)
        let limit = video.isImage || video.isAudio ? 30 : 50
        while videos.count(where: { $0.kind == video.kind }) > limit,
              let oldest = videos.firstIndex(where: { $0.kind == video.kind }) {
            let removed = videos.remove(at: oldest)
            seen.remove(removed.url)
            revisions.removeValue(forKey: removed.id)
            pending.removeAll { $0.video.id == removed.id }
            subtitlePreviews.removeValue(forKey: removed.id)
            qualities.removeValue(forKey: removed.id)
            thumbnails.removeValue(forKey: removed.id)
            thumbnailStates.removeValue(forKey: removed.id)
            manifests.removeValue(forKey: removed.id)
        }
        enrich(video)
    }

    /// Approximates the request headers the page itself sent for this stream.
    /// WKWebView has no `webRequest` interception (unlike GeckoView on Android),
    /// so Referer/Origin/User-Agent are reconstructed from the reporting frame.
    /// Without a Referer, hotlink-protected stream hosts reject the TV's request.
    static func requestHeaders(originUrl: String?, userAgent: String? = nil, includeOrigin: Bool = false) -> [String: String] {
        var headers: [String: String] = [:]
        if let originUrl, let o = URL(string: originUrl), let scheme = o.scheme, let host = o.host {
            headers["Referer"] = originUrl
            if includeOrigin {
                headers["Origin"] = "\(scheme)://\(host)" + (o.port.map { ":\($0)" } ?? "")
            }
        }
        if let userAgent, !userAgent.isEmpty {
            headers["User-Agent"] = userAgent
        }
        return headers
    }
    
    func beginMediaLifecycle() {
        lifecycleIndex += 1
        let cutoff = now() - 2_000
        // Match Android's grace period for requests emitted immediately before
        // the SPA updates its address. Existing older rows remain castable.
        for index in videos.indices where videos[index].timestamp >= cutoff {
            videos[index].lifecycleIndex = lifecycleIndex
        }
    }

    func clear() {
#if DEBUG
        debugTraces = [:]
        playbackDiagnostics = [:]
#endif
        lifecycleIndex = 0
        subtitlePreviews = [:]
        manifests = [:]
        // Invalidate before cancelling: uncooperative media callbacks may arrive late,
        // including for the same URL detected again on the new page.
        revisions.removeAll()
        pending.removeAll()
        for task in running.values { task.cancel() }
        running.removeAll()
        qualities = [:]
        thumbnails = [:]
        thumbnailStates = [:]
        seen.removeAll()
        videos = []
    }
    
    /// Build the header map to send with a cast (skip-list filter + UA fallback + Referer),
    /// mirroring `VideoDetector.mediaHeaders`.
    static func mediaHeaders(for video: DetectedVideo) -> [String: String] {
        var result: [String: String] = [:]
        for (k, v) in video.headers where !skipHeaders.contains(k.lowercased()) {
            result[k] = v
        }
        if !result.keys.contains(where: { $0.caseInsensitiveCompare("User-Agent") == .orderedSame }) {
            result["User-Agent"] = fallbackUA
        }
        return result
    }

    /// A subtitle carries its own observed request context, not the video's fallback UA.
    static func subtitleHeaders(for subtitle: DetectedVideo) -> [String: String] {
        var headers = subtitle.headers.filter { !skipHeaders.contains($0.key.lowercased()) }
        if let origin = subtitle.originUrl, !origin.isEmpty,
           !headers.keys.contains(where: { $0.caseInsensitiveCompare("Referer") == .orderedSame }) {
            headers["Referer"] = origin
        }
        return headers
    }
}
