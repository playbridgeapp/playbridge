import SwiftUI

enum AppScreen: Hashable {
    case browser
    case dashboard
    case connection
    case remote
    case phoneFiles
    case iptv
    case iptvDetail(UUID)
    case collections
    case collectionDetail(UUID)
    case history
    case bookmarks
    case browserSettings
}

final class NavigationViewModel: ObservableObject {
    @Published var currentScreen: AppScreen = .browser
    @Published var lastMainScreen: AppScreen = .browser
    @Published var remoteOrigin: AppScreen? = nil
    @Published var dashboardOrigin: AppScreen? = nil

    func navigate(to target: AppScreen) {
        // Remember where the Remote or Dashboard was opened from
        if target == .remote && currentScreen != .remote {
            remoteOrigin = currentScreen
        }
        if target == .dashboard && currentScreen != .dashboard {
            dashboardOrigin = currentScreen
        }
        if target == .browser || target == .dashboard {
            lastMainScreen = target
        }
        
        withAnimation(.easeInOut(duration: 0.25)) {
            currentScreen = target
        }
    }
}
