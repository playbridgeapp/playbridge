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
    @Published private(set) var externalReceiver: ExternalReceiverDevice?
    @Published private(set) var savedExternalReceiverDevices: [ExternalReceiverDevice] = []
    @Published var operationError: String?
    var isExternalReceiver: Bool { externalReceiver != nil }
    var supportsQueue: Bool { !isExternalReceiver }
    var supportsBrowser: Bool { !isExternalReceiver && pairedDevice?.browsers.isEmpty == false }
    var destinationID: String? { externalReceiver.map { $0.identity } ?? pairedDevice.map(deviceKey) }
    var receiverName: String? {
        if case .connected(let name, _) = state { return name }
        return externalReceiver?.name ?? pairedDevice?.name
    }
    let castHistory = CastHistoryStore()
    let ws = WebSocketClient()
    let coordinator = ConnectionCoordinator()

    @Published var state: ConnectionState = .disconnected
    @Published var pairedDevice: PairedDevice?
    /// All TVs we've paired with (history), most-recent first.
    @Published var savedDevices: [PairedDevice] = []
    /// Reachability of saved TVs, keyed by `deviceKey`.
    @Published var onlineStatus: [String: Bool] = [:]

    private let store = PairingStore.shared
    private var routedStreamRegistrations: [PhoneProxyRegistration] = []
    private var cancellables = Set<AnyCancellable>()
    /// The device we're currently bringing up, so we can persist a full record once paired.
    private var connectingDevice: DiscoveredDevice?
    private var savedReconnectDiscovery: AnyCancellable?
    private var savedReconnectGeneration: UUID?
    private var savedReconnectTimeout: DispatchWorkItem?
    private var savedEndpointRefreshTimeout: DispatchWorkItem?
    private static let savedReconnectDiscoveryTimeout: TimeInterval = 10

    func deviceKey(_ d: PairedDevice) -> String { d.uuid.isEmpty ? "\(d.ip):\(d.port)" : d.uuid }

    init() {
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

        ws.onMessage = { [weak self] text in guard let self, !isExternalReceiver else { return }; coordinator.handle(text) }
        ws.onCredentials = { [weak self] creds in self?.persistCredentials(creds) }
        ws.onCapabilities = { [weak self] caps in self?.persistCapabilities(caps) }

        // Re-publish nested object changes so views observing the VM refresh.
        ws.$state
            .receive(on: RunLoop.main)
            .sink { [weak self] value in guard let self, !isExternalReceiver else { return }; state = value }
            .store(in: &cancellables)
        googleCast.$state.receive(on: RunLoop.main).sink { [weak self] value in
            guard let self, isExternalReceiver else { return }
            state = value
            if case .connected(let name, _) = value, var device = externalReceiver {
                device.name = name
                externalReceiver = device
                savedExternalReceiverDevices.removeAll { $0.identity == device.identity }
                savedExternalReceiverDevices.insert(device, at: 0)
                savedExternalReceiverDevices = Array(savedExternalReceiverDevices.prefix(20))
                UserDefaults.standard.set(try? JSONEncoder().encode(savedExternalReceiverDevices), forKey: "google_cast_saved_devices")
            }
            if !value.isConnected {
                coordinator.clear()
                coordinator.activeContext = "idle"
                routedStreamRegistrations.removeAll()
            }
        }.store(in: &cancellables)
        googleCast.$playback.receive(on: RunLoop.main).sink { [weak self] playback in
            guard let self, isExternalReceiver else { return }
            coordinator.playback = playback
            coordinator.activeContext = playback == nil || playback?.state == "stopped" ? "idle" : "player"
        }.store(in: &cancellables)
        rokuBrowser.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        dialBrowser.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        dlnaBrowser.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        googleCastBrowser.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &cancellables)
        coordinator.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
        browser.$devices
            .receive(on: RunLoop.main)
            .sink { [weak self] devices in self?.refreshSavedEndpoints(devices) }
            .store(in: &cancellables)
        browser.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    // MARK: - Discovery

    func startDiscovery() { browser.start(owner: .userInterface); googleCastBrowser.start(); dlnaBrowser.start(); rokuBrowser.start() }
    func stopDiscovery() { browser.stop(owner: .userInterface); googleCastBrowser.stop(); dlnaBrowser.stop(); rokuBrowser.stop(); dialBrowser.stop() }

    // MARK: - Connect

    /// Connect to a device found via Bonjour. Reuses a saved token/pin if we've paired with it.
    func connect(to device: DiscoveredDevice) {
        usePlayBridge()
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

    func connectExternalReceiver(_ device: ExternalReceiverDevice) {
        guard device.protocolID != "dial" else { operationError = "DIAL devices require a supported receiver app; generic video sending is unavailable."; return }
        endSavedReconnectDiscovery()
        let device = (googleCastBrowser.devices + dlnaBrowser.devices + rokuBrowser.devices).first { $0.identity == device.identity } ?? device
        externalReceiver = device
        connectingDevice = nil
        ws.disconnect()
        coordinator.clear()
        coordinator.activeContext = "idle"
        operationError = nil
        state = .connecting
        UserDefaults.standard.set("google_cast", forKey: "last_receiver_protocol")
        googleCast.connect(device)
    }

    func forgetExternalReceiver(_ device: ExternalReceiverDevice) {
        savedExternalReceiverDevices.removeAll { $0.identity == device.identity }
        UserDefaults.standard.set(try? JSONEncoder().encode(savedExternalReceiverDevices), forKey: "google_cast_saved_devices")
    }

    private func usePlayBridge() {
        externalReceiver = nil
        googleCast.disconnect()
        routedStreamRegistrations.removeAll()
        UserDefaults.standard.set("playbridge", forKey: "last_receiver_protocol")
    }

    /// Manual IP entry (no Bonjour). Pairs if we have no token for this address.
    func connectManual(ip: String, port: Int = ProtocolConstants.defaultPort, wssPort: Int? = nil) {
        let device = DiscoveredDevice(ip: ip, port: port, name: ip, wssPort: wssPort)
        connect(to: device)
    }

    /// Reconnect to the previously paired device (used on launch).
    func reconnectSaved() {
        guard UserDefaults.standard.string(forKey: "last_receiver_protocol") != "google_cast" else { return }
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
        usePlayBridge()
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
        if isExternalReceiver {
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
        if isExternalReceiver { sendExternalReceiverURL(trimmed, title: title, contentType: nil, headers: [:]); return }
        SenderDebugNetwork.request("Cast output", url: trimmed)
        sendMediaCommand(WireProtocol.singleVideoCommand(url: trimmed, title: title))
    }

    /// Local library media is already served by this phone; do not wrap its LAN URL in a remote proxy.
    func castLocalMedia(url: String, title: String, contentType: String) {
        if isExternalReceiver, let mediaURL = URL(string: url) {
            Task {
                do { try await googleCast.load(url: mediaURL, title: title, contentType: contentType) }
                catch { operationError = "Couldn’t send this file to the connected device. Check the connection and supported media formats." }
            }
        } else { ws.send(WireProtocol.singleVideoCommand(url: url, title: title, contentType: contentType)) }
    }

    /// Cast an arbitrary media URL with optional request headers (IPTV channels and
    /// saved collection items, which may require a Referer/User-Agent).
    func castMedia(url: String, title: String? = nil, headers: [String: String] = [:], contentType: String? = nil) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
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
        if isExternalReceiver {
            guard subtitles.isEmpty else { operationError = "External subtitles are not supported by this receiver adapter yet."; return }
            sendExternalReceiverURL(quality?.url ?? video.url, title: video.displayTitle, contentType: video.contentType, headers: VideoDetector.mediaHeaders(for: video))
            return
        }
        let url = quality?.url ?? video.url
        let headers = VideoDetector.mediaHeaders(for: video)
        SenderDebugNetwork.request("Browser cast output", url: url, headers: headers)
        sendMediaCommand(WireProtocol.singleVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            headers: headers,
            detectedBy: video.detectedBy,
            playerMode: playerMode
        ))
    }

    /// Retain phone routes beyond sheet dismissal and across queued items.
    func sendRoutedStream(_ media: RoutedStream, video: DetectedVideo, subtitles: [RoutedStream], queue: Bool) async throws {
        let historyCommand = WireProtocol.singleVideoCommand(
            url: media.sourceURL ?? video.url, title: video.displayTitle,
            contentType: video.contentType, subtitles: subtitles.compactMap(\.sourceURL),
            headers: media.sourceURL == nil ? VideoDetector.mediaHeaders(for: video) : media.sourceHeaders,
            detectedBy: video.detectedBy)
        if isExternalReceiver {
            guard !queue, subtitles.isEmpty else { throw StreamRoutingError.message("Queueing and external subtitles are not supported by this receiver adapter yet.") }
            let target = destinationID
            try await googleCast.load(url: media.url, title: video.displayTitle, contentType: video.kind == .hls ? "application/vnd.apple.mpegurl" : video.contentType)
            guard destinationID == target, isExternalReceiver, googleCast.state.isConnected else { throw CancellationError() }
            routedStreamRegistrations = [media.registration].compactMap { $0 }
            recordCast(historyCommand)
            return
        }
        if !queue { routedStreamRegistrations.removeAll() }
        routedStreamRegistrations.append(contentsOf: ([media] + subtitles).compactMap(\.registration))
        let urls = subtitles.map { $0.url.absoluteString }
        let message = queue
            ? WireProtocol.queueVideoCommand(url: media.url.absoluteString, title: video.displayTitle,
                contentType: video.contentType, subtitles: urls, headers: media.headers, detectedBy: video.detectedBy)
            : WireProtocol.singleVideoCommand(url: media.url.absoluteString, title: video.displayTitle,
                contentType: video.contentType, subtitles: urls, headers: media.headers, detectedBy: video.detectedBy)
        if ws.send(message) { recordCast(historyCommand) }
    }

    /// Queue a browser-detected stream.
    func queueStream(_ video: DetectedVideo, quality: VideoQuality? = nil, subtitles: [String] = [], playerMode: String? = nil) {
        guard !isExternalReceiver else { operationError = "Queueing is not supported by this receiver adapter yet."; return }
        let url = quality?.url ?? video.url
        let headers = VideoDetector.mediaHeaders(for: video)
        SenderDebugNetwork.request("Browser queue output", url: url, headers: headers)
        sendMediaCommand(WireProtocol.queueVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
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
        SenderDebugNetwork.request("Browser command output", url: trimmed)
        ws.send(WireProtocol.browserCommand(
            url: trimmed,
            browserMode: browserMode,
            desktopMode: desktopMode
        ))
    }

    func control(_ command: String) {
        guard isExternalReceiver else { ws.send(WireProtocol.controlCommand(command)); return }
        Task { @MainActor in
            do { try await googleCast.control(command) }
            catch { operationError = error.localizedDescription }
        }
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

    func remote(_ key: String) { ws.send(WireProtocol.remoteCommand(key: key)) }
    func jump(to item: PlaylistEpisode) {
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
        ws.send(WireProtocol.queueRemove(
            itemIds: itemIds,
            playbackId: coordinator.playlist?.playbackId
        ))
    }
    func moveInQueue(itemId: String, beforeItemId: String?) {
        guard supportsQueueV1 else { return }
        ws.send(WireProtocol.queueMove(
            itemId: itemId,
            beforeItemId: beforeItemId,
            playbackId: coordinator.playlist?.playbackId
        ))
    }
    func clearQueue() {
        guard supportsQueueV1 else { return }
        ws.send(WireProtocol.queueClear(playbackId: coordinator.playlist?.playbackId))
    }
    func mouse(event: String, dx: Float = 0, dy: Float = 0) { ws.sendMouse(event: event, dx: dx, dy: dy) }
    func queryContext() { if !isExternalReceiver { ws.send(WireProtocol.contextQuery()) } }

    private func sendMediaCommand(_ command: String) {
        guard isConnected else { operationError = "Connect a device before casting."; return }
        if ws.send(command) { recordCast(command) }
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
        let target = destinationID
        let route = StreamRoute(rawValue: UserDefaults.standard.string(forKey: "stream_route_default") ?? "direct") ?? .direct
        let configuration = StreamProxySettingsStore.load()
        do {
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
            device.features = caps.features
            self.store.savePairedDevice(device)
            self.upsertSaved(device)
            self.pairedDevice = device
        }
    }
}

private extension PairedDevice {
    mutating func setToken(_ t: String) { self.token = t }
}
