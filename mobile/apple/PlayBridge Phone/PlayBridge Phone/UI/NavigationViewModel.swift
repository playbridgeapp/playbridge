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
    case castHistory
    case history
    case bookmarks
    case browserSettings
}

final class NavigationViewModel: ObservableObject {
    @Published var currentScreen: AppScreen = .browser
    @Published var lastMainScreen: AppScreen = .browser
    @Published var remoteOrigin: AppScreen? = nil
    @Published var dashboardOrigin: AppScreen? = nil

    var dashboardSource: AppScreen? {
        dashboardOrigin == .remote ? remoteOrigin : dashboardOrigin
    }

    func navigate(to target: AppScreen) {
        // Remember where the Remote or Dashboard was opened from
        if target == .remote && currentScreen != .remote {
            remoteOrigin = currentScreen
        }
        if target == .dashboard && currentScreen != .dashboard {
            dashboardOrigin = currentScreen
        }
        if target == .browser || target == .connection {
            lastMainScreen = target
        }
        
        withAnimation(.easeInOut(duration: 0.25)) {
            currentScreen = target
        }
    }
}

/// Persistent top-level escape hatch used by every non-dashboard destination.
struct DashboardNavigationButton: View {
    @EnvironmentObject private var nav: NavigationViewModel

    var body: some View {
        Button { nav.navigate(to: .dashboard) } label: {
            Image(systemName: "square.grid.2x2.fill")
                .font(.system(size: 21, weight: .semibold))
                .foregroundColor(Theme.primary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Dashboard")
        .accessibilityHint("Opens the PlayBridge dashboard")
    }
}

/// Contextual back navigation for detail screens. The dashboard button remains first.
struct ScreenBackButton: View {
    @EnvironmentObject private var nav: NavigationViewModel
    let destination: AppScreen
    let accessibilityLabel: String

    var body: some View {
        Button { nav.navigate(to: destination) } label: {
            Image(systemName: "chevron.left")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(Theme.onSurface)
                .frame(width: 36, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }
}
