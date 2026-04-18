//
//  WebSocketServer.swift
//  PlayBridge TV
//

import Combine
import Foundation
import Network
import PlayBridgeProtocol

class WebSocketServer: ObservableObject {
    private var listener: NWListener?
    private var connections: [String: NWConnection] = [:]
    private let port: UInt16
    private var statusTimer: AnyCancellable?

    // Pairing — backed by Keychain via PairingStore
    private let authToken: String
    @Published private(set) var pairingPin: String = ""

    // NSD retry
    private var retryCount = 0
    private let maxRetries = 4
    private let retryDelays: [TimeInterval] = [3, 6, 9, 12]

    // Coordination
    weak var coordinator: ServerCoordinator?

    @Published var isRunning = false
    @Published var connectionCount = 0
    @Published var lastMessage: String = ""
    @Published var localIP: String = "Unknown"
    @Published var connectionState: ConnectionState = .stopped

    // Player abstraction layer
    @Published var playerViewModel = PlayerViewModel()

    enum ConnectionState {
        case stopped
        case starting
        case running(port: UInt16)
        case connected(clientId: String)
        case error(message: String)

        var description: String {
            switch self {
            case .stopped: return "Stopped"
            case .starting: return "Starting"
            case .running(let port): return "Running on port \(port)"
            case .connected(let clientId): return "Connected: \(clientId)"
            case .error(let message): return "Error: \(message)"
            }
        }
    }

    init(port: UInt16 = 8765) {
        self.port = port
        // Auth token is now Keychain-backed via PairingStore (migrates UserDefaults automatically)
        self.authToken = PairingStore.shared.authToken
        self.pairingPin = String(self.authToken.prefix(4)).uppercased()
        self.localIP = getIPAddress() ?? "Unknown"

        // Start the subtitle HTTP server so VLC can slave cached subtitle files
        SubtitleHTTPServer.shared.start()

        setupStatusTimer()
    }

    // MARK: - Status Timer

    private func setupStatusTimer() {
        statusTimer = Timer.publish(every: 1.0, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                // Gate broadcasts to player context — avoids spamming the phone when idle
                guard self?.coordinator?.route == .player else { return }
                self?.broadcastStatus()
            }
    }

    // MARK: - Start / Stop

    func start() {
        retryCount = 0
        startListener()
    }

    private func startListener() {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            connectionState = .error(message: "Invalid port \(port)")
            return
        }
        connectionState = .starting

        let parameters = NWParameters(tls: nil, tcp: .init())
        let wsOptions = NWProtocolWebSocket.Options()
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        do {
            listener = try NWListener(using: parameters, on: nwPort)

            listener?.stateUpdateHandler = { [weak self] state in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    switch state {
                    case .ready:
                        self.retryCount = 0
                        self.isRunning = true
                        self.connectionState = .running(port: self.port)
                        self.localIP = getIPAddress() ?? "Unknown"
                    case .failed(let error):
                        self.isRunning = false
                        self.connectionState = .error(message: error.localizedDescription)
                        self.stop()
                        self.scheduleRetry()
                    case .cancelled:
                        self.isRunning = false
                        self.connectionState = .stopped
                    default:
                        break
                    }
                }
            }

            listener?.newConnectionHandler = { [weak self] connection in
                self?.setupConnection(connection)
            }

            // NSD registration with TXT records so the phone can distinguish TVs by UUID
            var txtRecord = NWTXTRecord()
            txtRecord.setEntry(NWTXTRecord.Entry(Data(PairingStore.shared.deviceUUID.utf8)), for: "uuid")
            if localIP != "Unknown" {
                txtRecord.setEntry(NWTXTRecord.Entry(Data(localIP.utf8)), for: "custom_ip")
            }
            listener?.service = NWListener.Service(
                name: nil, type: "_playbridge._tcp", domain: nil, txtRecord: txtRecord)

            listener?.start(queue: .main)
        } catch {
            connectionState = .error(message: error.localizedDescription)
            scheduleRetry()
        }
    }

    /// Retry with backoff (3 / 6 / 9 / 12 s) to survive mDNS races after server restart.
    private func scheduleRetry() {
        guard retryCount < maxRetries else {
            print("[WebSocketServer] Max retries reached. Give up.")
            return
        }
        let delay = retryDelays[min(retryCount, retryDelays.count - 1)]
        retryCount += 1
        print("[WebSocketServer] Retry \(retryCount)/\(maxRetries) in \(delay)s")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.startListener()
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
        isRunning = false
        connectionCount = 0
        connectionState = .stopped
    }

    // MARK: - Connection Lifecycle

    private func setupConnection(_ connection: NWConnection) {
        let clientId = UUID().uuidString
        connection.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                switch state {
                case .ready:
                    self?.handleAuthentication(connection, clientId: clientId)
                case .failed, .cancelled:
                    self?.removeConnection(clientId)
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }

    private func handleAuthentication(_ connection: NWConnection, clientId: String) {
        connection.receiveMessage { [weak self] (content, _, _, error) in
            guard let self = self else { return }
            if error != nil { connection.cancel(); return }

            guard let content = content,
                let messageString = String(data: content, encoding: .utf8)
            else {
                self.handleAuthentication(connection, clientId: clientId)
                return
            }

            let message = PlayBridgeProtocol.decode(messageString)
            switch message {
            case .ping:
                self.sendPong(to: connection)
                self.handleAuthentication(connection, clientId: clientId)

            case .requestPairing:
                self.send(json: ["type": "pairing_ack"], to: connection)
                self.handleAuthentication(connection, clientId: clientId)

            case .auth(let auth):
                // Loopback short-circuit: local tooling connecting from 127.0.0.1/::1 auto-passes
                if self.isLoopbackConnection(connection) {
                    self.completeAuth(connection, clientId: clientId, success: true, provideToken: false)
                } else if let token = auth.token, token == self.authToken {
                    self.completeAuth(connection, clientId: clientId, success: true, provideToken: false)
                } else if let pin = auth.pin, pin.uppercased() == self.pairingPin {
                    self.completeAuth(connection, clientId: clientId, success: true, provideToken: true)
                } else {
                    self.completeAuth(connection, clientId: clientId, success: false, provideToken: false)
                }

            default:
                self.handleAuthentication(connection, clientId: clientId)
            }
        }
    }

    private func isLoopbackConnection(_ connection: NWConnection) -> Bool {
        if case .hostPort(let host, _) = connection.endpoint {
            let h = "\(host)"
            return h == "127.0.0.1" || h == "::1" || h == "localhost"
        }
        return false
    }

    private func completeAuth(
        _ connection: NWConnection, clientId: String, success: Bool, provideToken: Bool
    ) {
        let response = AuthResponse(success: success, token: provideToken ? authToken : nil)
        if let data = try? JSONEncoder().encode(response),
            let jsonString = String(data: data, encoding: .utf8)
        {
            send(message: jsonString, to: connection)
        }
        if success {
            DispatchQueue.main.async {
                self.connections[clientId] = connection
                self.connectionCount = self.connections.count
                self.connectionState = .connected(clientId: clientId)
                self.sendStatus(to: connection)
                self.receive(on: connection, clientId: clientId)
            }
        } else {
            connection.cancel()
        }
    }

    private func removeConnection(_ clientId: String) {
        connections.removeValue(forKey: clientId)
        connectionCount = connections.count
        if connections.isEmpty && isRunning {
            connectionState = .running(port: port)
        }
    }

    // MARK: - Message Receive Loop

    private func receive(on connection: NWConnection, clientId: String) {
        connection.receiveMessage { [weak self] (content, _, _, error) in
            guard let self = self else { return }
            if error != nil { self.removeConnection(clientId); return }

            if let content = content, let messageString = String(data: content, encoding: .utf8) {
                DispatchQueue.main.async {
                    self.lastMessage = messageString
                    self.handleIncomingMessage(messageString, from: connection, clientId: clientId)
                }
            }
            if error == nil {
                self.receive(on: connection, clientId: clientId)
            }
        }
    }

    // MARK: - Command Dispatch

    private func handleIncomingMessage(
        _ jsonString: String, from connection: NWConnection, clientId: String
    ) {
        let message = PlayBridgeProtocol.decode(jsonString)

        switch message {
        case .ping:
            sendPong(to: connection)

        case .play(let payload):
            playVideo(payload: payload)

        case .playContent(let payload):
            handlePlayContent(payload: payload)

        case .playlist(let payload):
            playPlaylist(payload: payload)

        case .queueAdd(let payload):
            queueAdd(payload: payload)

        case .playlistJump(let payload):
            playlistJump(payload: payload)

        case .control(let payload, let position):
            handleControl(command: payload.command, position: position)

        case .contextQuery:
            sendContext(to: connection)

        // Browser, remote and mouse commands are not supported on tvOS.
        // Reply with a typed error so the phone can grey out the relevant UI affordance.
        case .browser:
            sendUnsupported(command: "browser", to: connection)

        case .browserControl:
            sendUnsupported(command: "browser_control", to: connection)

        case .remote:
            sendUnsupported(command: "remote", to: connection)

        case .mouse:
            sendUnsupported(command: "mouse", to: connection)

        default:
            print("[WebSocketServer] Unhandled message: \(message)")
        }
    }

    // MARK: - Playback Commands

    func playVideo(payload: PlayPayload) {
        DispatchQueue.main.async {
            self.playerViewModel.load(payload)
            self.coordinator?.route = .player
            self.broadcastPlaylistStatus()
        }
    }

    func handlePlayContent(payload: ContentPlayPayload) {
        coordinator?.handlePlayContent(payload)
    }

    func playPlaylist(payload: PlaylistPayload) {
        DispatchQueue.main.async {
            if payload.startIndex < payload.items.count {
                self.playerViewModel.load(
                    payload.items[payload.startIndex],
                    items: payload.items,
                    index: payload.startIndex)
                self.coordinator?.route = .player
                self.broadcastPlaylistStatus()
            }
        }
    }

    func queueAdd(payload: QueueAddPayload) {
        DispatchQueue.main.async {
            self.playerViewModel.queueAdd(payload.item)
            self.broadcastPlaylistStatus()
        }
    }

    func playlistJump(payload: PlaylistJumpPayload) {
        DispatchQueue.main.async {
            self.playerViewModel.jumpTo(index: payload.index)
            self.broadcastPlaylistStatus()
        }
    }

    private func handleControl(command: String, position: Int64?) {
        DispatchQueue.main.async {
            switch command {
            case "play":  self.playerViewModel.play()
            case "pause": self.playerViewModel.pause()
            case "stop":  self.coordinator?.route = .home
            case "seek":
                if let posMs = position {
                    self.playerViewModel.seek(to: TimeInterval(posMs) / 1000.0)
                }
            default: break
            }
            self.broadcastStatus()
        }
    }

    // MARK: - Outbound Broadcast

    private func broadcastStatus() {
        guard !connections.isEmpty else { return }
        for connection in connections.values {
            sendStatus(to: connection)
        }
        broadcastPlaylistStatus()
    }

    private func broadcastPlaylistStatus() {
        guard !connections.isEmpty else { return }
        let items = playerViewModel.playlistItems.enumerated().map { (i, p) in
            PlaylistItemInfo(index: i, title: p.title ?? "Item \(i + 1)")
        }
        let status = PlaylistStatusMessage(
            items: items,
            currentIndex: playerViewModel.currentIndex,
            totalCount: playerViewModel.playlistItems.count)
        if let data = try? JSONEncoder().encode(status),
            let jsonString = String(data: data, encoding: .utf8)
        {
            connections.values.forEach { send(message: jsonString, to: $0) }
        }
    }

    private func sendStatus(to connection: NWConnection) {
        let status = StatusMessage(
            state: playerViewModel.state.rawValue,
            position: Int64(playerViewModel.position * 1000),
            duration: Int64(playerViewModel.duration * 1000),
            title: playerViewModel.currentTitle)
        if let data = try? JSONEncoder().encode(status),
            let jsonString = String(data: data, encoding: .utf8)
        {
            send(message: jsonString, to: connection)
        }
    }

    private func sendPong(to connection: NWConnection) {
        send(message: "{\"type\":\"pong\"}", to: connection)
    }

    private func sendContext(to connection: NWConnection) {
        let active = coordinator?.route == .player ? "player" : "idle"
        let context = ContextMessage(active: active)
        if let data = try? JSONEncoder().encode(context),
            let jsonString = String(data: data, encoding: .utf8)
        {
            send(message: jsonString, to: connection)
        }
    }

    /// Reply to unsupported commands (browser, browserControl, remote, mouse) so the phone
    /// can degrade its UI when paired with a tvOS receiver.
    private func sendUnsupported(command: String, to connection: NWConnection) {
        send(
            json: [
                "type": "command_unsupported",
                "command": command,
                "reason": "tvos_receiver",
            ], to: connection)
    }

    // MARK: - Send Helpers

    func send(message: String, to connection: NWConnection) {
        let data = message.data(using: .utf8)
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        connection.send(
            content: data, contentContext: context, isComplete: true,
            completion: .contentProcessed { error in
                if let error = error {
                    print("[WebSocketServer] Send error: \(error)")
                }
            })
    }

    private func send(json: [String: Any], to connection: NWConnection) {
        if let data = try? JSONSerialization.data(withJSONObject: json),
            let string = String(data: data, encoding: .utf8)
        {
            send(message: string, to: connection)
        }
    }
}

// MARK: - IP Helper

private func getIPAddress() -> String? {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0 else { return nil }
    defer { freeifaddrs(ifaddr) }

    var ptr = ifaddr
    while let current = ptr {
        defer { ptr = current.pointee.ifa_next }
        let interface = current.pointee
        guard interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
        let name = String(cString: interface.ifa_name)
        guard name == "en0" || name == "en1" else { continue }
        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        getnameinfo(
            interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
            &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
        address = String(cString: hostname)
    }
    return address
}
