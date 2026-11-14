import AVKit
import Combine
import Foundation
import Network
import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var historyStore: HistoryStore
    @StateObject private var playlistStore: PlaylistStore
    @StateObject private var server: WebSocketServer
    @StateObject private var appRemote = AppRemoteFocusDriver()
    @StateObject private var stillWatching = StillWatchingController()
    @ObservedObject private var together = TvTogether.shared

    @AppStorage("app_theme") var appThemeRaw: String = AppThemeMode.dark.rawValue
    @AppStorage("still_watching_enabled") var stillWatchingEnabled: Bool = false
    @AppStorage("still_watching_threshold_min") var stillWatchingThreshold: Int = StillWatchingController.defaultThreshold
    @AppStorage("still_watching_response_sec") var stillWatchingResponseSeconds: Int = StillWatchingController.defaultResponseSeconds

    @State private var currentScreen: AppScreen = .home
    @State private var settingsCategory: SettingsCategory = .playback
    @State private var playerStarted: Bool = false
    @Environment(\.scenePhase) private var scenePhase

    private var pairingPending: Bool { server.pendingPairingRequest != nil }

    private var currentTvColors: TvColors {
        switch AppThemeMode.fromStorage(appThemeRaw) {
        case .dark: return .dark
        case .amoled: return .amoled
        case .light: return .light
        }
    }

    init() {
        PlaybackEngine.migrateLegacyPreference(in: .standard)
        let hStore = HistoryStore()
        let pStore = PlaylistStore()
        _historyStore = StateObject(wrappedValue: hStore)
        _playlistStore = StateObject(wrappedValue: pStore)
        _server = StateObject(wrappedValue: WebSocketServer(historyStore: hStore, playlistStore: pStore))
    }

    var body: some View {
        ZStack {
            Group {
                switch currentScreen {
                case .home:
                    HomeView(
                        onOpenWatchTogether: { currentScreen = .watchTogether },
                        onOpenSettings: {
                            settingsCategory = .playback
                            currentScreen = .settings
                        }
                    )
                case .settings:
                    SettingsView(
                        selectedCategory: $settingsCategory,
                        onBack: { currentScreen = .home }
                    )
                case .watchTogether:
                    WatchTogetherView(
                        together: together,
                        onBack: { currentScreen = .home }
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .focusSection()
            .disabled(server.currentPlayRequest != nil || pairingPending)

            // Instant black curtain — covers home screen the moment a request arrives,
            // in the same render pass, before PrePlayView has a chance to composite.
            if server.currentPlayRequest != nil {
                Color.black
                    .ignoresSafeArea()
                    .zIndex(4)
            }

            if let request = server.currentPlayRequest {
                let isPreBuffering = !playerStarted &&
                    request.resolvedMediaKind == .video &&
                    (playlistStore.currentItem ?? request).resolvedMediaKind == .video &&
                    request.hasVisualMetadata &&
                    !server.skipPreplayForCurrentRequest

                let historyEntry = URL(string: request.url).flatMap { u in
                    historyStore.history.first { $0.url == u }
                }
                let savedPositionMs: Int64? = request.startPositionMs > 0
                    ? request.startPositionMs
                    : historyEntry?.resumePositionMs
                let nextIndex = playlistStore.currentIndex + 1
                let upNextItem: Playbridge_PlayPayload? = nextIndex < playlistStore.items.count
                    ? playlistStore.items[nextIndex]
                    : nil

                ZStack {
                    PlayerView(payload: request, isPreBuffering: isPreBuffering, stillWatching: stillWatching) {
                        server.progressWebhook.finish(clear: true)
                        playlistStore.clear()
                        stillWatching.reset()
                        withAnimation { server.currentPlayRequest = nil }
                    }
                    .id(server.playbackSessionID)
                    .zIndex(5)
                    .edgesIgnoringSafeArea(.all)

                    if isPreBuffering, request.hasVisualMetadata {
                        PrePlayView(
                            metadata: request.visualMetadata,
                            senderName: server.currentSenderName,
                            savedPositionMs: savedPositionMs,
                            durationMs: historyEntry?.durationMs,
                            upNextTitle: upNextItem?.titleOrNil,
                            upNextSubtitle: upNextItem.map { _ in "Up next · \(nextIndex + 1) of \(playlistStore.items.count)" },
                            onStart: { overridePositionMs in
                                if let overridePositionMs {
                                    var updated = request
                                    updated.startPositionMs = overridePositionMs
                                    server.currentPlayRequest = updated
                                } else if request.startPositionMs == 0, let savedPositionMs, savedPositionMs > 0 {
                                    var updated = request
                                    updated.startPositionMs = savedPositionMs
                                    server.currentPlayRequest = updated
                                }
                                withAnimation { playerStarted = true }
                            },
                            onBack: {
                                server.progressWebhook.finish(clear: true)
                                playlistStore.clear()
                                stillWatching.reset()
                                server.currentPlayRequest = nil
                                playerStarted = false
                            }
                        )
                        .zIndex(10)
                        .edgesIgnoringSafeArea(.all)
                    }
                }
                .zIndex(5)
                .disabled(pairingPending)
            }

            if let request = server.pendingPairingRequest {
                PairingRequestOverlay(
                    request: request,
                    overVideo: server.currentPlayRequest != nil
                )
                .zIndex(20)
            }
        }
        .environment(\.tvColors, currentTvColors)
        .background(AppRemoteAnchor(
            driver: appRemote,
            enabled: { AppRemoteInputGate.allows(foreground: scenePhase == .active,
                                                hasPlayer: server.currentPlayRequest != nil,
                                                pairingPending: pairingPending) },
            onBack: {
                if case .settings = currentScreen {
                    currentScreen = .home
                } else if case .watchTogether = currentScreen {
                    currentScreen = .home
                }
            },
            onHome: { currentScreen = .home },
            shouldInterceptMenu: {
                if let presented = appRemote.presentedController {
                    return !(presented is UIAlertController)
                }
                if pairingPending { return true }
                if server.currentPlayRequest != nil { return false }
                if currentScreen != .home { return true }
                return appRemote.hasNonRootScope
            },
            onMenu: {
                if pairingPending {
                    server.denyPairing()
                } else {
                    appRemote.handle("back")
                }
            }
        ))
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.remoteKey)) { note in
            guard !appRemote.isSuppressedForCurrentTurn,
                  AppRemoteInputGate.allows(foreground: scenePhase == .active,
                                           hasPlayer: server.currentPlayRequest != nil,
                                           pairingPending: pairingPending),
                  let key = note.userInfo?["key"] as? String else { return }
            appRemote.handle(key)
        }
        .onAppear {
            together.onPlayPartyItem = { [weak server] payload, _ in
                server?.playForWatchTogether(payload) ?? false
            }
            server.start()
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .active:
                server.start()
                stillWatching.foregroundChanged(true)
            case .background:
                historyStore.flushProgress()
                server.stop()
                stillWatching.foregroundChanged(false)
            default: break
            }
        }
        .onChange(of: stillWatchingEnabled) { _, _ in stillWatching.settingsChanged() }
        .onChange(of: stillWatchingThreshold) { _, _ in stillWatching.settingsChanged() }
        .onChange(of: stillWatchingResponseSeconds) { _, _ in stillWatching.settingsChanged() }
        .onChange(of: server.playRequestNonce) { _, _ in
            playerStarted = false
            stillWatching.reset()
        }
        .appRemoteScope(root: true)
        .environment(\.appRemoteDriver, appRemote)
        .environmentObject(historyStore)
        .environmentObject(playlistStore)
        .environmentObject(server)
    }
}
