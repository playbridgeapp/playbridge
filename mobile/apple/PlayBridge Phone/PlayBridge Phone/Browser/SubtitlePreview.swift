import Foundation
import NaturalLanguage

enum SubtitlePreviewState {
    case loading
    /// `language` is the detected language's name in the phone's language, `code` its two-letter code.
    case ready(preview: String, language: String?, code: String?)
    case unavailable
    /// The file isn't subtitles (an error page, JSON…) or is a thumbnail sprite track; it is hidden.
    case invalid

    var languageCode: String? {
        if case .ready(_, _, let code) = self { return code }
        return nil
    }
}

enum SubtitleFetchResult {
    case sample(SubtitleSample)
    /// Fetched, but not subtitles.
    case invalid
    case failed
}

struct SubtitleSample {
    let preview: String
    let languageText: String
}

/// Fetches only the beginning of a detected subtitle file for language identification.
enum SubtitlePreview {
    private static let byteLimit = 8 * 1024

    static func fetch(url urlString: String, headers: [String: String]) async -> SubtitleFetchResult {
        guard let url = URL(string: urlString),
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return .failed }
        var request = URLRequest(url: url, timeoutInterval: 5)
        for (name, value) in headers where name.caseInsensitiveCompare("Range") != .orderedSame {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.setValue("bytes=0-\(byteLimit - 1)", forHTTPHeaderField: "Range")
        do {
            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            defer { bytes.task.cancel() }
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { return .failed }
            var data = Data()
            for try await byte in bytes {
                guard !Task.isCancelled else { return .failed }
                if data.count == byteLimit { break }
                data.append(byte)
            }
            guard !data.isEmpty else { return .failed }
            return check(SubtitleContentCheck.decodeHead(data))
        } catch {
            return .failed
        }
    }

    /// Hides content that isn't subtitles, and WebVTT thumbnail tracks whose cues are images.
    static func check(_ text: String) -> SubtitleFetchResult {
        guard SubtitleContentCheck.looksLikeSubtitles(text) else { return .invalid }
        if SubtitleContentCheck.isWebVTT(text), SubtitleContentCheck.isImageTrack(cueTexts(text)) { return .invalid }
        return parseSample(text).map(SubtitleFetchResult.sample) ?? .failed
    }

    static func parse(_ text: String) -> String? {
        parseSample(text)?.preview
    }

    /// The text of the first cues, without markup.
    static func cueTexts(_ text: String, limit: Int = 20) -> [String] {
        let lines = text.components(separatedBy: .newlines)
        var cues: [String] = []
        var index = 0
        while index < lines.count, cues.count < limit {
            guard lines[index].contains("-->") else { index += 1; continue }
            index += 1
            var cueLines: [String] = []
            while index < lines.count {
                let line = lines[index].trimmingCharacters(in: .whitespacesAndNewlines)
                guard !line.isEmpty, !line.contains("-->") else { break }
                cueLines.append(line)
                index += 1
            }
            let cue = clean(cueLines.joined(separator: " "))
            if !cue.isEmpty { cues.append(cue) }
        }
        return cues
    }

    static func parseSample(_ text: String) -> SubtitleSample? {
        let lines = text.components(separatedBy: .newlines)
        let cues = cueTexts(text)
        if !cues.isEmpty {
            return SubtitleSample(
                preview: cues.prefix(3).joined(separator: " • "),
                languageText: String(cues.joined(separator: " ").prefix(200))
            )
        }

        // Some subtitle files use malformed cue headers; show text, not timestamps.
        let fallback = lines.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { line in
                !line.isEmpty && !line.uppercased().hasPrefix("WEBVTT") &&
                    !line.contains("-->") && Int(line) == nil &&
                    !line.uppercased().hasPrefix("NOTE") && !line.uppercased().hasPrefix("STYLE")
            }
            .prefix(2)
            .map(clean)
            .filter { !$0.isEmpty }
            .joined(separator: " • ")
        return fallback.isEmpty ? nil : SubtitleSample(preview: fallback, languageText: fallback)
    }

    private static func clean(_ value: String) -> String {
        value.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

enum SubtitleLanguageDetector {
    /// The detected language's two-letter code and its name in the phone's language.
    static func detectLanguage(_ text: String) -> (code: String, name: String)? {
        guard let code = detectCode(text) else { return nil }
        let name = (Locale.current.localizedString(forLanguageCode: code) ?? code).capitalized(with: Locale.current)
        return (SubtitleLanguages.normalize(code) ?? code, name)
    }

    /// Avoid guessing from a title, name, or a single short subtitle cue.
    static func detect(_ text: String) -> String? { detectLanguage(text)?.name }

    private static func detectCode(_ text: String) -> String? {
        let sample = String(text.prefix(200))
        guard sample.unicodeScalars.filter({ CharacterSet.letters.contains($0) }).count >= 60 else { return nil }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(sample)
        let hypotheses = recognizer.languageHypotheses(withMaximum: 2)
            .map { ($0.key.rawValue, $0.value) }
        return likelyLanguageCode(hypotheses)
    }

    static func likelyLanguageCode(_ candidates: [(String, Double)]) -> String? {
        let ranked = candidates.sorted { $0.1 > $1.1 }
        guard let best = ranked.first, best.0 != "und", best.1 >= 0.75,
              best.1 - (ranked.dropFirst().first?.1 ?? 0) >= 0.15 else { return nil }
        return best.0
    }
}
