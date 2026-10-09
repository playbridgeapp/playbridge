import Foundation
import Network
import UniformTypeIdentifiers

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

        // Parse an optional Range header (bytes=start-end) matching stream-proxy-rust.
        var rangeHeaderValue: String?
        for line in lines.dropFirst() where line.lowercased().hasPrefix("range:") {
            rangeHeaderValue = line.dropFirst("range:".count).trimmingCharacters(in: .whitespaces)
            break
        }

        let start: Int64
        let end: Int64
        let isPartial: Bool

        if let rangeHeaderValue {
            guard let parsed = Self.parseByteRange(rangeHeaderValue, total: fileSize) else {
                sendRangeNotSatisfiable(conn, fileSize: fileSize)
                return
            }
            start = parsed.start
            end = parsed.end
            isPartial = true
        } else {
            start = 0
            end = max(0, fileSize - 1)
            isPartial = false
        }
        let length = fileSize == 0 ? 0 : (end - start + 1)

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
            if method == "HEAD" || length == 0 { conn.cancel(); return }
            self?.streamFile(conn, fileURL: fileURL, offset: start, remaining: length)
        })
    }

    private func sendRangeNotSatisfiable(_ conn: NWConnection, fileSize: Int64) {
        var head = "HTTP/1.1 416 Range Not Satisfiable\r\n"
        head += "Content-Range: bytes */\(fileSize)\r\n"
        head += "Content-Length: 0\r\n"
        head += "Connection: close\r\n\r\n"
        conn.send(content: Data(head.utf8), completion: .contentProcessed { _ in conn.cancel() })
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
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic", "heif": return "image/heic"
        case "bmp": return "image/bmp"
        case "tif", "tiff": return "image/tiff"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "aac": return "audio/aac"
        case "flac": return "audio/flac"
        case "wav": return "audio/wav"
        case "ogg", "oga", "opus": return "audio/ogg"
        default: return UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
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

    /// Match stream-proxy-rust parse_byte_range:
    /// - "bytes=" prefix required
    /// - multi-ranges (containing ',') or total == 0 return nil (-> 416)
    /// - suffix "bytes=-N": last N bytes (max(0, total - N)..<total)
    /// - start-only "bytes=N-": if start >= total -> nil (-> 416), else N..<(total - 1)
    /// - start-end "bytes=S-E": clamped end = min(E, total - 1); if S > end -> nil (-> 416)
    /// - invalid numbers / garbage -> nil (-> 416)
    static func parseByteRange(_ value: String, total: Int64) -> (start: Int64, end: Int64)? {
        guard value.hasPrefix("bytes=") else { return nil }
        let raw = value.dropFirst("bytes=".count)
        if raw.contains(",") || total <= 0 {
            return nil
        }
        guard let dashIndex = raw.firstIndex(of: "-") else {
            return nil
        }
        let startPart = raw[..<dashIndex]
        let endPart = raw[raw.index(after: dashIndex)...]

        if startPart.isEmpty {
            guard let suffix = Int64(endPart), suffix > 0 else {
                return nil
            }
            let clamped = min(suffix, total)
            let start = total - clamped
            return (start, total - 1)
        }

        guard let start = Int64(startPart), start >= 0 else {
            return nil
        }
        if start >= total {
            return nil
        }

        let end: Int64
        if endPart.isEmpty {
            end = total - 1
        } else {
            guard let parsedEnd = Int64(endPart) else {
                return nil
            }
            end = min(parsedEnd, total - 1)
        }

        guard start <= end else {
            return nil
        }
        return (start, end)
    }
}
