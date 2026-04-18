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

    /// Parse SRT format: 00:00:20,000 --> 00:00:24,400
    func parseSRT(_ content: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []
        let scanner = Scanner(string: content)
        scanner.charactersToBeSkipped = nil

        while !scanner.isAtEnd {
            // Index
            _ = scanner.scanInt()
            _ = scanner.scanCharacters(from: .newlines)

            // Timecodes
            guard let startTimeString: String = scanner.scanUpToString(" --> "),
                let startTime = parseTime(
                    startTimeString.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines))
            else { break }

            _ = scanner.scanString(" --> ")

            guard let endTimeString: String = scanner.scanUpToCharacters(from: .newlines),
                let endTime = parseTime(
                    endTimeString.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines))
            else { break }

            _ = scanner.scanCharacters(from: .newlines)

            // Text
            guard let text: String = scanner.scanUpToString("\n\n") else {
                // Might be the last subtitle
                let remaining = content[scanner.currentIndex...]
                if !remaining.isEmpty {
                    cues.append(
                        SubtitleCue(
                            startTime: startTime,
                            endTime: endTime,
                            text: String(remaining).trimmingCharacters(
                                in: CharacterSet.whitespacesAndNewlines)
                        ))
                }
                break
            }

            cues.append(
                SubtitleCue(
                    startTime: startTime,
                    endTime: endTime,
                    text: text.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
                ))

            _ = scanner.scanString("\n\n")
        }
        return cues
    }

    private func parseTime(_ timeString: String) -> TimeInterval? {
        let parts = timeString.replacingOccurrences(of: ",", with: ".").components(separatedBy: ":")
        guard parts.count == 3 else { return nil }

        let hours = Double(parts[0]) ?? 0
        let minutes = Double(parts[1]) ?? 0
        let seconds = Double(parts[2]) ?? 0

        return (hours * 3600) + (minutes * 60) + seconds
    }

    func loadCues(_ cues: [SubtitleCue]) {
        queue.async {
            self.allCues = cues.sorted { $0.startTime < $1.startTime }
        }
    }

    func update(currentTime: TimeInterval) {
        queue.async {
            let matching = self.allCues.filter {
                currentTime >= $0.startTime && currentTime <= $0.endTime
            }
            DispatchQueue.main.async {
                if self.activeCues != matching {
                    self.activeCues = matching
                }
            }
        }
    }

    func clear() {
        allCues = []
        activeCues = []
    }

    func downloadSubtitle(url: URL, headers: [String: String]?) async throws -> [SubtitleCue] {
        var request = URLRequest(url: url)
        headers?.forEach { request.addValue($1, forHTTPHeaderField: $0) }

        let (data, _) = try await URLSession.shared.data(for: request)
        guard let content = String(data: data, encoding: .utf8) else {
            throw NSError(
                domain: "SubtitleManager", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Failed to decode subtitle data"])
        }

        // Simple heuristic: if it contains "-->" it's likely SRT or VTT
        if content.contains("-->") {
            return parseSRT(content)
        }
        return []
    }
}
