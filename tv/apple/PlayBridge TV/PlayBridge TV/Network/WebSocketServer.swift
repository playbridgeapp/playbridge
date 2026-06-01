import Foundation
import Network
import SwiftUI
import UIKit
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
    private var tlsListener: NWListener?
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
    // Bound wss:// port (the port external senders connect to). Nil until TLS starts.
    @Published var wssPort: UInt16?

    var deviceName: String { UIDevice.current.name }
    private let authorizedTokensKey = "pb_authorized_tokens"
    private let deviceUUIDKey = "pb_device_uuid"
    private let pairedDevicesKey = "pb_paired_devices"

    private var autoTimeoutWork: DispatchWorkItem?
    private var keepaliveTimer: Timer?
    private var restartWork: DispatchWorkItem?
    private var restartAttempts = 0

    /// SPKI pin of our TLS cert, sent to senders at pairing. Nil until the
    /// wss:// listener starts.
    private var certFingerprint: String?

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

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func handleForeground() {
        if serverState != "Ready to Connect" { restart() }
    }

    private let allowInsecureKey = "pb_allow_insecure"

    /// When false (default) the receiver serves wss:// only; ws:// is opt-in for
    /// legacy senders that can't pin a self-signed cert.
    var allowInsecure: Bool {
        get { UserDefaults.standard.bool(forKey: allowInsecureKey) }
        set { UserDefaults.standard.set(newValue, forKey: allowInsecureKey) }
    }

    func start(port: UInt16 = 8765) {
        let wssPort = port + 1
        let insecure = allowInsecure

        // wss is the default transport. It carries the mDNS service unless ws is
        // enabled, in which case ws carries it so legacy clients can reach 8765.
        let tlsUp = startTLSListener(
            port: wssPort,
            service: insecure ? nil : makeBonjourService(wssPort: wssPort)
        )

        // ws only when explicitly opted in — no silent plaintext fallback.
        if insecure {
            startPlaintextListener(
                port: port,
                service: makeBonjourService(wssPort: tlsUp ? wssPort : nil)
            )
        } else if !tlsUp {
            // Fail closed: no listener is running, so surface why.
            DispatchQueue.main.async {
                self.serverState = "Secure server failed — enable Allow Insecure in Settings"
            }
        }
        startKeepalive()
    }

    private func makeBonjourService(wssPort: UInt16?) -> NWListener.Service {
        var txtDict: [String: Data] = [
            "uuid": deviceUUID.data(using: .utf8)!,
            "device_name": deviceName.data(using: .utf8)!,
        ]
        if let wssPort {
            txtDict["wss_port"] = String(wssPort).data(using: .utf8)!
        }
        return NWListener.Service(
            name: deviceName, type: "_playbridge._tcp", domain: nil,
            txtRecord: NetService.data(fromTXTRecord: txtDict))
    }

    private func makeTCPOptions() -> NWProtocolTCP.Options {
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 60
        tcp.keepaliveInterval = 30
        tcp.keepaliveCount = 3
        return tcp
    }

    /// The listener carrying the bonjour service is "primary" and drives the UI
    /// serverState + restart; a secondary listener just logs.
    private func makeStateHandler(label: String, primary: Bool) -> (NWListener.State) -> Void {
        return { [weak self] state in
            if primary {
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    switch state {
                    case .ready:
                        self.serverState = "Ready to Connect"
                        self.restartAttempts = 0
                    case .failed(let error):
                        self.serverState = "Error: \(error.localizedDescription)"
                        self.scheduleRestart()
                    case .waiting(let error):
                        self.serverState = "Waiting: \(error.localizedDescription)"
                    case .setup: self.serverState = "Starting..."
                    case .cancelled: self.serverState = "Stopped"
                    default: break
                    }
                }
            } else {
                switch state {
                case .ready: print("[\(label)] ready")
                case .failed(let error): print("[\(label)] failed: \(error)")
                default: break
                }
            }
        }
    }

    private func startPlaintextListener(port: UInt16, service: NWListener.Service?) {
        let parameters = NWParameters(tls: nil, tcp: makeTCPOptions())
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)
        do {
            let l = try NWListener(using: parameters, on: NWEndpoint.Port(integerLiteral: port))
            l.service = service
            l.stateUpdateHandler = makeStateHandler(label: "ws", primary: service != nil)
            l.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            l.start(queue: .main)
            listener = l
            print("[ws] listening on \(port)")
        } catch { print("Server error: \(error)") }
    }

    /// Starts the encrypted wss:// listener on [port], reusing the plaintext
    /// connection handler. Returns false if the TLS identity is unavailable or
    /// the listener fails to bind (in which case we serve ws:// only).
    @discardableResult
    private func startTLSListener(port: UInt16, service: NWListener.Service?) -> Bool {
        let identity: TLSIdentity.Result
        do {
            identity = try TLSIdentity.loadOrCreate(commonName: deviceName)
        } catch {
            print("[wss] TLS identity unavailable — encrypted listener disabled: \(error)")
            return false
        }
        certFingerprint = identity.fingerprint

        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(
            tlsOptions.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_local_identity(
            tlsOptions.securityProtocolOptions, identity.identity)

        let parameters = NWParameters(tls: tlsOptions, tcp: makeTCPOptions())
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        do {
            let l = try NWListener(using: parameters, on: NWEndpoint.Port(integerLiteral: port))
            l.service = service
            l.stateUpdateHandler = makeStateHandler(label: "wss", primary: service != nil)
            l.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            l.start(queue: .main)
            tlsListener = l
            DispatchQueue.main.async { self.wssPort = port }
            print("[wss] listening on \(port) (pin \(identity.fingerprint))")
            return true
        } catch {
            print("[wss] listener error: \(error)")
            return false
        }
    }

    func stop() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = nil
        restartWork?.cancel()
        restartWork = nil
        listener?.stateUpdateHandler = nil
        listener?.newConnectionHandler = nil
        listener?.cancel()
        listener = nil
        tlsListener?.stateUpdateHandler = nil
        tlsListener?.newConnectionHandler = nil
        tlsListener?.cancel()
        tlsListener = nil
        certFingerprint = nil
        for connection in connectedConnections { connection.cancel() }
        connectedConnections.removeAll()
        DispatchQueue.main.async {
            self.connectedCount = 0
            self.isAuthenticated = false
            self.pendingPairingRequest = nil
            self.serverState = "Stopped"
            self.wssPort = nil
        }
    }

    func restart(port: UInt16 = 8765) {
        stop()
        start(port: port)
    }

    private func scheduleRestart(port: UInt16 = 8765) {
        restartWork?.cancel()
        let delay = min(pow(2.0, Double(restartAttempts)), 30.0)
        restartAttempts += 1
        let work = DispatchWorkItem { [weak self] in self?.restart(port: port) }
        restartWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
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
                handleCommand(action: json["action"] as? String, payload: json["payload"], from: connection)
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

        var approved: [String: Any] = ["type": "pairing_approved", "token": token]
        if let fp = certFingerprint { approved["certFingerprint"] = fp }
        approved["players"] = Self.capabilityPlayers
        send(json: approved, to: request.connection)
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

    /// Players this receiver advertises to the phone at auth, so the phone's player picker
    /// shows "TV Default" + AVPlayer + VLC. A concrete choice is honored per cast in
    /// `PlayerView` via the play payload's `playerMode`. (No browsers — Apple TV has no web view.)
    static let capabilityPlayers = ["avplayer", "vlc"]

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
            var response: [String: Any] = ["type": "auth_response", "success": true, "token": token]
            if let fp = self.certFingerprint { response["certFingerprint"] = fp }
            response["players"] = Self.capabilityPlayers
            self.send(json: response, to: connection)
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

    private func handleCommand(action: String?, payload: Any?, from connection: NWConnection) {
        guard let action = action else {
            print("WebSocket Command Error: missing 'action'")
            return
        }

        // context_query carries no payload — answer it (player vs idle; Apple TV has no
        // browser) before the payload guard below would otherwise reject it.
        if action == "context_query" {
            DispatchQueue.main.async {
                let active = self.currentPlayRequest != nil ? "player" : "idle"
                self.send(json: ["type": "context", "active": active], to: connection)
            }
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
