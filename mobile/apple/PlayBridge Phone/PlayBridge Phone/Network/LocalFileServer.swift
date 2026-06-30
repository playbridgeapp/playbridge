import Foundation
import Network

/// Minimal LAN HTTP/1.1 server that serves a single local media file with byte-range
/// support, so a connected receiver (PlayBridge TV / DLNA) can fetch and play files
/// straight from the phone. Mirrors the Android `LocalProxyServer` role for local files.
///
/// The file is streamed from disk in chunks (never loaded fully into memory), so
/// multi-gigabyte videos are fine.
final class LocalFileServer {
    static let shared = LocalFileServer()

    private let queue = DispatchQueue(label: "com.playbridge.localfileserver")
    private var listener: NWListener?
    private var fileURL: URL?
    private var scoped = false
    private var contentType = "application/octet-stream"
    private(set) var port: UInt16 = 0

    /// Start (or restart) serving `url`. Returns the LAN URL the receiver should
    /// fetch, or nil if the server or local IP couldn't be resolved.
    func serve(fileURL url: URL) async -> String? {
        stop()

        scoped = url.startAccessingSecurityScopedResource()
        fileURL = url
        contentType = Self.mimeType(for: url)

        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        guard let listener = try? NWListener(using: params) else { stop(); return nil }
        self.listener = listener
        listener.newConnectionHandler = { [weak self] conn in self?.handle(conn) }

        let ready: Bool = await withCheckedContinuation { cont in
            var resumed = false
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if !resumed { resumed = true; cont.resume(returning: true) }
                case .failed, .cancelled:
                    if !resumed { resumed = true; cont.resume(returning: false) }
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }

        guard ready, let p = listener.port?.rawValue, let ip = Self.lanIPAddress() else {
            stop(); return nil
        }
        port = p
        let ext = url.pathExtension.isEmpty ? "" : ".\(url.pathExtension)"
        return "http://\(ip):\(port)/media\(ext)"
    }

    func stop() {
        listener?.cancel()
        listener = nil
        if scoped, let u = fileURL { u.stopAccessingSecurityScopedResource() }
        scoped = false
        fileURL = nil
        port = 0
    }

    // MARK: - Connection handling

    private func handle(_ conn: NWConnection) {
        conn.start(queue: queue)
        receiveRequest(conn, buffer: Data())
    }

    private func receiveRequest(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self else { conn.cancel(); return }
            var buf = buffer
            if let data { buf.append(data) }
            if let range = buf.range(of: Data("\r\n\r\n".utf8)) {
                let header = String(decoding: buf.subdata(in: buf.startIndex..<range.upperBound), as: UTF8.self)
                self.respond(conn, requestHeader: header)
            } else if error != nil || isComplete {
                conn.cancel()
            } else {
                self.receiveRequest(conn, buffer: buf)
            }
        }
    }

    private func respond(_ conn: NWConnection, requestHeader: String) {
        guard let fileURL,
              let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let fileSize = (attrs[.size] as? NSNumber)?.int64Value else {
            sendNotFound(conn); return
        }

        let lines = requestHeader.components(separatedBy: "\r\n")
        let requestLine = lines.first ?? ""
        let method = requestLine.split(separator: " ").first.map(String.init) ?? "GET"

        // Parse an optional Range header (bytes=start-end).
        var start: Int64 = 0
        var end: Int64 = fileSize - 1
        var isPartial = false
        for line in lines.dropFirst() where line.lowercased().hasPrefix("range:") {
            let spec = line.dropFirst("range:".count).trimmingCharacters(in: .whitespaces)
            guard spec.hasPrefix("bytes=") else { continue }
            let comps = spec.dropFirst("bytes=".count).split(separator: "-", omittingEmptySubsequences: false)
            if let first = comps.first, let s = Int64(first) { start = s; isPartial = true }
            if comps.count >= 2, let e = Int64(comps[1]) { end = e }
        }
        if start < 0 || start >= fileSize { start = 0; isPartial = false }
        if end >= fileSize { end = fileSize - 1 }
        if end < start { end = fileSize - 1 }
        let length = end - start + 1

        var head = ""
        if isPartial {
            head += "HTTP/1.1 206 Partial Content\r\n"
            head += "Content-Range: bytes \(start)-\(end)/\(fileSize)\r\n"
        } else {
            head += "HTTP/1.1 200 OK\r\n"
        }
        head += "Content-Type: \(contentType)\r\n"
        head += "Accept-Ranges: bytes\r\n"
        head += "Content-Length: \(length)\r\n"
        head += "Connection: close\r\n\r\n"

        conn.send(content: Data(head.utf8), completion: .contentProcessed { [weak self] err in
            if err != nil { conn.cancel(); return }
            if method == "HEAD" { conn.cancel(); return }
            self?.streamFile(conn, fileURL: fileURL, offset: start, remaining: length)
        })
    }

    private func streamFile(_ conn: NWConnection, fileURL: URL, offset: Int64, remaining: Int64) {
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { conn.cancel(); return }
        try? handle.seek(toOffset: UInt64(offset))
        let chunkSize = 256 * 1024

        func sendNext(_ left: Int64) {
            guard left > 0 else { try? handle.close(); conn.cancel(); return }
            let toRead = Int(min(Int64(chunkSize), left))
            let data = handle.readData(ofLength: toRead)
            guard !data.isEmpty else { try? handle.close(); conn.cancel(); return }
            conn.send(content: data, completion: .contentProcessed { err in
                if err != nil { try? handle.close(); conn.cancel(); return }
                sendNext(left - Int64(data.count))
            })
        }
        sendNext(remaining)
    }

    private func sendNotFound(_ conn: NWConnection) {
        let resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        conn.send(content: Data(resp.utf8), completion: .contentProcessed { _ in conn.cancel() })
    }

    // MARK: - Helpers

    static func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mkv": return "video/x-matroska"
        case "webm": return "video/webm"
        case "avi": return "video/x-msvideo"
        case "ts": return "video/mp2t"
        case "m3u8": return "application/vnd.apple.mpegurl"
        case "mp3": return "audio/mpeg"
        case "m4a", "aac": return "audio/mp4"
        case "flac": return "audio/flac"
        case "wav": return "audio/wav"
        case "ogg", "oga": return "audio/ogg"
        default: return "application/octet-stream"
        }
    }

    /// Best-effort Wi-Fi (en0/en1) IPv4 address for building a LAN-reachable URL.
    static func lanIPAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = ptr {
            let iface = cur.pointee
            if iface.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: iface.ifa_name)
                if name == "en0" || name == "en1" {
                    var addr = iface.ifa_addr.pointee
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(&addr, socklen_t(iface.ifa_addr.pointee.sa_len),
                                   &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        address = String(cString: host)
                    }
                }
            }
            ptr = iface.ifa_next
        }
        return address
    }
}
