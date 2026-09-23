import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            currentScreen
                .id(nav.currentScreen)
                .transition(.opacity)
        }
        .tint(Theme.primary)
        .alert("Couldn’t complete cast action", isPresented: Binding(
            get: { vm.operationError != nil }, set: { if !$0 { vm.operationError = nil } }
        )) {
            Button("OK", role: .cancel) { vm.operationError = nil }
        } message: { Text(vm.operationError ?? "") }
        .onAppear {
            // Auto-reconnect to a previously paired receiver on launch.
            if vm.pairedDevice != nil, !vm.state.isConnected {
                vm.reconnectSaved()
            }
        }
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
