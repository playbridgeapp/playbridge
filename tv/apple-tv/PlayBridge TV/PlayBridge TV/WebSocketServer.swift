//
//  WebSocketServer.swift
//  PlayBridge TV
//
//  Created by Atul Mehla on 2026-04-18.
//

import Combine
import Foundation
import Network
import PlayBridgeProtocol

class WebSocketServer: ObservableObject {
    private var listener: NWListener?
    private var connections: [String: NWConnection] = [:]
    private let port: UInt16
    private var authToken: String
    private var statusTimer: AnyCancellable?

    // Coordination
    weak var coordinator: ServerCoordinator?

    @Published var isRunning = false
    @Published var connectionCount = 0
    @Published var lastMessage: String = ""
    @Published var localIP: String = "Unknown"
    @Published var pairingPin: String = ""
    @Published var connectionState: ConnectionState = .stopped

    // Abstraction layer
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
        self.authToken = UserDefaults.standard.string(forKey: "auth_token") ?? ""
        if self.authToken.isEmpty {
            self.authToken = UUID().uuidString
            UserDefaults.standard.set(self.authToken, forKey: "auth_token")
        }
        self.pairingPin = String(self.authToken.prefix(4)).uppercased()
        self.localIP = getIPAddress() ?? "Unknown"

        setupStatusTimer()
    }

    private func setupStatusTimer() {
        statusTimer = Timer.publish(every: 1.0, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                self?.broadcastStatus()
            }
    }

    func start() {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            self.connectionState = .error(message: "Invalid port")
            return
        }

        self.connectionState = .starting

        let parameters = NWParameters(tls: nil, tcp: .init())
        let options = NWProtocolWebSocket.Options()
        parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)

        do {
            listener = try NWListener(using: parameters, on: nwPort)

            listener?.stateUpdateHandler = { [weak self] state in
                DispatchQueue.main.async {
                    switch state {
                    case .ready:
                        self?.isRunning = true
                        self?.connectionState = .running(port: self?.port ?? 0)
                        self?.localIP = self?.getIPAddress() ?? "Unknown"
                    case .failed(let error):
                        self?.isRunning = false
                        self?.connectionState = .error(message: error.localizedDescription)
                        self?.stop()
                    case .cancelled:
                        self?.isRunning = false
                        self?.connectionState = .stopped
                    default:
                        break
                    }
                }
            }

            listener?.newConnectionHandler = { [weak self] connection in
                self?.setupConnection(connection)
            }

            listener?.service = NWListener.Service(type: "_playbridge._tcp")
            listener?.start(queue: .main)
        } catch {
            self.connectionState = .error(message: error.localizedDescription)
        }
    }

    func stop() {
        listener?.cancel()
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
        isRunning = false
        connectionCount = 0
        connectionState = .stopped
    }

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
        connection.receiveMessage { [weak self] (content, context, isComplete, error) in
            guard let self = self else { return }

            if error != nil {
                connection.cancel()
                return
            }

            guard let content = content, let messageString = String(data: content, encoding: .utf8)
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
                if let token = auth.token, token == self.authToken {
                    self.completeAuth(
                        connection, clientId: clientId, success: true, provideToken: false)
                } else if let pin = auth.pin, pin.uppercased() == self.pairingPin {
                    self.completeAuth(
                        connection, clientId: clientId, success: true, provideToken: true)
                } else {
                    self.completeAuth(
                        connection, clientId: clientId, success: false, provideToken: false)
                }

            default:
                self.handleAuthentication(connection, clientId: clientId)
            }
        }
    }

    private func completeAuth(
        _ connection: NWConnection, clientId: String, success: Bool, provideToken: Bool
    ) {
        let response = AuthResponse(success: success, token: provideToken ? self.authToken : nil)

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

                // Send initial status immediately
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

    private func receive(on connection: NWConnection, clientId: String) {
        connection.receiveMessage { [weak self] (content, context, isComplete, error) in
            guard let self = self else { return }

            if error != nil {
                self.removeConnection(clientId)
                return
            }

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
        case .remote(let payload):
            print("Remote key: \(payload.key)")
        case .mouse(let payload):
            print("Mouse event: \(payload.event) dx=\(payload.dx) dy=\(payload.dy)")
        case .contextQuery:
            sendContext(to: connection)
        default:
            print("Unhandled message: \(message)")
        }
    }

    func playVideo(payload: PlayPayload) {
        DispatchQueue.main.async {
            self.playerViewModel.load(payload)
            self.coordinator?.route = .player
            self.broadcastPlaylistStatus()
        }
    }

    func handlePlayContent(payload: ContentPlayPayload) {
        self.coordinator?.handlePlayContent(payload)
    }

    func playPlaylist(payload: PlaylistPayload) {
        DispatchQueue.main.async {
            if payload.startIndex < payload.items.count {
                self.playerViewModel.load(
                    payload.items[payload.startIndex], items: payload.items,
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
            case "play": self.playerViewModel.play()
            case "pause": self.playerViewModel.pause()
            case "stop":
                self.coordinator?.route = .home
            case "seek":
                if let posMs = position {
                    self.playerViewModel.seek(to: TimeInterval(posMs) / 1000.0)
                }
            default: break
            }
            // Broadast status after control mutation
            self.broadcastStatus()
        }
    }

    private func broadcastStatus() {
        guard !connections.isEmpty else { return }
        for connection in connections.values {
            sendStatus(to: connection)
        }
        // Also broadcast playlist status periodically or on mutation
        broadcastPlaylistStatus()
    }

    private func broadcastPlaylistStatus() {
        guard !connections.isEmpty else { return }

        let playlistItems = playerViewModel.playlistItems.enumerated().map { (index, payload) in
            PlaylistItemInfo(index: index, title: payload.title ?? "Item \(index + 1)")
        }

        let status = PlaylistStatusMessage(
            items: playlistItems,
            currentIndex: playerViewModel.currentIndex,
            totalCount: playerViewModel.playlistItems.count
        )

        if let data = try? JSONEncoder().encode(status),
            let jsonString = String(data: data, encoding: .utf8)
        {
            for connection in connections.values {
                send(message: jsonString, to: connection)
            }
        }
    }

    private func sendStatus(to connection: NWConnection) {
        let status = StatusMessage(
            state: playerViewModel.state.rawValue,
            position: Int64(playerViewModel.position * 1000),
            duration: Int64(playerViewModel.duration * 1000),
            title: playerViewModel.currentTitle
        )

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

    func send(message: String, to connection: NWConnection) {
        let data = message.data(using: .utf8)
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])

        connection.send(
            content: data, contentContext: context, isComplete: true,
            completion: .contentProcessed { error in
                if let error = error {
                    print("Send error: \(error)")
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

    private func getIPAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifaddr) == 0 {
            var ptr = ifaddr
            while ptr != nil {
                defer { ptr = ptr?.pointee.ifa_next }
                let interface = ptr?.pointee
                let addrFamily = interface?.ifa_addr.pointee.sa_family
                if addrFamily == UInt8(AF_INET) {
                    let name = String(cString: (interface?.ifa_name)!)
                    if name == "en0" || name == "en1" {
                        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                        getnameinfo(
                            interface?.ifa_addr, socklen_t((interface?.ifa_addr.pointee.sa_len)!),
                            &hostname, socklen_t(hostname.count), nil, socklen_t(0), NI_NUMERICHOST)
                        address = String(cString: hostname)
                    }
                }
            }
            freeifaddrs(ifaddr)
        }
        return address
    }
}
