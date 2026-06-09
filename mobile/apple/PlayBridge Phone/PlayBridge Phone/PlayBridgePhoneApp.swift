import SwiftUI

@main
struct PlayBridgePhoneApp: App {
    @StateObject private var vm = ConnectionViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(vm)
                .preferredColorScheme(.dark)
        }
    }
}
