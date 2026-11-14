import Foundation

enum SettingsCategory: String, CaseIterable, Identifiable {
    case playback
    case subtitles
    case skip
    case phones
    case appearance
    case about
    case advanced

    var id: String { rawValue }

    var title: String {
        switch self {
        case .playback: return "Playback"
        case .subtitles: return "Subtitles"
        case .skip: return "Skip intros and recaps"
        case .phones: return "Phones"
        case .appearance: return "Appearance"
        case .about: return "About"
        case .advanced: return "Advanced"
        }
    }

    static var primaryCategories: [SettingsCategory] {
        [.playback, .subtitles, .skip, .phones, .appearance, .about]
    }
}

enum AppScreen: Equatable {
    case home
    case settings
    case watchTogether
}

enum SettingsFocusZone: Equatable {
    case sidebar
    case detail
}

enum SettingsBackOutcome: Equatable {
    case closeOverlay
    case returnToSidebar(SettingsCategory)
    case leaveSettings
}

enum SettingsFocusNavigation {
    static func zone(isCategoryID: Bool, hasFocus: Bool) -> SettingsFocusZone {
        (hasFocus && !isCategoryID) ? .detail : .sidebar
    }

    static func backOutcome(
        showingConnectPhone: Bool,
        zone: SettingsFocusZone,
        selectedCategory: SettingsCategory
    ) -> SettingsBackOutcome {
        if showingConnectPhone { return .closeOverlay }
        switch zone {
        case .detail: return .returnToSidebar(selectedCategory)
        case .sidebar: return .leaveSettings
        }
    }

    static func categorySelectionOnSidebarFocus(
        focusedCategory: SettingsCategory
    ) -> SettingsCategory {
        focusedCategory
    }
}
