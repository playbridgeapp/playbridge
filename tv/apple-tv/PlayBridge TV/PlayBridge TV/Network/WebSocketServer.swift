import Foundation
import Network
import SwiftUI
import Combine

struct PairingRequest {
    let deviceName: String
    let deviceUUID: String
    let connection: NWConnection
}

// MARK: - Server Logic
class WebSocketServer: ObservableObject {
    private var listener: NWListener?
    private var connectedConnections: [NWConnection] = []
    private var historyStore: HistoryStore?
    var playlistStore: PlaylistStore?

    @Published var currentPlayRequest: PlayRequest?
    @Published var isAuthenticated = false
    @Published var pendingPairingRequest: PairingRequest?
    @Published var pairedDevicesList: [PairedDevice] = []
    @Published var connectedCount: Int = 0
    @Published var localIP: String = "0.0.0.0"
    @Published var serverState: String = "Stopped"

    var deviceName: String { UIDevice.current.name }
    private let authorizedTokensKey = "pb_authorized_tokens"
    private let deviceUUIDKey = "pb_device_uuid"
    private let pairedDevicesKey = "pb_paired_devices"

    private var autoTimeoutWork: DispatchWorkItem?
    private var keepaliveTimer: Timer?

    private var deviceUUID: String {
        if let uuid = UserDefaults.standard.string(forKey: deviceUUIDKey) { return uuid }
        let newUUID = UUID().uuidString
        UserDefaults.standard.set(newUUID, forKey: deviceUUIDKey)
        return newUUID
    }

    private var storedPairedDevices: [PairedDevice] {
        get {
            guard let data = UserDefaults.standard.data(forKey: pairedDevicesKey),
                  let devices = try? JSONDecoder().decode([PairedDevice].self, from: data) else {
                return []
            }
            return devices
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: pairedDevicesKey)
            }
            pairedDevicesList = newValue
        }
    }

    private var authorizedTokens: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: authorizedTokensKey) ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: authorizedTokensKey) }
    }

    init(historyStore: HistoryStore? = nil, playlistStore: PlaylistStore? = nil) {
        self.historyStore = historyStore
        self.playlistStore = playlistStore
        self.localIP = getIPAddress()
        self.pairedDevicesList = storedPairedDevices
    }

    func start(port: UInt16 = 8765) {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = 60
        tcpOptions.keepaliveInterval = 30
        tcpOptions.keepaliveCount = 3
        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        do {
            listener = try NWListener(using: parameters, on: NWEndpoint.Port(integerLiteral: port))
            let txtDict: [String: Data] = [
                "uuid": deviceUUID.data(using: .utf8)!,
                "device_name": deviceName.data(using: .utf8)!,
            ]
            listener?.service = NWListener.Service(
                name: deviceName, type: "_playbridge._tcp", domain: nil,
                txtRecord: NetService.data(fromTXTRecord: txtDict))
            listener?.stateUpdateHandler = { [weak self] state in
                DispatchQueue.main.async {
                    switch state {
                    case .ready: self?.serverState = "Ready to Connect"
                    case .failed(let error):
                        self?.serverState = "Error: \(error.localizedDescription)"
                    case .setup: self?.serverState = "Starting..."
                    case .cancelled: self?.serverState = "Stopped"
                    default: break
                    }
                }
            }
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            listener?.start(queue: .main)
            startKeepalive()
        } catch { print("Server error: \(error)") }
    }

    func stop() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = nil
        listener?.cancel()
        listener = nil
        for connection in connectedConnections { connection.cancel() }
        connectedConnections.removeAll()
        DispatchQueue.main.async {
            self.connectedCount = 0
            self.isAuthenticated = false
            self.pendingPairingRequest = nil
            self.serverState = "Stopped"
        }
    }

    func restart(port: UInt16 = 8765) {
        stop()
        start(port: port)
    }

    private func startKeepalive() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            for connection in self.connectedConnections {
                self.sendPing(to: connection)
            }
        }
    }

    private func sendPing(to connection: NWConnection) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .ping)
        let context = NWConnection.ContentContext(identifier: "keepalive-ping", metadata: [metadata])
        connection.send(content: nil, contentContext: context, isComplete: true,
                        completion: .contentProcessed({ [weak self] error in
            if let error = error {
                print("Keepalive ping failed for \(connection.endpoint): \(error)")
                self?.removeConnection(connection)
            }
        }))
    }

    private func handleNewConnection(_ connection: NWConnection) {
        connection.viabilityUpdateHandler = { [weak self] isViable in
            if !isViable {
                print("WebSocket connection (\(connection.endpoint)) is no longer viable.")
                self?.removeConnection(connection)
            }
        }
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let error):
                print("WebSocket connection (\(connection.endpoint)) failed: \(error)")
                self?.removeConnection(connection)
            case .cancelled:
                self?.removeConnection(connection)
            default: break
            }
        }
        connection.start(queue: .main)
        receiveMessages(from: connection)
    }

    private func removeConnection(_ connection: NWConnection) {
        connection.cancel()
        DispatchQueue.main.async {
            self.connectedConnections.removeAll(where: { $0 === connection })
            self.connectedCount = self.connectedConnections.count
            if self.connectedConnections.isEmpty { self.isAuthenticated = false }
            // Clear pending request if it was this connection
            if self.pendingPairingRequest?.connection === connection {
                self.autoTimeoutWork?.cancel()
                self.autoTimeoutWork = nil
                self.pendingPairingRequest = nil
            }
        }
    }

    private func receiveMessages(from connection: NWConnection) {
        connection.receiveMessage { [weak self] content, _, _, error in
            if let error = error {
                print("WebSocket Receive Error (\(connection.endpoint)): \(error)")
                self?.removeConnection(connection)
                return
            }
            if let content = content, let message = String(data: content, encoding: .utf8) {
                self?.handleMessage(message, from: connection)
            }
            self?.receiveMessages(from: connection)
        }
    }

    private func handleMessage(_ jsonString: String, from connection: NWConnection) {
        print("WebSocket Received: \(jsonString)")
        guard let data = jsonString.data(using: .utf8),
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = json["type"] as? String
        else {
            print("WebSocket Error: Failed to parse JSON or missing 'type'")
            return
        }

        switch type {
        case "ping": send(json: ["type": "pong"], to: connection)
        case "pairing_request": handlePairingRequest(json, from: connection)
        case "auth": handleAuth(json, from: connection)
        case "command": if isAuthenticated { handleCommand(json) }
        default: break
        }
    }

    // MARK: - Pairing

    private func handlePairingRequest(_ json: [String: Any], from connection: NWConnection) {
        guard let incomingDeviceName = json["deviceName"] as? String,
              let deviceUUID = json["deviceUUID"] as? String else { return }

        // Deny immediately if another request is already pending
        if pendingPairingRequest != nil {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }

        let request = PairingRequest(deviceName: incomingDeviceName, deviceUUID: deviceUUID, connection: connection)
        DispatchQueue.main.async { self.pendingPairingRequest = request }

        // Auto-deny after 30 seconds
        autoTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self,
                  self.pendingPairingRequest?.deviceUUID == deviceUUID else { return }
            self.denyPairing()
        }
        autoTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: work)
    }

    func approvePairing() {
        guard let request = pendingPairingRequest else { return }
        autoTimeoutWork?.cancel()
        autoTimeoutWork = nil

        let token = UUID().uuidString

        var tokens = authorizedTokens
        tokens.insert(token)
        authorizedTokens = tokens

        let device = PairedDevice(
            deviceUUID: request.deviceUUID,
            deviceName: request.deviceName,
            token: token,
            lastConnected: Date()
        )
        savePairedDevice(device)

        send(json: ["type": "pairing_approved", "token": token], to: request.connection)
        completeAuth(from: request.connection, token: token)

        pendingPairingRequest = nil
    }

    func denyPairing() {
        guard let request = pendingPairingRequest else { return }
        autoTimeoutWork?.cancel()
        autoTimeoutWork = nil

        send(json: ["type": "pairing_denied"], to: request.connection)
        request.connection.cancel()

        pendingPairingRequest = nil
    }

    // MARK: - Reconnection

    private func handleAuth(_ json: [String: Any], from connection: NWConnection) {
        guard let token = json["token"] as? String else {
            send(json: ["type": "auth_response", "success": false], to: connection)
            return
        }
        if authorizedTokens.contains(token) {
            updateLastConnected(token: token)
            completeAuth(from: connection, token: token)
        } else {
            send(json: ["type": "auth_response", "success": false], to: connection)
        }
    }

    private func completeAuth(from connection: NWConnection, token: String) {
        DispatchQueue.main.async {
            self.isAuthenticated = true
            self.connectedConnections.append(connection)
            self.connectedCount = self.connectedConnections.count
            self.send(
                json: ["type": "auth_response", "success": true, "token": token], to: connection)
        }
    }

    // MARK: - Paired Device Management

    private func savePairedDevice(_ device: PairedDevice) {
        var devices = storedPairedDevices
        if let idx = devices.firstIndex(where: { $0.deviceUUID == device.deviceUUID }) {
            devices[idx] = device
        } else {
            devices.append(device)
        }
        storedPairedDevices = devices
    }

    func forgetDevice(_ device: PairedDevice) {
        var devices = storedPairedDevices
        devices.removeAll { $0.deviceUUID == device.deviceUUID }
        storedPairedDevices = devices

        var tokens = authorizedTokens
        tokens.remove(device.token)
        authorizedTokens = tokens
    }

    func forgetAllDevices() {
        storedPairedDevices = []
        authorizedTokens = []
        DispatchQueue.main.async { self.pairedDevicesList = [] }
    }

    private func updateLastConnected(token: String) {
        var devices = storedPairedDevices
        if let idx = devices.firstIndex(where: { $0.token == token }) {
            let d = devices[idx]
            devices[idx] = PairedDevice(
                deviceUUID: d.deviceUUID, deviceName: d.deviceName,
                token: token, lastConnected: Date()
            )
            storedPairedDevices = devices
        }
    }

    // MARK: - Command Handling

    private func handleCommand(_ json: [String: Any]) {
        let commandType = (json["command"] as? String) ?? (json["action"] as? String)

        guard let type = commandType else {
            print("WebSocket Warning: No 'command' or 'action' field found in message")
            if json["payload"] != nil {
                print("WebSocket: Falling back to 'play' command")
                handlePlayCommand(json)
            }
            return
        }

        print("WebSocket Handling Command: \(type)")
        switch type {
        case "play":
            handlePlayCommand(json)
        case "playlist":
            handlePlaylistCommand(json)
        case "queue_add":
            handleQueueAddCommand(json)
        case "playlist_jump":
            handlePlaylistJumpCommand(json)
        default:
            print("Unknown command: \(type)")
        }
    }

    private func handlePlayCommand(_ json: [String: Any]) {
        if let payload = json["payload"] as? [String: Any],
           let request = parsePlayRequest(payload)
        {
            print("WebSocket Play: \(request.title ?? "No Title") - \(request.url)")
            historyStore?.addToHistory(url: request.url, title: request.title, headers: request.headers)
            DispatchQueue.main.async {
                self.playlistStore?.clear()
                self.playlistStore?.addToQueue(item: request)
                self.currentPlayRequest = request
            }
        } else {
            print("WebSocket Play Error: Missing payload or failed to parse PlayRequest")
        }
    }

    private func handlePlaylistCommand(_ json: [String: Any]) {
        let data = (json["payload"] as? [String: Any]) ?? json

        guard let itemsArray = data["items"] as? [[String: Any]] else {
            print("WebSocket Playlist Error: Missing 'items' array in data: \(data)")
            return
        }
        let startIndex = data["startIndex"] as? Int ?? 0
        print("WebSocket Playlist: Received \(itemsArray.count) items, startIndex: \(startIndex)")

        let requests = itemsArray.compactMap { parsePlayRequest($0) }
        print("WebSocket Playlist: Successfully parsed \(requests.count) / \(itemsArray.count) items")

        guard !requests.isEmpty else {
            print("WebSocket Playlist Error: No valid items parsed")
            return
        }

        DispatchQueue.main.async {
            self.playlistStore?.setPlaylist(items: requests, startIndex: startIndex)
            if let first = self.playlistStore?.currentItem {
                self.historyStore?.addToHistory(url: first.url, title: first.title, headers: first.headers)
                self.currentPlayRequest = first
            }
        }
    }

    private func handleQueueAddCommand(_ json: [String: Any]) {
        let data = (json["payload"] as? [String: Any]) ?? json
        guard let item = data["item"] as? [String: Any],
              let request = parsePlayRequest(item) else { return }

        DispatchQueue.main.async {
            self.playlistStore?.addToQueue(item: request)
        }
    }

    private func handlePlaylistJumpCommand(_ json: [String: Any]) {
        let data = (json["payload"] as? [String: Any]) ?? json
        guard let index = data["index"] as? Int else { return }

        DispatchQueue.main.async {
            if let request = self.playlistStore?.jumpTo(index: index) {
                self.currentPlayRequest = request
            }
        }
    }

    private func parsePlayRequest(_ payload: [String: Any]) -> PlayRequest? {
        guard let urlString = payload["url"] as? String,
              let url = URL(string: urlString) else {
            print("WebSocket Parse Error: Missing or invalid 'url' in payload: \(payload)")
            return nil
        }

        return PlayRequest(
            url: url,
            title: payload["title"] as? String,
            headers: payload["headers"] as? [String: String],
            subtitles: payload["subtitles"] as? [String],
            preferredAudioLanguage: payload["preferredAudioLanguage"] as? String,
            preferredSubtitleLanguage: payload["preferredSubtitleLanguage"] as? String,
            visualMetadata: parseVisualMetadata(payload["visualMetadata"] as? [String: Any])
        )
    }

    private func parseVisualMetadata(_ dict: [String: Any]?) -> VisualMetadata? {
        guard let dict = dict, let title = dict["title"] as? String else { return nil }
        return VisualMetadata(
            title: title,
            overview: dict["overview"] as? String,
            posterUrl: dict["posterUrl"] as? String,
            backdropUrl: dict["backdropUrl"] as? String,
            logoUrl: dict["logoUrl"] as? String,
            year: dict["year"] as? String,
            rating: dict["rating"] as? String,
            runtime: dict["runtime"] as? String,
            season: dict["season"] as? Int,
            episode: dict["episode"] as? Int,
            episodeTitle: dict["episodeTitle"] as? String
        )
    }

    private func send(json: [String: Any], to connection: NWConnection) {
        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return }
        let context = NWConnection.ContentContext(
            identifier: "send", metadata: [NWProtocolWebSocket.Metadata(opcode: .text)])
        connection.send(
            content: data, contentContext: context, isComplete: true,
            completion: .contentProcessed({ _ in }))
    }

    private func getIPAddress() -> String {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifaddr) == 0 {
            var ptr = ifaddr
            while ptr != nil {
                let interface = ptr?.pointee
                if interface?.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                    let name = String(cString: (interface?.ifa_name)!)
                    if name == "en0" || name == "en1" {
                        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                        getnameinfo(
                            interface?.ifa_addr, socklen_t((interface?.ifa_addr.pointee.sa_len)!),
                            &hostname, socklen_t(hostname.count), nil, socklen_t(NI_MAXSERV),
                            NI_NUMERICHOST)
                        address = String(cString: hostname)
                    }
                }
                ptr = ptr?.pointee.ifa_next
            }
            freeifaddrs(ifaddr)
        }
        return address ?? "0.0.0.0"
    }
}
