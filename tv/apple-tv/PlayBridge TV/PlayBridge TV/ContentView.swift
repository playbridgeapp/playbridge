import AVKit
import Combine
import Foundation
import Network
import SwiftUI
import TVVLCKit
import UIKit





// MARK: - UI Components


struct ContentView: View {
    @StateObject private var historyStore = HistoryStore()
    @StateObject private var playlistStore = PlaylistStore()
    @StateObject private var server: WebSocketServer
    @State private var currentScreen: AppScreen = .pairing
    @State private var time = 0.0
    @State private var showingPrePlay: Bool = false
    let timer = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    init() {
        let hStore = HistoryStore()
        let pStore = PlaylistStore()
        _historyStore = StateObject(wrappedValue: hStore)
        _playlistStore = StateObject(wrappedValue: pStore)
        _server = StateObject(wrappedValue: WebSocketServer(historyStore: hStore, playlistStore: pStore))
    }

    var body: some View {
        ZStack {
            AuroraBackgroundView(time: time)
                .onReceive(timer) { _ in time += 0.01 }
                .edgesIgnoringSafeArea(.all)

            HStack(spacing: 0) {
                // Sidebar
                VStack(alignment: .leading, spacing: 10) {
                    BrandingView().padding(.bottom, 60).padding(.top, 40)

                    MenuButton(
                        title: "Pair Device", icon: "sensor.tag.radiowaves.forward",
                        currentScreen: $currentScreen, screen: .pairing)
                    MenuButton(
                        title: "History", icon: "clock.arrow.circlepath",
                        currentScreen: $currentScreen, screen: .history)
                    MenuButton(
                        title: "Favorites", icon: "star.fill", currentScreen: $currentScreen,
                        screen: .favorites)
                    MenuButton(
                        title: "Settings", icon: "gearshape.fill", currentScreen: $currentScreen,
                        screen: .settings)

                    Spacer()
                }
                .padding(.horizontal, 40)
                .frame(width: 400)
                .background(Color.black.opacity(0.3).edgesIgnoringSafeArea(.all))
                .disabled(server.currentPlayRequest != nil)

                // Main Content
                ZStack {
                    switch currentScreen {
                    case .pairing: PairingView()
                    case .history:
                        LibraryListView(
                            title: "History", items: historyStore.history)
                    case .favorites:
                        LibraryListView(
                            title: "Favorites",
                            items: historyStore.history.filter { $0.isFavorite })
                    case .settings: SettingsView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .disabled(server.currentPlayRequest != nil)
            }
            .disabled(server.currentPlayRequest != nil)

            if let request = server.currentPlayRequest {
                ZStack {
                    // Using the fixed PlayerView
                    PlayerView(request: request, isPreBuffering: showingPrePlay) {
                        withAnimation { server.currentPlayRequest = nil }
                    }
                    .id(request.url)  // Forces re-init if URL changes
                    .zIndex(5)
                    .edgesIgnoringSafeArea(.all)
                    .opacity(showingPrePlay ? 0 : 1) // Keep it invisible during pre-buffering
                    
                    if showingPrePlay, let metadata = request.visualMetadata {
                        PrePlayView(
                            metadata: metadata,
                            onStart: {
                                withAnimation { showingPrePlay = false }
                            },
                            onBack: {
                                withAnimation { server.currentPlayRequest = nil }
                            }
                        )
                        .zIndex(10)
                    }
                }
            }
        }
        .onAppear { server.start() }
        .onReceive(server.$currentPlayRequest) { request in
            if let request = request {
                if request.visualMetadata != nil {
                    showingPrePlay = true
                } else {
                    showingPrePlay = false
                }
            }
        }
        .environmentObject(historyStore)
        .environmentObject(playlistStore)
        .environmentObject(server)
    }
}

// MARK: - Focusable Components




