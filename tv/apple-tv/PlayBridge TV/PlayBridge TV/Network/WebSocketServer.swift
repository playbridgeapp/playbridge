import Foundation
import Network
import SwiftUI
import Combine
import SwiftProtobuf

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

    @Published var currentPlayRequest: Playbridge_PlayPayload?
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
            for connection in self.connectedConnections { self.sendPing(to: connection) }
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
            if !isViable { self?.removeConnection(connection) }
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
            if let content = content, let jsonString = String(data: content, encoding: .utf8) {
                self?.handleMessage(jsonString, data: content, from: connection)
            }
            self?.receiveMessages(from: connection)
        }
    }

    // MARK: - Message Dispatch
    // Use JSONSerialization for the outer envelope (proto MessageEnvelope omits `payload`).
    // Use SwiftProtobuf JSON for all typed sub-messages.

    private func handleMessage(_ jsonString: String, data: Data, from connection: NWConnection) {
        print("WebSocket Received: \(jsonString)")
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let msgType = json["type"] as? String else {
            print("WebSocket Error: Failed to parse message")
            return
        }

        switch msgType {
        case "ping":
            send(json: ["type": "pong"], to: connection)
        case "pairing_request":
            if let msg = try? Playbridge_PairingRequestMessage(jsonString: jsonString) {
                handlePairingRequest(msg, from: connection)
            }
        case "auth":
            if let msg = try? Playbridge_AuthMessage(jsonString: jsonString) {
                handleAuth(msg, from: connection)
            }
        case "command":
            if isAuthenticated {
                handleCommand(action: json["action"] as? String, payload: json["payload"])
            }
        default:
            break
        }
    }

    // MARK: - Pairing

    private func handlePairingRequest(_ msg: Playbridge_PairingRequestMessage, from connection: NWConnection) {
        if pendingPairingRequest != nil {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }

        let request = PairingRequest(
            deviceName: msg.deviceName, deviceUUID: msg.deviceUuid, connection: connection)
        DispatchQueue.main.async { self.pendingPairingRequest = request }

        autoTimeoutWork?.cancel()
        let uuid = msg.deviceUuid
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.pendingPairingRequest?.deviceUUID == uuid else { return }
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

    // MARK: - Auth

    private func handleAuth(_ msg: Playbridge_AuthMessage, from connection: NWConnection) {
        guard msg.hasToken else {
            send(json: ["type": "auth_response", "success": false], to: connection)
            return
        }
        if authorizedTokens.contains(msg.token) {
            updateLastConnected(token: msg.token)
            completeAuth(from: connection, token: msg.token)
        } else {
            send(json: ["type": "auth_response", "success": false], to: connection)
        }
    }

    private func completeAuth(from connection: NWConnection, token: String) {
        DispatchQueue.main.async {
            self.isAuthenticated = true
            self.connectedConnections.append(connection)
            self.connectedCount = self.connectedConnections.count
            self.send(json: ["type": "auth_response", "success": true, "token": token], to: connection)
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

    private func handleCommand(action: String?, payload: Any?) {
        guard let action = action else {
            print("WebSocket Command Error: missing 'action'")
            return
        }
        guard let payloadObj = payload else {
            print("WebSocket Command Error: missing 'payload' for action \(action)")
            return
        }
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payloadObj),
              let payloadJson = String(data: payloadData, encoding: .utf8) else {
            print("WebSocket Command Error: failed to re-encode payload for action \(action)")
            return
        }

        print("WebSocket Handling Command: \(action)")

        switch action {
        case "play":
            if let p = try? Playbridge_PlayPayload(jsonString: payloadJson), p.validURL != nil {
                handlePlay(p)
            } else {
                print("WebSocket Play Error: Failed to decode PlayPayload or invalid URL")
            }
        case "playlist":
            if let p = try? Playbridge_PlaylistPayload(jsonString: payloadJson) {
                handlePlaylist(p)
            } else {
                print("WebSocket Playlist Error: Failed to decode PlaylistPayload")
            }
        case "queue_add":
            if let p = try? Playbridge_QueueAddPayload(jsonString: payloadJson),
               p.hasItem, p.item.validURL != nil {
                let item = p.item
                DispatchQueue.main.async { self.playlistStore?.addToQueue(item: item) }
            }
        case "playlist_jump":
            if let p = try? Playbridge_PlaylistJumpPayload(jsonString: payloadJson) {
                DispatchQueue.main.async {
                    if let req = self.playlistStore?.jumpTo(index: Int(p.index)) {
                        self.currentPlayRequest = req
                    }
                }
            }
        default:
            print("Unknown command action: \(action)")
        }
    }

    private func handlePlay(_ payload: Playbridge_PlayPayload) {
        let url = payload.validURL!  // pre-validated by caller
        print("WebSocket Play: \(payload.titleOrNil ?? "No Title") - \(url)")
        historyStore?.addToHistory(url: url, title: payload.titleOrNil, headers: payload.headersOrNil)
        DispatchQueue.main.async {
            self.playlistStore?.clear()
            self.playlistStore?.addToQueue(item: payload)
            self.currentPlayRequest = payload
        }
    }

    private func handlePlaylist(_ payload: Playbridge_PlaylistPayload) {
        let valid = payload.items.filter { $0.validURL != nil }
        print("WebSocket Playlist: \(valid.count)/\(payload.items.count) items, startIndex: \(payload.startIndex)")
        guard !valid.isEmpty else { return }
        DispatchQueue.main.async {
            self.playlistStore?.setPlaylist(items: valid, startIndex: Int(payload.startIndex))
            if let first = self.playlistStore?.currentItem, let firstURL = first.validURL {
                self.historyStore?.addToHistory(url: firstURL, title: first.titleOrNil, headers: first.headersOrNil)
                self.currentPlayRequest = first
            }
        }
    }

    // MARK: - Send

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
