import AVKit
import Combine
import Foundation
import Network
import SwiftUI
import TVVLCKit
import UIKit

// MARK: - VLC Local Proxy (for custom HTTP headers)

/// Manages the upstream URLSession and routes streamed data to the correct
/// NWConnection without ever blocking a thread (no semaphores).
/// Each connection owns a serial DispatchQueue that serialises NWConnection
/// sends so chunks arrive in order without overlap.
class ProxySessionManager: NSObject, URLSessionDataDelegate {
    static let shared = ProxySessionManager()

    private struct ConnState {
        let connection: NWConnection
        let sendQueue: DispatchQueue
    }

    private var states: [Int: ConnState] = [:]
    private let lock = NSLock()

    lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 0
        config.httpMaximumConnectionsPerHost = 8
        let opQueue = OperationQueue()
        opQueue.maxConcurrentOperationCount = OperationQueue.defaultMaxConcurrentOperationCount
        return URLSession(configuration: config, delegate: self, delegateQueue: opQueue)
    }()

    func proxy(request: URLRequest, to connection: NWConnection) {
        let task = session.dataTask(with: request)
        let q = DispatchQueue(label: "vlc-proxy-send-\(task.taskIdentifier)", qos: .userInitiated)
        lock.lock()
        states[task.taskIdentifier] = ConnState(connection: connection, sendQueue: q)
        lock.unlock()
        task.resume()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        let state = states[dataTask.taskIdentifier]
        lock.unlock()

        guard let state = state, let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            return
        }

        var header = "HTTP/1.1 \(http.statusCode) \(HTTPURLResponse.localizedString(forStatusCode: http.statusCode))\r\n"
        for (key, value) in http.allHeaderFields {
            if let key = key as? String {
                header += "\(key): \(value)\r\n"
            }
        }
        header += "\r\n"

        // Enqueue header send asynchronously - never blocks URLSession callbacks
        state.sendQueue.async {
            state.connection.send(content: header.data(using: .utf8), completion: .contentProcessed({ _ in }))
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let state = states[dataTask.taskIdentifier]
        lock.unlock()
        guard let state = state else { return }

        let chunk = data
        state.sendQueue.async {
            state.connection.send(content: chunk, completion: .contentProcessed({ _ in }))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let state = states.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        guard let state = state else { return }

        if let error = error as? NSError, error.code != NSURLErrorCancelled {
            print("VLC Proxy upstream error: \(error.localizedDescription)")
        }

        state.sendQueue.async {
            state.connection.send(
                content: nil,
                contentContext: .finalMessage,
                isComplete: true,
                completion: .contentProcessed({ _ in state.connection.cancel() })
            )
        }
    }
}

/// Lightweight local HTTP proxy that intercepts VLC connections and
/// re-issues them upstream with custom headers via URLSession.
class VLCProxyServer {
    private let targetURL: URL
    private let headers: [String: String]
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    private let listenerQueue = DispatchQueue(label: "vlc-proxy-listener", qos: .userInitiated)

    var localURL: URL {
        var c = URLComponents()
        c.scheme = "http"
        c.host = "127.0.0.1"
        c.port = Int(port)
        c.path = targetURL.path.isEmpty ? "/" : targetURL.path
        c.query = targetURL.query
        return c.url!
    }

    init(targetURL: URL, headers: [String: String]) {
        self.targetURL = targetURL
        self.headers = headers
    }

    func start() {
        guard let newListener = try? NWListener(using: .tcp, on: .any) else { return }
        self.listener = newListener

        let semaphore = DispatchSemaphore(value: 0)
        newListener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.port = newListener.port?.rawValue ?? 0
                semaphore.signal()
            case .failed:
                semaphore.signal()
            default: break
            }
        }
        newListener.newConnectionHandler = { [weak self] conn in
            let q = DispatchQueue(label: "vlc-proxy-conn", qos: .userInitiated)
            self?.handleConnection(conn, on: q)
        }
        newListener.start(queue: listenerQueue)
        _ = semaphore.wait(timeout: .now() + 3)
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handleConnection(_ connection: NWConnection, on queue: DispatchQueue) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, _, error in
            guard let self = self, error == nil, let data = data,
                  let req = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }

            let lines = req.components(separatedBy: "\r\n")
            guard let requestLine = lines.first else { connection.cancel(); return }

            let parts = requestLine.split(separator: " ")
            let method = parts.count > 0 ? String(parts[0]) : "GET"
            let rawPath = parts.count > 1 ? String(parts[1]) : "/"

            let upstreamURL: URL
            if rawPath == self.targetURL.path || rawPath == "/" {
                upstreamURL = self.targetURL
            } else if let abs = URL(string: rawPath, relativeTo: self.targetURL)?.absoluteURL {
                upstreamURL = abs
            } else {
                var c = URLComponents()
                c.scheme = self.targetURL.scheme; c.host = self.targetURL.host
                c.port = self.targetURL.port;     c.path = rawPath
                upstreamURL = c.url ?? self.targetURL
            }

            var urlRequest = URLRequest(url: upstreamURL)
            urlRequest.httpMethod = method

            for line in lines.dropFirst() {
                guard !line.isEmpty, let colon = line.firstIndex(of: ":") else { continue }
                let k = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
                let v = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                switch k.lowercased() {
                case "range", "accept-encoding", "accept": urlRequest.setValue(v, forHTTPHeaderField: k)
                default: break
                }
            }

            for (k, v) in self.headers { urlRequest.setValue(v, forHTTPHeaderField: k) }

            ProxySessionManager.shared.proxy(request: urlRequest, to: connection)
        }
    }
}

// MARK: - Models

struct PlayRequest: Equatable {
    let url: URL
    let headers: [String: String]?
}

struct PlaybackHistoryItem: Identifiable, Codable, Equatable {
    var id: String { url.absoluteString }
    let url: URL
    let title: String?
    let timestamp: Date
    var isFavorite: Bool
    let headers: [String: String]?
}

// MARK: - Theme Constants
enum Theme {
    static let accent = Color(red: 0.0, green: 0.85, blue: 0.9)
    static let secondaryText = Color.white.opacity(0.5)
}

// MARK: - Stores
class HistoryStore: ObservableObject {
    @Published var history: [PlaybackHistoryItem] = []
    private let historyKey = "pb_playback_history"

    init() { loadHistory() }

    func loadHistory() {
        if let data = UserDefaults.standard.data(forKey: historyKey),
            let decoded = try? JSONDecoder().decode([PlaybackHistoryItem].self, from: data)
        {
            DispatchQueue.main.async { self.history = decoded }
        }
    }

    func addToHistory(url: URL, title: String?, headers: [String: String]?) {
        let newItem = PlaybackHistoryItem(
            url: url, title: title ?? "Unknown Media", timestamp: Date(), isFavorite: false,
            headers: headers
        )
        DispatchQueue.main.async {
            self.history.removeAll { $0.url == url }
            self.history.insert(newItem, at: 0)
            if self.history.count > 100 { self.history = Array(self.history.prefix(100)) }
            self.saveHistory()
        }
    }

    func toggleFavorite(item: PlaybackHistoryItem) {
        if let index = history.firstIndex(where: { $0.url == item.url }) {
            history[index].isFavorite.toggle()
            saveHistory()
        }
    }

    func clearHistory() {
        history.removeAll()
        saveHistory()
    }

    private func saveHistory() {
        if let encoded = try? JSONEncoder().encode(history) {
            UserDefaults.standard.set(encoded, forKey: historyKey)
        }
    }
}

// MARK: - Server Logic
class WebSocketServer: ObservableObject {
    private var listener: NWListener?
    private var connectedConnections: [NWConnection] = []
    private var historyStore: HistoryStore?

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

    init(historyStore: HistoryStore? = nil) {
        self.historyStore = historyStore
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
        guard let data = jsonString.data(using: .utf8),
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = json["type"] as? String
        else { return }

        switch type {
        case "ping": send(json: ["type": "pong"], to: connection)
        case "auth": handleAuth(json, from: connection)
        case "command": if isAuthenticated { handlePlayCommand(json) }
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

    private func handlePlayCommand(_ json: [String: Any]) {
        if let payload = json["payload"] as? [String: Any],
            let urlString = payload["url"] as? String,
            let url = URL(string: urlString)
        {
            let headers = payload["headers"] as? [String: String]
            let title = payload["title"] as? String
            historyStore?.addToHistory(url: url, title: title, headers: headers)
            DispatchQueue.main.async {
                self.currentPlayRequest = PlayRequest(url: url, headers: headers)
            }
        }
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

// MARK: - UI Components

enum AppScreen { case pairing, history, favorites, settings }

struct ContentView: View {
    @StateObject private var historyStore = HistoryStore()
    @StateObject private var server: WebSocketServer
    @State private var currentScreen: AppScreen = .pairing
    @State private var showClearConfirm = false
    @State private var time = 0.0
    let timer = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    init() {
        let store = HistoryStore()
        _historyStore = StateObject(wrappedValue: store)
        _server = StateObject(wrappedValue: WebSocketServer(historyStore: store))
    }

    var body: some View {
        ZStack {
            AuroraBackgroundView(time: time)
                .onReceive(timer) { _ in time += 0.01 }
                .edgesIgnoringSafeArea(.all)

            HStack(spacing: 0) {
                // Sidebar
                VStack(alignment: .leading, spacing: 10) {
                    BrandingView().padding(.bottom, 60).padding(.top, 40)

                    MenuButton(
                        title: "Pair Device", icon: "sensor.tag.radiowaves.forward",
                        currentScreen: $currentScreen, screen: .pairing)
                    MenuButton(
                        title: "History", icon: "clock.arrow.circlepath",
                        currentScreen: $currentScreen, screen: .history)
                    MenuButton(
                        title: "Favorites", icon: "star.fill", currentScreen: $currentScreen,
                        screen: .favorites)
                    MenuButton(
                        title: "Settings", icon: "gearshape.fill", currentScreen: $currentScreen,
                        screen: .settings)

                    Spacer()

                    DangerButton(title: "Clear All", icon: "trash") { showClearConfirm = true }
                        .padding(.bottom, 40)
                }
                .padding(.horizontal, 40)
                .frame(width: 400)
                .background(Color.black.opacity(0.3).edgesIgnoringSafeArea(.all))
                .disabled(server.currentPlayRequest != nil)

                // Main Content
                ZStack {
                    switch currentScreen {
                    case .pairing: PairingView(server: server)
                    case .history:
                        LibraryListView(
                            title: "History", items: historyStore.history,
                            historyStore: historyStore, server: server)
                    case .favorites:
                        LibraryListView(
                            title: "Favorites",
                            items: historyStore.history.filter { $0.isFavorite },
                            historyStore: historyStore, server: server)
                    case .settings: SettingsView(server: server)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .disabled(server.currentPlayRequest != nil)
            }
            .disabled(server.currentPlayRequest != nil)

            if let request = server.currentPlayRequest {
                // Using the fixed PlayerView
                PlayerView(request: request) {
                    withAnimation { server.currentPlayRequest = nil }
                }
                .id(request.url)  // Forces re-init if URL changes
                .zIndex(10)
                .edgesIgnoringSafeArea(.all)
            }
        }
        .onAppear { server.start() }
        .alert("Clear Data", isPresented: $showClearConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Erase All", role: .destructive) { historyStore.clearHistory() }
        }
    }
}

// MARK: - Focusable Components

struct MenuButton: View {
    let title: String
    let icon: String
    @Binding var currentScreen: AppScreen
    let screen: AppScreen
    @FocusState private var isFocused: Bool

    var isSelected: Bool { currentScreen == screen }

    var body: some View {
        Button(action: { currentScreen = screen }) {
            HStack(spacing: 20) {
                Image(systemName: icon).font(.system(size: 28)).frame(width: 40)
                Text(title).font(
                    .system(size: 26, weight: isSelected || isFocused ? .bold : .medium))
                Spacer()
                if isSelected { Circle().fill(Theme.accent).frame(width: 8, height: 8) }
            }
            .padding(.vertical, 20)
            .padding(.horizontal, 24)
            .foregroundColor(isFocused ? Theme.accent : (isSelected ? .white : Theme.secondaryText))
            .scaleEffect(isFocused ? 1.1 : 1.0)
            .animation(.interactiveSpring(), value: isFocused)
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}

struct PairingView: View {
    @ObservedObject var server: WebSocketServer

    var body: some View {
        VStack(spacing: 60) {
            VStack(spacing: 10) {
                Text(server.serverState.uppercased()).font(.system(size: 20, weight: .black))
                    .foregroundColor(Theme.accent)
                Text(server.deviceName).font(.system(size: 70, weight: .black)).foregroundColor(
                    .white)
            }

            HStack(spacing: 25) {
                ForEach(Array(server.pairingPin.enumerated()), id: \.offset) { index, char in
                    Text(String(char))
                        .font(.system(size: 100, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                        .frame(width: 120, height: 160)
                        .background(RoundedRectangle(cornerRadius: 24).fill(.ultraThinMaterial))
                        .overlay(
                            RoundedRectangle(cornerRadius: 24).stroke(
                                Color.white.opacity(0.2), lineWidth: 1))
                }
            }

            Text("\(server.localIP):8765").font(.system(size: 32, design: .monospaced))
                .foregroundColor(.white.opacity(0.6))
        }
    }
}

struct HistoryCard: View {
    let item: PlaybackHistoryItem
    @ObservedObject var historyStore: HistoryStore
    let action: () -> Void
    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    LinearGradient(
                        colors: [Color.blue.opacity(0.4), Color.purple.opacity(0.4)],
                        startPoint: .top, endPoint: .bottom)
                    if isFocused {
                        Image(systemName: "play.fill").font(.system(size: 60)).foregroundColor(
                            .white)
                    }
                }
                .frame(height: 200)

                HStack {
                    VStack(alignment: .leading) {
                        Text(item.title ?? "Unknown").font(.headline).foregroundColor(
                            isFocused ? .black : .white)
                        Text(item.url.host ?? "Link").font(.subheadline).foregroundColor(
                            isFocused ? .black.opacity(0.6) : .white.opacity(0.5))
                    }
                    Spacer()
                    Button(action: { historyStore.toggleFavorite(item: item) }) {
                        Image(systemName: item.isFavorite ? "star.fill" : "star")
                            .foregroundColor(
                                item.isFavorite ? .yellow : (isFocused ? .black : .white))
                    }.buttonStyle(.plain)
                }
                .padding()
                .background(isFocused ? Color.white : Color.black.opacity(0.4))
            }
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .scaleEffect(isFocused ? 1.05 : 1.0)
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}

struct LibraryListView: View {
    let title: String
    let items: [PlaybackHistoryItem]
    @ObservedObject var historyStore: HistoryStore
    @ObservedObject var server: WebSocketServer
    let columns = [GridItem(.adaptive(minimum: 400), spacing: 40)]

    var body: some View {
        VStack(alignment: .leading) {
            Text(title).font(.system(size: 50, weight: .black)).padding([.leading, .top], 60)
            ScrollView {
                LazyVGrid(columns: columns, spacing: 40) {
                    ForEach(items) { item in
                        HistoryCard(item: item, historyStore: historyStore) {
                            server.currentPlayRequest = PlayRequest(
                                url: item.url, headers: item.headers)
                        }
                    }
                }
                .padding(60)
            }
        }
    }
}

struct SettingsView: View {
    @ObservedObject var server: WebSocketServer
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"

    var body: some View {
        VStack(alignment: .leading, spacing: 40) {
            Text("Settings").font(.system(size: 50, weight: .black)).padding([.leading, .top], 60)
            List {
                Section(header: Text("Playback Settings")) {
                    Button(action: { preferredPlayer = "avplayer" }) {
                        HStack {
                            Text("Native (AVPlayer)")
                            Spacer()
                            if preferredPlayer == "avplayer" {
                                Image(systemName: "checkmark").foregroundColor(Theme.accent)
                            }
                        }
                    }
                    Button(action: { preferredPlayer = "vlc" }) {
                        HStack {
                            Text("VLC Player")
                            Spacer()
                            if preferredPlayer == "vlc" {
                                Image(systemName: "checkmark").foregroundColor(Theme.accent)
                            }
                        }
                    }
                }

                Section("Server Information") {
                    HStack {
                        Text("Device Name")
                        Spacer()
                        Text(server.deviceName).foregroundColor(.gray)
                    }
                    HStack {
                        Text("IP Address")
                        Spacer()
                        Text(server.localIP).foregroundColor(.gray)
                    }
                    HStack {
                        Text("Status")
                        Spacer()
                        Text(server.serverState).foregroundColor(Theme.accent)
                    }
                }
            }
            .listStyle(.grouped)
        }
    }
}

struct DangerButton: View {
    let title: String
    let icon: String
    let action: () -> Void
    @FocusState var isFocused: Bool
    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                Text(title)
            }
            .font(.headline)
            .foregroundColor(isFocused ? .white : .red.opacity(0.6))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(isFocused ? Color.red : Color.red.opacity(0.1))
            .cornerRadius(10)
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}

struct BrandingView: View {
    var body: some View {
        HStack(spacing: 0) {
            Image(systemName: "play.tv.fill").foregroundColor(Theme.accent).font(.title)
                .padding(.trailing, 10)
            Text("PLAY").font(.system(size: 28, weight: .black))
            Text("BRIDGE").font(.system(size: 28, weight: .light))
        }.foregroundColor(.white)
    }
}

struct AuroraBackgroundView: View {
    var time: Double
    var body: some View {
        ZStack {
            Color(red: 0.01, green: 0.01, blue: 0.03).ignoresSafeArea()
            Circle().fill(Color.blue.opacity(0.15)).blur(radius: 100).offset(
                x: sin(time) * 200, y: cos(time) * 200)
            Circle().fill(Color.purple.opacity(0.15)).blur(radius: 100).offset(
                x: -sin(time) * 200, y: -cos(time) * 200)
        }
    }
}

// MARK: - Robust Player Implementation

/// Using UIViewControllerRepresentable is much more stable on tvOS for custom headers and MKVs
struct NativePlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()

        // Setup Audio Session for TV output
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        // Add Headers to the Asset
        var options: [String: Any] = [:]
        if let headers = headers {
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
        }
        let asset = AVURLAsset(url: url, options: options)
        let playerItem = AVPlayerItem(asset: asset)

        // Native AVPlayer
        let player = AVPlayer(playerItem: playerItem)
        controller.player = player
        controller.allowsPictureInPicturePlayback = true

        player.play()
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}
}

// MARK: - Enhanced VLC Player with Controls

struct VLCPlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?

    func makeUIViewController(context: Context) -> VLCViewController {
        let controller = VLCViewController()
        controller.url = url
        controller.headers = headers
        return controller
    }

    func updateUIViewController(_ uiViewController: VLCViewController, context: Context) {}

    class VLCViewController: UIViewController, VLCMediaPlayerDelegate {
        var mediaPlayer: VLCMediaPlayer = VLCMediaPlayer()
        var url: URL?
        var headers: [String: String]?

        // Custom focusable view to capture remote events
        class FocusableView: UIView {
            override var canBecomeFocused: Bool { true }
        }

        // UI State
        private let videoView = FocusableView()
        private var controlsContainer = UIView()
        private var hostingController: UIHostingController<VLCControlsOverlay>?

        private var rightSwipe: UISwipeGestureRecognizer?
        private var leftSwipe: UISwipeGestureRecognizer?

        override var preferredFocusEnvironments: [UIFocusEnvironment] {
            // Only route focus to the SwiftUI buttons when user explicitly paused
            if playbackState.userPaused, let hostingView = hostingController?.view {
                return [hostingView]
            }
            return [videoView]
        }

        // Data for HUD
        private var playbackState = VLCPlaybackData()
        private var hideControlsTimer: Timer?
        private var continuousSeekTimer: Timer?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black

            view.isUserInteractionEnabled = true
            videoView.isUserInteractionEnabled = true

            // 1. Setup Video Surface
            videoView.frame = view.bounds
            videoView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(videoView)

            // 2. Setup HUD Overlay (SwiftUI)
            setupHUD()

            // 3. Setup Gestures
            setupGestures()

            // 4. Initialize Player
            setupPlayer()

            // Show UI initially
            showUI(autoHide: true)
        }

        private func setupHUD() {
            let overlay = VLCControlsOverlay(
                data: playbackState,
                onSelectSubtitle: { [weak self] trackId in
                    self?.mediaPlayer.currentVideoSubTitleIndex = Int32(trackId)
                    self?.playbackState.currentSubtitleIndex = trackId
                },
                onSelectAudio: { [weak self] trackId in
                    self?.mediaPlayer.currentAudioTrackIndex = Int32(trackId)
                    self?.playbackState.currentAudioIndex = trackId
                },
                onTogglePlayPause: { [weak self] in
                    self?.togglePlayPause()
                })
            let hosting = UIHostingController(rootView: overlay)
            hosting.view.backgroundColor = .clear
            hosting.view.frame = view.bounds
            hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]

            addChild(hosting)
            view.addSubview(hosting.view)
            hosting.didMove(toParent: self)
            self.hostingController = hosting
        }

        private func updateSubtitleTracks() {
            guard let indexes = mediaPlayer.videoSubTitlesIndexes as? [Int],
                let names = mediaPlayer.videoSubTitlesNames as? [String],
                indexes.count == names.count
            else { return }

            let tracks = zip(indexes, names).map { (id: $0, name: $1) }
            DispatchQueue.main.async {
                self.playbackState.subtitleTracks = tracks
                self.playbackState.currentSubtitleIndex = Int(
                    self.mediaPlayer.currentVideoSubTitleIndex)
            }
        }

        private func updateAudioTracks() {
            guard let indexes = mediaPlayer.audioTrackIndexes as? [Int],
                let names = mediaPlayer.audioTrackNames as? [String],
                indexes.count == names.count
            else { return }

            let tracks = zip(indexes, names).map { (id: $0, name: $1) }
            DispatchQueue.main.async {
                self.playbackState.audioTracks = tracks
                self.playbackState.currentAudioIndex = Int(self.mediaPlayer.currentAudioTrackIndex)
            }
        }

        private var proxyServer: VLCProxyServer?

        private func setupPlayer() {
            mediaPlayer.delegate = self
            mediaPlayer.drawable = videoView

            if let url = url {
                // Separate headers into VLC-native and those that need proxy
                var nativeOptions: [String] = []
                var needsProxy = false

                if let headers = headers {
                    for (key, value) in headers {
                        switch key.lowercased() {
                        case "user-agent":
                            nativeOptions.append(":http-user-agent=\(value)")
                        case "referer":
                            nativeOptions.append(":http-referrer=\(value)")
                        default:
                            needsProxy = true
                        }
                    }
                }

                let playURL: URL
                if needsProxy {
                    // Route through local proxy to inject custom headers
                    let proxy = VLCProxyServer(targetURL: url, headers: headers ?? [:])
                    proxy.start()
                    self.proxyServer = proxy
                    playURL = proxy.localURL
                    print("VLC Proxy: \(url.absoluteString) -> \(playURL.absoluteString)")
                } else {
                    playURL = url
                }

                let media = VLCMedia(url: playURL)

                // Optimized caching for tvOS streaming
                media.addOptions([
                    "--network-caching": "3000",
                    "--clock-jitter": "0",
                    "--drop-late-frames": "1",
                    "--skip-frames": "1",
                ])

                // Apply VLC-native header options
                for option in nativeOptions {
                    media.addOption(option)
                }

                mediaPlayer.media = media
                mediaPlayer.play()
            }
        }

        // MARK: - VLCMediaPlayerDelegate
        func mediaPlayerStateChanged(_ aNotification: Notification) {
            DispatchQueue.main.async {
                self.playbackState.isPlaying = self.mediaPlayer.isPlaying
                if self.mediaPlayer.state == .playing {
                    // Clear userPaused when VLC resumes
                    self.playbackState.userPaused = false
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
                }
                if self.mediaPlayer.state == .error {
                    print("VLC Error: Playback failed")
                }
            }
        }

        func mediaPlayerTimeChanged(_ aNotification: Notification) {
            DispatchQueue.main.async {
                self.playbackState.currentTime = Double(self.mediaPlayer.time.intValue) / 1000.0
                if let media = self.mediaPlayer.media {
                    let length = Double(media.length.intValue) / 1000.0
                    if length > 0 {
                        self.playbackState.duration = length
                    }
                }
                // Keep trying to fetch tracks until they're available
                // (VLC may not have parsed them on the initial .playing state change)
                if self.playbackState.subtitleTracks.isEmpty || self.playbackState.audioTracks.isEmpty {
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
                }
            }
        }

        // MARK: - Interactions
        private func setupGestures() {
            // Swipe seeking (still useful for touchpad)
            let rightSwipe = UISwipeGestureRecognizer(target: self, action: #selector(skipForward))
            rightSwipe.direction = .right
            view.addGestureRecognizer(rightSwipe)
            self.rightSwipe = rightSwipe

            let leftSwipe = UISwipeGestureRecognizer(target: self, action: #selector(skipBackward))
            leftSwipe.direction = .left
            view.addGestureRecognizer(leftSwipe)
            self.leftSwipe = leftSwipe
        }

        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            guard let type = presses.first?.type else {
                super.pressesBegan(presses, with: event)
                return
            }

            // Intercept Menu/Back button to close popups before exiting
            if type == .menu {
                if playbackState.showSubtitleMenu {
                    playbackState.showSubtitleMenu = false
                    return
                }
                if playbackState.showAudioMenu {
                    playbackState.showAudioMenu = false
                    return
                }
                if !mediaPlayer.isPlaying && playbackState.showUI {
                    // If paused with controls visible, resume playback first
                    togglePlayPause()
                    return
                }
                // Otherwise let it propagate to exit the player
                super.pressesBegan(presses, with: event)
                return
            }

            // Let menus handle their own navigation input
            if playbackState.showSubtitleMenu || playbackState.showAudioMenu {
                super.pressesBegan(presses, with: event)
                return
            }

            if mediaPlayer.isPlaying {
                // PLAYING MODE: arrows seek, center pauses
                switch type {
                case .playPause, .select:
                    togglePlayPause()
                case .leftArrow:
                    showUI()
                    startContinuousSeek(forward: false)
                case .rightArrow:
                    showUI()
                    startContinuousSeek(forward: true)
                default:
                    super.pressesBegan(presses, with: event)
                }
            } else {
                // PAUSED MODE: arrows navigate buttons, center activates focused button
                switch type {
                case .playPause:
                    togglePlayPause()
                default:
                    // Let the focus engine handle all navigation and selection
                    super.pressesBegan(presses, with: event)
                }
            }
        }

        override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            stopContinuousSeek()
            super.pressesEnded(presses, with: event)
        }

        override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            stopContinuousSeek()
            super.pressesCancelled(presses, with: event)
        }

        private func startContinuousSeek(forward: Bool) {
            // Initial skip (one-time)
            if forward { skipForward() } else { skipBackward() }

            continuousSeekTimer?.invalidate()
            // After 0.4s hold, start repeating fast
            continuousSeekTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) {
                [weak self] _ in
                DispatchQueue.main.async {
                    self?.continuousSeekTimer?.invalidate()
                    self?.continuousSeekTimer = Timer.scheduledTimer(
                        withTimeInterval: 0.2, repeats: true
                    ) { [weak self] _ in
                        DispatchQueue.main.async {
                            if forward { self?.skipForward() } else { self?.skipBackward() }
                        }
                    }
                }
            }
        }

        private func stopContinuousSeek() {
            continuousSeekTimer?.invalidate()
            continuousSeekTimer = nil
        }



        @objc private func togglePlayPause() {
            if mediaPlayer.isPlaying {
                mediaPlayer.pause()
                playbackState.userPaused = true
                // Refresh tracks right before showing controls
                updateSubtitleTracks()
                updateAudioTracks()
                // Pausing: show controls permanently and give focus to buttons
                showUI(autoHide: false)
            } else {
                mediaPlayer.play()
                playbackState.userPaused = false
                // Resuming: show controls briefly then auto-hide
                showUI(autoHide: true)
            }
        }

        @objc private func skipForward() {
            let currentTime = mediaPlayer.time.intValue
            let duration = mediaPlayer.media?.length.intValue ?? 0

            var target = currentTime + 15000
            if duration > 0 {
                target = min(target, duration - 2000)
            }

            if target > currentTime {
                mediaPlayer.time = VLCTime(int: target)
            }
            showUI()
        }

        @objc private func skipBackward() {
            let currentTime = mediaPlayer.time.intValue
            let target = max(0, currentTime - 15000)

            mediaPlayer.time = VLCTime(int: target)
            showUI()
        }

        private func showUI(autoHide: Bool = true) {
            DispatchQueue.main.async {
                self.playbackState.showUI = true
                self.setNeedsFocusUpdate()
                
                self.hideControlsTimer?.invalidate()
                if autoHide {
                    self.hideControlsTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false)
                    { [weak self] _ in
                        DispatchQueue.main.async {
                            withAnimation {
                                self?.playbackState.showUI = false
                                self?.setNeedsFocusUpdate()
                            }
                        }
                    }
                }
            }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            mediaPlayer.stop()
            proxyServer?.stop()
            proxyServer = nil
        }
    }
}

// MARK: - VLC UI Models & Overlay

class VLCPlaybackData: ObservableObject {
    @Published var isPlaying: Bool = false
    @Published var userPaused: Bool = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    @Published var showUI: Bool = true

    // Subtitles
    @Published var subtitleTracks: [(id: Int, name: String)] = []
    @Published var currentSubtitleIndex: Int = -1
    @Published var showSubtitleMenu: Bool = false

    // Audio Tracks
    @Published var audioTracks: [(id: Int, name: String)] = []
    @Published var currentAudioIndex: Int = -1
    @Published var showAudioMenu: Bool = false
}

struct TrackMenuView: View {
    let title: String
    let icon: String
    let tracks: [(id: Int, name: String)]
    let currentId: Int
    let includeOff: Bool
    let onSelect: (Int) -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(Theme.accent)
                Text(title)
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
            }
            .padding(.horizontal, 36)
            .padding(.top, 32)
            .padding(.bottom, 20)

            Divider().background(Color.white.opacity(0.15))

            ScrollView {
                VStack(spacing: 4) {
                    if includeOff {
                        trackRow(id: -1, name: "Off")
                    }
                    ForEach(tracks, id: \.id) { track in
                        trackRow(id: track.id, name: track.name)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
        }
        .frame(width: 540, height: min(CGFloat(tracks.count + (includeOff ? 1 : 0)) * 80 + 120, 620))
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func trackRow(id: Int, name: String) -> some View {
        Button(action: { onSelect(id) }) {
            HStack {
                Text(name)
                    .font(.system(size: 22))
                    .lineLimit(1)
                Spacer()
                if currentId == id {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 22))
                        .foregroundColor(Theme.accent)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .buttonStyle(.plain)
    }
}

struct VLCControlsOverlay: View {
    @ObservedObject var data: VLCPlaybackData
    let onSelectSubtitle: (Int) -> Void
    let onSelectAudio: (Int) -> Void
    let onTogglePlayPause: () -> Void

    var body: some View {
        ZStack {
            // MARK: - Main Controls HUD
            if data.showUI {
                // Full-screen dim when user paused
                if data.userPaused {
                    Color.black.opacity(0.5)
                        .ignoresSafeArea()
                        .transition(.opacity)
                }

                VStack(spacing: 0) {
                    Spacer()

                    // Center play/pause indicator (large, when user paused)
                    if data.userPaused {
                        Image(systemName: "pause.fill")
                            .font(.system(size: 72, weight: .medium))
                            .foregroundColor(.white.opacity(0.6))
                            .transition(.scale.combined(with: .opacity))
                            .padding(.bottom, 60)
                    }

                    Spacer()

                    // Bottom control bar
                    VStack(spacing: 24) {
                        // Progress bar
                        HStack(spacing: 16) {
                            Text(formatTime(data.currentTime))
                                .font(.system(size: 22, weight: .medium, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))

                            GeometryReader { proxy in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color.white.opacity(0.2))
                                    Capsule()
                                        .fill(
                                            LinearGradient(
                                                colors: [Theme.accent, Theme.accent.opacity(0.7)],
                                                startPoint: .leading, endPoint: .trailing)
                                        )
                                        .frame(
                                            width: proxy.size.width
                                                * CGFloat(
                                                    data.duration > 0
                                                        ? data.currentTime / data.duration : 0))
                                }
                            }.frame(height: 8)

                            Text(formatTime(data.duration))
                                .font(.system(size: 22, weight: .medium, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                        }
                        .padding(.horizontal, 80)

                        // Action buttons row (only when user explicitly paused)
                        if data.userPaused {
                            HStack(spacing: 40) {
                                // Play/Resume button (center, prominent)
                                Button(action: { onTogglePlayPause() }) {
                                    HStack(spacing: 12) {
                                        Image(systemName: "play.fill")
                                            .font(.system(size: 26))
                                        Text("Resume")
                                            .font(.system(size: 22, weight: .semibold))
                                    }
                                    .padding(.horizontal, 36)
                                    .padding(.vertical, 16)
                                }
                                .buttonStyle(.card)

                                // Subtitles button
                                if !data.subtitleTracks.isEmpty {
                                    Button(action: {
                                        data.showSubtitleMenu.toggle()
                                        data.showAudioMenu = false
                                    }) {
                                        VStack(spacing: 6) {
                                            Image(systemName: "captions.bubble.fill")
                                                .font(.system(size: 26))
                                            Text("Subtitles")
                                                .font(.system(size: 16, weight: .medium))
                                        }
                                        .frame(width: 100, height: 70)
                                    }
                                    .buttonStyle(.card)
                                }

                                // Audio tracks button
                                if data.audioTracks.count > 1 {
                                    Button(action: {
                                        data.showAudioMenu.toggle()
                                        data.showSubtitleMenu = false
                                    }) {
                                        VStack(spacing: 6) {
                                            Image(systemName: "waveform")
                                                .font(.system(size: 26))
                                            Text("Audio")
                                                .font(.system(size: 16, weight: .medium))
                                        }
                                        .frame(width: 100, height: 70)
                                    }
                                    .buttonStyle(.card)
                                }
                            }
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .padding(.bottom, 60)
                }
                .transition(.opacity)
            }

            // MARK: - Track Picker Popups
            if data.showSubtitleMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                    .transition(.opacity)

                TrackMenuView(
                    title: "Subtitles",
                    icon: "captions.bubble.fill",
                    tracks: data.subtitleTracks,
                    currentId: data.currentSubtitleIndex,
                    includeOff: true
                ) { id in
                    onSelectSubtitle(id)
                    data.showSubtitleMenu = false
                }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
            }

            if data.showAudioMenu {
                Color.black.opacity(0.6).ignoresSafeArea()
                    .transition(.opacity)

                TrackMenuView(
                    title: "Audio Track",
                    icon: "waveform",
                    tracks: data.audioTracks,
                    currentId: data.currentAudioIndex,
                    includeOff: false
                ) { id in
                    onSelectAudio(id)
                    data.showAudioMenu = false
                }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.3), value: data.showUI)
        .animation(.easeInOut(duration: 0.3), value: data.userPaused)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showSubtitleMenu)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: data.showAudioMenu)
        .allowsHitTesting(data.showUI || data.showSubtitleMenu || data.showAudioMenu)
    }

    private func formatTime(_ seconds: Double) -> String {
        let sec = Int(seconds)
        let h = sec / 3600
        let m = (sec % 3600) / 60
        let s = sec % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        } else {
            return String(format: "%d:%02d", m, s)
        }
    }
}

struct PlayerView: View {
    let request: PlayRequest
    let onDismiss: () -> Void
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"

    // 1. Define focus state
    @FocusState private var isPlayerFocused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if preferredPlayer == "vlc" {
                VLCPlayerView(url: request.url, headers: request.headers)
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
            } else {
                NativePlayerView(url: request.url, headers: request.headers)
                    .ignoresSafeArea()
                    .focused($isPlayerFocused)
            }
        }
        .onAppear {
            isPlayerFocused = true
        }
        .onExitCommand { onDismiss() }
    }
}
