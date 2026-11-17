import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var together: PhoneTogether
    @StateObject private var pageCasting = PageCastCoordinator()
    @StateObject private var atv = AtvRemoteCoordinator()
    @StateObject private var remoteTVBrowser = AtvBonjourBrowser()
    @State private var remoteTVScan: Task<Void, Never>?
    @State private var attemptedReconnectInForeground = false

    private var showsMiniBar: Bool {
        switch nav.currentScreen {
        case .browser: return false
        case .dashboard, .remote, .history, .bookmarks, .browserSettings, .watchTogether:
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
        .environmentObject(pageCasting)
        .environmentObject(atv)
        .tint(Theme.primary)
        .overlay(alignment: .bottom) {
            if let toast = together.toastMessage {
                Text(toast)
                    .font(Theme.font(.subheadline))
                    .foregroundStyle(Theme.onSurface)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Theme.surfaceContainerHigh, in: Capsule())
                    .overlay(Capsule().stroke(Theme.outlineVariant, lineWidth: 1))
                    .padding(.bottom, 16)
                    .padding(.horizontal, 16)
                    .allowsHitTesting(false)
                    // A new message is a new view, so its own timer clears it.
                    .id(toast)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.8) {
                            if together.toastMessage == toast {
                                together.consumeToast()
                            }
                        }
                    }
            }
        }
        .fullScreenCover(item: $vm.websitePlayerItem) { item in
            FullScreenVideoPlayerView(session: item.session, diagnosticsReport: {
#if DEBUG
                item.report()
#else
                ""
#endif
            }, onDismiss: { vm.websitePlayerItem = nil })
        }
        .fullScreenCover(item: $together.activePlayerItem, onDismiss: {
            together.userClosedPlayer()
        }) { item in
            FullScreenVideoPlayerView(
                session: item.session,
                diagnosticsReport: { "" },
                onDismiss: { together.userClosedPlayer() },
                autoPlayOnAppear: false,
                closeSessionOnDisappear: false
            )
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
        .alert(playbackFailureTitle, isPresented: Binding(
            get: { !vm.isExternalReceiver && !vm.isAirPlay && vm.coordinator.playbackFailure != nil },
            set: { if !$0 { vm.coordinator.dismissPlaybackFailure() } }
        )) {
            Button("OK", role: .cancel) { vm.coordinator.dismissPlaybackFailure() }
        } message: {
            Text(verbatim: vm.coordinator.playbackFailure?.message ?? "")
        }
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
            vm.linkedReceiverSite = { [weak pageCasting] in
                MainActor.assumeIsolated {
                    guard let pageCasting, pageCasting.isLinkedToReceiver else { return nil }
                    return pageCasting.controllerName
                }
            }
            vm.unlinkWebsite = { [weak pageCasting] in
                MainActor.assumeIsolated { pageCasting?.unlink() }
            }
            let party = together
            PartyMediaURL.ownAddresses = { LocalFileServer.lanIPAddress().map { [$0] } ?? [] }
            vm.isPartySelected = { [weak party] in
                MainActor.assumeIsolated { party?.isPartySelected == true }
            }
            vm.selectedPartyRoomId = { [weak party] in
                MainActor.assumeIsolated { party?.isPartySelected == true ? party?.currentParty?.roomId : nil }
            }
            vm.sendToParty = { [weak party] items, queue in
                MainActor.assumeIsolated { party?.send(items, queue: queue) == true }
            }
            vm.partyNotice = { [weak party] message in
                MainActor.assumeIsolated { party?.notify(message) }
            }
            reconnectOnActivation()
            updateAtv()
            if together.pendingOpenScreen {
                together.consumePendingOpenScreen()
                nav.navigate(to: .watchTogether)
            }
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
        .onReceive(together.$partyTargetSelected) { _ in
            // A linked website session aimed elsewhere ends when the party is picked.
            Task { @MainActor in pageCasting.refresh() }
        }
        .onReceive(vm.$pairedDevice) { device in updateAtv(selection: (vm.nativeReceiverSelected, device)) }
        .onReceive(vm.$nativeReceiverSelected) { selected in updateAtv(selection: (selected, vm.pairedDevice)) }
        .onReceive(nav.$currentScreen) { screen in updateAtv(screen: screen) }
        .onChange(of: vm.state) { state in presentPairingIfNeeded(state) }
        .onChange(of: together.pendingOpenScreen) { open in
            guard open else { return }
            together.consumePendingOpenScreen()
            nav.navigate(to: .watchTogether)
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { vm.pendingPlayBridgeLaunch = nil }
            updateAtv()
            updateRemoteTVDiscovery()
            if phase == .background {
                attemptedReconnectInForeground = false
                together.applicationEnteredBackground()
            } else if phase == .active {
                reconnectOnActivation()
                together.applicationBecameActive()
            }
        }
    }

    private var playbackFailureTitle: String {
        let name = PlayOnCatalog.displayName(vm.pairedDevice?.name ?? "")
        return "Couldn’t play on \(name.isEmpty ? "the TV" : name)"
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
        case .watchTogether: WatchTogetherScreen()
        }
    }
}
