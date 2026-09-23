import Foundation

/// A short local sample for image decoding, never a second playback session.
/// Unsupported encryption/range layouts fail closed rather than playing the URL.
enum HLSPreviewSample {
    struct Plan {
        var initialization: URL?
        var segments: [URL]
    }

    static func plan(_ text: String, base: URL) -> Plan? {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U") else { return nil }
        var initialization: URL?
        var segments: [URL] = []
        var expectsSegment = false
        for raw in text.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            // Do not join encrypted bytes, partial byte ranges, or different timelines.
            if line.hasPrefix("#EXT-X-KEY:"), !line.contains("METHOD=NONE") { StreamDebugTrace.record("Unsupported encrypted HLS sample"); return nil }
            if line.contains("BYTERANGE") { StreamDebugTrace.record("Unsupported HLS byte-range sample"); return nil }
            if line.hasPrefix("#EXT-X-DISCONTINUITY"), !segments.isEmpty { break }
            if line.hasPrefix("#EXT-X-MAP:") {
                guard segments.isEmpty,
                      let start = line.range(of: "URI=\""),
                      let end = line[start.upperBound...].firstIndex(of: "\""),
                      let url = resolve(String(line[start.upperBound..<end]), base: base) else { return nil }
                initialization = url
            } else if line.hasPrefix("#EXTINF:") {
                expectsSegment = true
            } else if !line.hasPrefix("#"), expectsSegment {
                guard let url = resolve(line, base: base) else { return nil }
                segments.append(url)
                expectsSegment = false
                if segments.count == 3 { break }
            }
        }
        return segments.isEmpty ? nil : Plan(initialization: initialization, segments: segments)
    }

    private static func resolve(_ path: String, base: URL) -> URL? {
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL,
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return url
    }

    static func download(from url: URL, headers: [String: String]) async -> URL? {
        var current = url
        var sample: Plan?
        // Resolve a small number of nested masters, preferring the cheapest variant.
        for _ in 0..<3 {
            guard !Task.isCancelled,
                  let bytes = await fetch(current, headers: headers, limit: 512 * 1024),
                  let text = String(data: bytes, encoding: .utf8) else { return nil }
            let manifest = HLSParser.parse(text, masterURL: current.absoluteString)
            if let variant = manifest.qualities.min(by: { $0.bandwidth < $1.bandwidth }),
               let next = URL(string: variant.url) {
                current = next
                continue
            }
            sample = plan(text, base: current)
            break
        }
        guard let sample else { StreamDebugTrace.record("No usable HLS sample plan (invalid layout or master depth limit)"); return nil }
        StreamDebugTrace.record("Sample plan: \(sample.segments.count) segments; initialization: \(sample.initialization != nil)")
        let urls = sample.initialization.map { [$0] } ?? []
        var data = Data()
        let limit = 12 * 1024 * 1024
        for part in urls + sample.segments {
            guard !Task.isCancelled,
                  let bytes = await fetch(part, headers: headers, limit: limit - data.count) else { return nil }
            data.append(bytes)
        }
        guard !Task.isCancelled else { return nil }
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("playbridge-preview-\(UUID().uuidString)")
            .appendingPathExtension(sample.initialization == nil ? "ts" : "mp4")
        do {
            try data.write(to: file, options: .atomic)
            StreamDebugTrace.record("Sample saved for decoding: \(data.count) bytes, \(file.pathExtension)")
            return file
        } catch {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
    }

    private static func fetch(_ url: URL, headers: [String: String], limit: Int) async -> Data? {
        guard limit > 0 else { StreamDebugTrace.record("Sample byte budget exhausted"); return nil }
        StreamDebugTrace.record("GET \(StreamDebugTrace.safeURL(url.absoluteString))")
        var request = URLRequest(url: url, timeoutInterval: 10)
        for (name, value) in headers where name.caseInsensitiveCompare("Range") != .orderedSame {
            request.setValue(value, forHTTPHeaderField: name)
        }
        do {
            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            StreamDebugTrace.record("HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0); expected bytes: \(response.expectedContentLength); limit: \(limit)")
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  response.expectedContentLength <= Int64(limit) else { return nil }
            var data = Data()
            for try await byte in bytes {
                guard !Task.isCancelled, data.count < limit else {
                    StreamDebugTrace.record(Task.isCancelled ? "Download cancelled" : "Response exceeded sample byte limit")
                    return nil
                }
                data.append(byte)
            }
            StreamDebugTrace.record("Received \(data.count) bytes")
            return data.isEmpty ? nil : data
        } catch {
            let error = error as NSError
            StreamDebugTrace.record("Network failure: \(error.domain) code \(error.code)")
            return nil
        }
    }
}
