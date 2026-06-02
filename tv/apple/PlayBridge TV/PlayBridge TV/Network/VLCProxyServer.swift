import Foundation
import Network

// MARK: - VLC Local Proxy (for custom HTTP headers)

/// Manages upstream URLSession tasks and routes data to NWConnections.
/// Cancels upstream tasks immediately when VLC closes its connection (e.g. after
/// reading an MP4 range or HLS segment), preventing "Socket is not connected" spam.
class ProxySessionManager: NSObject, URLSessionDataDelegate {
    static let shared = ProxySessionManager()

    private final class ConnState {
        let connection: NWConnection
        let task: URLSessionDataTask
        let sendGate: DispatchSemaphore       // backpressure: only 1 in-flight send at a time
        weak var proxyServer: VLCProxyServer? // needed to rewrite HLS URIs into proxy URLs
        var hlsBuffer: Data?                  // non-nil = buffer this response for URI rewriting
        var hlsResponse: HTTPURLResponse?
        var hlsWatchdog: DispatchSourceTimer?

        init(connection: NWConnection, task: URLSessionDataTask, proxyServer: VLCProxyServer) {
            self.connection = connection
            self.task = task
            self.sendGate = DispatchSemaphore(value: 1)
            self.proxyServer = proxyServer
        }

        func invalidateHLSWatchdog() {
            hlsWatchdog?.cancel()
            hlsWatchdog = nil
        }
    }

    // Legitimate HLS playlists are KB-sized and complete in well under a second.
    // Anything beyond these limits is either a hung upstream or a non-playlist
    // body misidentified as HLS — in both cases buffering forever explodes
    // memory.
    private static let hlsMaxBufferBytes = 5 * 1024 * 1024
    private static let hlsWatchdogSeconds = 15

    private var states: [Int: ConnState] = [:]
    private let lock = NSLock()

    lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        // Bypass the shared URLCache entirely. VLC reloads HLS playlists in a
        // tight loop and the default cache will balloon to hundreds of MB of
        // playlist + segment bodies otherwise.
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = 300   // 5 min — large files with backpressure need slack
        config.timeoutIntervalForResource = 0
        config.httpMaximumConnectionsPerHost = 8
        let q = OperationQueue()
        q.maxConcurrentOperationCount = OperationQueue.defaultMaxConcurrentOperationCount
        return URLSession(configuration: config, delegate: self, delegateQueue: q)
    }()

    func proxy(request: URLRequest, to connection: NWConnection, proxyServer: VLCProxyServer) {
        let task = session.dataTask(with: request)
        let state = ConnState(connection: connection, task: task, proxyServer: proxyServer)

        lock.lock()
        states[task.taskIdentifier] = state
        lock.unlock()

        print("[Proxy] T\(task.taskIdentifier) upstream request: \(request.httpMethod ?? "GET") \(request.url?.absoluteString ?? "?")")
        if let headers = request.allHTTPHeaderFields, !headers.isEmpty {
            print("[Proxy] T\(task.taskIdentifier) request headers: \(headers)")
        }

        // Watch for VLC closing its end of the connection. Capture only the
        // taskId — capturing `task` strongly would keep the URLSessionDataTask
        // alive for the full lifetime of NWConnection's handler, which the
        // Network framework holds onto past `cancel()`.
        let taskId = task.taskIdentifier
        connection.stateUpdateHandler = { [weak self] connState in
            switch connState {
            case .failed(let err):
                print("[Proxy] T\(taskId) VLC connection failed: \(err)")
                self?.cancelUpstream(for: taskId)
            case .cancelled:
                print("[Proxy] T\(taskId) VLC connection cancelled")
                self?.cancelUpstream(for: taskId)
            default: break
            }
        }

        task.resume()
    }

    // Cancel the upstream task and remove state when VLC disconnects
    private func cancelUpstream(for taskId: Int) {
        lock.lock()
        let state = states.removeValue(forKey: taskId)
        lock.unlock()
        if let state = state {
            state.invalidateHLSWatchdog()
            print("[Proxy] T\(taskId) cancelling upstream (VLC disconnected)")
            state.sendGate.signal()   // unblock any waiting didReceive call
            state.task.cancel()
        }
    }

    /// Drop HLS-buffering mode and switch to pass-through streaming. Used when
    /// the response was misidentified as HLS — we flush whatever we've buffered
    /// as the response body, then let subsequent didReceive callbacks go
    /// through the normal streaming path.
    private func fallbackFromHLSToStreaming(state: ConnState) {
        state.invalidateHLSWatchdog()
        guard let buffer = state.hlsBuffer, let http = state.hlsResponse else { return }
        state.hlsBuffer = nil
        state.hlsResponse = nil

        let taskId = state.task.taskIdentifier
        let header = buildResponseHeader(http)
        var payload = header.data(using: .utf8) ?? Data()
        payload.append(buffer)

        state.sendGate.wait()
        state.connection.send(content: payload, completion: .contentProcessed({ [weak self] error in
            state.sendGate.signal()
            if error != nil { self?.cancelUpstream(for: taskId) }
        }))
    }

    /// Returns nil if there aren't yet enough bytes to decide, true/false once
    /// the prefix is known. RFC 8216 allows an optional UTF-8 BOM before the
    /// mandatory `#EXTM3U` tag.
    private func looksLikeHLSPlaylist(_ data: Data) -> Bool? {
        let bomLen = data.starts(with: [0xEF, 0xBB, 0xBF]) ? 3 : 0
        let needed = bomLen + 7
        guard data.count >= needed else { return nil }
        return data.dropFirst(bomLen).prefix(7).elementsEqual("#EXTM3U".utf8)
    }

    /// Build the HTTP response headers we hand back to VLC.
    ///
    /// URLSession transparently decodes `Content-Encoding` (gzip/deflate/br) and
    /// unwraps `Transfer-Encoding: chunked` before invoking delegate callbacks.
    /// Forwarding either header verbatim makes VLC try to decode the body again
    /// — for chunked responses VLC waits forever for a chunk terminator that
    /// never arrives, which manifests as HLS playlists "getting stuck".
    /// `Content-Length` is also dropped when the original body was encoded
    /// (decoded size differs) or when we rewrote the body ourselves.
    private func buildResponseHeader(_ http: HTTPURLResponse, overrideContentLength: Int? = nil) -> String {
        var header = "HTTP/1.1 \(http.statusCode) \(HTTPURLResponse.localizedString(forStatusCode: http.statusCode))\r\n"

        let hasContentEncoding = http.value(forHTTPHeaderField: "Content-Encoding") != nil

        for (key, value) in http.allHeaderFields {
            guard let k = key as? String else { continue }
            switch k.lowercased() {
            case "content-encoding", "transfer-encoding", "connection":
                continue
            case "content-length":
                if hasContentEncoding || overrideContentLength != nil { continue }
                header += "\(k): \(value)\r\n"
            default:
                header += "\(k): \(value)\r\n"
            }
        }

        if let len = overrideContentLength {
            header += "Content-Length: \(len)\r\n"
        }
        header += "Connection: close\r\n\r\n"
        return header
    }

    // MARK: URLSessionDataDelegate

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        let state = states[dataTask.taskIdentifier]
        lock.unlock()

        guard let state = state, let http = response as? HTTPURLResponse else {
            print("[Proxy] T\(dataTask.taskIdentifier) no state or non-HTTP response, cancelling")
            completionHandler(.cancel)
            return
        }

        print("[Proxy] T\(dataTask.taskIdentifier) upstream response: \(http.statusCode) content-length=\(http.value(forHTTPHeaderField: "Content-Length") ?? "unknown") type=\(http.value(forHTTPHeaderField: "Content-Type") ?? "unknown")")

        // HLS playlists: buffer the body so we can rewrite every URI inside to
        // route back through this proxy. Headers + rewritten body are flushed
        // atomically in didCompleteWithError so we can include an accurate
        // Content-Length for the rewritten payload.
        if isHLSResponse(http) {
            state.hlsBuffer = Data()
            state.hlsResponse = http

            // Watchdog: if the upstream hasn't finished within
            // hlsWatchdogSeconds, the response is either hung or isn't really
            // a playlist — abort instead of buffering indefinitely.
            let taskId = dataTask.taskIdentifier
            let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
            timer.schedule(deadline: .now() + .seconds(Self.hlsWatchdogSeconds))
            timer.setEventHandler { [weak self] in
                print("[Proxy] T\(taskId) HLS response timed out after \(Self.hlsWatchdogSeconds)s, aborting")
                self?.cancelUpstream(for: taskId)
            }
            state.hlsWatchdog = timer
            timer.resume()

            completionHandler(.allow)
            return
        }

        let taskId = dataTask.taskIdentifier
        let header = buildResponseHeader(http)

        state.sendGate.wait()

        state.connection.send(content: header.data(using: .utf8), completion: .contentProcessed({ [weak self] error in
            state.sendGate.signal()
            if let error = error {
                print("[Proxy] T\(taskId) send header failed: \(error)")
                self?.cancelUpstream(for: taskId)
            }
        }))

        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let state = states[dataTask.taskIdentifier]
        lock.unlock()
        guard let state = state else { return }

        // HLS path: accumulate; we'll rewrite + flush in didCompleteWithError.
        if state.hlsBuffer != nil {
            state.hlsBuffer?.append(data)

            // Validate the magic bytes once we have enough — RFC 8216 requires
            // every playlist to start with "#EXTM3U" (optionally preceded by a
            // UTF-8 BOM). Misidentified responses (e.g. a binary stream with
            // an .m3u8 path or wrong content-type) get flushed and switched
            // to streaming mode so they don't buffer indefinitely.
            if let buf = state.hlsBuffer, let isHLS = looksLikeHLSPlaylist(buf) {
                if !isHLS {
                    print("[Proxy] T\(dataTask.taskIdentifier) response missing #EXTM3U, falling back to streaming")
                    fallbackFromHLSToStreaming(state: state)
                    return
                }
            }

            // Hard cap: a legitimate playlist never approaches this.
            if let buf = state.hlsBuffer, buf.count > Self.hlsMaxBufferBytes {
                print("[Proxy] T\(dataTask.taskIdentifier) HLS buffer exceeded \(Self.hlsMaxBufferBytes) bytes, aborting")
                cancelUpstream(for: dataTask.taskIdentifier)
                return
            }
            return
        }

        let taskId = dataTask.taskIdentifier

        // Backpressure: block until the previous send completes before sending next chunk.
        // This prevents unbounded memory growth for large files (e.g. 9 GB).
        // URLSession serialises delegate callbacks per-task, so blocking here
        // naturally throttles upstream data delivery.
        state.sendGate.wait()

        state.connection.send(content: data, completion: .contentProcessed({ [weak self] error in
            state.sendGate.signal()
            if error != nil { self?.cancelUpstream(for: taskId) }
        }))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let state = states.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        guard let state = state else { return }
        state.invalidateHLSWatchdog()

        if let error = error as? NSError {
            if error.code != NSURLErrorCancelled {
                print("[Proxy] T\(task.taskIdentifier) upstream completed with error: \(error.localizedDescription)")
            } else {
                print("[Proxy] T\(task.taskIdentifier) upstream cancelled (VLC closed connection)")
            }
        } else {
            print("[Proxy] T\(task.taskIdentifier) upstream completed successfully")
        }

        // HLS flush: rewrite URIs in the buffered playlist, then send headers +
        // body atomically with a corrected Content-Length.
        if let buffer = state.hlsBuffer, let response = state.hlsResponse, error == nil {
            let body: Data
            if let baseURL = response.url ?? task.currentRequest?.url ?? task.originalRequest?.url,
               let proxyServer = state.proxyServer {
                body = rewriteHLSPlaylist(buffer, baseURL: baseURL, proxyServer: proxyServer)
            } else {
                body = buffer
            }
            let header = buildResponseHeader(response, overrideContentLength: body.count)
            let payload = (header.data(using: .utf8) ?? Data()) + body

            let preview = String(data: buffer.prefix(200), encoding: .utf8)?
                .replacingOccurrences(of: "\n", with: "\\n") ?? "<non-utf8>"
            print("[Proxy] T\(task.taskIdentifier) HLS upstream size=\(buffer.count) rewritten=\(body.count) preview=\(preview)")

            state.sendGate.wait()
            state.connection.send(content: payload, completion: .contentProcessed({ _ in
                state.sendGate.signal()
                state.connection.send(
                    content: nil, contentContext: .finalMessage, isComplete: true,
                    completion: .contentProcessed({ _ in state.connection.cancel() })
                )
            }))
            return
        }

        // Wait for last data chunk to finish sending before closing
        state.sendGate.wait()

        state.connection.send(
            content: nil, contentContext: .finalMessage, isComplete: true,
            completion: .contentProcessed({ _ in
                state.sendGate.signal()
                state.connection.cancel()
            })
        )
    }
}

// MARK: - HLS detection & playlist URI rewriting

private let hlsContentTypeMarkers: [String] = [
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
    "vnd.apple.mpegurl",
    "x-mpegurl",
]

private func isHLSResponse(_ http: HTTPURLResponse) -> Bool {
    if let ct = http.value(forHTTPHeaderField: "Content-Type")?.lowercased() {
        for marker in hlsContentTypeMarkers where ct.contains(marker) { return true }
    }
    if let url = http.url, url.pathExtension.lowercased() == "m3u8" { return true }
    return false
}

private let hlsURIAttributeRegex = try! NSRegularExpression(pattern: #"URI="([^"]*)""#)

/// Rewrite every URI in an HLS playlist (plain URI lines and `URI="..."`
/// attributes on tags like `#EXT-X-KEY`, `#EXT-X-MAP`, `#EXT-X-MEDIA`,
/// `#EXT-X-I-FRAME-STREAM-INF`) to point at the proxy. Without this, segments
/// or sub-playlists hosted on a different CDN bypass the proxy and arrive
/// upstream without the custom headers — usually a 403.
private func rewriteHLSPlaylist(_ data: Data, baseURL: URL, proxyServer: VLCProxyServer) -> Data {
    guard let text = String(data: data, encoding: .utf8) else { return data }
    let lines = text.components(separatedBy: "\n")

    let rewrittenLines: [String] = lines.map { rawLine in
        let trimmed = rawLine.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return rawLine }

        if trimmed.hasPrefix("#") {
            return rewriteURIAttributes(in: rawLine, baseURL: baseURL, proxyServer: proxyServer)
        }

        guard let upstream = URL(string: trimmed, relativeTo: baseURL)?.absoluteURL else {
            return rawLine
        }
        return proxyServer.proxyURL(forUpstream: upstream).absoluteString
    }

    return rewrittenLines.joined(separator: "\n").data(using: .utf8) ?? data
}

private func rewriteURIAttributes(in line: String, baseURL: URL, proxyServer: VLCProxyServer) -> String {
    let nsLine = line as NSString
    let matches = hlsURIAttributeRegex.matches(in: line, range: NSRange(location: 0, length: nsLine.length))
    if matches.isEmpty { return line }

    var result = line
    // Replace right-to-left so earlier ranges remain valid
    for m in matches.reversed() {
        let captureRange = m.range(at: 1)
        guard captureRange.location != NSNotFound else { continue }
        let original = nsLine.substring(with: captureRange)
        guard let upstream = URL(string: original, relativeTo: baseURL)?.absoluteURL else { continue }
        let proxied = proxyServer.proxyURL(forUpstream: upstream).absoluteString
        if let r = Range(captureRange, in: result) {
            result.replaceSubrange(r, with: proxied)
        }
    }
    return result
}

// MARK: - base64url helpers

private func base64URLEncode(_ string: String) -> String {
    return Data(string.utf8).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

private func base64URLDecode(_ string: String) -> Data? {
    var b64 = string
        .replacingOccurrences(of: "-", with: "+")
        .replacingOccurrences(of: "_", with: "/")
    while b64.count % 4 != 0 { b64.append("=") }
    return Data(base64Encoded: b64)
}

/// Lightweight local HTTP proxy that intercepts VLC connections and
/// re-issues them upstream with custom headers via URLSession.
class VLCProxyServer {
    /// Path used for sub-resources whose original URL doesn't share targetURL's
    /// host (e.g. an HLS playlist on origin pointing at segments on a CDN).
    /// The original URL is base64url-encoded into the `_pb_u` query parameter.
    static let extPath = "/_pb_ext"
    static let extQueryKey = "_pb_u"

    private let targetURL: URL
    private let headers: [String: String]
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    private let listenerQueue = DispatchQueue(label: "vlc-proxy-listener", qos: .userInitiated)

    var localURL: URL {
        var c = URLComponents()
        c.scheme = "http"
        c.host = "127.0.0.1"
        c.port = Int(port)
        c.path = targetURL.path.isEmpty ? "/" : targetURL.path
        // Use percentEncodedQuery to avoid double-encoding (e.g. %3D -> %253D)
        c.percentEncodedQuery = targetURL.query
        return c.url!
    }

    init(targetURL: URL, headers: [String: String]) {
        self.targetURL = targetURL
        self.headers = headers
    }

    /// Build a proxy URL that, when fetched by VLC, will route to the given
    /// upstream URL with the proxy's custom headers attached. Used when
    /// rewriting HLS playlists so cross-host segments still get our headers.
    func proxyURL(forUpstream url: URL) -> URL {
        let encoded = base64URLEncode(url.absoluteString)
        var c = URLComponents()
        c.scheme = "http"
        c.host = "127.0.0.1"
        c.port = Int(port)
        c.path = Self.extPath
        c.queryItems = [URLQueryItem(name: Self.extQueryKey, value: encoded)]
        return c.url!
    }

    func start() {
        guard let newListener = try? NWListener(using: .tcp, on: .any) else { return }
        self.listener = newListener

        let semaphore = DispatchSemaphore(value: 0)
        newListener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.port = newListener.port?.rawValue ?? 0
                semaphore.signal()
            case .failed:
                semaphore.signal()
            default: break
            }
        }
        newListener.newConnectionHandler = { [weak self] conn in
            let q = DispatchQueue(label: "vlc-proxy-conn", qos: .userInitiated)
            self?.handleConnection(conn, on: q)
        }
        newListener.start(queue: listenerQueue)
        _ = semaphore.wait(timeout: .now() + 3)
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handleConnection(_ connection: NWConnection, on queue: DispatchQueue) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, _, error in
            guard let self = self, error == nil, let data = data,
                  let req = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }

            let lines = req.components(separatedBy: "\r\n")
            guard let requestLine = lines.first else { connection.cancel(); return }

            print("[Proxy] VLC request: \(requestLine)")

            let parts = requestLine.split(separator: " ")
            let method = parts.count > 0 ? String(parts[0]) : "GET"
            let requestURI = parts.count > 1 ? String(parts[1]) : "/"

            // Split request-URI into path and query
            let uriParts = requestURI.split(separator: "?", maxSplits: 1)
            let requestPath = String(uriParts[0])
            let requestQuery: String? = uriParts.count > 1 ? String(uriParts[1]) : nil

            // Decode VLC's percent-encoded path for comparison (targetURL.path is always decoded)
            let decodedRequestPath = requestPath.removingPercentEncoding ?? requestPath
            let targetPath = self.targetURL.path.isEmpty ? "/" : self.targetURL.path

            // Determine the upstream URL
            let upstreamURL: URL
            if decodedRequestPath == Self.extPath {
                // HLS sub-resource on an arbitrary host — full URL is encoded in the query
                guard let q = requestQuery, let decoded = Self.decodeExtURL(fromQuery: q) else {
                    print("[Proxy] Failed to decode ext URL: \(requestURI)")
                    connection.cancel()
                    return
                }
                upstreamURL = decoded
                print("[Proxy] Resolved as ext-encoded sub-request -> \(upstreamURL.absoluteString)")
            } else if decodedRequestPath == targetPath || requestPath == "/" {
                // Initial request — use the original target URL exactly
                upstreamURL = self.targetURL
                print("[Proxy] Resolved as initial request -> \(upstreamURL.absoluteString)")
            } else {
                // Same-host sub-request (path-relative resolution)
                var c = URLComponents()
                c.scheme = self.targetURL.scheme
                c.host = self.targetURL.host
                c.port = self.targetURL.port
                c.path = decodedRequestPath
                c.percentEncodedQuery = requestQuery
                upstreamURL = c.url ?? self.targetURL
                print("[Proxy] Resolved as same-host sub-request -> \(upstreamURL.absoluteString)")
            }

            var urlRequest = URLRequest(url: upstreamURL)
            urlRequest.httpMethod = method

            // Forward relevant headers from VLC
            for line in lines.dropFirst() {
                guard !line.isEmpty, let colon = line.firstIndex(of: ":") else { continue }
                let k = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
                let v = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                switch k.lowercased() {
                case "range", "accept-encoding", "accept":
                    urlRequest.setValue(v, forHTTPHeaderField: k)
                    print("[Proxy] Forwarding VLC header: \(k): \(v)")
                default: break
                }
            }

            for (k, v) in self.headers { urlRequest.setValue(v, forHTTPHeaderField: k) }

            ProxySessionManager.shared.proxy(request: urlRequest, to: connection, proxyServer: self)
        }
    }

    private static func decodeExtURL(fromQuery query: String) -> URL? {
        guard let comps = URLComponents(string: "?\(query)"),
              let value = comps.queryItems?.first(where: { $0.name == Self.extQueryKey })?.value
        else { return nil }
        guard let data = base64URLDecode(value),
              let str = String(data: data, encoding: .utf8),
              let url = URL(string: str)
        else { return nil }
        return url
    }
}
