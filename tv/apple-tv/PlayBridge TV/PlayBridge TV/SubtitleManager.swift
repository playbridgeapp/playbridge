import Combine
import Foundation

struct SubtitleCue: Codable, Equatable {
    let startTime: TimeInterval
    let endTime: TimeInterval
    let text: String
}

class SubtitleManager: ObservableObject {
    @Published var activeCues: [SubtitleCue] = []
    private var allCues: [SubtitleCue] = []
    private let queue = DispatchQueue(
        label: "com.playbridge.SubtitleManager", qos: .userInteractive)

    private let subtitleCacheDirectory: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("subtitles")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    // MARK: - SRT Parser

    /// Parse SRT format: index, 00:00:20,000 --> 00:00:24,400, text, blank line
    func parseSRT(_ content: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []
        let scanner = Scanner(string: content)
        scanner.charactersToBeSkipped = nil

        while !scanner.isAtEnd {
            // Index line (may be missing in malformed SRTs — skip gracefully)
            _ = scanner.scanInt()
            _ = scanner.scanCharacters(from: .newlines)

            // Timecodes
            guard let startTimeString: String = scanner.scanUpToString(" --> "),
                let startTime = parseTime(
                    startTimeString.trimmingCharacters(in: .whitespacesAndNewlines))
            else { break }

            _ = scanner.scanString(" --> ")

            guard let endTimeString: String = scanner.scanUpToCharacters(from: .newlines),
                let endTime = parseTime(
                    endTimeString.trimmingCharacters(in: .whitespacesAndNewlines))
            else { break }

            _ = scanner.scanCharacters(from: .newlines)

            // Text block (ends at double newline or EOF)
            guard let text: String = scanner.scanUpToString("\n\n") else {
                let remaining = content[scanner.currentIndex...]
                if !remaining.isEmpty {
                    cues.append(
                        SubtitleCue(
                            startTime: startTime,
                            endTime: endTime,
                            text: String(remaining).trimmingCharacters(in: .whitespacesAndNewlines)
                        ))
                }
                break
            }

            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                cues.append(SubtitleCue(startTime: startTime, endTime: endTime, text: trimmed))
            }
            _ = scanner.scanString("\n\n")
        }
        return cues
    }

    // MARK: - VTT Parser

    /// Parse WebVTT format: WEBVTT header, then blocks separated by blank lines.
    func parseVTT(_ content: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []
        // Split on blank lines (handles \r\n and \n)
        let blocks = content.components(separatedBy: "\n\n")

        for block in blocks {
            let lines = block.components(separatedBy: "\n")
                .map { $0.trimmingCharacters(in: .init(charactersIn: "\r")) }
                .filter { !$0.isEmpty }

            guard !lines.isEmpty else { continue }

            // Skip WEBVTT header block and metadata blocks
            let first = lines[0]
            if first.hasPrefix("WEBVTT") || first.hasPrefix("NOTE")
                || first.hasPrefix("STYLE") || first.hasPrefix("REGION")
            { continue }

            // Find timecode line (the line containing " --> ")
            guard let tcIdx = lines.firstIndex(where: { $0.contains(" --> ") }) else { continue }

            // Parse timecode — may have positioning after end time ("00:00:05.000 align:left")
            let timecodeLine = lines[tcIdx]
            let arrowRange = timecodeLine.range(of: " --> ")!
            let startStr = String(timecodeLine[..<arrowRange.lowerBound])
                .trimmingCharacters(in: .whitespaces)
            let afterArrow = String(timecodeLine[arrowRange.upperBound...])
            // End time ends at the first space (positioning info follows)
            let endStr = afterArrow.components(separatedBy: " ").first ?? afterArrow

            guard let startTime = parseVTTTime(startStr), let endTime = parseVTTTime(endStr) else {
                continue
            }

            // Collect text lines after the timecode line
            let textLines = lines[(tcIdx + 1)...].joined(separator: "\n")
            let strippedText = stripVTTTags(textLines)
                .trimmingCharacters(in: .whitespacesAndNewlines)

            if !strippedText.isEmpty {
                cues.append(SubtitleCue(startTime: startTime, endTime: endTime, text: strippedText))
            }
        }
        return cues
    }

    // MARK: - Time Parsing

    /// Parse SRT timestamp: 00:00:20,000
    private func parseTime(_ timeString: String) -> TimeInterval? {
        let parts = timeString.replacingOccurrences(of: ",", with: ".")
            .components(separatedBy: ":")
        guard parts.count == 3,
            let hours = Double(parts[0]),
            let minutes = Double(parts[1]),
            let seconds = Double(parts[2])
        else { return nil }
        return hours * 3600 + minutes * 60 + seconds
    }

    /// Parse VTT timestamp: 00:00:20.000 or 00:20.000
    private func parseVTTTime(_ timeString: String) -> TimeInterval? {
        let parts = timeString.components(separatedBy: ":")
        guard parts.count >= 2 else { return nil }

        let lastPart = parts.last ?? ""
        let secParts = lastPart.components(separatedBy: ".")
        guard secParts.count == 2,
            let wholeSeconds = Double(secParts[0]),
            let ms = Double(secParts[1])
        else { return nil }

        let fractional = wholeSeconds + ms / pow(10.0, Double(secParts[1].count))

        if parts.count == 3 {
            // HH:MM:SS.mmm
            guard let hours = Double(parts[0]), let minutes = Double(parts[1]) else { return nil }
            return hours * 3600 + minutes * 60 + fractional
        } else {
            // MM:SS.mmm
            guard let minutes = Double(parts[0]) else { return nil }
            return minutes * 60 + fractional
        }
    }

    /// Strip VTT inline tags: <i>, </b>, <c.classname>, <00:00:00.000>, etc.
    private func stripVTTTags(_ text: String) -> String {
        var result = ""
        var inTag = false
        for ch in text {
            if ch == "<" { inTag = true }
            else if ch == ">" { inTag = false }
            else if !inTag { result.append(ch) }
        }
        return result
    }

    // MARK: - Cue Management

    func loadCues(_ cues: [SubtitleCue]) {
        queue.async {
            self.allCues = cues.sorted { $0.startTime < $1.startTime }
        }
    }

    /// Update active cues using binary search — O(log n + k) where k is the active window.
    func update(currentTime: TimeInterval) {
        queue.async {
            let matching = self.activeCuesAt(currentTime)
            DispatchQueue.main.async {
                if self.activeCues != matching {
                    self.activeCues = matching
                }
            }
        }
    }

    private func activeCuesAt(_ time: TimeInterval) -> [SubtitleCue] {
        guard !allCues.isEmpty else { return [] }

        // Binary search: find rightmost index where startTime <= time
        var lo = 0
        var hi = allCues.count
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            if allCues[mid].startTime <= time {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        guard lo > 0 else { return [] }

        // Walk backwards from (lo-1), collecting cues that are still active.
        // Stop when a cue started more than 30s ago (generous window for any subtitle format).
        var result: [SubtitleCue] = []
        var i = lo - 1
        while i >= 0 && time - allCues[i].startTime < 30.0 {
            let cue = allCues[i]
            if cue.endTime >= time {
                result.append(cue)
            }
            i -= 1
        }
        return result.reversed()
    }

    func clear() {
        allCues = []
        activeCues = []
    }

    // MARK: - Download & Cache

    /// Download a subtitle from `url`, cache it to `Caches/subtitles/sub_{index}.{ext}`,
    /// and return the parsed cues together with the local file URL.
    ///
    /// The local URL is what you pass to `SubtitleHTTPServer` so VLC can slave it.
    func downloadSubtitle(
        url: URL,
        headers: [String: String]?,
        index: Int = 0
    ) async throws -> (cues: [SubtitleCue], localURL: URL) {
        var request = URLRequest(url: url)
        headers?.forEach { request.addValue($1, forHTTPHeaderField: $0) }

        let (data, _) = try await URLSession.shared.data(for: request)
        guard let content = String(data: data, encoding: .utf8) else {
            throw NSError(
                domain: "SubtitleManager", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Failed to decode subtitle data as UTF-8"])
        }

        // Detect format: VTT starts with "WEBVTT" or URL extension is .vtt
        let ext = url.pathExtension.lowercased()
        let isVTT = ext == "vtt" || content.hasPrefix("WEBVTT")

        let cues = isVTT ? parseVTT(content) : parseSRT(content)

        // Write to named cache path
        let cacheExt = isVTT ? "vtt" : "srt"
        let filename = "sub_\(index).\(cacheExt)"
        let localURL = subtitleCacheDirectory.appendingPathComponent(filename)
        try data.write(to: localURL, options: .atomic)

        return (cues: cues, localURL: localURL)
    }
}
