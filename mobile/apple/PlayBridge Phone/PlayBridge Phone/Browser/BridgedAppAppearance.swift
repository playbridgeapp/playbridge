import Foundation

/// Keep ids, order and RGB stops identical to BridgedAppAppearance.kt.
enum BridgedAppAppearance {
    struct PaletteColor: Equatable {
        let id: String
        let name: String
        let dark: UInt32
        let light: UInt32
    }
    static let palette: [PaletteColor] = [
        .init(id: "pine", name: "Pine", dark: 0x153D2C, light: 0x23573F),
        .init(id: "teal", name: "Teal", dark: 0x16433F, light: 0x235E58),
        .init(id: "olive", name: "Olive", dark: 0x3E4218, light: 0x565C24),
        .init(id: "ochre", name: "Ochre", dark: 0x513B13, light: 0x6C501D),
        .init(id: "brick", name: "Brick", dark: 0x5E2428, light: 0x7C353B),
        .init(id: "cranberry", name: "Cranberry", dark: 0x581F36, light: 0x763047),
        .init(id: "plum", name: "Plum", dark: 0x46284F, light: 0x603B6B),
        .init(id: "indigo", name: "Indigo", dark: 0x2B335B, light: 0x404D7B),
        .init(id: "ocean", name: "Ocean", dark: 0x193E57, light: 0x295773),
        .init(id: "graphite", name: "Graphite", dark: 0x343840, light: 0x4B515B),
    ]

    static func paletteID(_ value: String?) -> String? { palette.first { $0.id == value }?.id }

    static func paletteIndex(_ origin: URL) -> Int {
        // Android stores origins without a trailing slash; URL's origin has one.
        let text = origin.absoluteString.hasSuffix("/") ? String(origin.absoluteString.dropLast()) : origin.absoluteString
        var hash: UInt32 = 2166136261
        for byte in text.utf8 { hash = (hash ^ UInt32(byte)) &* 16777619 }
        return Int(hash % UInt32(palette.count))
    }

    static func parseTheme(_ value: String?) -> UInt32? {
        guard let value, value.first == "#" else { return nil }
        let digits = String(value.dropFirst())
        guard (digits.count == 3 || digits.count == 6),
              digits.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { return nil }
        let expanded = digits.count == 3 ? digits.map { "\($0)\($0)" }.joined() : digits
        return UInt32(expanded, radix: 16)
    }

    static func themeColor(_ value: String?) -> String? { parseTheme(value) == nil ? nil : value }

    static func contrast(_ rgb: UInt32) -> Double {
        func linear(_ channel: UInt32) -> Double {
            let value = Double(channel) / 255
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        let luminance = 0.2126 * linear((rgb >> 16) & 255) +
            0.7152 * linear((rgb >> 8) & 255) + 0.0722 * linear(rgb & 255)
        return 1.05 / (luminance + 0.05)
    }

    private static func scale(_ rgb: UInt32, _ factor: Double) -> UInt32 {
        (UInt32(Double((rgb >> 16) & 255) * factor) << 16) |
            (UInt32(Double((rgb >> 8) & 255) * factor) << 8) | UInt32(Double(rgb & 255) * factor)
    }

    static func themeGradient(_ rgb: UInt32) -> [UInt32] {
        var light = rgb
        while contrast(light) < 4.5 { light = scale(light, 0.99) }
        light = (max((light >> 16) & 255, 12) << 16) | (max((light >> 8) & 255, 12) << 8) | max(light & 255, 12)
        while contrast(light) < 4.5 { light = scale(light, 0.99) }
        return [scale(light, 0.76), light]
    }

    static func gradient(_ app: BridgedApp) -> [UInt32] {
        if let color = palette.first(where: { $0.id == app.tileColorID }) { return [color.dark, color.light] }
        if let rgb = parseTheme(app.themeColor) { return themeGradient(rgb) }
        let color = palette[paletteIndex(app.origin)]
        return [color.dark, color.light]
    }

    static func iconCandidates(_ app: BridgedApp) -> [URL] {
        var seen = Set<URL>()
        return [app.iconURL, app.origin.appendingPathComponent("favicon.ico")].compactMap { $0 }
            .filter { BridgedAppDeclaration.origin(of: $0) == app.origin && seen.insert($0).inserted }
    }

    static func monogram(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).first.map { String($0).uppercased() } ?? "?"
    }
}
