import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @StateObject private var pageCasting = PageCastCoordinator()
    @StateObject private var atv = AtvRemoteCoordinator()
    @StateObject private var remoteTVBrowser = AtvBonjourBrowser()
    @State private var remoteTVScan: Task<Void, Never>?
    @State private var attemptedReconnectInForeground = false

    private var showsMiniBar: Bool {
        switch nav.currentScreen {
        case .browser: return false
        case .dashboard, .remote, .history, .bookmarks, .browserSettings:
            return false
        default:
            return true
        }
    }

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            AirPlayPlayerHost(player: vm.airPlay.player)
                .frame(width: 1, height: 1).opacity(0.01)
                .allowsHitTesting(false).accessibilityHidden(true)
            currentScreen
                .id(nav.currentScreen)
                .transition(.opacity)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if showsMiniBar {
                        NowPlayingMiniBar()
                    }
                }
        }
        .safeAreaInset(edge: .top, spacing: 0) { playbackFailureBanner }
        .environmentObject(pageCasting)
        .environmentObject(atv)
        .tint(Theme.primary)
        .fullScreenCover(item: $vm.websitePlayerItem) { item in
            FullScreenVideoPlayerView(session: item.session, diagnosticsReport: {
#if DEBUG
                item.report()
#else
                ""
#endif
            }, onDismiss: { vm.websitePlayerItem = nil })
        }
        .sheet(item: $nav.playOnRequest) { request in
            DeviceConnectionSheet(initialPage: request.page, openedForPicking: request.openedForPicking)
        }
        .sheet(item: Binding(get: { pageCasting.presentation }, set: { if $0 == nil { pageCasting.dismissPresentation() } })) { _ in
            PageCastRequestSheet(casting: pageCasting)
        }
        .alert("Couldn’t complete cast action", isPresented: Binding(
            get: { vm.operationError != nil }, set: { if !$0 { vm.operationError = nil } }
        )) {
            Button("OK", role: .cancel) { vm.operationError = nil }
        } message: { Text(vm.operationError ?? "") }
        .onAppear {
            vm.atvRemote = atv
            atv.reloadManagement()
            updateRemoteTVDiscovery()
            nav.browserStore = store
            store.onBridgedAppExternalNavigation = { [weak nav] in nav?.navigate(to: .browser) }
            pageCasting.attach(vm)
            pageCasting.onChooseDestination = { nav.openPlayOn() }
            pageCasting.onError = { [weak vm] in vm?.operationError = $0 }
            store.onWebsiteCast = { [weak pageCasting] tab, message in
                MainActor.assumeIsolated { pageCasting?.receive(message, from: tab) }
            }
            store.onPageCastInvalidated = { [weak pageCasting] tab in
                MainActor.assumeIsolated { pageCasting?.sourceInvalidated(tab) }
            }
            vm.onUserMediaAction = { [weak pageCasting] in
                MainActor.assumeIsolated { pageCasting?.userStartedCast() }
            }
            reconnectOnActivation()
            updateAtv()
        }
        .onReceive(atv.$records) { records in
            vm.remoteTVRecords = records
            Task { @MainActor in updateRemoteTVDiscovery() }
        }
        .onReceive(atv.$recordsLoaded) { vm.remoteTVRecordsLoaded = $0 }
        .onReceive(remoteTVBrowser.$devices) { devices in
            vm.remoteTVDiscoveries = devices
            atv.updateDiscovery(devices)
        }
        .onReceive(atv.$channel) { _ in
            Task { @MainActor in vm.objectWillChange.send(); vm.completePlayBridgeLaunchIfReady() }
        }
        .onReceive(vm.objectWillChange) { _ in
            Task { @MainActor in pageCasting.refresh(); updateAtv() }
        }
        .onReceive(vm.$pairedDevice) { device in updateAtv(selection: (vm.nativeReceiverSelected, device)) }
        .onReceive(vm.$nativeReceiverSelected) { selected in updateAtv(selection: (selected, vm.pairedDevice)) }
        .onReceive(nav.$currentScreen) { screen in updateAtv(screen: screen) }
        .onChange(of: vm.state) { state in presentPairingIfNeeded(state) }
        .onChange(of: scenePhase) { phase in
            if phase != .active { vm.pendingPlayBridgeLaunch = nil }
            updateAtv()
            updateRemoteTVDiscovery()
            if phase == .background {
                attemptedReconnectInForeground = false
            } else if phase == .active {
                reconnectOnActivation()
            }
        }
    }

    @ViewBuilder
    private var playbackFailureBanner: some View {
        if !vm.isExternalReceiver, !vm.isAirPlay, let failure = vm.coordinator.playbackFailure {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle").foregroundStyle(Theme.danger)
                    .accessibilityHidden(true)
                Text(verbatim: failure.message)
                    .font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button { vm.coordinator.dismissPlaybackFailure() } label: {
                    Image(systemName: "xmark")
                }.accessibilityLabel("Dismiss playback error")
            }
            .padding(14).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
    }

    private func updateRemoteTVDiscovery() {
        guard scenePhase == .active, !vm.remoteTVRecords.isEmpty else {
            remoteTVBrowser.stop(); remoteTVScan?.cancel(); remoteTVScan = nil
            vm.remoteTVScanComplete = false
            return
        }
        guard !remoteTVBrowser.isScanning else { return }
        remoteTVBrowser.start()
        remoteTVScan = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 4_000_000_000) } catch { return }
            vm.remoteTVScanComplete = true
        }
    }

    private func updateAtv(selection: (Bool, PairedDevice?)? = nil, screen: AppScreen? = nil) {
        let (selected, device) = selection ?? (vm.nativeReceiverSelected, vm.pairedDevice)
        let target = selected && !vm.isAirPlay && !vm.isExternalReceiver
            ? device.map { AtvReceiverTarget(playBridge: $0) } : nil
        atv.update(receiver: target, remoteVisible: (screen ?? nav.currentScreen) == .remote && vm.websitePlayerItem == nil, foreground: scenePhase != .background)
    }

    private func presentPairingIfNeeded(_ state: ConnectionState) {
        guard case .waitingForCodeInput = state else { return }
        guard nav.playOnRequest == nil, nav.playOnSuppressCount == 0 else { return }
        nav.openPlayOn(picking: false, page: .pairing)
    }

    private func reconnectOnActivation() {
        guard !attemptedReconnectInForeground else { return }
        attemptedReconnectInForeground = true
        vm.applicationBecameActive()
    }

    @ViewBuilder
    private var currentScreen: some View {
        switch nav.currentScreen {
        case .browser: BrowserScreen()
        case .dashboard: DashboardScreen()
        case .remote: RemoteControlScreen()
        case .phoneFiles: PhoneFilesScreen()
        case .iptv: IptvScreen()
        case .iptvDetail(let id): IptvDetailScreen(playlistId: id)
        case .collections: CollectionsScreen()
        case .collectionDetail(let id): CollectionDetailScreen(collectionId: id)
        case .castHistory: CastHistoryScreen()
        case .history: HistoryScreen()
        case .bookmarks: BookmarksScreen()
        case .browserSettings: BrowserSettingsScreen()
        }
    }
}
