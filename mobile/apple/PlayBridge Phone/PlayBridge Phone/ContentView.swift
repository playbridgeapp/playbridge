import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()

            switch nav.currentScreen {
            case .browser:
                BrowserScreen()
                    .transition(.opacity)
            case .dashboard:
                DashboardScreen()
                    .transition(.opacity)
            case .connection:
                ConnectionScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .remote:
                RemoteControlScreen()
                    .transition(.move(edge: .bottom))
            case .phoneFiles:
                PhoneFilesScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .iptv:
                IptvScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .iptvDetail(let id):
                IptvDetailScreen(playlistId: id)
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .collections:
                CollectionsScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .collectionDetail(let id):
                CollectionDetailScreen(collectionId: id)
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .history:
                HistoryScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .bookmarks:
                BookmarksScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            case .browserSettings:
                BrowserSettingsScreen()
                    .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
            }
        }
        .tint(Theme.primary)
        .onAppear {
            // Auto-reconnect to a previously paired receiver on launch.
            if vm.pairedDevice != nil, !vm.state.isConnected {
                vm.reconnectSaved()
            }
        }
    }
}
