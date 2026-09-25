import Foundation
import Network

struct AirPlaySubtitleSource {
    let url: URL
    let headers: [String: String]
    let title: String
}

/// Retain alongside the AVPlayerItem. Releasing it closes the generated resources
/// and releases any protected-media proxy registration.
final class AirPlaySubtitlePresentation {
    let url: URL
    let preferredSubtitleTitle: String
    private let server: AirPlaySubtitleHTTPServer
    private let media: RoutedStream

    fileprivate init(url: URL, preferredSubtitleTitle: String, server: AirPlaySubtitleHTTPServer, media: RoutedStream) {
        self.url = url
        self.preferredSubtitleTitle = preferredSubtitleTitle
        self.server = server
        self.media = media
    }

    func stop() { server.stop() }
    deinit { server.stop() }
}

enum AirPlaySubtitleError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let value) = self { return value }; return nil }
}

/// Packages external captions as genuine HLS renditions so the AirPlay receiver
/// can render them. Phone overlays cannot provide captions on an AirPlay TV.
enum AirPlaySubtitleService {
    static func prepare(media: RoutedStream, subtitles: [AirPlaySubtitleSource]) async throws -> AirPlaySubtitlePresentation {
        guard !subtitles.isEmpty, subtitles.count <= 16 else {
            throw AirPlaySubtitleError.message("Choose between 1 and 16 external subtitles.")
        }
        try Task.checkCancellation()
        var routed = media
        // The system receiver cannot replay arbitrary AVURLAsset HTTP headers.
        // This extra route is disclosed by the AirPlay destination UI.
        if !media.headers.isEmpty {
            let registration = try await PhoneSenderServices.shared.register(
                url: media.url.absoluteString, headers: media.headers, contentType: "application/vnd.apple.mpegurl")
            routed = RoutedStream(url: registration.url, headers: [:], registration: registration,
                                  sourceURL: media.sourceURL, sourceHeaders: media.sourceHeaders)
        }
        let root = try await fetch(routed.url, headers: routed.headers, limit: 1024 * 1024)
        let rootText = try AirPlaySubtitleHLS.text(root.data)
        let isMaster = rootText.contains("#EXT-X-STREAM-INF:")
        let variants = isMaster ? try AirPlaySubtitleHLS.variants(rootText, base: root.url) : [root.url]
        let playlistURL = variants[0]
        let playlist = isMaster ? try await fetch(playlistURL, headers: routed.headers, limit: 1024 * 1024) : root
        let timeline = try AirPlaySubtitleHLS.timeline(try AirPlaySubtitleHLS.text(playlist.data), base: playlist.url)
        // Adaptive switches must keep the same caption timeline. Validate every
        // advertised variant before publishing the generated master, rather than
        // accepting a first TS variant alongside unsupported fMP4/live variants.
        for variant in variants.dropFirst() {
            let other = try await fetch(variant, headers: routed.headers, limit: 1024 * 1024)
            let otherTimeline = try AirPlaySubtitleHLS.timeline(try AirPlaySubtitleHLS.text(other.data), base: other.url)
            guard abs(otherTimeline.duration - timeline.duration) <= 1 else {
                throw AirPlaySubtitleError.message("The HLS variants do not share a compatible subtitle timeline.")
            }
        }
        let probe = try await fetch(timeline.firstSegment, headers: routed.headers, limit: 512 * 1024, prefixOnly: true)
        guard let timestamp = AirPlaySubtitleHLS.firstPresentationTimestamp(probe.data) else {
            throw AirPlaySubtitleError.message("External AirPlay subtitles currently require unencrypted MPEG-TS HLS video. Embedded subtitles remain available.")
        }
        var resources: [String: AirPlaySubtitleHTTPServer.Resource] = [:]
        var tracks: [(name: String, uri: String)] = []
        var totalCaptionBytes = 0
        for (index, source) in subtitles.enumerated() {
            try Task.checkCancellation()
            let downloaded = try await fetch(source.url, headers: source.headers, limit: 8 * 1024 * 1024)
            let vtt = try AirPlaySubtitleHLS.webVTT(downloaded.data, timestamp: timestamp)
            totalCaptionBytes += vtt.utf8.count
            guard totalCaptionBytes <= 16 * 1024 * 1024 else {
                throw AirPlaySubtitleError.message("These subtitles are too large to share together. Choose fewer subtitle tracks.")
            }
            let captionPath = "captions-\(index).vtt"
            let playlistPath = "captions-\(index).m3u8"
            resources[captionPath] = .init(data: Data(vtt.utf8), contentType: "text/vtt; charset=utf-8")
            resources[playlistPath] = .init(data: Data(AirPlaySubtitleHLS.subtitlePlaylist(
                duration: timeline.duration, captionURI: captionPath).utf8), contentType: "application/vnd.apple.mpegurl")
            let name = source.title.trimmingCharacters(in: .whitespacesAndNewlines)
            tracks.append((name.isEmpty ? "Subtitle \(index + 1)" : name, playlistPath))
        }
        let master = try AirPlaySubtitleHLS.master(rootText, base: root.url, tracks: tracks)
        guard let preferredSubtitleTitle = AirPlaySubtitleHLS.renditionTitle(master, uri: "captions-0.m3u8") else {
            throw AirPlaySubtitleError.message("Couldn’t prepare the external subtitle track.")
        }
        resources["master.m3u8"] = .init(data: Data(master.utf8), contentType: "application/vnd.apple.mpegurl")
        let server = AirPlaySubtitleHTTPServer(resources: resources)
        do {
            let url = try await server.start()
            try Task.checkCancellation()
            return AirPlaySubtitlePresentation(url: url, preferredSubtitleTitle: preferredSubtitleTitle, server: server, media: routed)
        } catch { server.stop(); throw error }
    }

    private static func fetch(_ url: URL, headers: [String: String], limit: Int,
                              prefixOnly: Bool = false) async throws -> (data: Data, url: URL) {
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil,
              url.user == nil, url.password == nil else {
            throw AirPlaySubtitleError.message("Choose an HTTP or HTTPS subtitle or stream URL.")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: AirPlaySubtitleRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url, timeoutInterval: 20)
        for (name, value) in headers {
            guard !name.contains("\r"), !name.contains("\n"), !value.contains("\r"), !value.contains("\n"),
                  name.lowercased() != "host" else { continue }
            request.setValue(value, forHTTPHeaderField: name)
        }
        if prefixOnly { request.setValue("bytes=0-\(limit - 1)", forHTTPHeaderField: "Range") }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode) else {
                throw AirPlaySubtitleError.message("Couldn’t download the AirPlay stream or subtitle. Check its URL and request headers.")
            }
            guard prefixOnly || response.expectedContentLength <= Int64(limit) else {
                throw AirPlaySubtitleError.message("The AirPlay playlist or subtitle file is too large.")
            }
            var data = Data()
            for try await byte in bytes {
                if data.count == limit {
                    if prefixOnly { break }
                    throw AirPlaySubtitleError.message("The AirPlay playlist or subtitle file is too large.")
                }
                data.append(byte)
            }
            try Task.checkCancellation()
            return (data, response.url ?? url)
        } catch is CancellationError { throw CancellationError() }
        catch let error as AirPlaySubtitleError { throw error }
        catch {
            try Task.checkCancellation()
            // Foundation errors may contain authenticated URLs.
            throw AirPlaySubtitleError.message("Couldn’t download the AirPlay stream or subtitle. Check the connection and try again.")
        }
    }
}

private final class AirPlaySubtitleRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let source = response.url, let destination = request.url,
              ["http", "https"].contains(destination.scheme?.lowercased() ?? ""),
              !(source.scheme == "https" && destination.scheme == "http") else { completionHandler(nil); return }
        var redirected = request
        if source.host != destination.host || source.scheme != destination.scheme || source.port != destination.port {
            for name in redirected.allHTTPHeaderFields?.keys ?? Dictionary<String, String>().keys {
                if !["accept", "accept-language", "user-agent", "range"].contains(name.lowercased()) {
                    redirected.setValue(nil, forHTTPHeaderField: name)
                }
            }
        }
        completionHandler(redirected)
    }
}

/// Pure manifest/timestamp transformations have standalone regression coverage.
enum AirPlaySubtitleHLS {
    static func text(_ data: Data) throws -> String {
        guard let text = String(data: data, encoding: .utf8),
              text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U") else {
            throw AirPlaySubtitleError.message("External AirPlay subtitles are supported for HLS video streams only.")
        }
        return text.replacingOccurrences(of: "\r\n", with: "\n")
    }

    static func firstVariant(_ text: String, base: URL) throws -> URL {
        try variants(text, base: base)[0]
    }

    static func variants(_ text: String, base: URL) throws -> [URL] {
        var nextIsVariant = false
        var variants: [URL] = []
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("#EXT-X-STREAM-INF:") { nextIsVariant = true }
            else if nextIsVariant && !line.isEmpty && !line.hasPrefix("#") {
                variants.append(try absolute(line, base: base))
                nextIsVariant = false
                guard variants.count <= 32 else { throw AirPlaySubtitleError.message("This HLS stream has too many video variants.") }
            }
        }
        guard !variants.isEmpty else { throw AirPlaySubtitleError.message("The HLS stream contains no playable video variant.") }
        return variants
    }

    static func timeline(_ text: String, base: URL) throws -> (duration: Double, firstSegment: URL) {
        guard text.contains("#EXT-X-ENDLIST"), !text.contains("#EXT-X-DISCONTINUITY"),
              !text.contains("#EXT-X-MAP:"), !text.contains("#EXT-X-BYTERANGE:"),
              !text.components(separatedBy: "\n").contains(where: { $0.hasPrefix("#EXT-X-KEY:") && !$0.contains("METHOD=NONE") }) else {
            throw AirPlaySubtitleError.message("External AirPlay subtitles currently require unencrypted MPEG-TS HLS video on demand without timeline discontinuities.")
        }
        var duration = 0.0
        var first: URL?
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("#EXTINF:"), let length = Double(line.dropFirst(8).split(separator: ",").first ?? ""), length.isFinite, length > 0 {
                duration += length
            } else if !line.isEmpty && !line.hasPrefix("#") && first == nil { first = try absolute(line, base: base) }
        }
        guard duration > 0, duration <= 7 * 24 * 3600, let first else {
            throw AirPlaySubtitleError.message("The HLS stream does not have a supported finite timeline.")
        }
        return (duration, first)
    }

    static func master(_ original: String, base: URL, tracks: [(name: String, uri: String)]) throws -> String {
        var lines: [String]
        var groups = Set<String>()
        if original.contains("#EXT-X-STREAM-INF:") {
            lines = try original.components(separatedBy: "\n").map { raw in
                var line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                if !line.isEmpty && !line.hasPrefix("#") { return try absolute(line, base: base).absoluteString }
                let regex = try NSRegularExpression(pattern: #"URI="([^"]*)""#)
                for match in regex.matches(in: line, range: NSRange(line.startIndex..., in: line)).reversed() {
                    guard let valueRange = Range(match.range(at: 1), in: line) else { continue }
                    let url = try absolute(String(line[valueRange]), base: base)
                    line.replaceSubrange(valueRange, with: url.absoluteString)
                }
                if line.hasPrefix("#EXT-X-STREAM-INF:") {
                    if let groupRange = line.range(of: #"SUBTITLES="[^"]*""#, options: .regularExpression) {
                        let attribute = String(line[groupRange])
                        groups.insert(String(attribute.dropFirst(11).dropLast()))
                    } else {
                        groups.insert("pb-external")
                        line += ",SUBTITLES=\"pb-external\""
                    }
                }
                return line
            }
        } else {
            groups.insert("pb-external")
            lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-STREAM-INF:BANDWIDTH=20000000,SUBTITLES=\"pb-external\"", base.absoluteString]
        }
        guard groups.count <= 32 else { throw AirPlaySubtitleError.message("This HLS stream has too many subtitle groups.") }
        let namePattern = try NSRegularExpression(pattern: #"NAME="([^"]*)""#)
        var names = Set<String>()
        for line in lines where line.hasPrefix("#EXT-X-MEDIA:") && line.contains("TYPE=SUBTITLES") {
            if let match = namePattern.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
               let range = Range(match.range(at: 1), in: line) { names.insert(String(line[range])) }
        }
        let namedTracks = tracks.map { track -> (name: String, uri: String) in
            let baseName = String(track.name.prefix(128)).replacingOccurrences(of: "\"", with: "'")
                .replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " ")
            var name = baseName
            var suffix = 2
            while names.contains(name) { name = "\(baseName) (\(suffix))"; suffix += 1 }
            names.insert(name)
            return (name, track.uri)
        }
        let renditions = groups.sorted().flatMap { group in namedTracks.map { track -> String in
            return "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"\(group)\",NAME=\"\(track.name)\",LANGUAGE=\"und\",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,URI=\"\(track.uri)\""
        } }
        if let versionIndex = lines.firstIndex(where: { $0.hasPrefix("#EXT-X-VERSION:") }) {
            let version = Int(lines[versionIndex].dropFirst("#EXT-X-VERSION:".count)) ?? 5
            lines[versionIndex] = "#EXT-X-VERSION:\(max(5, version))"
        } else { lines.insert("#EXT-X-VERSION:5", at: min(1, lines.count)) }
        lines.insert(contentsOf: renditions, at: min(1, lines.count))
        return lines.joined(separator: "\n") + "\n"
    }

    static func subtitlePlaylist(duration: Double, captionURI: String) -> String {
        "#EXTM3U\n#EXT-X-VERSION:5\n#EXT-X-TARGETDURATION:\(Int(ceil(duration)))\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:\(duration),\n\(captionURI)\n#EXT-X-ENDLIST\n"
    }

    static func renditionTitle(_ master: String, uri: String) -> String? {
        guard let line = master.components(separatedBy: "\n").first(where: {
            $0.hasPrefix("#EXT-X-MEDIA:") && $0.contains("URI=\"\(uri)\"")
        }), let range = line.range(of: #"NAME="[^"]*""#, options: .regularExpression) else { return nil }
        return String(line[range].dropFirst(6).dropLast())
    }

    static func webVTT(_ data: Data, timestamp: UInt64) throws -> String {
        guard var body = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16) else {
            throw AirPlaySubtitleError.message("This subtitle is not a supported WebVTT or SRT text file.")
        }
        body = body.replacingOccurrences(of: "\u{FEFF}", with: "").replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        if body.hasPrefix("WEBVTT") {
            guard let headerEnd = body.range(of: "\n\n") else { throw AirPlaySubtitleError.message("The subtitle file contains no captions.") }
            body = String(body[headerEnd.upperBound...])
        }
        var validCues = 0
        let converted = body.components(separatedBy: "\n").map { line -> String in
            guard line.contains("-->") else { return line }
            let converted = line.replacingOccurrences(of: #"(\d{2}:\d{2}:\d{2}),(\d{3})"#, with: "$1.$2", options: .regularExpression)
            if converted.range(of: #"^(?:\d{2,}:)?\d{2}:\d{2}\.\d{3}\s+-->\s+(?:\d{2,}:)?\d{2}:\d{2}\.\d{3}(?:\s|$)"#, options: .regularExpression) != nil { validCues += 1 }
            return converted
        }
        guard validCues > 0 else { throw AirPlaySubtitleError.message("This subtitle has no readable WebVTT or SRT captions.") }
        return "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:\(timestamp)\n\n" + converted.joined(separator: "\n") + "\n"
    }

    /// First video PES timestamp, falling back to audio for audio-only TS.
    /// Accounts for TS adaptation fields; no demux or full segment download is needed.
    static func firstPresentationTimestamp(_ data: Data) -> UInt64? {
        let bytes = [UInt8](data)
        guard bytes.count >= 188 else { return nil }
        var fallback: UInt64?
        for start in 0..<min(188, bytes.count - 187) where bytes[start] == 0x47 {
            if start + 188 < bytes.count && bytes[start + 188] != 0x47 { continue }
            for offset in stride(from: start, through: bytes.count - 188, by: 188) {
                guard bytes[offset] == 0x47, bytes[offset + 1] & 0x40 != 0, bytes[offset + 3] & 0x10 != 0 else { continue }
                var pes = offset + 4
                if bytes[offset + 3] & 0x20 != 0 { pes += 1 + Int(bytes[pes]) }
                guard pes + 14 <= offset + 188, bytes[pes] == 0, bytes[pes + 1] == 0, bytes[pes + 2] == 1,
                      bytes[pes + 7] & 0x80 != 0, bytes[pes + 8] >= 5 else { continue }
                let p = pes + 9
                let pts = (UInt64(bytes[p] & 0x0e) << 29) | (UInt64(bytes[p + 1]) << 22)
                    | (UInt64(bytes[p + 2] & 0xfe) << 14) | (UInt64(bytes[p + 3]) << 7) | UInt64(bytes[p + 4] >> 1)
                if bytes[pes + 3] & 0xf0 == 0xe0 { return pts }
                if bytes[pes + 3] & 0xe0 == 0xc0 { fallback = fallback ?? pts }
            }
            return fallback
        }
        return nil
    }

    private static func absolute(_ raw: String, base: URL) throws -> URL {
        guard let url = URL(string: raw, relativeTo: base)?.absoluteURL,
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
            throw AirPlaySubtitleError.message("The HLS stream contains an unsupported resource URL.")
        }
        return url
    }
}

/// Private per-presentation server with an unguessable path and bounded requests.
/// Generated subtitle resources never touch persistent storage.
private final class AirPlaySubtitleHTTPServer: @unchecked Sendable {
    struct Resource { let data: Data; let contentType: String }
    private let resources: [String: Resource]
    private let token = UUID().uuidString
    private let queue = DispatchQueue(label: "com.playbridge.airplay-subtitles")
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]

    init(resources: [String: Resource]) { self.resources = resources }

    func start() async throws -> URL {
        guard let address = LocalFileServer.lanIPAddress() else {
            throw AirPlaySubtitleError.message("Connect to Wi-Fi to share subtitles with AirPlay.")
        }
        let listener = try NWListener(using: .tcp)
        self.listener = listener
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                var finished = false
                let finish: (Result<URL, Error>) -> Void = { result in
                    guard !finished else { return }; finished = true
                    continuation.resume(with: result)
                }
                listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
                listener.stateUpdateHandler = { [weak self, weak listener] state in
                    guard let self, let listener else { return }
                    switch state {
                    case .ready:
                        if let port = listener.port, let url = URL(string: "http://\(address):\(port.rawValue)/\(self.token)/master.m3u8") {
                            finish(.success(url))
                        }
                    case .failed, .cancelled:
                        finish(.failure(AirPlaySubtitleError.message("Couldn’t start the AirPlay subtitle server.")))
                    default: break
                    }
                }
                queue.asyncAfter(deadline: .now() + 10) {
                    if !finished { listener.cancel(); finish(.failure(AirPlaySubtitleError.message("The AirPlay subtitle server did not start in time."))) }
                }
                listener.start(queue: queue)
            }
        }, onCancel: { listener.cancel() })
    }

    func stop() {
        // Keep mutations on the listener queue, including teardown of accepted clients.
        queue.async { [self] in
            listener?.cancel(); listener = nil
            connections.values.forEach { $0.cancel() }; connections.removeAll()
        }
    }

    private func accept(_ connection: NWConnection) {
        guard connections.count < 32 else { connection.cancel(); return }
        let id = UUID()
        connections[id] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            if case .cancelled = state { self?.connections.removeValue(forKey: id) }
            if case .failed = state { connection?.cancel() }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 15) { [weak connection] in connection?.cancel() }
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] bytes, _, done, error in
            guard let self else { connection.cancel(); return }
            var data = buffer
            if let bytes { data.append(bytes) }
            guard data.count <= 16 * 1024 else { connection.cancel(); return }
            if let end = data.range(of: Data("\r\n\r\n".utf8)) {
                respond(connection, request: String(decoding: data[..<end.upperBound], as: UTF8.self))
            } else if done || error != nil { connection.cancel() }
            else { receive(connection, buffer: data) }
        }
    }

    private func respond(_ connection: NWConnection, request: String) {
        let first = request.components(separatedBy: "\r\n").first?.split(separator: " ") ?? []
        guard first.count == 3, ["GET", "HEAD"].contains(String(first[0])),
              first[1].hasPrefix("/\(token)/"), let resource = resources[String(first[1].dropFirst(token.count + 2))] else {
            send(connection, header: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", body: Data()); return
        }
        let header = "HTTP/1.1 200 OK\r\nContent-Type: \(resource.contentType)\r\nContent-Length: \(resource.data.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        send(connection, header: header, body: first[0] == "HEAD" ? Data() : resource.data)
    }

    private func send(_ connection: NWConnection, header: String, body: Data) {
        var data = Data(header.utf8); data.append(body)
        connection.send(content: data, completion: .contentProcessed { _ in connection.cancel() })
    }
}
