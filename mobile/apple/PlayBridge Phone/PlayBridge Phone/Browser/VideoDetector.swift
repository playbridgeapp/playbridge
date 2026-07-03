import Foundation

/// Receives detection messages bridged from `DetectionScript` and maintains the per-tab list of
/// playable streams. Port of the native side of `cast/VideoDetector.kt` (dedup, classification,
/// `mediaHeaders`). One instance per browser tab.
final class VideoDetector: ObservableObject {
    @Published private(set) var videos: [DetectedVideo] = []
    
    private var seen = Set<String>()
    
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
        let kind = DetectedVideo.classify(url: url, contentType: contentType)

        if seen.contains(url) {
            // Upgrade an existing entry's content-type if we learned a better one.
            if let ct = contentType, let idx = videos.firstIndex(where: { $0.url == url }), videos[idx].contentType == nil {
                videos[idx].contentType = ct
                videos[idx].kind = DetectedVideo.classify(url: url, contentType: ct)
            }
            return
        }
        seen.insert(url)
        let detectedBy = (body["detectedBy"] as? String) ?? "unknown"
        let originUrl = body["originUrl"] as? String
        let video = DetectedVideo(
            url: url,
            contentType: contentType,
            detectedBy: detectedBy,
            originUrl: originUrl,
            headers: VideoDetector.requestHeaders(
                originUrl: originUrl,
                userAgent: body["ua"] as? String,
                // The browser only sends Origin on CORS requests (fetch/XHR), not
                // on <video> element loads — mirror that so token-bound CDNs see
                // the same headers the page's own request carried.
                includeOrigin: detectedBy.hasPrefix("fetch") || detectedBy.hasPrefix("xhr")
            ),
            kind: kind
        )
        videos.append(video)
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
    
    func clear() {
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
}
