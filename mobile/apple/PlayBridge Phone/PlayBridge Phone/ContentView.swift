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
