import Foundation
import Network
import SwiftUI
import Combine

// MARK: - Server Logic
class WebSocketServer: ObservableObject {
    private var listener: NWListener?
    private var connectedConnections: [NWConnection] = []
    private var historyStore: HistoryStore?
    var playlistStore: PlaylistStore?

    @Published var currentPlayRequest: PlayRequest?
    @Published var isAuthenticated = false
    @Published var pairingPin: String = ""
    @Published var connectedCount: Int = 0
    @Published var localIP: String = "0.0.0.0"
    @Published var serverState: String = "Stopped"

    var deviceName: String { UIDevice.current.name }
    private let serverTokenKey = "pb_server_token"
    private let authorizedTokensKey = "pb_authorized_tokens"
    private let deviceUUIDKey = "pb_device_uuid"

    private var deviceUUID: String {
        if let uuid = UserDefaults.standard.string(forKey: deviceUUIDKey) { return uuid }
        let newUUID = UUID().uuidString
        UserDefaults.standard.set(newUUID, forKey: deviceUUIDKey)
        return newUUID
    }

    private var serverToken: String {
        if let token = UserDefaults.standard.string(forKey: serverTokenKey) { return token }
        let newToken = UUID().uuidString
        UserDefaults.standard.set(newToken, forKey: serverTokenKey)
        return newToken
    }

    private var authorizedTokens: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: authorizedTokensKey) ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: authorizedTokensKey) }
    }

    init(historyStore: HistoryStore? = nil, playlistStore: PlaylistStore? = nil) {
        self.historyStore = historyStore
        self.playlistStore = playlistStore
        self.pairingPin = String(format: "%04d", Int.random(in: 0...9999))
        self.localIP = getIPAddress()
    }

    func start(port: UInt16 = 8765) {
        let parameters = NWParameters.tcp
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
        } catch { print("Server error: \(error)") }
    }

    private func handleNewConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.removeConnection(connection)
            default: break
            }
        }
        connection.start(queue: .main)
        receiveMessages(from: connection)
    }

    private func removeConnection(_ connection: NWConnection) {
        DispatchQueue.main.async {
            self.connectedConnections.removeAll(where: { $0 === connection })
            self.connectedCount = self.connectedConnections.count
            if self.connectedConnections.isEmpty { self.isAuthenticated = false }
        }
    }

    private func receiveMessages(from connection: NWConnection) {
        connection.receiveMessage { [weak self] content, _, _, error in
            if error != nil {
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
        case "auth": handleAuth(json, from: connection)
        case "command": if isAuthenticated { handleCommand(json) }
        default: break
        }
    }

    private func handleAuth(_ json: [String: Any], from connection: NWConnection) {
        let pin = json["pin"] as? String
        let token = json["token"] as? String
        if let token = token, authorizedTokens.contains(token) {
            completeAuth(from: connection, token: token)
        } else if let pin = pin, pin == self.pairingPin {
            let newToken = serverToken
            authorizedTokens.insert(newToken)
            completeAuth(from: connection, token: newToken)
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

    private func handleCommand(_ json: [String: Any]) {
        let commandType = (json["command"] as? String) ?? (json["action"] as? String)
        
        guard let type = commandType else {
            print("WebSocket Warning: No 'command' or 'action' field found in message")
            // Legacy/fallback: if no explicit command field, treat as "play" if payload exists
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
        // Items and startIndex might be at top level OR inside payload
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
            preferredSubtitleLanguage: payload["preferredSubtitleLanguage"] as? String
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
