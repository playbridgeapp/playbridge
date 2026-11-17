import Foundation

/// A font file the user picked, checked before it is installed or sent.
enum PickedSubtitleFont: Equatable {
    case font(Data, family: String)
    case rejected(String)

    /// Reads and checks a picked file. Blocking; call off the main thread.
    static func read(_ url: URL) -> PickedSubtitleFont {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .rejected("Couldn't open that file.") }
        defer { try? handle.close() }
        // One byte over the limit is enough to know the font is too large.
        guard let data = try? handle.read(upToCount: SubtitleFontLimits.maxFontBytes + 1) else {
            return .rejected("Couldn't open that file.")
        }
        return check(data)
    }

    static func check(_ data: Data) -> PickedSubtitleFont {
        if data.count > SubtitleFontLimits.maxFontBytes { return .rejected(message(for: "too_large")) }
        guard let family = SubtitleFontParser.readFontFamily(data) else { return .rejected(message(for: "invalid_font")) }
        return .font(data, family: family)
    }

    /// A sentence for a `subtitle_font_result` or font library error code.
    static func message(for code: String?, toTV: Bool = true) -> String {
        switch code {
        case "too_large": return "Fonts over 10 MB can't be used. Pick a smaller font file."
        case "invalid_font": return "That file isn't a TrueType or OpenType font."
        case "font_limit":
            return "There are already \(SubtitleFontLimits.maxFonts) fonts or 30 MB of fonts. Remove one first."
        default: return toTV ? "Couldn't send the font to the TV. Try again." : "Couldn't add that font. Try again."
        }
    }
}
