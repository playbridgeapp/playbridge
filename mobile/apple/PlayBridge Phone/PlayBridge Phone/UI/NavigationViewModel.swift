import SwiftUI

struct PlayOnRequest: Identifiable, Equatable {
    let id = UUID()
    var page: PlayOnPage?
    var openedForPicking: Bool
}

enum AppScreen: Hashable {
    case browser
    case dashboard
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
    // A new app process opens the hub; foregrounding retains the current screen.
    @Published var currentScreen: AppScreen = .dashboard
    @Published var lastMainScreen: AppScreen = .browser
    @Published var remoteOrigin: AppScreen? = nil
    @Published var dashboardOrigin: AppScreen? = nil
    /// Where Settings was opened from: the Browser menu or the Dashboard top bar.
    @Published var settingsOrigin: AppScreen? = nil
    @Published var playOnRequest: PlayOnRequest?
    @Published var pendingAtvPairing = false
    /// The mini player's Up next pill: the Remote opens with Up next showing.
    @Published var openRemoteQueue = false
    var playOnSuppressCount = 0
    /// Banner dismissals last for this process, including after the sheet closes and reopens.
    var playOnDismissedBanners = Set<String>()

    func openPlayOn(picking: Bool = true, page: PlayOnPage? = nil) {
        playOnRequest = PlayOnRequest(page: page, openedForPicking: picking)
    }
    weak var browserStore: BrowserStore?
    private var remoteAppTabID: UUID?
    private var dashboardAppTabID: UUID?

    var dashboardSource: AppScreen? {
        dashboardOrigin == .remote ? remoteOrigin : dashboardOrigin
    }

    func navigate(to target: AppScreen) {
        // Remember where the Remote or Dashboard was opened from
        if target == .remote && currentScreen != .remote {
            remoteOrigin = currentScreen
            remoteAppTabID = currentScreen == .browser && browserStore?.activeBridgedApp != nil
                ? browserStore?.activeID : nil
        }
        if target == .dashboard && currentScreen != .dashboard {
            dashboardOrigin = currentScreen
            dashboardAppTabID = currentScreen == .browser && browserStore?.activeBridgedApp != nil
                ? browserStore?.activeID : nil
        }
        if target == .browserSettings && currentScreen != .browserSettings {
            settingsOrigin = currentScreen
        }
        if target == .browser {
            lastMainScreen = target
        }
        
        withAnimation(.easeInOut(duration: 0.25)) {
            currentScreen = target
        }
    }

    func openBrowser() {
        browserStore?.showBrowser()
        navigate(to: .browser)
    }

    func returnFromRemote() {
        returnTo(remoteOrigin ?? .dashboard, appTabID: remoteAppTabID)
    }

    func returnFromDashboard() {
        returnTo(dashboardOrigin ?? .browser, appTabID: dashboardAppTabID)
    }

    private func returnTo(_ screen: AppScreen, appTabID: UUID?) {
        if screen == .browser {
            if let id = appTabID {
                guard browserStore?.restoreBridgedApp(id) == true else { navigate(to: .dashboard); return }
            } else { browserStore?.showBrowser() }
        }
        navigate(to: screen)
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
