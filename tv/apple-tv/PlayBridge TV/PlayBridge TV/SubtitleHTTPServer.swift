import Foundation
import Network

/// Minimal local HTTP server that serves cached subtitle files to VLC's addPlaybackSlave API.
///
/// VLC cannot consume `file://` URLs via `addPlaybackSlave`; it needs an HTTP URL.
/// This server bridges the gap: after a subtitle is cached to `Caches/subtitles/`,
/// VLCPlayerEngine calls `SubtitleHTTPServer.shared.localURL(for:)` to get an
/// `http://127.0.0.1:8766/subtitle/{filename}` URL, then passes it to VLC.
///
/// Start once on app launch (called from PlayBridge_TVApp or WebSocketServer.init).
final class SubtitleHTTPServer {
    static let shared = SubtitleHTTPServer()
    static let port: UInt16 = 8766

    private var listener: NWListener?
    private let serverQueue = DispatchQueue(label: "com.playbridge.SubtitleHTTPServer")

    private let subtitleDir: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("subtitles")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private init() {}

    // MARK: - Lifecycle

    func start() {
        guard listener == nil else { return }

        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(
            host: "127.0.0.1",
            port: NWEndpoint.Port(rawValue: Self.port)!
        )

        do {
            listener = try NWListener(using: params)
            listener?.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("[SubtitleHTTPServer] Listening on 127.0.0.1:\(Self.port)")
                case .failed(let error):
                    print("[SubtitleHTTPServer] Failed: \(error)")
                default:
                    break
                }
            }
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleConnection(connection)
            }
            listener?.start(queue: serverQueue)
        } catch {
            print("[SubtitleHTTPServer] Could not start: \(error)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - URL Helper

    /// Returns the localhost URL for a cached subtitle file (e.g. "sub_0.srt").
    func localURL(for filename: String) -> URL {
        URL(string: "http://127.0.0.1:\(Self.port)/subtitle/\(filename)")!
    }

    // MARK: - Connection Handling

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: serverQueue)
        receive(on: connection)
    }

    private func receive(on connection: NWConnection) {
        // Read up to 8 KB — enough for any HTTP request header
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) {
            [weak self] data, _, isComplete, error in
            guard let self = self else { return }

            if let data = data, !data.isEmpty {
                let request = String(data: data, encoding: .utf8) ?? ""
                self.handleRequest(request, on: connection)
            } else {
                connection.cancel()
            }
        }
    }

    private func handleRequest(_ request: String, on connection: NWConnection) {
        // Parse the first line: "GET /subtitle/{filename} HTTP/1.1"
        let firstLine = request.components(separatedBy: "\r\n").first ?? ""
        let parts = firstLine.components(separatedBy: " ")

        guard parts.count >= 2, parts[0] == "GET" else {
            send(status: 400, body: Data(), contentType: "text/plain", on: connection)
            return
        }

        let path = parts[1]  // e.g. "/subtitle/sub_0.srt"
        let prefix = "/subtitle/"
        guard path.hasPrefix(prefix) else {
            send(status: 404, body: Data(), contentType: "text/plain", on: connection)
            return
        }

        // Percent-decode the filename
        let rawFilename = String(path.dropFirst(prefix.count))
        let filename = rawFilename.removingPercentEncoding ?? rawFilename

        // Guard against directory traversal
        guard !filename.contains("/"), !filename.contains("..") else {
            send(status: 403, body: Data(), contentType: "text/plain", on: connection)
            return
        }

        let fileURL = subtitleDir.appendingPathComponent(filename)

        guard let data = try? Data(contentsOf: fileURL) else {
            send(status: 404, body: Data(), contentType: "text/plain", on: connection)
            return
        }

        let ext = (filename as NSString).pathExtension.lowercased()
        let contentType = ext == "vtt" ? "text/vtt" : "text/plain; charset=utf-8"

        send(status: 200, body: data, contentType: contentType, on: connection)
    }

    // MARK: - Response

    private func send(
        status: Int,
        body: Data,
        contentType: String,
        on connection: NWConnection
    ) {
        let statusText: String
        switch status {
        case 200: statusText = "OK"
        case 400: statusText = "Bad Request"
        case 403: statusText = "Forbidden"
        case 404: statusText = "Not Found"
        default: statusText = "Error"
        }

        let header =
            "HTTP/1.1 \(status) \(statusText)\r\n"
            + "Content-Type: \(contentType)\r\n"
            + "Content-Length: \(body.count)\r\n"
            + "Connection: close\r\n"
            + "\r\n"

        var response = header.data(using: .utf8)!
        response.append(body)

        connection.send(
            content: response,
            contentContext: .defaultMessage,
            isComplete: true,
            completion: .contentProcessed { _ in
                connection.cancel()
            })
    }
}
