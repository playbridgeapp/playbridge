import SwiftUI

@main
struct PlayBridgePhoneApp: App {
    @StateObject private var vm = ConnectionViewModel()
    @StateObject private var nav = NavigationViewModel()
    @StateObject private var store = BrowserStore()
    @StateObject private var iptv = IptvStore()
    @StateObject private var library = PhoneMediaLibrary()
    @StateObject private var collections = CollectionsStore()

    init() { Theme.configureTypography() }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .font(Theme.font(.body))
                .environmentObject(vm)
                .environmentObject(nav)
                .environmentObject(store)
                .environmentObject(store.data)
                .environmentObject(iptv)
                .environmentObject(collections)
                .environmentObject(library)
                .preferredColorScheme(.dark)
        }
    }
}
