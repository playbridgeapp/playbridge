import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

enum TvTone {
    case app
    case player
}

/// Themed colors for the PlayBridge TV redesign (matching `Color.kt` / `Tokens.kt`).
struct TvColors {
    let surface: Color
    let containerLow: Color
    let container: Color
    let containerHigh: Color
    let containerHighest: Color
    let bright: Color
    let outline: Color
    let onSurface: Color
    let onSurfaceMuted: Color
    let onSurfaceDim: Color
    let accent: Color
    let onAccent: Color
    let accentContainer: Color
    let onAccentContainer: Color
    let tileBlue: Color
    let onTileBlue: Color
    let tilePlum: Color
    let onTilePlum: Color
    let focusFill: Color
    let onFocusFill: Color
    let focusRing: Color
    let success: Color
    let warning: Color
    let error: Color
    let onError: Color
    let errorContainer: Color
    let onErrorContainer: Color

    static let dark = TvColors(
        surface: Color(hex: 0x0D072E),
        containerLow: Color(hex: 0x120C37),
        container: Color(hex: 0x181241),
        containerHigh: Color(hex: 0x1E1748),
        containerHighest: Color(hex: 0x241D54),
        bright: Color(hex: 0x2A2660),
        outline: Color(hex: 0x3D3770),
        onSurface: Color(hex: 0xE7E2FF),
        onSurfaceMuted: Color(hex: 0xB0A8D8),
        onSurfaceDim: Color(hex: 0x6E6A9A),
        accent: Color(hex: 0x9EA7FF),
        onAccent: Color(hex: 0x0D072E),
        accentContainer: Color(hex: 0x2E3480),
        onAccentContainer: Color(hex: 0xDFE1FF),
        tileBlue: Color(hex: 0x343C8C),
        onTileBlue: Color(hex: 0xDFE1FF),
        tilePlum: Color(hex: 0x5C3E62),
        onTilePlum: Color(hex: 0xFFD7FA),
        focusFill: Color(hex: 0xFFFFFF),
        onFocusFill: Color(hex: 0x0D072E),
        focusRing: Color(hex: 0xFFFFFF),
        success: Color(hex: 0x7BDCA0),
        warning: Color(hex: 0xF2C14E),
        error: Color(hex: 0xE8696E),
        onError: Color(hex: 0x3A0A0C),
        errorContainer: Color(hex: 0x6E1C20),
        onErrorContainer: Color(hex: 0xFFDAD8)
    )

    static let amoled = TvColors(
        surface: Color(hex: 0x000000),
        containerLow: Color(hex: 0x06051A),
        container: Color(hex: 0x0C0A28),
        containerHigh: Color(hex: 0x121038),
        containerHighest: Color(hex: 0x181548),
        bright: Color(hex: 0x1E1A58),
        outline: dark.outline,
        onSurface: dark.onSurface,
        onSurfaceMuted: dark.onSurfaceMuted,
        onSurfaceDim: dark.onSurfaceDim,
        accent: dark.accent,
        onAccent: dark.onAccent,
        accentContainer: dark.accentContainer,
        onAccentContainer: dark.onAccentContainer,
        tileBlue: dark.tileBlue,
        onTileBlue: dark.onTileBlue,
        tilePlum: dark.tilePlum,
        onTilePlum: dark.onTilePlum,
        focusFill: dark.focusFill,
        onFocusFill: dark.onFocusFill,
        focusRing: dark.focusRing,
        success: dark.success,
        warning: dark.warning,
        error: dark.error,
        onError: dark.onError,
        errorContainer: dark.errorContainer,
        onErrorContainer: dark.onErrorContainer
    )

    static let light = TvColors(
        surface: Color(hex: 0xF4F1FF),
        containerLow: Color(hex: 0xEDE9FF),
        container: Color(hex: 0xE3DEFF),
        containerHigh: Color(hex: 0xD8D2FF),
        containerHighest: Color(hex: 0xCCC5FF),
        bright: Color(hex: 0xFFFFFF),
        outline: Color(hex: 0xC0BBE0),
        onSurface: Color(hex: 0x0A0720),
        onSurfaceMuted: Color(hex: 0x403A6A),
        onSurfaceDim: Color(hex: 0x8A86B0),
        accent: Color(hex: 0x3040CC),
        onAccent: Color(hex: 0xFFFFFF),
        accentContainer: Color(hex: 0xC5CAFF),
        onAccentContainer: Color(hex: 0x0D1780),
        tileBlue: Color(hex: 0xDDE1FF),
        onTileBlue: Color(hex: 0x00105C),
        tilePlum: Color(hex: 0xF2D6F5),
        onTilePlum: Color(hex: 0x2A0E30),
        focusFill: Color(hex: 0x0A0720),
        onFocusFill: Color(hex: 0xF4F1FF),
        focusRing: Color(hex: 0x0A0720),
        success: Color(hex: 0x1B7F4B),
        warning: Color(hex: 0x9A6A00),
        error: Color(hex: 0xB3261E),
        onError: Color(hex: 0xFFFFFF),
        errorContainer: Color(hex: 0xF9DEDC),
        onErrorContainer: Color(hex: 0x410E0B)
    )
}

/// Neutral near-black player tones; identical across themes because they sit over video.
enum TvPlayerColors {
    static let background = Color(hex: 0x0B0B10)
    static let panel = Color(hex: 0x17161F)
    static let row = Color(hex: 0x23222E)
    static let control = Color(hex: 0x2A2836)
    static let track = Color(hex: 0x3A3848)
    static let buffer = Color(hex: 0x6E6A86)
    static let divider = Color(hex: 0x33323F)
    static let onSurface = Color(hex: 0xFFFFFF)
    static let onSurfaceMuted = Color(hex: 0xC9C6DA)
    static let onSurfaceDim = Color(hex: 0x8F8AA8)
    static let focusFill = Color(hex: 0xFFFFFF)
    static let onFocusFill = Color(hex: 0x0D072E)
    static let focusRing = Color(hex: 0xFFFFFF)
    static let accent = TvColors.dark.accent
    static let accentContainer = TvColors.dark.accentContainer
    static let onAccentContainer = TvColors.dark.onAccentContainer
    static let scrimTopAlpha: Double = 0.72
    static let scrimBottomAlpha: Double = 0.80
}

/// Mockups are 1280x720; tvOS is 1920x1080 points (1.5x scale).
enum TvScale {
    static let factor: CGFloat = 1.5
    static func pt(_ mockupPx: CGFloat) -> CGFloat { mockupPx * factor }
}

enum TvRadius {
    static let bar: CGFloat = TvScale.pt(3)
    static let seekBar: CGFloat = TvScale.pt(4)
    static let chip: CGFloat = TvScale.pt(17)
    static let thumbSmall: CGFloat = TvScale.pt(8)
    static let row: CGFloat = TvScale.pt(14)
    static let tile: CGFloat = TvScale.pt(14)
    static let option: CGFloat = TvScale.pt(16)
    static let code: CGFloat = TvScale.pt(18)
    static let card: CGFloat = TvScale.pt(24)
}

enum TvSpacing {
    static let xxs: CGFloat = TvScale.pt(2)
    static let xs: CGFloat = TvScale.pt(4)
    static let s: CGFloat = TvScale.pt(8)
    static let m: CGFloat = TvScale.pt(12)
    static let l: CGFloat = TvScale.pt(16)
    static let xl: CGFloat = TvScale.pt(20)
    static let xxl: CGFloat = TvScale.pt(24)
    static let xxxl: CGFloat = TvScale.pt(32)
    static let screenGutter: CGFloat = TvScale.pt(56)
    static let screenTop: CGFloat = TvScale.pt(36)
    static let cornerInset: CGFloat = TvScale.pt(48)
    static let playerEdge: CGFloat = TvScale.pt(40)
    static let panelPaddingV: CGFloat = TvScale.pt(40)
    static let panelPaddingH: CGFloat = TvScale.pt(36)
    static let rowGap: CGFloat = TvScale.pt(8)
}

enum TvSize {
    static let pillHeight: CGFloat = TvScale.pt(56)
    static let pillHeightCompact: CGFloat = TvScale.pt(48)
    static let iconButtonSmall: CGFloat = TvScale.pt(48)
    static let iconButton: CGFloat = TvScale.pt(60)
    static let iconButtonLarge: CGFloat = TvScale.pt(72)
    static let listRow: CGFloat = TvScale.pt(60)
    static let chapterRow: CGFloat = TvScale.pt(64)
    static let queueRow: CGFloat = TvScale.pt(92)
    static let settingsRow: CGFloat = TvScale.pt(84)
    static let navRow: CGFloat = TvScale.pt(56)
    static let chip: CGFloat = TvScale.pt(34)
    static let sidePanelWidth: CGFloat = TvScale.pt(480)
    static let cornerCardWidth: CGFloat = TvScale.pt(480)
    static let pairingCardWidth: CGFloat = TvScale.pt(440)
    static let seekBarHeight: CGFloat = TvScale.pt(8)
    static let seekThumbSize: CGFloat = TvScale.pt(22)
    static let controlLabelSlot: CGFloat = TvScale.pt(220)
    static let focusRingWidth: CGFloat = TvScale.pt(4)
    static let focusRingGap: CGFloat = TvScale.pt(3)
}

enum TvText {
    static let pageTitle = Font.system(size: TvScale.pt(42), weight: .bold)
    static let screenTitle = Font.system(size: TvScale.pt(36), weight: .bold)
    static let panelTitle = Font.system(size: TvScale.pt(30), weight: .semibold)
    static let seekTime = Font.system(size: TvScale.pt(34), weight: .semibold).monospacedDigit()
    static let cardHeadline = Font.system(size: TvScale.pt(28), weight: .bold)
    static let cardTitle = Font.system(size: TvScale.pt(24), weight: .semibold)
    static let sectionHeading = Font.system(size: TvScale.pt(22), weight: .semibold)
    static let settingTitle = Font.system(size: TvScale.pt(20), weight: .medium)
    static let row = Font.system(size: TvScale.pt(19), weight: .medium)
    static let rowSelected = Font.system(size: TvScale.pt(19), weight: .semibold)
    static let button = Font.system(size: TvScale.pt(18), weight: .semibold)
    static let value = Font.system(size: TvScale.pt(17), weight: .medium)
    static let caption = Font.system(size: TvScale.pt(16), weight: .regular)
    static let small = Font.system(size: TvScale.pt(15), weight: .regular)
    static let micro = Font.system(size: TvScale.pt(14), weight: .medium)
}

private struct TvColorsKey: EnvironmentKey {
    static let defaultValue = TvColors.dark
}

extension EnvironmentValues {
    var tvColors: TvColors {
        get { self[TvColorsKey.self] }
        set { self[TvColorsKey.self] = newValue }
    }
}
