import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @StateObject private var pageCasting = PageCastCoordinator()
    @State private var showDestinationPicker = false
    @State private var attemptedReconnectInForeground = false

    private var showsMiniBar: Bool {
        switch nav.currentScreen {
        case .browser: return pageCasting.isLinked
        case .dashboard, .connection, .remote, .history, .bookmarks, .browserSettings:
            return false
        default:
            return true
        }
    }

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            AirPlayPlayerHost(player: vm.airPlay.player)
                .frame(width: 1, height: 1).opacity(0.01)
                .allowsHitTesting(false).accessibilityHidden(true)
            currentScreen
                .id(nav.currentScreen)
                .transition(.opacity)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if showsMiniBar {
                        NowPlayingMiniBar(showDestinationPicker: $showDestinationPicker)
                    }
                }
        }
        .environmentObject(pageCasting)
        .tint(Theme.primary)
        .sheet(isPresented: $showDestinationPicker) { DeviceConnectionSheet() }
        .sheet(item: Binding(get: { pageCasting.presentation }, set: { if $0 == nil { pageCasting.dismissPresentation() } })) { _ in
            PageCastRequestSheet(casting: pageCasting)
        }
        .alert("Couldn’t complete cast action", isPresented: Binding(
            get: { vm.operationError != nil }, set: { if !$0 { vm.operationError = nil } }
        )) {
            Button("OK", role: .cancel) { vm.operationError = nil }
        } message: { Text(vm.operationError ?? "") }
        .onAppear {
            pageCasting.attach(vm)
            pageCasting.onError = { [weak vm] in vm?.operationError = $0 }
            store.onWebsiteCast = { [weak pageCasting] tab, message in
                MainActor.assumeIsolated { pageCasting?.receive(message, from: tab) }
            }
            store.onPageCastInvalidated = { [weak pageCasting] tab in
                MainActor.assumeIsolated { pageCasting?.sourceInvalidated(tab) }
            }
            vm.onUserMediaAction = { [weak pageCasting] in
                MainActor.assumeIsolated { pageCasting?.userStartedCast() }
            }
            reconnectOnActivation()
        }
        .onReceive(vm.objectWillChange) { _ in
            Task { @MainActor in pageCasting.refresh() }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                attemptedReconnectInForeground = false
            } else if phase == .active {
                reconnectOnActivation()
            }
        }
    }

    private func reconnectOnActivation() {
        guard !attemptedReconnectInForeground else { return }
        attemptedReconnectInForeground = true
        vm.applicationBecameActive()
    }

    @ViewBuilder
    private var currentScreen: some View {
        switch nav.currentScreen {
        case .browser: BrowserScreen()
        case .dashboard: DashboardScreen()
        case .connection: ConnectionScreen()
        case .remote: RemoteControlScreen()
        case .phoneFiles: PhoneFilesScreen()
        case .iptv: IptvScreen()
        case .iptvDetail(let id): IptvDetailScreen(playlistId: id)
        case .collections: CollectionsScreen()
        case .collectionDetail(let id): CollectionDetailScreen(collectionId: id)
        case .castHistory: CastHistoryScreen()
        case .history: HistoryScreen()
        case .bookmarks: BookmarksScreen()
        case .browserSettings: BrowserSettingsScreen()
        }
    }
}
