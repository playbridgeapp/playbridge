import Foundation
import Network

/// Minimal loopback HTTP/1.1 server for AVPlayer: serves the remuxed HLS of each
/// registered source at `http://127.0.0.1:<port>/<token>/master.m3u8`. The
/// remuxer opens lazily on the first request, so a source AVPlayer can't take
/// fails that request and the AVPlayer item falls back to mpv. Never logs URLs.
final class LocalHLSServer {
    static let shared = LocalHLSServer()

    private final class Session {
        let source: URL
        let headers: [String: String]
        let work: DispatchQueue
        var remuxer: HLSRemuxer?
        var failure: Error?
        /// Segments produced ahead of AVPlayer's requests (on `work` only).
        var ready: [Int: Data] = [:]
        var latestRequested = -1
        var prefetching = false

        /// Read-ahead like mpv's cache: up to `aheadSegments` / `aheadBytes` past the
        /// last request, one segment per job so a new request waits for at most one.
        static let aheadSegments = 6
        static let aheadBytes = 96 * 1_048_576

        func requested(_ index: Int) {
            latestRequested = index
            ready = ready.filter { $0.key > index && $0.key <= index + Self.aheadSegments }
        }

        func schedulePrefetch(_ remuxer: HLSRemuxer) {
            guard !prefetching else { return }
            prefetching = true
            work.async { [weak self] in self?.prefetchNext(remuxer) }
        }

        private func prefetchNext(_ remuxer: HLSRemuxer) {
            prefetching = false
            guard latestRequested >= 0, ready.values.reduce(0, { $0 + $1.count }) < Self.aheadBytes else { return }
            // Empty once the last segment has been requested (lower > upper would trap).
            let first = latestRequested + 1
            let last = min(latestRequested + Self.aheadSegments, remuxer.segments.count - 1)
            guard first <= last, let next = (first...last).first(where: { ready[$0] == nil }),
                  let data = try? remuxer.segment(next) else { return }
            ready[next] = data
            schedulePrefetch(remuxer)
        }

        init(source: URL, headers: [String: String], token: String) {
            self.source = source
            self.headers = headers
            work = DispatchQueue(label: "app.playbridge.tv.remux.\(token.prefix(8))", qos: .userInitiated)
        }

        /// Called on `work` only.
        func open() throws -> HLSRemuxer {
            if let remuxer { return remuxer }
            if let failure { throw failure }
            do {
                let path = source.isFileURL ? source.path : source.absoluteString
                let opened = try HLSRemuxer(url: path, headers: headers)
                #if DEBUG
                print("[Remux] opened: \(opened.summary)")
                print("[Remux] master playlist:\n\(opened.masterPlaylist)")
                let durations = opened.segments.map(\.duration)
                print(String(format: "[Remux] segments: count=%d min=%.2f max=%.2f over10=%d over20=%d", durations.count,
                             durations.min() ?? 0, durations.max() ?? 0, durations.filter { $0 > 10 }.count,
                             durations.filter { $0 > 20 }.count))
                print("[Remux] media head: \(opened.mediaPlaylist.split(separator: "\n").prefix(10).joined(separator: " | "))")
                #endif
                remuxer = opened
                return opened
            } catch {
                #if DEBUG
                print("[Remux] open failed: \(error)")
                #endif
                failure = error
                throw error
            }
        }
    }

    private let queue = DispatchQueue(label: "app.playbridge.tv.hls-server")
    private var listener: NWListener?
    private var port: NWEndpoint.Port?
    private var sessions: [String: Session] = [:]

    /// The local master playlist URL for `source`, or nil if the server can't start.
    func register(source: URL, headers: [String: String]?) -> (url: URL, token: String)? {
        guard let port = startIfNeeded() else { return nil }
        let token = UUID().uuidString.lowercased()
        queue.sync { sessions[token] = Session(source: source, headers: headers ?? [:], token: token) }
        guard let url = URL(string: "http://127.0.0.1:\(port.rawValue)/\(token)/master.m3u8") else { return nil }
        return (url, token)
    }

    func remove(token: String) {
        queue.async { self.sessions[token] = nil }
    }

    private func startIfNeeded() -> NWEndpoint.Port? {
        if let port = queue.sync(execute: { port }) { return port }
        let parameters = NWParameters.tcp
        parameters.acceptLocalOnly = true
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        guard let listener = try? NWListener(using: parameters) else { return nil }
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready, .failed, .cancelled: ready.signal()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 2)
        guard let bound = listener.port, listener.state == .ready else {
            #if DEBUG
            print("[Remux] server failed to start: \(listener.state)")
            #endif
            listener.cancel()
            return nil
        }
        #if DEBUG
        print("[Remux] server listening on 127.0.0.1:\(bound.rawValue)")
        #endif
        listener.stateUpdateHandler = nil
        queue.sync {
            self.listener = listener
            self.port = bound
        }
        return bound
    }

    // MARK: HTTP

    private func accept(_ connection: NWConnection) {
        #if DEBUG
        print("[Remux] accept")
        connection.stateUpdateHandler = { state in print("[Remux] connection \(state)") }
        #endif
        connection.start(queue: queue)
        receiveHead(connection, buffer: Data())
    }

    private func receiveHead(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            var buffer = buffer
            if let data { buffer.append(data) }
            #if DEBUG
            print("[Remux] received \(data?.count ?? 0) bytes complete=\(complete) error=\(String(describing: error))")
            #endif
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
                self.handle(head, on: connection)
            } else if error != nil || complete || buffer.count > 64 * 1024 {
                connection.cancel()
            } else {
                self.receiveHead(connection, buffer: buffer)
            }
        }
    }

    private func handle(_ head: String, on connection: NWConnection) {
        let requestLine = head.split(separator: "\r\n", maxSplits: 1).first.map(String.init) ?? ""
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2, parts[0] == "GET" || parts[0] == "HEAD",
              let route = RemuxRoute.parse(path: String(parts[1])),
              let session = sessions[route.token] else {
            respond(connection, status: "404 Not Found", type: "text/plain", body: Data())
            return
        }
        let headOnly = parts[0] == "HEAD"
        #if DEBUG
        if case .segment = route.resource {} else {
            let variant = parts[1].split(separator: "?").dropFirst().first.map { " ?\($0)" } ?? ""
            print("[Remux] request \(route.resource)\(variant)")
        }
        #endif
        session.work.async { [weak self] in
            guard let self else { return }
            do {
                let remuxer = try session.open()
                let body: Data
                switch route.resource {
                case .master: body = Data(remuxer.masterPlaylist.utf8)
                case .media: body = Data(remuxer.mediaPlaylist.utf8)
                case .initSegment: body = try remuxer.initSegment()
                case .segment(let index):
                    let cached = session.ready.removeValue(forKey: index)
                    session.requested(index)
                    body = try cached ?? remuxer.segment(index)
                    session.schedulePrefetch(remuxer)
                }
                self.queue.async {
                    self.respond(connection, status: "200 OK", type: route.resource.contentType,
                                 body: body, headOnly: headOnly)
                }
            } catch {
                #if DEBUG
                print("[Remux] \(route.resource) failed: \(error)")
                #endif
                self.queue.async {
                    self.respond(connection, status: "500 Internal Server Error", type: "text/plain", body: Data())
                }
            }
        }
    }

    private func respond(_ connection: NWConnection, status: String, type: String, body: Data, headOnly: Bool = false) {
        let header = "HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\n"
            + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        var payload = Data(header.utf8)
        if !headOnly { payload.append(body) }
        connection.send(content: payload, completion: .contentProcessed { _ in connection.cancel() })
    }
}
