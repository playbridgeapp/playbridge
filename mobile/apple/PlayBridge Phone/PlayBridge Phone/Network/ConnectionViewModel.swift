import Foundation
import Combine
import Network

/// Top-level glue the UI observes: owns discovery, the socket, the inbound coordinator, and
/// credential persistence. Mirrors the role of `ConnectionViewModel` on Android.
final class ConnectionViewModel: ObservableObject {
    let browser = BonjourBrowser()
    let ws = WebSocketClient()
    let coordinator = ConnectionCoordinator()

    @Published var state: ConnectionState = .disconnected
    @Published var pairedDevice: PairedDevice?
    /// All TVs we've paired with (history), most-recent first.
    @Published var savedDevices: [PairedDevice] = []
    /// Reachability of saved TVs, keyed by `deviceKey`.
    @Published var onlineStatus: [String: Bool] = [:]

    private let store = PairingStore.shared
    private var cancellables = Set<AnyCancellable>()
    /// The device we're currently bringing up, so we can persist a full record once paired.
    private var connectingDevice: DiscoveredDevice?
    private var savedReconnectDiscovery: AnyCancellable?
    private var savedReconnectTimeout: DispatchWorkItem?
    private static let savedReconnectDiscoveryTimeout: TimeInterval = 10

    func deviceKey(_ d: PairedDevice) -> String { d.uuid.isEmpty ? "\(d.ip):\(d.port)" : d.uuid }

    init() {
        pairedDevice = store.loadPairedDevice()
        savedDevices = store.loadSavedDevices()
        // Migrate a pre-existing single paired device into the history list.
        if savedDevices.isEmpty, let p = pairedDevice {
            savedDevices = [p]
            store.saveSavedDevices(savedDevices)
        }

        ws.onMessage = { [weak self] text in self?.coordinator.handle(text) }
        ws.onCredentials = { [weak self] creds in self?.persistCredentials(creds) }
        ws.onCapabilities = { [weak self] caps in self?.persistCapabilities(caps) }

        // Re-publish nested object changes so views observing the VM refresh.
        ws.$state
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
        coordinator.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
        browser.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    // MARK: - Discovery

    func startDiscovery() { browser.start(owner: .userInterface) }
    func stopDiscovery() { browser.stop(owner: .userInterface) }

    // MARK: - Connect

    /// Connect to a device found via Bonjour. Reuses a saved token/pin if we've paired with it.
    func connect(to device: DiscoveredDevice) {
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

    /// Manual IP entry (no Bonjour). Pairs if we have no token for this address.
    func connectManual(ip: String, port: Int = ProtocolConstants.defaultPort, wssPort: Int? = nil) {
        let device = DiscoveredDevice(ip: ip, port: port, name: ip, wssPort: wssPort)
        connect(to: device)
    }

    /// Reconnect to the previously paired device (used on launch).
    func reconnectSaved() {
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
    }

    /// Keep launch reconnect discovery independent of the discovery screen. The stored
    /// endpoint is tried immediately; a live Bonjour endpoint for the same UUID replaces
    /// it once, without disturbing the receiver's token, pin, or capabilities.
    private func beginSavedReconnectDiscovery(for saved: PairedDevice) {
        guard !saved.uuid.isEmpty else { return }

        browser.start(owner: .savedReconnect)
        savedReconnectDiscovery = browser.$devices
            .receive(on: RunLoop.main)
            .sink { [weak self] devices in
                guard let live = devices.first(where: { $0.uuid == saved.uuid }) else { return }
                // `$devices` can synchronously emit its current value while the
                // cancellable is still being assigned. Defer handling so cleanup can
                // always cancel the installed subscription and prevent duplicate retries.
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.savedReconnectDiscovery != nil else { return }
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
        let endpointChanged = live.ip != saved.ip
            || live.port != saved.port
            || live.wssPort != saved.wssPort
        endSavedReconnectDiscovery()
        guard endpointChanged else { return }

        var refreshed = saved
        refreshed.ip = live.ip
        refreshed.port = live.port
        refreshed.name = live.name
        refreshed.wssPort = live.wssPort
        // Copying the saved record preserves its token, SPKI pin, capabilities,
        // last-connected timestamp, and stable receiver UUID.
        store.savePairedDevice(refreshed)
        upsertSaved(refreshed)
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

    /// Best-effort TCP reachability check of each saved TV, updating `onlineStatus`.
    func pingSavedDevices() {
        for d in savedDevices {
            let key = deviceKey(d)
            let port = UInt16(d.wssPort ?? d.port)
            ConnectionViewModel.isReachable(host: d.ip, port: port) { [weak self] ok in
                DispatchQueue.main.async { self?.onlineStatus[key] = ok }
            }
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
        conn.start(queue: .global(qos: .utility))
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) { finish(false) }
    }

    private func upsertSaved(_ device: PairedDevice) {
        var list = store.loadSavedDevices()
        list.removeAll { deviceKey($0) == deviceKey(device) }
        list.insert(device, at: 0)
        store.saveSavedDevices(list)
        DispatchQueue.main.async { self.savedDevices = list }
    }

    func disconnect() {
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

    func cast(urlString: String, title: String? = nil) {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        SenderDebugNetwork.request("Cast output", url: trimmed)
        ws.send(WireProtocol.singleVideoCommand(url: trimmed, title: title))
    }

    /// Cast an arbitrary media URL with optional request headers (IPTV channels and
    /// saved collection items, which may require a Referer/User-Agent).
    func castMedia(url: String, title: String? = nil, headers: [String: String] = [:], contentType: String? = nil) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        SenderDebugNetwork.request("Cast output", url: trimmed, headers: headers)
        ws.send(WireProtocol.singleVideoCommand(
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
        let url = quality?.url ?? video.url
        let headers = VideoDetector.mediaHeaders(for: video)
        SenderDebugNetwork.request("Browser cast output", url: url, headers: headers)
        ws.send(WireProtocol.singleVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            headers: headers,
            detectedBy: video.detectedBy,
            playerMode: playerMode
        ))
    }

    /// Queue a browser-detected stream.
    func queueStream(_ video: DetectedVideo, quality: VideoQuality? = nil, subtitles: [String] = [], playerMode: String? = nil) {
        let url = quality?.url ?? video.url
        let headers = VideoDetector.mediaHeaders(for: video)
        SenderDebugNetwork.request("Browser queue output", url: url, headers: headers)
        ws.send(WireProtocol.queueVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            headers: headers,
            detectedBy: video.detectedBy,
            playerMode: playerMode
        ))
    }

    /// Open a URL on the TV browser.
    func browseTo(url: String, browserMode: String? = nil, desktopMode: Bool = false) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        SenderDebugNetwork.request("Browser command output", url: trimmed)
        ws.send(WireProtocol.browserCommand(
            url: trimmed,
            browserMode: browserMode,
            desktopMode: desktopMode
        ))
    }

    func control(_ command: String) { ws.send(WireProtocol.controlCommand(command)) }
    func remote(_ key: String) { ws.send(WireProtocol.remoteCommand(key: key)) }
    func jump(toIndex index: Int) { ws.send(WireProtocol.playlistJumpCommand(index: index)) }
    func mouse(event: String, dx: Float = 0, dy: Float = 0) { ws.sendMouse(event: event, dx: dx, dy: dy) }
    func queryContext() { ws.send(WireProtocol.contextQuery()) }

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
        guard let d = connectingDevice else { return }
        var device = PairedDevice(
            ip: d.ip, port: d.port, name: d.name, uuid: d.uuid,
            wssPort: d.wssPort, certFingerprint: creds.certFingerprint
        )
        device.players = pairedDevice?.players ?? []
        device.browsers = pairedDevice?.browsers ?? []
        // Store token alongside the rest of the record (the whole struct lives in the Keychain).
        var stored = device
        stored.setToken(creds.token)
        store.savePairedDevice(stored)
        upsertSaved(stored)
        DispatchQueue.main.async { self.pairedDevice = stored }
    }

    private func persistCapabilities(_ caps: WebSocketClient.TvCapabilities) {
        guard var device = pairedDevice else { return }
        device.players = caps.players
        device.browsers = caps.browsers
        store.savePairedDevice(device)
        upsertSaved(device)
        DispatchQueue.main.async { self.pairedDevice = device }
    }
}

private extension PairedDevice {
    mutating func setToken(_ t: String) { self.token = t }
}
