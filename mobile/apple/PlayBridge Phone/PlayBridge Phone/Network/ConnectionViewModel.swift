import Foundation
import Combine
import Network

/// Top-level glue the UI observes: owns discovery, the socket, the inbound coordinator, and
/// credential persistence. Mirrors the role of `ConnectionViewModel` on Android.
final class ConnectionViewModel: ObservableObject {
    let browser = BonjourBrowser()
    let googleCastBrowser = GoogleCastBrowser()
    let dlnaBrowser = DLNABrowser()
    let rokuBrowser = DLNABrowser(kind: .roku)
    let dialBrowser = DLNABrowser(kind: .dial)
    let googleCast = GoogleCastController()
    let airPlay = AirPlayController()
    var isAirPlay: Bool { airPlay.selected }
    @Published private(set) var externalReceiver: ExternalReceiverDevice?
    @Published private(set) var savedExternalReceiverDevices: [ExternalReceiverDevice] = []
    @Published var operationError: String?
    var isExternalReceiver: Bool { externalReceiver != nil }
    var supportsQueue: Bool { !isExternalReceiver }
    var supportsBrowser: Bool { !isAirPlay && !isExternalReceiver && pairedDevice?.browsers.isEmpty == false }
    func supportsNativeMediaKind(_ kind: String) -> Bool {
        if isAirPlay { return kind == "video" || kind == "audio" }
        return isExternalReceiver || kind == "video" || pairedDevice?.mediaKinds?.contains(kind) == true
    }
    var destinationID: String? { isAirPlay ? "airplay" : externalReceiver.map { $0.identity } ?? pairedDevice.map(deviceKey) }
    var receiverName: String? {
        if isAirPlay { return airPlay.routeName }
        if case .connected(let name, _) = state { return name }
        return externalReceiver?.name ?? pairedDevice?.name
    }
    let castHistory = CastHistoryStore()
    /// A manual media/queue action takes playlist ownership back from a website.
    var onUserMediaAction: (() -> Void)?
    let ws = WebSocketClient()
    let coordinator = ConnectionCoordinator()
    let castPlaybackSession = CastPlaybackSession(renderer: CastSystemPlayback.shared)
    private var externalMediaKind = "video"

    @Published var state: ConnectionState = .disconnected
    @Published var pairedDevice: PairedDevice?
    /// All TVs we've paired with (history), most-recent first.
    @Published var savedDevices: [PairedDevice] = []
    /// Reachability of saved TVs, keyed by `deviceKey`.
    @Published var onlineStatus: [String: Bool] = [:]

    private let store = PairingStore.shared
    private var routedStreamRegistrations: [PhoneProxyRegistration] = []
    private var subtitleFileServers: [LocalFileServer] = []
    private var pendingSubtitleConfirmations: [String: AnyCancellable] = [:]
    private var cancellables = Set<AnyCancellable>()
    /// The device we're currently bringing up, so we can persist a full record once paired.
    private var connectingDevice: DiscoveredDevice?
    private var savedReconnectDiscovery: AnyCancellable?
    private var savedReconnectGeneration: UUID?
    private var savedReconnectTimeout: DispatchWorkItem?
    private var savedEndpointRefreshTimeout: DispatchWorkItem?
    private var discoveryOwners = 0
    private static let savedReconnectDiscoveryTimeout: TimeInterval = 10

    func deviceKey(_ d: PairedDevice) -> String { d.uuid.isEmpty ? "\(d.ip):\(d.port)" : d.uuid }

    init() {
        airPlay.onDestinationSelected = { [weak self] in self?.adoptAirPlayDestination() }
        airPlay.onUpdate = { [weak self] in self?.updateAirPlayState() }
        airPlay.onStop = { [weak self] in self?.releaseCastResources() }
        CastSystemPlayback.shared.onLocalPlaybackBegan = { [weak self] in self?.airPlay.suspendForLocalPlayback() }
        airPlay.objectWillChange.receive(on: RunLoop.main).sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        airPlay.$error.compactMap { $0 }.receive(on: RunLoop.main).sink { [weak self] in self?.operationError = $0 }.store(in: &cancellables)
        castPlaybackSession.onCommand = { [weak self] command in
            guard let self, self.isConnected else { return false }
            self.control(command)
            return true
        }
        castPlaybackSession.onReconnect = { [weak self] in self?.reconnectActiveCast() }
        castPlaybackSession.onReleaseResources = { [weak self] in self?.releaseCastResources() }
        CastSystemPlayback.shared.onAction = { [weak self] action in
            self?.castPlaybackSession.perform(action) ?? false
        }
        googleCast.onReceiverEnded = { [weak self] in
            guard let self, isExternalReceiver else { return }
            castPlaybackSession.stopLocally()
            releaseCastResources()
            coordinator.clear()
        }
        if let data = UserDefaults.standard.data(forKey: "google_cast_saved_devices") {
            savedExternalReceiverDevices = (try? JSONDecoder().decode([ExternalReceiverDevice].self, from: data)) ?? []
        }
        pairedDevice = store.loadPairedDevice()
        savedDevices = store.loadSavedDevices()
        // Migrate a pre-existing single paired device into the history list.
        if savedDevices.isEmpty, let p = pairedDevice {
            savedDevices = [p]
            store.saveSavedDevices(savedDevices)
        }

        ws.onMessage = { [weak self] text in guard let self, !isExternalReceiver, !isAirPlay else { return }; coordinator.handle(text) }
        ws.onCredentials = { [weak self] creds in self?.persistCredentials(creds) }
        ws.onCapabilities = { [weak self] caps in self?.persistCapabilities(caps) }

        // Re-publish nested object changes so views observing the VM refresh.
        ws.$state
            .receive(on: RunLoop.main)
            .sink { [weak self] value in
                guard let self, !isExternalReceiver, !isAirPlay else { return }
                state = value
                handleCastConnectionState(value)
                if !value.isConnected {
                    pendingSubtitleConfirmations.values.forEach { $0.cancel() }
                    pendingSubtitleConfirmations.removeAll()
                }
            }
            .store(in: &cancellables)
        googleCast.$state.receive(on: RunLoop.main).sink { [weak self] value in
            guard let self, isExternalReceiver else { return }
            state = value
            handleCastConnectionState(value)
            if case .connected(let name, _) = value, var device = externalReceiver {
                device.name = name
                externalReceiver = device
                savedExternalReceiverDevices.removeAll { $0.identity == device.identity }
                savedExternalReceiverDevices.insert(device, at: 0)
                savedExternalReceiverDevices = Array(savedExternalReceiverDevices.prefix(20))
                UserDefaults.standard.set(try? JSONEncoder().encode(savedExternalReceiverDevices), forKey: "google_cast_saved_devices")
            }
            // The receiver may still be fetching media while its control socket
            // reconnects. The casting session owns bounded retention and cleanup.
        }.store(in: &cancellables)
        googleCast.$playback.receive(on: RunLoop.main).sink { [weak self] playback in
            guard let self, isExternalReceiver, let playback else { return }
            coordinator.playback = playback
            coordinator.activeContext = playback.state == "stopped" ? "idle" : "player"
        }.store(in: &cancellables)
        rokuBrowser.objectWillChange.receive(on: RunLoop.main).sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        dialBrowser.objectWillChange.receive(on: RunLoop.main).sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        dlnaBrowser.objectWillChange.receive(on: RunLoop.main).sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        googleCastBrowser.objectWillChange.receive(on: RunLoop.main).sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        coordinator.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
        coordinator.$playback
            .receive(on: RunLoop.main)
            .sink { [weak self] playback in self?.refreshCastPlayback(playback) }
            .store(in: &cancellables)
        browser.$devices
            .receive(on: RunLoop.main)
            .sink { [weak self] devices in self?.refreshSavedEndpoints(devices) }
            .store(in: &cancellables)
        browser.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    // MARK: - Discovery

    func startDiscovery() {
        discoveryOwners += 1
        guard discoveryOwners == 1 else { return }
        browser.start(owner: .userInterface)
        googleCastBrowser.start()
        dlnaBrowser.start()
        rokuBrowser.start()
    }

    func stopDiscovery() {
        guard discoveryOwners > 0 else { return }
        discoveryOwners -= 1
        guard discoveryOwners == 0 else { return }
        browser.stop(owner: .userInterface)
        googleCastBrowser.stop()
        dlnaBrowser.stop()
        rokuBrowser.stop()
        dialBrowser.stop()
    }

    // MARK: - Connect

    /// Connect to a device found via Bonjour. Reuses a saved token/pin if we've paired with it.
    func connect(to device: DiscoveredDevice) {
        let sameReceiver = !isExternalReceiver && pairedDevice.map { saved in
            (!device.uuid.isEmpty && saved.uuid == device.uuid) || (saved.ip == device.ip && saved.port == device.port)
        } == true
        usePlayBridge(preservingPlayback: sameReceiver && castPlaybackSession.isActive)
        endSavedReconnectDiscovery()
        connectingDevice = device
        let saved = matchingSaved(for: device)
        ws.connect(
            ip: device.ip,
            port: device.port,
            token: saved?.token ?? "",
            serverName: device.name,
            deviceName: store.localDeviceName,
            deviceUUID: store.localDeviceUUID,
            wssPort: device.wssPort,
            certFingerprint: saved?.certFingerprint
        )
    }

    func connectExternalReceiver(_ device: ExternalReceiverDevice, preservingPlayback: Bool = false) {
        guard device.protocolID != "dial" else { operationError = "DIAL devices require a supported receiver app; generic video sending is unavailable."; return }
        airPlay.disconnect()
        let preservingPlayback = (preservingPlayback || castPlaybackSession.isActive) && externalReceiver?.identity == device.identity
        if !preservingPlayback { endCastSession() }
        endSavedReconnectDiscovery()
        let device = (googleCastBrowser.devices + dlnaBrowser.devices + rokuBrowser.devices).first { $0.identity == device.identity } ?? device
        externalReceiver = device
        connectingDevice = nil
        ws.disconnect()
        if !preservingPlayback {
            coordinator.clear()
            coordinator.activeContext = "idle"
        }
        operationError = nil
        state = .connecting
        UserDefaults.standard.set("google_cast", forKey: "last_receiver_protocol")
        googleCast.connect(device)
    }

    func forgetExternalReceiver(_ device: ExternalReceiverDevice) {
        savedExternalReceiverDevices.removeAll { $0.identity == device.identity }
        UserDefaults.standard.set(try? JSONEncoder().encode(savedExternalReceiverDevices), forKey: "google_cast_saved_devices")
    }

    private func usePlayBridge(preservingPlayback: Bool = false) {
        airPlay.disconnect()
        if !preservingPlayback { endCastSession() }
        externalReceiver = nil
        googleCast.disconnect()
        if !preservingPlayback { routedStreamRegistrations.removeAll() }
        UserDefaults.standard.set("playbridge", forKey: "last_receiver_protocol")
    }

    /// Manual IP entry (no Bonjour). Pairs if we have no token for this address.
    func connectManual(ip: String, port: Int = ProtocolConstants.defaultPort, wssPort: Int? = nil) {
        let device = DiscoveredDevice(ip: ip, port: port, name: ip, wssPort: wssPort)
        connect(to: device)
    }

    /// Retry the last chosen receiver after launch or a return from the background.
    /// An explicit disconnect selects this phone, and pairing/security failures need
    /// user action rather than an automatic retry.
    func reconnectLastReceiverIfNeeded() {
        if isAirPlay { airPlay.refreshRoute(); return }
        switch state {
        case .disconnected, .error:
            break
        default:
            return
        }

        let route = UserDefaults.standard.string(forKey: "last_receiver_protocol")
        if route == "google_cast" {
            guard GoogleCastNativeAvailability.isAvailable else { return }
            switch googleCast.state {
            case .disconnected, .error:
                if let device = externalReceiver ?? savedExternalReceiverDevices.first {
                    connectExternalReceiver(device, preservingPlayback: castPlaybackSession.isActive)
                }
            default:
                break
            }
        } else if route == nil || route == "playbridge" {
            guard pairedDevice != nil else { return }
            switch ws.state {
            case .disconnected, .error:
                reconnectSaved()
            default:
                break
            }
        }
    }

    /// Reconnect to the previously paired PlayBridge device.
    func reconnectSaved() {
        let route = UserDefaults.standard.string(forKey: "last_receiver_protocol")
        guard route != "google_cast", route != "this_phone" else { return }
        guard let saved = pairedDevice else { return }
        endSavedReconnectDiscovery()
        connectingDevice = DiscoveredDevice(ip: saved.ip, port: saved.port, name: saved.name,
                                            uuid: saved.uuid, wssPort: saved.wssPort)
        ws.connect(
            ip: saved.ip,
            port: saved.port,
            token: saved.token ?? "",
            serverName: saved.name,
            deviceName: store.localDeviceName,
            deviceUUID: store.localDeviceUUID,
            wssPort: saved.wssPort,
            certFingerprint: saved.certFingerprint
        )
        beginSavedReconnectDiscovery(for: saved)
    }

    /// Connect to a specific saved TV from the history list.
    func connectSaved(_ device: PairedDevice) {
        let current = savedDevices.first { deviceKey($0) == deviceKey(device) } ?? device
        let device = SavedReceiverEndpoint.refresh(current, from: browser.devices)
        let sameReceiver = !isExternalReceiver && pairedDevice.map { deviceKey($0) == deviceKey(device) } == true
        usePlayBridge(preservingPlayback: sameReceiver && castPlaybackSession.isActive)
        endSavedReconnectDiscovery()
        pairedDevice = device
        connectingDevice = DiscoveredDevice(ip: device.ip, port: device.port, name: device.name,
                                            uuid: device.uuid, wssPort: device.wssPort)
        ws.connect(
            ip: device.ip,
            port: device.port,
            token: device.token ?? "",
            serverName: device.name,
            deviceName: store.localDeviceName,
            deviceUUID: store.localDeviceUUID,
            wssPort: device.wssPort,
            certFingerprint: device.certFingerprint
        )
        beginSavedReconnectDiscovery(for: device)
    }

    /// Keep launch reconnect discovery independent of the discovery screen. The stored
    /// endpoint is tried immediately; a live Bonjour endpoint for the same UUID replaces
    /// it once, without disturbing the receiver's token, pin, or capabilities.
    private func beginSavedReconnectDiscovery(for saved: PairedDevice) {
        guard !saved.uuid.isEmpty else { return }

        let generation = UUID()
        savedReconnectGeneration = generation
        browser.start(owner: .savedReconnect)
        savedReconnectDiscovery = browser.$devices
            .receive(on: RunLoop.main)
            .sink { [weak self] devices in
                guard let live = devices.first(where: { $0.uuid == saved.uuid }) else { return }
                // `$devices` can synchronously emit its current value while the
                // cancellable is still being assigned. Defer handling so cleanup can
                // always cancel the installed subscription and prevent duplicate retries.
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.savedReconnectGeneration == generation else { return }
                    self.handleSavedReconnectEndpoint(live, replacing: saved)
                }
            }

        let timeout = DispatchWorkItem { [weak self] in
            self?.endSavedReconnectDiscovery()
        }
        savedReconnectTimeout = timeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Self.savedReconnectDiscoveryTimeout,
            execute: timeout
        )
    }

    private func handleSavedReconnectEndpoint(
        _ live: DiscoveredDevice,
        replacing saved: PairedDevice
    ) {
        let refreshed = SavedReceiverEndpoint.refresh(saved, from: [live])
        guard !SavedReceiverEndpoint.sameAddress(refreshed, saved) else { return }
        endSavedReconnectDiscovery()
        refreshSavedEndpoints([live])
        // Refresh the next connection without interrupting a healthy active session.
        guard !ws.state.isConnected else { return }
        pairedDevice = refreshed
        connectingDevice = DiscoveredDevice(
            ip: refreshed.ip,
            port: refreshed.port,
            name: refreshed.name,
            uuid: refreshed.uuid,
            wssPort: refreshed.wssPort
        )
        ws.connect(
            ip: refreshed.ip,
            port: refreshed.port,
            token: refreshed.token ?? "",
            serverName: refreshed.name,
            deviceName: store.localDeviceName,
            deviceUUID: store.localDeviceUUID,
            wssPort: refreshed.wssPort,
            certFingerprint: refreshed.certFingerprint
        )
    }

    private func endSavedReconnectDiscovery() {
        savedReconnectGeneration = nil
        savedReconnectTimeout?.cancel()
        savedReconnectTimeout = nil
        savedReconnectDiscovery?.cancel()
        savedReconnectDiscovery = nil
        browser.stop(owner: .savedReconnect)
    }

    /// Remove one saved TV from history (and disconnect if it's the active one).
    func forget(_ device: PairedDevice) {
        endSavedReconnectDiscovery()
        var list = store.loadSavedDevices()
        list.removeAll { deviceKey($0) == deviceKey(device) }
        store.saveSavedDevices(list)
        savedDevices = list
        onlineStatus[deviceKey(device)] = nil
        if let active = pairedDevice, deviceKey(active) == deviceKey(device) {
            ws.disconnect()
            store.clearPairedDevice()
            pairedDevice = nil
            connectingDevice = nil
        }
    }

    /// A bounded Bonjour refresh updates saved UUIDs even in the saved-device sheet.
    /// External discovery remains confined to the setup/discovery screen.
    func pingSavedDevices() {
        if savedDevices.contains(where: { !$0.uuid.isEmpty }) {
            if savedEndpointRefreshTimeout == nil { browser.start(owner: .savedDevices) }
            savedEndpointRefreshTimeout?.cancel()
            let timeout = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.browser.stop(owner: .savedDevices)
                self.savedEndpointRefreshTimeout = nil
            }
            savedEndpointRefreshTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: timeout)
        }
        refreshSavedEndpoints(browser.devices)
        savedDevices.forEach(checkReachability)
    }

    private func refreshSavedEndpoints(_ devices: [DiscoveredDevice]) {
        let refreshed = savedDevices.map { SavedReceiverEndpoint.refresh($0, from: devices) }
        let changedAddresses = zip(savedDevices, refreshed).compactMap { old, new in
            SavedReceiverEndpoint.sameAddress(old, new) ? nil : new
        }
        if refreshed != savedDevices {
            savedDevices = refreshed
            store.saveSavedDevices(refreshed)
        }
        if let current = pairedDevice {
            let updated = SavedReceiverEndpoint.refresh(current, from: devices)
            if updated != current {
                pairedDevice = updated
                store.savePairedDevice(updated)
            }
        }
        for device in changedAddresses {
            onlineStatus[deviceKey(device)] = nil
            checkReachability(device)
        }
    }

    private func checkReachability(_ device: PairedDevice) {
        let key = deviceKey(device)
        guard let port = UInt16(exactly: device.wssPort ?? device.port), port > 0 else {
            onlineStatus[key] = false
            return
        }
        Self.isReachable(host: device.ip, port: port) { [weak self] reachable in
            guard let self,
                  let current = self.savedDevices.first(where: { self.deviceKey($0) == key }),
                  SavedReceiverEndpoint.sameAddress(current, device) else { return }
            // A probe to the previous IP must not overwrite the new endpoint's status.
            self.onlineStatus[key] = reachable
        }
    }

    private static func isReachable(host: String, port: UInt16, timeout: TimeInterval = 2.0, completion: @escaping (Bool) -> Void) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { completion(false); return }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        var finished = false
        func finish(_ ok: Bool) {
            if finished { return }
            finished = true
            conn.cancel()
            completion(ok)
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready: finish(true)
            case .failed, .cancelled: finish(false)
            default: break
            }
        }
        conn.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { finish(false) }
    }

    private func upsertSaved(_ device: PairedDevice) {
        var list = store.loadSavedDevices()
        list.removeAll { deviceKey($0) == deviceKey(device) }
        list.insert(device, at: 0)
        store.saveSavedDevices(list)
        savedDevices = list
    }

    func disconnect() {
        onUserMediaAction?()
        airPlay.disconnect()
        state = .disconnected
        endCastSession()
        UserDefaults.standard.set("this_phone", forKey: "last_receiver_protocol")
        pendingSubtitleConfirmations.values.forEach { $0.cancel() }
        pendingSubtitleConfirmations.removeAll()
        subtitleFileServers.forEach { $0.stop() }
        subtitleFileServers.removeAll()
        if isExternalReceiver {
            // Clear routing intent before the transport publishes its disconnect state.
            externalReceiver = nil
            googleCast.disconnect()
            state = .disconnected
            coordinator.clear()
            coordinator.activeContext = "idle"
            routedStreamRegistrations.removeAll()
            return
        }
        routedStreamRegistrations.removeAll()
        endSavedReconnectDiscovery()
        ws.disconnect()
    }

    /// Submit the 6-digit SAS code the user read off the TV during pairing.
    func submitPairingCode(_ code: String) { ws.submitPairingCode(code) }

    func forgetDevice() {
        endCastSession()
        if let active = pairedDevice { forget(active); return }
        ws.disconnect()
        store.clearPairedDevice()
        connectingDevice = nil
    }

    // MARK: - Commands

    var isConnected: Bool { state.isConnected }
    var supportsQueueV1: Bool {
        let features = Set(pairedDevice?.features ?? [])
        return ["queue_crud_v1", "stable_item_ids", "command_results"]
            .allSatisfy(features.contains)
    }

    func cast(urlString: String, title: String? = nil) {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onUserMediaAction?()
        if isAirPlay { sendAirPlayURL(trimmed, title: title, headers: [:], contentType: nil); return }
        if isExternalReceiver { sendExternalReceiverURL(trimmed, title: title, contentType: nil, headers: [:]); return }
        SenderDebugNetwork.request("Cast output", url: trimmed)
        sendMediaCommand(WireProtocol.singleVideoCommand(url: trimmed, title: title))
    }

    /// Local library media is already served by this phone; do not wrap its LAN URL in a remote proxy.
    func castLocalMedia(url: String, title: String, contentType: String) {
        onUserMediaAction?()
        if isAirPlay { sendAirPlayURL(url, title: title, headers: [:], contentType: contentType, local: true); return }
        let kind = contentType.hasPrefix("image/") ? "image" : contentType.hasPrefix("audio/") ? "audio" : "video"
        noteNewCast(mediaKind: kind, title: title)
        if isExternalReceiver, let mediaURL = URL(string: url) {
            Task { @MainActor in
                do { try await googleCast.load(url: mediaURL, title: title, contentType: contentType) }
                catch { operationError = "Couldn’t send this file to the connected device. Check the connection and supported media formats." }
            }
        } else { ws.send(WireProtocol.singleVideoCommand(url: url, title: title, contentType: contentType, mediaKind: kind)) }
    }

    /// Cast an arbitrary media URL with optional request headers (IPTV channels and
    /// saved collection items, which may require a Referer/User-Agent).
    func castMedia(url: String, title: String? = nil, headers: [String: String] = [:], contentType: String? = nil) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onUserMediaAction?()
        if isAirPlay { sendAirPlayURL(trimmed, title: title, headers: headers, contentType: contentType); return }
        if isExternalReceiver { sendExternalReceiverURL(trimmed, title: title, contentType: contentType, headers: headers); return }
        SenderDebugNetwork.request("Cast output", url: trimmed, headers: headers)
        sendMediaCommand(WireProtocol.singleVideoCommand(
            url: trimmed,
            title: title,
            contentType: contentType,
            subtitles: [],
            headers: headers,
            detectedBy: "iptv"
        ))
    }

    /// Cast a browser-detected stream: chosen quality URL (or the master), `mediaHeaders`,
    /// attached subtitles. Mirrors the Android `CastSheet` → `createSingleVideoCommandJson` path.
    func castStream(_ video: DetectedVideo, quality: VideoQuality? = nil, subtitles: [String] = [], playerMode: String? = nil) {
        onUserMediaAction?()
        if isAirPlay {
            sendAirPlayURL(quality?.url ?? video.url, title: video.displayTitle,
                headers: VideoDetector.mediaHeaders(for: video), contentType: video.contentType,
                subtitles: subtitles.compactMap { URL(string: $0).map { AirPlaySubtitleSource(url: $0, headers: [:], title: "Subtitle") } })
            return
        }
        if isExternalReceiver {
            guard subtitles.isEmpty else { operationError = "External subtitles are not supported by this receiver adapter yet."; return }
            sendExternalReceiverURL(quality?.url ?? video.url, title: video.displayTitle, contentType: video.contentType, headers: VideoDetector.mediaHeaders(for: video))
            return
        }
        let url = quality?.url ?? video.url
        let headers = VideoDetector.mediaHeaders(for: video)
        let kind = video.isImage ? "image" : video.isAudio ? "audio" : "video"
        SenderDebugNetwork.request("Browser cast output", url: url, headers: headers)
        sendMediaCommand(WireProtocol.singleVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            mediaKind: kind,
            headers: headers,
            detectedBy: video.detectedBy,
            playerMode: playerMode
        ))
    }

    /// Retain phone routes beyond sheet dismissal and across queued items.
    @MainActor
    func sendRoutedStream(_ media: RoutedStream, video: DetectedVideo, subtitles: [RoutedStream], queue: Bool, subtitleTitles: [String] = [], airPlayRequest: UUID? = nil) async throws {
        onUserMediaAction?()
        let kind = video.isImage ? "image" : video.isAudio ? "audio" : "video"
        let contentType = video.contentType ?? ((video.isImage || video.isAudio)
            ? URL(string: video.url).map(LocalFileServer.mimeType(for:)) : nil)
        let historyCommand = WireProtocol.singleVideoCommand(
            url: media.sourceURL ?? video.url, title: video.displayTitle,
            contentType: contentType, subtitles: subtitles.compactMap(\.sourceURL),
            subtitleResources: subtitles.map { sub in
                ["url": sub.sourceURL ?? sub.url.absoluteString,
                 "headers": sub.sourceURL == nil ? sub.headers : sub.sourceHeaders] as [String: Any]
            },
            mediaKind: kind,
            headers: media.sourceURL == nil ? VideoDetector.mediaHeaders(for: video) : media.sourceHeaders,
            detectedBy: video.detectedBy)
        if isAirPlay {
            let request = airPlayRequest ?? airPlay.beginRequest(queue: queue)
            guard airPlay.generation == request else { throw CancellationError() }
            let routed = try await prepareAirPlayMedia(media, contentType: contentType)
            guard isAirPlay else { throw CancellationError() }
            let sources = subtitles.enumerated().map { index, sub in
                AirPlaySubtitleSource(url: sub.url, headers: sub.headers,
                    title: subtitleTitles.indices.contains(index) ? subtitleTitles[index] : "Subtitle \(index + 1)")
            }
            try await airPlay.send(media: routed, title: video.displayTitle, kind: kind, subtitles: sources, queue: queue, request: request)
            if !queue { recordCast(historyCommand) }
            return
        }
        if isExternalReceiver {
            guard !queue, subtitles.isEmpty else { throw StreamRoutingError.message("Queueing and external subtitles are not supported by this receiver adapter yet.") }
            let target = destinationID
            noteNewCast(mediaKind: kind, title: video.displayTitle)
            try await googleCast.load(url: media.url, title: video.displayTitle, contentType: video.kind == .hls ? "application/vnd.apple.mpegurl" : contentType)
            guard destinationID == target, isExternalReceiver, googleCast.state.isConnected else { throw CancellationError() }
            routedStreamRegistrations = [media.registration].compactMap { $0 }
            recordCast(historyCommand)
            return
        }
        if !queue {
            noteNewCast(mediaKind: kind, title: video.displayTitle)
            routedStreamRegistrations.removeAll()
        }
        routedStreamRegistrations.append(contentsOf: ([media] + subtitles).compactMap(\.registration))
        let urls = subtitles.map { $0.url.absoluteString }
        let resources: [[String: Any]] = subtitles.map { subtitle in
            var resource: [String: Any] = ["url": subtitle.url.absoluteString]
            if !subtitle.headers.isEmpty { resource["headers"] = subtitle.headers }
            return resource
        }
        let message = queue
            ? WireProtocol.queueVideoCommand(url: media.url.absoluteString, title: video.displayTitle,
                contentType: contentType, subtitles: urls, subtitleResources: resources,
                mediaKind: kind, headers: media.headers, detectedBy: video.detectedBy)
            : WireProtocol.singleVideoCommand(url: media.url.absoluteString, title: video.displayTitle,
                contentType: contentType, subtitles: urls, subtitleResources: resources,
                mediaKind: kind, headers: media.headers, detectedBy: video.detectedBy)
        if ws.send(message) { recordCast(historyCommand) }
    }

    /// Queue a browser-detected stream.
    func queueStream(_ video: DetectedVideo, quality: VideoQuality? = nil, subtitles: [String] = [], playerMode: String? = nil) {
        onUserMediaAction?()
        if isAirPlay {
            sendAirPlayURL(quality?.url ?? video.url, title: video.displayTitle,
                headers: VideoDetector.mediaHeaders(for: video), contentType: video.contentType, queue: true,
                subtitles: subtitles.compactMap { URL(string: $0).map { AirPlaySubtitleSource(url: $0, headers: [:], title: "Subtitle") } })
            return
        }
        guard !isExternalReceiver else { operationError = "Queueing is not supported by this receiver adapter yet."; return }
        let url = quality?.url ?? video.url
        let headers = VideoDetector.mediaHeaders(for: video)
        let kind = video.isImage ? "image" : video.isAudio ? "audio" : "video"
        SenderDebugNetwork.request("Browser queue output", url: url, headers: headers)
        sendMediaCommand(WireProtocol.queueVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            mediaKind: kind,
            headers: headers,
            detectedBy: video.detectedBy,
            playerMode: playerMode,
            playbackId: supportsQueueV1 ? coordinator.playlist?.playbackId : nil,
            useQueueV1: supportsQueueV1
        ))
    }

    /// Open a URL on the TV browser.
    func browseTo(url: String, browserMode: String? = nil, desktopMode: Bool = false) {
        guard !isExternalReceiver else { operationError = "This receiver cannot open a browser page."; return }
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onUserMediaAction?()
        SenderDebugNetwork.request("Browser command output", url: trimmed)
        ws.send(WireProtocol.browserCommand(
            url: trimmed,
            browserMode: browserMode,
            desktopMode: desktopMode
        ))
    }

    func control(_ command: String) {
        if command == "stop" || command == "end_receiver" { onUserMediaAction?() }
        if isAirPlay {
            switch command {
            case "play": airPlay.play()
            case "pause": airPlay.pause()
            case "play_pause": airPlay.toggle()
            case "stop": airPlay.stop()
            case "next": airPlay.next()
            case "seek_back": airPlay.seek(airPlay.position - 10)
            case "seek_forward": airPlay.seek(airPlay.position + 10)
            default:
                if command.hasPrefix("seek_to:"), let ms = Double(command.dropFirst(8)) { airPlay.seek(ms / 1000) }
                if command.hasPrefix("sub_track:") { airPlay.selectSubtitle(Int(command.dropFirst(10))) }
                if command.hasPrefix("audio_track:"), let id = Int(command.dropFirst(12)) { airPlay.selectAudio(id) }
            }
            return
        }
        guard isConnected else { operationError = "Reconnect to the receiver to control playback."; return }
        if command == "play" {
            castPlaybackSession.allowNewPlayback()
            CastSystemPlayback.shared.userRequestedPlayback()
        }
        guard isExternalReceiver else {
            if ws.send(WireProtocol.controlCommand(command)), command == "stop" {
                castPlaybackSession.stopLocally()
                releaseCastResources()
            }
            return
        }
        let target = destinationID
        Task { @MainActor in
            do {
                try await googleCast.control(command)
                if destinationID == target, command == "stop" || command == "end_receiver" {
                    castPlaybackSession.stopLocally()
                    releaseCastResources()
                }
            }
            catch { operationError = error.localizedDescription }
        }
    }

    func addSubtitle(url: String, headers: [String: String] = [:], label: String? = nil) -> Bool {
        guard isConnected, !isExternalReceiver else { return false }
        if isAirPlay {
            guard let source = URL(string: url), ["http", "https"].contains(source.scheme?.lowercased() ?? ""),
                  airPlay.current != nil, !airPlay.preparing else { return false }
            airPlay.addSubtitle(.init(url: source, headers: headers, title: label ?? "Added subtitle"))
            return true
        }
        guard pairedDevice?.features?.contains("subtitle_resource_add_v1") == true else {
            return headers.isEmpty && ws.send(WireProtocol.controlCommand("add_subtitle:\(url)"))
        }
        let requestID = UUID().uuidString
        guard ws.send(WireProtocol.addSubtitleCommand(url: url, headers: headers, label: label,
                                                      requestID: requestID)) else { return false }
        pendingSubtitleConfirmations[requestID] = coordinator.$lastCommandResult
            .compactMap { $0 }
            .filter { $0.requestId == requestID }
            .first()
            .sink { [weak self] result in
                guard let self else { return }
                self.pendingSubtitleConfirmations.removeValue(forKey: requestID)
                if !result.ok {
                    self.operationError = result.error == "no_active_playback" ? "No video is playing on the receiver."
                        : result.error == "subtitle_unavailable" ? "The receiver could not load this subtitle."
                        : "The receiver did not accept this subtitle."
                }
            }
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self, let pending = self.pendingSubtitleConfirmations.removeValue(forKey: requestID) else { return }
            pending.cancel()
            self.operationError = "The receiver did not confirm this subtitle."
        }
        return true
    }

    func serveLocalSubtitle(_ fileURL: URL) async -> String? {
        let server = LocalFileServer()
        guard let url = await server.serve(fileURL: fileURL) else { return nil }
        if subtitleFileServers.count >= 16 { subtitleFileServers.removeFirst().stop() }
        subtitleFileServers.append(server)
        return url
    }

    func setCastVolume(_ level: Double) {
        guard isExternalReceiver else { return }
        Task { @MainActor in
            do { try await googleCast.setVolume(level) }
            catch { operationError = error.localizedDescription }
        }
    }

    private func sendExternalReceiverURL(_ url: String, title: String?, contentType: String?, headers: [String: String]) {
        let target = destinationID
        let route = StreamRoute(rawValue: UserDefaults.standard.string(forKey: "stream_route_default") ?? "direct") ?? .direct
        let configuration = StreamProxySettingsStore.load()
        Task { @MainActor in
            do {
                let media = try await StreamRouteService().prepare(url: url, headers: headers, contentType: contentType, route: route, configuration: configuration)
                guard destinationID == target, isExternalReceiver else { return }
                if media.registration != nil && media.url.host == "127.0.0.1" { throw StreamRoutingError.message("Connect to Wi-Fi to send via phone.") }
                noteNewCast(mediaKind: contentType?.hasPrefix("image/") == true ? "image" : contentType?.hasPrefix("audio/") == true ? "audio" : "video", title: title)
                try await googleCast.load(url: media.url, title: title, contentType: contentType)
                guard destinationID == target, isExternalReceiver else { return }
                routedStreamRegistrations = [media.registration].compactMap { $0 }
                recordCast(WireProtocol.singleVideoCommand(url: url, title: title, contentType: contentType, headers: headers))
            } catch { if destinationID == target { operationError = error.localizedDescription } }
        }
    }
    func browserControl(_ action: String) {
        guard isConnected, supportsBrowser else { return }
        ws.send(WireProtocol.browserControlCommand(action))
    }

    func remote(_ key: String) {
        guard isConnected else { return }
        guard isExternalReceiver else { ws.send(WireProtocol.remoteCommand(key: key)); return }
        switch key {
        case "volume_up": adjustExternalVolume(up: true)
        case "volume_down": adjustExternalVolume(up: false)
        default:
            if externalReceiver?.protocolID == "roku" { sendRokuKeypress(key) }
        }
    }

    func jump(to item: PlaylistEpisode) {
        if isAirPlay {
            if let itemId = item.itemId, let id = UUID(uuidString: itemId) { airPlay.jump(to: id) }
            return
        }
        ws.send(WireProtocol.playlistJumpCommand(
            index: item.index,
            itemId: supportsQueueV1 ? item.itemId : nil,
            playbackId: supportsQueueV1 ? coordinator.playlist?.playbackId : nil,
            useQueueV1: supportsQueueV1
        ))
    }
    func queryQueue() {
        guard supportsQueueV1 else { queryContext(); return }
        ws.send(WireProtocol.queueQuery())
    }
    func removeFromQueue(itemIds: [String]) {
        guard supportsQueueV1, !itemIds.isEmpty else { return }
        onUserMediaAction?()
        ws.send(WireProtocol.queueRemove(
            itemIds: itemIds,
            playbackId: coordinator.playlist?.playbackId
        ))
    }
    func moveInQueue(itemId: String, beforeItemId: String?) {
        guard supportsQueueV1 else { return }
        onUserMediaAction?()
        ws.send(WireProtocol.queueMove(
            itemId: itemId,
            beforeItemId: beforeItemId,
            playbackId: coordinator.playlist?.playbackId
        ))
    }
    func clearQueue() {
        guard supportsQueueV1 else { return }
        onUserMediaAction?()
        ws.send(WireProtocol.queueClear(playbackId: coordinator.playlist?.playbackId))
    }
    func mouse(event: String, dx: Float = 0, dy: Float = 0) { ws.sendMouse(event: event, dx: dx, dy: dy) }
    func endPointerGesture() { ws.endPointerGesture() }
    func queryContext() {
        if isAirPlay { airPlay.refreshRoute() }
        else if !isExternalReceiver { ws.send(WireProtocol.contextQuery()) }
    }
    func queryUserScripts() { guard isConnected, supportsBrowser else { return }; ws.send(WireProtocol.userScriptQuery()) }
    func installUserScript(name: String, content: String) { guard isConnected, supportsBrowser else { return }; ws.send(WireProtocol.userScript(name: name, content: content)) }
    func queryUserAgents() { guard isConnected, supportsBrowser else { return }; ws.send(WireProtocol.userAgentQuery()) }
    func setUserAgent(name: String, value: String, save: Bool) {
        guard isConnected, supportsBrowser else { return }
        ws.send(WireProtocol.userAgent(name: name, value: value, save: save))
    }

    private func adjustExternalVolume(up: Bool) {
        if externalReceiver?.protocolID == "roku" {
            sendRokuKeypress(up ? "volume_up" : "volume_down")
            return
        }
        guard externalReceiver?.protocolID == "google_cast" else { return }
        Task { @MainActor in
            do { try await googleCast.adjustVolume(up: up) }
            catch { operationError = error.localizedDescription }
        }
    }

    private func sendRokuKeypress(_ key: String) {
        let rokuKey: String
        switch key {
        case "dpad_up": rokuKey = "Up"
        case "dpad_down": rokuKey = "Down"
        case "dpad_left": rokuKey = "Left"
        case "dpad_right": rokuKey = "Right"
        case "dpad_center", "key_enter": rokuKey = "Select"
        case "back": rokuKey = "Back"
        case "home": rokuKey = "Home"
        case "volume_up": rokuKey = "VolumeUp"
        case "volume_down": rokuKey = "VolumeDown"
        default: return
        }
        guard rokuKey.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }),
              let host = externalReceiver?.addresses.first, !host.isEmpty else { return }
        let literal = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        guard let url = URL(string: "http://\(literal):\(externalReceiver?.port ?? 8060)/keypress/\(rokuKey)") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        URLSession.shared.dataTask(with: request).resume()
    }

    private func sendMediaCommand(_ command: String) {
        guard isConnected else { operationError = "Connect a device before casting."; return }
        if ws.send(command) {
            if let json = try? JSONSerialization.jsonObject(with: Data(command.utf8)) as? [String: Any],
               json["action"] as? String == "playlist" {
                let first = (json["payload"] as? [String: Any])?["items"] as? [[String: Any]]
                noteNewCast(mediaKind: first?.first?["mediaKind"] as? String ?? "video", title: first?.first?["title"] as? String)
                routedStreamRegistrations.removeAll()
            }
            recordCast(command)
        }
    }

    private func recordCast(_ command: String) {
        guard UserDefaults.standard.object(forKey: "cast_save_history") as? Bool ?? true else { return }
        // Explicit private payloads are never saved. The receiver preference is applied
        // separately at transport time and does not disable sender-side tracking.
        castHistory.record(command, receiver: receiverName)
    }

    @MainActor
    func replayCast(_ entry: CastHistoryStore.Entry) async {
        guard isConnected, let items = CastHistoryStore.items(in: entry.command) else {
            operationError = "Connect a device before replaying this cast."
            return
        }
        onUserMediaAction?()
        let target = destinationID
        let route = StreamRoute(rawValue: UserDefaults.standard.string(forKey: "stream_route_default") ?? "direct") ?? .direct
        let configuration = StreamProxySettingsStore.load()
        do {
            if isAirPlay {
                let request = airPlay.beginRequest(queue: false)
                for (index, item) in items.enumerated() {
                    let media = try await StreamRouteService().prepare(url: item["url"] as! String,
                        headers: item["headers"] as? [String: String] ?? [:], contentType: item["contentType"] as? String,
                        route: route, configuration: configuration)
                    let routed = try await prepareAirPlayMedia(media, contentType: item["contentType"] as? String)
                    let resources = item["subtitleResources"] as? [[String: Any]] ?? []
                    let sources = (item["subtitles"] as? [String] ?? []).enumerated().compactMap { offset, raw -> AirPlaySubtitleSource? in
                        guard let url = URL(string: raw) else { return nil }
                        let headers = resources.first { $0["url"] as? String == raw }?["headers"] as? [String: String] ?? [:]
                        return .init(url: url, headers: headers, title: "Subtitle \(offset + 1)")
                    }
                    guard isAirPlay, destinationID == target else { throw CancellationError() }
                    try await airPlay.send(media: routed, title: item["title"] as? String ?? "AirPlay media",
                        kind: item["mediaKind"] as? String ?? "video", subtitles: sources, queue: index > 0, request: request)
                }
                recordCast(entry.command)
                return
            }
            if isExternalReceiver && (items.count != 1 || !(items[0]["subtitles"] as? [String] ?? []).isEmpty) {
                throw StreamRoutingError.message("This receiver does not support replaying playlists or external subtitles.")
            }
            var prepared: [[String: Any]] = []
            var registrations: [PhoneProxyRegistration] = []
            for original in items {
                var item = original
                let media = try await StreamRouteService().prepare(url: original["url"] as! String,
                    headers: original["headers"] as? [String: String] ?? [:],
                    contentType: original["contentType"] as? String, route: route, configuration: configuration)
                item["url"] = media.url.absoluteString
                item["headers"] = media.headers
                if let registration = media.registration { registrations.append(registration) }
                var subtitles: [String] = []
                for subtitle in original["subtitles"] as? [String] ?? [] {
                    let routed = try await StreamRouteService().prepare(url: subtitle,
                        headers: original["headers"] as? [String: String] ?? [:],
                        contentType: nil, route: route, configuration: configuration)
                    subtitles.append(routed.url.absoluteString)
                    if let registration = routed.registration { registrations.append(registration) }
                }
                item["subtitles"] = subtitles
                prepared.append(item)
            }
            guard destinationID == target, isConnected else { throw CancellationError() }
            noteNewCast(mediaKind: prepared.first?["mediaKind"] as? String ?? "video", title: prepared.first?["title"] as? String)
            if isExternalReceiver {
                let item = prepared[0]
                try await googleCast.load(url: URL(string: item["url"] as! String)!,
                    title: item["title"] as? String, contentType: item["contentType"] as? String)
            } else {
                let saved = try JSONSerialization.jsonObject(with: Data(entry.command.utf8)) as? [String: Any]
                var payload = saved?["action"] as? String == "playlist"
                    ? saved?["payload"] as? [String: Any] ?? [:] : [:]
                payload["items"] = prepared
                if payload["startIndex"] == nil { payload["startIndex"] = 0 }
                let data = try JSONSerialization.data(withJSONObject: [
                    "type": "command", "action": "playlist", "payload": payload
                ] as [String: Any])
                guard ws.send(String(decoding: data, as: UTF8.self)) else { throw CancellationError() }
            }
            guard destinationID == target, isConnected else { throw CancellationError() }
            routedStreamRegistrations = registrations
            recordCast(entry.command)
        } catch is CancellationError {
            operationError = "The receiver connection changed. Try replaying again."
        } catch { operationError = error.localizedDescription }
    }

    // MARK: - System playback and background casting

    private func adoptAirPlayDestination() {
        endCastSession()
        endSavedReconnectDiscovery()
        externalReceiver = nil
        googleCast.disconnect()
        ws.disconnect()
        connectingDevice = nil
        operationError = nil
        UserDefaults.standard.set("airplay", forKey: "last_receiver_protocol")
        updateAirPlayState()
    }

    private func updateAirPlayState() {
        guard isAirPlay else { return }
        let nextState: ConnectionState = airPlay.routeAvailable ? .connected(serverName: airPlay.routeName, secure: false) : .disconnected
        if state != nextState { state = nextState }
        coordinator.activeContext = airPlay.current == nil ? "idle" : "player"
        coordinator.mediaKind = airPlay.current?.kind ?? "video"
        coordinator.playerIsLive = airPlay.current != nil && airPlay.duration == 0
        coordinator.playerIsSeekable = airPlay.duration > 0 && airPlay.routeAvailable
        coordinator.subtitleTracks = airPlay.tracks.map {
            MediaTrack(id: String($0.id), name: $0.title, selected: airPlay.selectedTrack == $0.id)
        }
        coordinator.audioTracks = airPlay.audioTracks.map {
            MediaTrack(id: String($0.id), name: $0.title, selected: airPlay.selectedAudioTrack == $0.id)
        }
        let entries = airPlay.current.map { [$0] + airPlay.upcoming } ?? []
        coordinator.playlist = entries.isEmpty ? nil : PlaylistUiState(currentIndex: 0, totalCount: entries.count,
            items: entries.enumerated().map { PlaylistEpisode(index: $0.offset, title: $0.element.title, itemId: $0.element.id.uuidString) })
        coordinator.playback = airPlay.current.map {
            TvPlaybackStatus(state: airPlay.buffering ? "buffering" : airPlay.playing ? "playing" : "paused",
                positionMs: Int64(airPlay.position * 1000), durationMs: Int64(airPlay.duration * 1000),
                title: $0.title, playbackId: $0.id.uuidString)
        }
    }

    @MainActor
    private func prepareAirPlayMedia(_ media: RoutedStream, contentType: String?) async throws -> RoutedStream {
        // A receiver cannot rely on AVURLAsset's private header options. Keep
        // protected upstream requests on the phone and expose an ordinary LAN URL.
        guard !media.headers.isEmpty else { return media }
        return try await StreamRouteService().prepare(url: media.url.absoluteString, headers: media.headers,
            contentType: contentType, route: .phone, configuration: .init())
    }

    private func sendAirPlayURL(_ url: String, title: String?, headers: [String: String], contentType: String?,
                               local: Bool = false, queue: Bool = false, subtitles: [AirPlaySubtitleSource] = []) {
        let destination = destinationID
        let attempt = airPlay.beginRequest(queue: queue)
        let route = local ? StreamRoute.direct : StreamRoute(rawValue: UserDefaults.standard.string(forKey: "stream_route_default") ?? "direct") ?? .direct
        Task { @MainActor in
            do {
                let media = try await StreamRouteService().prepare(url: url, headers: headers, contentType: contentType,
                    route: route, configuration: StreamProxySettingsStore.load())
                let routed = try await prepareAirPlayMedia(media, contentType: contentType)
                guard isAirPlay, destinationID == destination, airPlay.generation == attempt else { return }
                let kind = contentType?.hasPrefix("image/") == true ? "image" : contentType?.hasPrefix("audio/") == true ? "audio" : "video"
                try await airPlay.send(media: routed, title: title ?? "AirPlay media", kind: kind, subtitles: subtitles, queue: queue, request: attempt)
                if !queue { recordCast(WireProtocol.singleVideoCommand(url: url, title: title, contentType: contentType, headers: headers)) }
            } catch is CancellationError {} catch {
                if isAirPlay, airPlay.generation == attempt {
                    operationError = (error as? AirPlaySubtitleError)?.localizedDescription ?? (error as? StreamRoutingError)?.localizedDescription ?? "Couldn’t prepare this item for AirPlay."
                }
            }
        }
    }

#if DEBUG
    var castPlaybackDiagnostics: String {
        "PlayBridge background casting\nConnected: \(isConnected)\nPlayback: \(coordinator.playback?.state ?? "none")\nPhone routes retained: \(routedStreamRegistrations.count)\n\(CastSystemPlayback.shared.diagnostics)"
    }
#endif

    func applicationBecameActive() {
        castPlaybackSession.tick()
        reconnectLastReceiverIfNeeded()
    }

    private func noteNewCast(mediaKind: String, title: String? = nil) {
        externalMediaKind = mediaKind
        castPlaybackSession.beginPlayback(title: title, receiverName: receiverName ?? "TV", mediaKind: mediaKind)
        CastSystemPlayback.shared.userRequestedPlayback()
    }

    private func handleCastConnectionState(_ value: ConnectionState) {
        castPlaybackSession.connectionChanged(connected: value.isConnected)
        switch value {
        case .authFailed, .pinMismatch, .pairingDenied, .waitingForApproval, .waitingForCodeInput, .verifyingCode:
            // Background reconnect must never bypass pairing or certificate checks.
            endCastSession()
        default: break
        }
    }

    private func refreshCastPlayback(_ playback: TvPlaybackStatus?) {
        guard !isAirPlay else { return }
        castPlaybackSession.receive(playback,
            receiverName: receiverName ?? "TV",
            mediaKind: isExternalReceiver ? externalMediaKind : coordinator.mediaKind,
            speed: Double(coordinator.playerSpeed),
            isLive: coordinator.playerIsLive,
            canSeek: coordinator.playerIsSeekable)
    }

    private func reconnectActiveCast() {
        guard castPlaybackSession.isActive else { return }
        switch state {
        case .disconnected, .error:
            if let device = externalReceiver { connectExternalReceiver(device, preservingPlayback: true) }
            else { reconnectSaved() }
        default: break // Connecting/retrying and security states own their lifecycle.
        }
    }

    private func releaseCastResources() {
        routedStreamRegistrations.removeAll()
        subtitleFileServers.forEach { $0.stop() }
        subtitleFileServers.removeAll()
        LocalFileServer.shared.stop()
    }

    private func endCastSession() {
        castPlaybackSession.end()
        castPlaybackSession.allowNewPlayback()
        releaseCastResources()
        coordinator.clear()
        coordinator.activeContext = "idle"
    }

    // MARK: - Persistence

    /// Find a previously-paired record for `device` so we can reuse its token + SPKI pin.
    /// Returns nil when there's no match — never falls back to the active device, or we'd
    /// try to validate a new TV against another TV's pin ("fingerprint changed").
    private func matchingSaved(for device: DiscoveredDevice) -> PairedDevice? {
        savedDevices.first { saved in
            if !device.uuid.isEmpty, !saved.uuid.isEmpty { return device.uuid == saved.uuid }
            return saved.ip == device.ip
        }
    }

    private func persistCredentials(_ creds: WebSocketClient.IssuedCredentials) {
        guard !isExternalReceiver else { return }
        guard let d = connectingDevice else { return }
        var device = PairedDevice(
            ip: d.ip, port: d.port, name: d.name, uuid: d.uuid,
            wssPort: d.wssPort, certFingerprint: creds.certFingerprint
        )
        device.players = pairedDevice?.players ?? []
        device.browsers = pairedDevice?.browsers ?? []
        device.mediaKinds = pairedDevice?.mediaKinds ?? []
        device.features = pairedDevice?.features
        // Store token alongside the rest of the record (the whole struct lives in the Keychain).
        var stored = device
        stored.setToken(creds.token)
        store.savePairedDevice(stored)
        upsertSaved(stored)
        DispatchQueue.main.async { self.pairedDevice = stored }
    }

    private func persistCapabilities(_ caps: WebSocketClient.TvCapabilities) {
        DispatchQueue.main.async {
            guard !self.isExternalReceiver else { return }
            guard var device = self.pairedDevice ?? self.store.loadPairedDevice() else { return }
            device.players = caps.players
            device.browsers = caps.browsers
            device.mediaKinds = caps.mediaKinds
            device.features = caps.features
            self.store.savePairedDevice(device)
            self.upsertSaved(device)
            self.pairedDevice = device
        }
    }
}

extension ConnectionViewModel: PageCastTransport {
    var canReconnectWebsiteReceiver: Bool { !isAirPlay && !isExternalReceiver && pairedDevice != nil }
    var websitePlayback: TvPlaybackStatus? { coordinator.playback }
    var websitePlaylist: PlaylistUiState? { coordinator.playlist }
    var websiteContext: String { coordinator.activeContext }
    func reconnectWebsiteReceiver() { if let pairedDevice { connectSaved(pairedDevice) } }
    func queryWebsiteState() {
        guard isConnected, !isAirPlay, !isExternalReceiver else { return }
        queryContext()
        // Apple TV's context reply doesn't include its playlist. Recover a lost
        // queue broadcast explicitly so linked prefetch can resume after reconnect.
        if supportsQueueV1 { ws.send(WireProtocol.queueQuery()) }
    }
    func websiteMatchesReceiver(_ id: String) -> Bool {
        guard !isAirPlay, !isExternalReceiver, let pairedDevice else { return false }
        return deviceKey(pairedDevice) == id || "\(pairedDevice.ip):\(pairedDevice.port)" == id
    }

    func sendWebsiteCommand(action: String, payload: [String: Any]) -> Bool {
        guard isConnected, !isAirPlay, !isExternalReceiver,
              let data = try? JSONSerialization.data(withJSONObject: ["type": "command", "action": action, "payload": payload]) else { return false }
        return ws.send(String(decoding: data, as: UTF8.self))
    }

    @MainActor
    func sendWebsitePlaylist(_ request: PageCastRequest, allowedPrivateOrigins: Set<String>) async throws {
        try Task.checkCancellation()
        guard isConnected, ws.isConnected else { throw PageCastError(code: "connect_failed") }
        guard !isAirPlay, !isExternalReceiver else { throw PageCastError(code: "unsupported_target") }
        let command = request.playlistCommand(allowedPrivateOrigins: allowedPrivateOrigins)
        guard !command.isEmpty else { throw PageCastError(code: "invalid_request") }
        // Drop the previous queue snapshot before waiting for this playlist's echo.
        coordinator.playlist = nil
        guard ws.send(command) else { throw PageCastError(code: "connect_failed") }
        let item = request.items[request.startIndex]
        noteNewCast(mediaKind: item["mediaKind"] as? String ?? "video", title: item["title"] as? String)
        routedStreamRegistrations.removeAll()
        recordCast(command)
    }
}

private extension PairedDevice {
    mutating func setToken(_ t: String) { self.token = t }
}
