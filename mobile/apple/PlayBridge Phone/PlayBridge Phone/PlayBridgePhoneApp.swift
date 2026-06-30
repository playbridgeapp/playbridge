import SwiftUI

@main
struct PlayBridgePhoneApp: App {
    @StateObject private var vm = ConnectionViewModel()
    @StateObject private var nav = NavigationViewModel()
    @StateObject private var store = BrowserStore()
    @StateObject private var iptv = IptvStore()
    @StateObject private var collections = CollectionsStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(vm)
                .environmentObject(nav)
                .environmentObject(store)
                .environmentObject(store.data)
                .environmentObject(iptv)
                .environmentObject(collections)
                .preferredColorScheme(.dark)
        }
    }
}
