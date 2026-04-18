import Foundation

public enum FilterPreset: String, Codable, CaseIterable {
    case none = "NONE"
    case sepia = "SEPIA"
    case grayscale = "GRAYSCALE"
    case custom = "CUSTOM"

    var displayName: String {
        switch self {
        case .none: return "None"
        case .sepia: return "Sepia"
        case .grayscale: return "Grayscale"
        case .custom: return "Custom"
        }
    }
}

public struct ColorFilterSettings: Codable, Equatable {
    var brightness: Float = 0.0  // -1.0 to 1.0 (default 0)
    var contrast: Float = 1.0  // 0.0 to 4.0 (default 1.0)
    var saturation: Float = 1.0  // 0.0 to 2.0 (default 1.0)

    static let `default` = ColorFilterSettings()
}
