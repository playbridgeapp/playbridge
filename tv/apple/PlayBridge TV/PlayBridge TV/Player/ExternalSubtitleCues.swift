import Foundation

struct ExternalSubtitleCue {
    let start: Double
    let end: Double
    let text: String
}

/// A deliberately small sidecar-caption reader. It supports ordinary WebVTT and SRT cues;
/// positioned/styled WebVTT cues are rendered as plain, bottom-centred text.
struct ExternalSubtitleCues {
    let cues: [ExternalSubtitleCue]
    private let maxEndThroughIndex: [Double]

    nonisolated init?(data: Data) {
        guard let source = String(data: data, encoding: .utf8) else { return nil }
        let normalized = source.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\u{FEFF}", with: "")
        let isWebVTT = normalized.hasPrefix("WEBVTT")
        var blocks: [[String]] = []
        var block: [String] = []
        for line in normalized.components(separatedBy: "\n") {
            if line.trimmingCharacters(in: .whitespaces).isEmpty {
                if !block.isEmpty { blocks.append(block); block = [] }
            } else {
                block.append(line)
            }
        }
        if !block.isEmpty { blocks.append(block) }

        var parsed: [ExternalSubtitleCue] = []
        for lines in blocks {
            guard let first = lines.first else { continue }
            if first.hasPrefix("WEBVTT") || first.hasPrefix("NOTE")
                || first == "STYLE" || first == "REGION" { continue }
            guard let timingIndex = lines.firstIndex(where: { $0.contains("-->") }),
                  timingIndex <= 1 else { continue }
            let parts = lines[timingIndex].components(separatedBy: "-->")
            guard parts.count == 2,
                  let start = Self.timestamp(parts[0]),
                  let end = Self.timestamp(String(parts[1].split(whereSeparator: \.isWhitespace).first ?? "")),
                  end > start,
                  timingIndex + 1 < lines.count else { continue }
            let caption = lines[(timingIndex + 1)...]
                .map(Self.plainText)
                .joined(separator: "\n")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !caption.isEmpty { parsed.append(.init(start: start, end: end, text: caption)) }
        }
        guard isWebVTT || !parsed.isEmpty else { return nil }
        cues = parsed.sorted { $0.start < $1.start }
        var largestEnd = 0.0
        maxEndThroughIndex = cues.map { cue in
            largestEnd = max(largestEnd, cue.end)
            return largestEnd
        }
    }

    func text(at seconds: Double) -> String? {
        guard seconds.isFinite, seconds >= 0 else { return nil }
        var lower = 0
        var upper = cues.count
        while lower < upper {
            let middle = (lower + upper) / 2
            if cues[middle].start <= seconds { lower = middle + 1 }
            else { upper = middle }
        }
        var active: [String] = []
        var index = lower - 1
        while index >= 0, maxEndThroughIndex[index] > seconds {
            let cue = cues[index]
            if cue.end > seconds { active.append(cue.text) }
            index -= 1
        }
        return active.isEmpty ? nil : active.reversed().joined(separator: "\n")
    }

    nonisolated private static func timestamp(_ raw: String) -> Double? {
        let fields = raw.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: ",", with: ".")
            .split(separator: ":")
        guard fields.count == 2 || fields.count == 3,
              let seconds = Double(fields[fields.count - 1]), seconds >= 0, seconds < 60,
              let minutes = Int(fields[fields.count - 2]), minutes >= 0, minutes < 60 else { return nil }
        let hours = fields.count == 3 ? Int(fields[0]) : 0
        guard let hours, hours >= 0 else { return nil }
        let value = Double(hours) * 3600 + Double(minutes * 60) + seconds
        return value.isFinite ? value : nil
    }

    nonisolated private static func plainText(_ raw: String) -> String {
        let withoutTags = raw.replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
        return withoutTags
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&lrm;", with: "")
            .replacingOccurrences(of: "&rlm;", with: "")
    }
}
