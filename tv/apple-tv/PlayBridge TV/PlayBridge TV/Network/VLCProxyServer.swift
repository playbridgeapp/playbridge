import Foundation
import Network

// MARK: - VLC Local Proxy (for custom HTTP headers)

/// Manages upstream URLSession tasks and routes data to NWConnections.
/// Cancels upstream tasks immediately when VLC closes its connection (e.g. after
/// reading an MP4 range or HLS segment), preventing "Socket is not connected" spam.
class ProxySessionManager: NSObject, URLSessionDataDelegate {
    static let shared = ProxySessionManager()

    private struct ConnState {
        let connection: NWConnection
        let task: URLSessionDataTask       // upstream task — cancelled when VLC disconnects
        let sendGate: DispatchSemaphore    // backpressure: only 1 in-flight send at a time
    }

    private var states: [Int: ConnState] = [:]
    private let lock = NSLock()

    lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 300   // 5 min — large files with backpressure need slack
        config.timeoutIntervalForResource = 0
        config.httpMaximumConnectionsPerHost = 8
        let q = OperationQueue()
        q.maxConcurrentOperationCount = OperationQueue.defaultMaxConcurrentOperationCount
        return URLSession(configuration: config, delegate: self, delegateQueue: q)
    }()

    func proxy(request: URLRequest, to connection: NWConnection) {
        let task = session.dataTask(with: request)
        let gate = DispatchSemaphore(value: 1)
        let state = ConnState(connection: connection, task: task, sendGate: gate)

        lock.lock()
        states[task.taskIdentifier] = state
        lock.unlock()

        print("[Proxy] T\(task.taskIdentifier) upstream request: \(request.httpMethod ?? "GET") \(request.url?.absoluteString ?? "?")")
        if let headers = request.allHTTPHeaderFields, !headers.isEmpty {
            print("[Proxy] T\(task.taskIdentifier) request headers: \(headers)")
        }

        // Watch for VLC closing its end of the connection
        connection.stateUpdateHandler = { [weak self] connState in
            switch connState {
            case .failed(let err):
                print("[Proxy] T\(task.taskIdentifier) VLC connection failed: \(err)")
                self?.cancelUpstream(for: task.taskIdentifier)
            case .cancelled:
                print("[Proxy] T\(task.taskIdentifier) VLC connection cancelled")
                self?.cancelUpstream(for: task.taskIdentifier)
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
            print("[Proxy] T\(taskId) cancelling upstream (VLC disconnected)")
            state.sendGate.signal()   // unblock any waiting didReceive call
            state.task.cancel()
        }
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

        print("[Proxy] T\(dataTask.taskIdentifier) upstream response: \(http.statusCode) content-length=\(http.allHeaderFields["Content-Length"] ?? "unknown") type=\(http.allHeaderFields["Content-Type"] ?? "unknown")")

        var header = "HTTP/1.1 \(http.statusCode) \(HTTPURLResponse.localizedString(forStatusCode: http.statusCode))\r\n"
        for (key, value) in http.allHeaderFields {
            if let key = key as? String { header += "\(key): \(value)\r\n" }
        }
        header += "\r\n"

        let taskId = dataTask.taskIdentifier

        // Backpressure: wait for any prior send to finish before sending header
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

        if let error = error as? NSError {
            if error.code != NSURLErrorCancelled {
                print("[Proxy] T\(task.taskIdentifier) upstream completed with error: \(error.localizedDescription)")
            } else {
                print("[Proxy] T\(task.taskIdentifier) upstream cancelled (VLC closed connection)")
            }
        } else {
            print("[Proxy] T\(task.taskIdentifier) upstream completed successfully")
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

/// Lightweight local HTTP proxy that intercepts VLC connections and
/// re-issues them upstream with custom headers via URLSession.
class VLCProxyServer {
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

            // Determine the upstream URL
            let upstreamURL: URL
            let targetPath = self.targetURL.path.isEmpty ? "/" : self.targetURL.path

            if decodedRequestPath == targetPath || requestPath == "/" {
                // Initial request — use the original target URL exactly
                upstreamURL = self.targetURL
                print("[Proxy] Resolved as initial request -> \(upstreamURL.absoluteString)")
            } else {
                // Sub-request (HLS segment/sub-playlist)
                // Decode first — .path setter will re-encode correctly
                var c = URLComponents()
                c.scheme = self.targetURL.scheme
                c.host = self.targetURL.host
                c.port = self.targetURL.port
                c.path = decodedRequestPath
                c.percentEncodedQuery = requestQuery
                upstreamURL = c.url ?? self.targetURL
                print("[Proxy] Resolved as sub-request -> \(upstreamURL.absoluteString)")
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

            ProxySessionManager.shared.proxy(request: urlRequest, to: connection)
        }
    }
}
