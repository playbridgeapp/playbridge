import SwiftUI

@main
struct PlayBridgePhoneApp: App {
    @UIApplicationDelegateAdaptor(PlayBridgeAppDelegate.self) private var appDelegate
    @StateObject private var vm = ConnectionViewModel()
    @StateObject private var nav = NavigationViewModel()
    @StateObject private var store = BrowserStore()
    @StateObject private var iptv = IptvStore()
    @StateObject private var library = PhoneMediaLibrary()
    @StateObject private var collections = CollectionsStore()
    @StateObject private var together = PhoneTogether.shared

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
                .environmentObject(together)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    _ = together.handleIncomingURL(url, connectedTvName: vm.connectedPlayBridgeTvName)
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    guard let url = activity.webpageURL else { return }
                    _ = together.handleIncomingURL(url, connectedTvName: vm.connectedPlayBridgeTvName)
                }
        }
    }
}
