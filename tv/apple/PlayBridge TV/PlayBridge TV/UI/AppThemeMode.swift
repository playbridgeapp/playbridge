import Foundation

/// App-chrome theme options shown in Settings -> Appearance.
/// Player chrome stays dark regardless of this setting.
enum AppThemeMode: String, CaseIterable, Identifiable {
    case dark
    case amoled
    case light

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dark: return "Dark"
        case .amoled: return "Black, for OLED"
        case .light: return "Light"
        }
    }

    static func fromStorage(_ raw: String?) -> AppThemeMode {
        guard let raw, let mode = AppThemeMode(rawValue: raw) else { return .dark }
        return mode
    }
}
