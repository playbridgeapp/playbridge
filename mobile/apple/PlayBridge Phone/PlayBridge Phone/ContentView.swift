import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showDestinationPicker = false
    @State private var attemptedReconnectInForeground = false

    private var showsMiniBar: Bool {
        switch nav.currentScreen {
        case .browser, .dashboard, .connection, .remote, .history, .bookmarks, .browserSettings:
            return false
        default:
            return true
        }
    }

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            currentScreen
                .id(nav.currentScreen)
                .transition(.opacity)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if showsMiniBar {
                        NowPlayingMiniBar(showDestinationPicker: $showDestinationPicker)
                    }
                }
        }
        .tint(Theme.primary)
        .sheet(isPresented: $showDestinationPicker) { DeviceConnectionSheet() }
        .alert("Couldn’t complete cast action", isPresented: Binding(
            get: { vm.operationError != nil }, set: { if !$0 { vm.operationError = nil } }
        )) {
            Button("OK", role: .cancel) { vm.operationError = nil }
        } message: { Text(vm.operationError ?? "") }
        .onAppear {
            reconnectOnActivation()
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
        vm.reconnectLastReceiverIfNeeded()
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
