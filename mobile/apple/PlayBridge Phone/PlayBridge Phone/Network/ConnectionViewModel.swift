import Foundation
import Combine

/// Top-level glue the UI observes: owns discovery, the socket, the inbound coordinator, and
/// credential persistence. Mirrors the role of `ConnectionViewModel` on Android.
final class ConnectionViewModel: ObservableObject {
    let browser = BonjourBrowser()
    let ws = WebSocketClient()
    let coordinator = ConnectionCoordinator()

    @Published var state: ConnectionState = .disconnected
    @Published var pairedDevice: PairedDevice?

    private let store = PairingStore.shared
    private var cancellables = Set<AnyCancellable>()
    /// The device we're currently bringing up, so we can persist a full record once paired.
    private var connectingDevice: DiscoveredDevice?

    init() {
        pairedDevice = store.loadPairedDevice()

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

    func startDiscovery() { browser.start() }
    func stopDiscovery() { browser.stop() }

    // MARK: - Connect

    /// Connect to a device found via Bonjour. Reuses a saved token/pin if we've paired with it.
    func connect(to device: DiscoveredDevice) {
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
    }

    func disconnect() { ws.disconnect() }

    /// Submit the 6-digit SAS code the user read off the TV during pairing.
    func submitPairingCode(_ code: String) { ws.submitPairingCode(code) }

    func forgetDevice() {
        ws.disconnect()
        store.clearPairedDevice()
        pairedDevice = nil
        connectingDevice = nil
    }

    // MARK: - Commands

    var isConnected: Bool { state.isConnected }

    func cast(urlString: String, title: String? = nil) {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        ws.send(WireProtocol.singleVideoCommand(url: trimmed, title: title))
    }

    /// Cast a browser-detected stream: chosen quality URL (or the master), `mediaHeaders`,
    /// attached subtitles. Mirrors the Android `CastSheet` → `createSingleVideoCommandJson` path.
    func castStream(_ video: DetectedVideo, quality: VideoQuality? = nil, subtitles: [String] = [], playerMode: String? = nil) {
        let url = quality?.url ?? video.url
        ws.send(WireProtocol.singleVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            headers: VideoDetector.mediaHeaders(for: video),
            detectedBy: video.detectedBy,
            playerMode: playerMode
        ))
    }

    /// Queue a browser-detected stream.
    func queueStream(_ video: DetectedVideo, quality: VideoQuality? = nil, subtitles: [String] = [], playerMode: String? = nil) {
        let url = quality?.url ?? video.url
        ws.send(WireProtocol.queueVideoCommand(
            url: url,
            title: video.displayTitle,
            contentType: video.contentType,
            subtitles: subtitles,
            headers: VideoDetector.mediaHeaders(for: video),
            detectedBy: video.detectedBy,
            playerMode: playerMode
        ))
    }

    /// Open a URL on the TV browser.
    func browseTo(url: String, browserMode: String? = nil, desktopMode: Bool = false) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
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

    private func matchingSaved(for device: DiscoveredDevice) -> PairedDevice? {
        guard let saved = pairedDevice else { return nil }
        if !device.uuid.isEmpty, !saved.uuid.isEmpty { return device.uuid == saved.uuid ? saved : nil }
        return saved.ip == device.ip ? saved : nil
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
        DispatchQueue.main.async { self.pairedDevice = stored }
    }

    private func persistCapabilities(_ caps: WebSocketClient.TvCapabilities) {
        guard var device = pairedDevice else { return }
        device.players = caps.players
        device.browsers = caps.browsers
        store.savePairedDevice(device)
        DispatchQueue.main.async { self.pairedDevice = device }
    }
}

private extension PairedDevice {
    mutating func setToken(_ t: String) { self.token = t }
}
