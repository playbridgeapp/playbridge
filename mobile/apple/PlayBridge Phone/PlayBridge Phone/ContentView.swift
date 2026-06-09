import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var vm: ConnectionViewModel

    var body: some View {
        TabView {
            BrowserScreen()
                .tabItem { Label("Browse", systemImage: "globe") }

            castTab
                .tabItem { Label("Cast", systemImage: "tv") }
        }
        .tint(Theme.primary)
        .onAppear {
            // Auto-reconnect to a previously paired receiver on launch.
            if vm.pairedDevice != nil, !vm.state.isConnected {
                vm.reconnectSaved()
            }
        }
    }

    /// The Cast tab shows the remote dashboard when connected, otherwise the connect/pair flow.
    private var castTab: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            if vm.state.isConnected {
                DashboardScreen()
            } else {
                ConnectionScreen()
            }
        }
    }
}
