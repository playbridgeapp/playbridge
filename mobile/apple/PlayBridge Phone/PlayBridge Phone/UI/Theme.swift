import SwiftUI

/// Color tokens from `DESIGN.md` (Dark palette — indigo/violet brand). Tonal architecture:
/// hierarchy comes from surface shifts, not borders.
enum Theme {
    /// The same bundled regular face used by Android; weights are synthesized.
    static func font(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
        if design == .monospaced { return .system(size: size, weight: weight, design: design) }
        return .custom("Poppins-Regular", size: size, relativeTo: .body).weight(weight)
    }

    static func font(_ style: Font.TextStyle) -> Font {
        let size: CGFloat
        switch style {
        case .largeTitle: size = 34
        case .title: size = 28
        case .title2: size = 22
        case .title3: size = 20
        case .headline, .body: size = 17
        case .callout: size = 16
        case .subheadline: size = 15
        case .footnote: size = 13
        case .caption: size = 12
        case .caption2: size = 11
        @unknown default: size = 17
        }
        return .custom("Poppins-Regular", size: size, relativeTo: style)
            .weight(style == .headline ? .semibold : .regular)
    }

    static func configureTypography() {
        let navigation = UINavigationBar.appearance()
        navigation.titleTextAttributes = [.font: UIFont(name: "Poppins-Regular", size: 17) ?? UIFont.preferredFont(forTextStyle: .headline)]
        navigation.largeTitleTextAttributes = [.font: UIFont(name: "Poppins-Regular", size: 34) ?? UIFont.preferredFont(forTextStyle: .largeTitle)]
    }

    static let surface = Color(hex: 0x0D072E)
    static let surfaceContainerLow = Color(hex: 0x120C37)
    static let surfaceContainer = Color(hex: 0x181241)
    static let surfaceContainerHigh = Color(hex: 0x1E1748)
    static let surfaceContainerHighest = Color(hex: 0x241D54)
    static let surfaceBright = Color(hex: 0x2A2660)

    static let primary = Color(hex: 0x9EA7FF)
    static let primaryDim = Color(hex: 0x5565F2)
    static let onPrimary = Color(hex: 0x0D072E)
    static let primaryFixedDim = Color(hex: 0x7B84E0)
    static let secondaryContainer = Color(hex: 0x2E3480)
    static let onSecondaryContainer = Color(hex: 0xBFC6FF)

    /// Body text — never pure white, per DESIGN.md.
    static let onSurface = Color(hex: 0xE7E2FF)
    static let onSurfaceVariant = Color(hex: 0xB0A8D8)
    static let outlineVariant = Color(hex: 0x3D3770)

    static let danger = Color(hex: 0xFF6B6B)

    /// CTA gradient (primary_dim → primary).
    static let ctaGradient = LinearGradient(
        colors: [primaryDim, primary],
        startPoint: .leading, endPoint: .trailing
    )
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
