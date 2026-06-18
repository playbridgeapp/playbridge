import SwiftUI

@main
struct PlayBridgePhoneApp: App {
    @StateObject private var vm = ConnectionViewModel()
    @StateObject private var nav = NavigationViewModel()
    @StateObject private var store = BrowserStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(vm)
                .environmentObject(nav)
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
