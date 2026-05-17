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
    @State private var playerStarted: Bool = false
    @Environment(\.scenePhase) private var scenePhase
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
                .focusSection()
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
                .focusSection()
                .disabled(server.currentPlayRequest != nil)
            }
            .disabled(server.currentPlayRequest != nil)

            // Instant black curtain — covers home screen the moment a request arrives,
            // in the same render pass, before PrePlayView has a chance to composite.
            if server.currentPlayRequest != nil {
                Color.black
                    .ignoresSafeArea()
                    .zIndex(4)
            }

            if let request = server.currentPlayRequest {
                let isPreBuffering = !playerStarted && request.hasVisualMetadata
                ZStack {
                    // PlayerView always renders so it can buffer in the background.
                    // isPreBuffering=true keeps it muted and UI-hidden during preplay.
                    PlayerView(payload: request, isPreBuffering: isPreBuffering) {
                        withAnimation { server.currentPlayRequest = nil }
                    }
                    .id(request.url)
                    .zIndex(5)
                    .edgesIgnoringSafeArea(.all)

                    if isPreBuffering, request.hasVisualMetadata {
                        PrePlayView(
                            metadata: request.visualMetadata,
                            onStart: {
                                withAnimation { playerStarted = true }
                            },
                            onBack: {
                                server.currentPlayRequest = nil
                                playerStarted = false
                            }
                        )
                        .zIndex(10)
                        .edgesIgnoringSafeArea(.all)
                    }
                }
                .zIndex(5)
            }
        }
        .onAppear { server.start() }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .active: server.restart()
            case .background: server.stop()
            default: break
            }
        }
        .onReceive(server.$currentPlayRequest) { request in
            // Reset playerStarted for every new incoming request
            playerStarted = false
        }
        .environmentObject(historyStore)
        .environmentObject(playlistStore)
        .environmentObject(server)
    }
}

// MARK: - Focusable Components




