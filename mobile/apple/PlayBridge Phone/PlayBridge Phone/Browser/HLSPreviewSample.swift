import Foundation

/// A short local sample for image decoding, never a second playback session.
/// Unsupported encryption/range layouts fail closed rather than playing the URL.
enum HLSPreviewSample {
    struct Plan {
        var initialization: URL?
        var segments: [URL]
    }

    struct Sample {
        let segmentFiles: [URL]
        let combinedFile: URL?

        func removeFiles() {
            for file in segmentFiles { try? FileManager.default.removeItem(at: file) }
            if let combinedFile { try? FileManager.default.removeItem(at: combinedFile) }
        }
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
            }
        }
        guard !segments.isEmpty else { return nil }
        // Skip the opening credits/black lead-in in longer playlists; retain a
        // bounded three-segment window so preview traffic cannot grow with them.
        let start = Int(Double(segments.count - 1) * 0.25)
        return Plan(initialization: initialization,
                    segments: Array(segments[start..<min(segments.count, start + 3)]))
    }

    private static func resolve(_ path: String, base: URL) -> URL? {
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL,
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return url
    }

    static func download(from url: URL, headers: [String: String]) async -> Sample? {
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
        let limit = 12 * 1024 * 1024
        let initialization: Data
        if let url = sample.initialization {
            guard let bytes = await fetch(url, headers: headers, limit: limit) else { return nil }
            initialization = bytes
        } else {
            initialization = Data()
        }
        var totalBytes = initialization.count
        var segmentFiles: [URL] = []
        var combined = initialization
        let fileExtension = sample.initialization == nil ? "ts" : "mp4"
        var failedSegment = false
        for segment in sample.segments {
            guard !Task.isCancelled else { break }
            guard let bytes = await fetch(segment, headers: headers, limit: limit - totalBytes) else {
                failedSegment = true
                continue
            }
            totalBytes += bytes.count
            combined.append(bytes)
            let file = FileManager.default.temporaryDirectory
                .appendingPathComponent("playbridge-preview-\(UUID().uuidString)")
                .appendingPathExtension(fileExtension)
            do {
                var data = initialization
                data.append(bytes)
                try data.write(to: file, options: .atomic)
                segmentFiles.append(file)
            } catch {
                failedSegment = true
                try? FileManager.default.removeItem(at: file)
            }
        }
        var combinedFile: URL?
        if !failedSegment && segmentFiles.count > 1 && !Task.isCancelled {
            let file = FileManager.default.temporaryDirectory
                .appendingPathComponent("playbridge-preview-\(UUID().uuidString)")
                .appendingPathExtension(fileExtension)
            if (try? combined.write(to: file, options: .atomic)) != nil { combinedFile = file }
            else { try? FileManager.default.removeItem(at: file) }
        }
        let result = Sample(segmentFiles: segmentFiles, combinedFile: combinedFile)
        guard !Task.isCancelled, !segmentFiles.isEmpty else { result.removeFiles(); return nil }
        StreamDebugTrace.record("Saved \(segmentFiles.count) preview segments for decoding: \(totalBytes) bytes")
        return result
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
