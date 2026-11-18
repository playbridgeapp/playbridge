import Foundation

/// Whether a fetched file is subtitles, from its opening bytes. Port of Android's checks in
/// `VideoDetector.kt` (#512): error pages, JSON and thumbnail sprites served at a
/// subtitle-looking URL are hidden.
enum SubtitleContentCheck {
    /// Decodes a subtitle file's opening bytes, honouring a UTF-16 byte order mark.
    static func decodeHead(_ data: Data) -> String {
        let bytes = [UInt8](data.prefix(2))
        let body = Data(data.dropFirst(2))
        let even = body.prefix(body.count - body.count % 2)
        if bytes == [0xFF, 0xFE] { return String(data: even, encoding: .utf16LittleEndian) ?? "" }
        if bytes == [0xFE, 0xFF] { return String(data: even, encoding: .utf16BigEndian) ?? "" }
        return String(decoding: data, as: UTF8.self)
    }

    private static let timing = try! NSRegularExpression(
        pattern: #"(\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*(\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3}"#)

    /// WebVTT, SRT cue timings, ASS/SSA, TTML or an HLS subtitle playlist.
    static func looksLikeSubtitles(_ head: String) -> Bool {
        let text = stripped(head)
        if text.hasPrefix("WEBVTT") || text.hasPrefix("#EXTM3U") { return true }
        if text.lowercased().hasPrefix("[script info]") { return true }
        if (text.hasPrefix("<?xml") || text.hasPrefix("<tt")) && text.contains("<tt") { return true }
        return timing.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    private static let imageCue = try! NSRegularExpression(
        pattern: #"^\S+\.(jpe?g|png|webp|gif|avif)(\?\S*)?(#xywh=\S*)?$|#xywh="#, options: .caseInsensitive)

    /// A WebVTT thumbnail track: most cues point at images (`sprite.jpg#xywh=…`) rather than dialogue.
    static func isImageTrack(_ cueTexts: [String]) -> Bool {
        let images = cueTexts.filter { imageCue.firstMatch(in: $0, range: NSRange($0.startIndex..., in: $0)) != nil }
        return !cueTexts.isEmpty && images.count * 2 > cueTexts.count
    }

    static func isWebVTT(_ head: String) -> Bool { stripped(head).hasPrefix("WEBVTT") }

    private static func stripped(_ head: String) -> String {
        var text = Substring(head)
        if text.first == "\u{FEFF}" { text = text.dropFirst() }
        return String(text.drop { $0.isWhitespace })
    }
}
