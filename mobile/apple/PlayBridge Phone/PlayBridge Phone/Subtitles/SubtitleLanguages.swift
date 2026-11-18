import Foundation

/// Languages of detected subtitles, and the order the cast sheet lists them in. Port of
/// Android's `SubtitleLanguages.kt` (#512).
enum SubtitleLanguages {
    /// A two-letter code where one exists (`eng`, `fre`, `en-US` → `en`, `fr`). Blank, `und`
    /// and `multi` mean no particular language.
    static func normalize(_ code: String?) -> String? {
        guard let primary = code?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init),
              !primary.isEmpty, primary != "und", primary != "multi" else { return nil }
        return codes[primary] ?? primary
    }

    /// Two- and three-letter codes, including bibliographic ISO 639-2, each mapped to its two-letter code.
    private static let codes: [String: String] = {
        var map: [String: String] = [:]
        for code in twoLetterCodes {
            map[code] = code
            if let alpha3 = Locale.LanguageCode(code).identifier(.alpha3) { map[alpha3] = code }
        }
        let bibliographic = ["fre": "fr", "ger": "de", "dut": "nl", "chi": "zh", "cze": "cs", "gre": "el",
                             "per": "fa", "rum": "ro", "slo": "sk", "alb": "sq", "arm": "hy", "ice": "is",
                             "mac": "mk", "may": "ms", "wel": "cy", "baq": "eu", "geo": "ka", "bur": "my"]
        map.merge(bibliographic) { _, new in new }
        return map
    }()

    private static var twoLetterCodes: [String] {
        Locale.LanguageCode.isoLanguageCodes.map(\.identifier).filter { $0.count == 2 }
    }

    /// `codes` plus English language names (`english`, `spanish`).
    private static let words: [String: String] = {
        var map = codes
        let english = Locale(identifier: "en")
        for code in twoLetterCodes {
            guard let name = english.localizedString(forLanguageCode: code)?.lowercased(),
                  name.count > 3, name != code, !name.contains(" ") else { continue }
            map[name] = code
        }
        return map
    }()

    private static func letterWords(_ text: String) -> [String] {
        text.lowercased().split { !$0.isLetter }.map(String.init)
    }

    /// A full language name or three-letter code in a title; two letters only when that's the whole title.
    static func inTitle(_ title: String?) -> String? {
        let parts = letterWords(title ?? "")
        if parts.count == 1, parts[0].count == 2 { return words[parts[0]] }
        return parts.lazy.compactMap { word in word.count >= 3 ? words[word] : nil }.first
    }

    /// The last word of a file name (`movie.en`, `show_eng`) or a full language name anywhere in it.
    static func inFileName(_ url: String) -> String? {
        var name = Substring(url)
        if let cut = name.firstIndex(where: { $0 == "?" || $0 == "#" }) { name = name[..<cut] }
        if let slash = name.lastIndex(of: "/") { name = name[name.index(after: slash)...] }
        if let dot = name.lastIndex(of: ".") { name = name[..<dot] }
        let parts = letterWords(String(name))
        if parts.count > 1, let last = parts.last, (2...3).contains(last.count), let code = words[last] { return code }
        return parts.lazy.compactMap { word in word.count > 3 ? words[word] : nil }.first
    }

    /// What language detection found in the dialogue, otherwise a language named in the
    /// subtitle's title (`English`, `eng`) or at the end of its file name (`movie.en.vtt`).
    static func hint(for subtitle: DetectedVideo, detected: String?) -> String? {
        normalize(detected) ?? inTitle(subtitle.title) ?? inFileName(subtitle.url)
    }

    private static let variant = try! NSRegularExpression(
        pattern: #"(^|[^\p{L}])(forced|sdh|cc|hearing)([^\p{L}]|$)"#, options: .caseInsensitive)

    /// Forced-only and hearing-impaired versions; the plain one is preferred when both exist.
    static func isVariant(_ subtitle: DetectedVideo) -> Bool {
        let file = subtitle.url.split(separator: "?").first.map { $0.split(separator: "/").last.map(String.init) ?? "" } ?? ""
        return [subtitle.title ?? "", file].contains { variant.firstMatch(in: $0, range: NSRange($0.startIndex..., in: $0)) != nil }
    }

    /// The preferred language first, then the phone's language, then the rest; newest first
    /// within each group. `subtitles` must already be newest first.
    static func rank(_ subtitles: [DetectedVideo], preferred: String?, device: String?,
                     language: (DetectedVideo) -> String?) -> [DetectedVideo] {
        let preferredCode = normalize(preferred)
        let deviceCode = normalize(device)
        func group(_ subtitle: DetectedVideo) -> Int {
            let code = language(subtitle)
            if preferredCode != nil, code == preferredCode { return 0 }
            if deviceCode != nil, code == deviceCode { return 1 }
            return 2
        }
        return subtitles.enumerated()
            .sorted { lhs, rhs in
                let (left, right) = (group(lhs.element), group(rhs.element))
                return left == right ? lhs.offset < rhs.offset : left < right
            }
            .map(\.element)
    }

    /// The subtitle to tick for the preferred language: the plain version over forced or SDH ones.
    static func preferredPick(_ ranked: [DetectedVideo], preferred: String?,
                              language: (DetectedVideo) -> String?) -> DetectedVideo? {
        guard let code = normalize(preferred) else { return nil }
        let matches = ranked.filter { language($0) == code }
        return matches.first { !isVariant($0) } ?? matches.first
    }
}
