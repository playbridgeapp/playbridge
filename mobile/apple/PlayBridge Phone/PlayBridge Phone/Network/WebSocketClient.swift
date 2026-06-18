import Foundation

/// `URLSessionWebSocketTask`-based client for connecting to a PlayBridge receiver.
/// Direct port of `connection/WebSocketClient.kt` (OkHttp) — same state machine, pairing/auth
/// handshake, SPKI pin validation (TOFU), reconnect/retry, and mouse-move throttling.
final class WebSocketClient: NSObject, ObservableObject {

    // MARK: - Published state

    @Published private(set) var state: ConnectionState = .disconnected

    /// Forwarded non-handshake messages (status/context/playlist_status/tracks/…).
    var onMessage: ((String) -> Void)?
    /// Token (+ pin) issued/rotated by the receiver — the owner persists these.
    var onCredentials: ((IssuedCredentials) -> Void)?
    /// player_mode / browser_mode ids the receiver reported at auth.
    var onCapabilities: ((TvCapabilities) -> Void)?

    struct IssuedCredentials { let token: String; let certFingerprint: String? }
    struct TvCapabilities { let players: [String]; let browsers: [String] }

    // MARK: - Internals

    private struct Target {
        let ip: String
        let port: Int
        var token: String
        let serverName: String
        let deviceName: String
        let deviceUUID: String
        let wssPort: Int?
        var pin: String?
    }

    private var session: URLSession!
    private let delegateQueue = OperationQueue()

    private var task: URLSessionWebSocketTask?
    private var target: Target?

    private var retryCount = 0
    private var isUserDisconnect = false
    private var isSecure = false

    // TLS pin captured during the current handshake, and whether it failed to match.
    private var capturedServerPin: String?
    private var pinMismatch = false

    private var pingTimer: DispatchSourceTimer?

    // Mouse-move batching (collapse rapid deltas into ~60Hz packets), as in the Kotlin client.
    private let mouseQueue = DispatchQueue(label: "com.playbridge.phone.mouse")
    private var pendingDx: Float = 0
    private var pendingDy: Float = 0
    private var mouseFlushScheduled = false

    override init() {
        super.init()
        delegateQueue.maxConcurrentOperationCount = 1
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 10
        session = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
    }

    // MARK: - Public API

    func connect(
        ip: String,
        port: Int,
        token: String,
        serverName: String,
        deviceName: String,
        deviceUUID: String,
        wssPort: Int? = nil,
        certFingerprint: String? = nil
    ) {
        retryCount = 0
        isUserDisconnect = false
        target = Target(ip: ip, port: port, token: token, serverName: serverName,
                        deviceName: deviceName, deviceUUID: deviceUUID,
                        wssPort: wssPort, pin: certFingerprint)
        attemptConnection()
    }

    func disconnect() {
        isUserDisconnect = true
        stopPing()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        setState(.disconnected)
    }

    var isConnected: Bool { state.isConnected }

    @discardableResult
    func send(_ text: String) -> Bool {
        guard let task else { return false }
        task.send(.string(text)) { _ in }
        return true
    }

    @discardableResult
    func send(_ data: Data) -> Bool {
        guard let task else { return false }
        task.send(.data(data)) { _ in }
        return true
    }

    func sendPing() { _ = send(WireProtocol.ping()) }

    /// Mouse command with automatic batching/throttling for high-frequency `move` events.
    func sendMouse(event: String, dx: Float = 0, dy: Float = 0) {
        if event == "move" {
            mouseQueue.async {
                self.pendingDx += dx
                self.pendingDy += dy
                if !self.mouseFlushScheduled {
                    self.mouseFlushScheduled = true
                    self.mouseQueue.asyncAfter(deadline: .now() + 0.016) { self.flushMouse() }
                }
            }
            return
        }
        _ = send(MousePacket.pack(event: event, dx: dx, dy: dy))
    }

    private func flushMouse() {
        mouseFlushScheduled = false
        let dx = pendingDx, dy = pendingDy
        pendingDx = 0; pendingDy = 0
        if dx != 0 || dy != 0 {
            _ = send(MousePacket.pack(event: "move", dx: dx, dy: dy))
        }
    }

    // MARK: - Connection lifecycle

    private func attemptConnection() {
        guard let conn = target else { return }
        setState(.connecting)
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        capturedServerPin = nil
        pinMismatch = false

        let useTls = conn.wssPort != nil
        isSecure = useTls
        let urlString = useTls ? "wss://\(conn.ip):\(conn.wssPort!)/" : "ws://\(conn.ip):\(conn.port)/"
        guard let url = URL(string: urlString) else {
            setState(.error(message: "Invalid address"))
            return
        }
        let newTask = session.webSocketTask(with: url)
        task = newTask
        newTask.resume()
        receiveLoop(on: newTask)
    }

    private func receiveLoop(on wsTask: URLSessionWebSocketTask) {
        wsTask.receive { [weak self] result in
            guard let self, wsTask === self.task else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message {
                    self.handleIncoming(text)
                }
                self.receiveLoop(on: wsTask)
            case .failure:
                // The matching didCompleteWithError will drive retry/teardown.
                break
            }
        }
    }

    private func onOpen() {
        guard let conn = target, let wsTask = task else { return }
        retryCount = 0
        _ = wsTask // handshake sends go through the active task
        if conn.token.isEmpty {
            // First-time pairing — identify and wait for the TV user to approve.
            _ = send(WireProtocol.pairingRequest(deviceName: conn.deviceName, deviceUUID: conn.deviceUUID))
            setState(.waitingForApproval(serverName: conn.serverName))
        } else {
            // Reconnect with a saved token; the receiver replies with auth_response.
            _ = send(WireProtocol.auth(token: conn.token))
        }
    }

    private func handleIncoming(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else {
            onMessage?(text)
            return
        }

        switch type {
        case "pairing_approved":
            handlePairingApproved(json)
        case "pairing_denied":
            isUserDisconnect = true
            setState(.pairingDenied(serverName: target?.serverName ?? ""))
            task?.cancel(with: .normalClosure, reason: nil)
        case "auth_response":
            handleAuthResponse(json)
        default:
            onMessage?(text)
        }
    }

    private func handlePairingApproved(_ json: [String: Any]) {
        let serverName = target?.serverName ?? ""
        let token = (json["token"] as? String) ?? ""
        let certFp = (json["certFingerprint"] as? String).flatMap { $0.isEmpty ? nil : $0 }

        // Bind the delivered pin to the cert actually served this handshake.
        if let certFp, let served = capturedServerPin, certFp != served {
            pinMismatch = true
            isUserDisconnect = true
            setState(.pinMismatch(serverName: serverName))
            task?.cancel(with: .normalClosure, reason: nil)
            return
        }
        if !token.isEmpty {
            let pin = certFp ?? capturedServerPin
            target?.token = token
            target?.pin = pin
            onCredentials?(IssuedCredentials(token: token, certFingerprint: pin))
        }
        emitCapabilities(json)
        setState(.connected(serverName: serverName, secure: isSecure))
        startPing()
    }

    private func handleAuthResponse(_ json: [String: Any]) {
        let serverName = target?.serverName ?? ""
        let success = (json["success"] as? Bool) ?? false
        if success {
            emitCapabilities(json)
            setState(.connected(serverName: serverName, secure: isSecure))
            let token = (json["token"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let certFp = (json["certFingerprint"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            if let token {
                let pin = certFp ?? capturedServerPin ?? target?.pin
                target?.token = token
                target?.pin = pin
                onCredentials?(IssuedCredentials(token: token, certFingerprint: pin))
            }
            startPing()
        } else {
            // Stale token — wipe and re-pair. Flag before close so teardown doesn't overwrite it.
            isUserDisconnect = true
            setState(.authFailed)
            task?.cancel(with: .normalClosure, reason: nil)
        }
    }

    private func emitCapabilities(_ json: [String: Any]) {
        let players = (json["players"] as? [String]) ?? []
        let browsers = (json["browsers"] as? [String]) ?? []
        if !players.isEmpty || !browsers.isEmpty {
            onCapabilities?(TvCapabilities(players: players, browsers: browsers))
        }
    }

    private func handleDisconnect() {
        stopPing()
        task = nil

        if pinMismatch {
            isUserDisconnect = true
            setState(.pinMismatch(serverName: target?.serverName ?? ""))
            return
        }
        // Don't overwrite terminal states the UI needs.
        switch state {
        case .authFailed, .pairingDenied, .pinMismatch:
            return
        default:
            break
        }
        if !isUserDisconnect && retryCount < ProtocolConstants.maxRetries {
            retryCount += 1
            setState(.retrying(attempt: retryCount, maxAttempts: ProtocolConstants.maxRetries,
                               nextRetrySeconds: Int(ProtocolConstants.retryDelay)))
            mouseQueue.asyncAfter(deadline: .now() + ProtocolConstants.retryDelay) { [weak self] in
                guard let self, !self.isUserDisconnect else { return }
                self.attemptConnection()
            }
        } else if isUserDisconnect {
            setState(.disconnected)
        } else {
            setState(.error(message: "Connection lost"))
        }
    }

    // MARK: - Ping timer

    private func startPing() {
        stopPing()
        let timer = DispatchSource.makeTimerSource(queue: mouseQueue)
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in self?.sendPing() }
        timer.resume()
        pingTimer = timer
    }

    private func stopPing() {
        pingTimer?.cancel()
        pingTimer = nil
    }

    private func setState(_ newState: ConnectionState) {
        DispatchQueue.main.async { self.state = newState }
    }
}

// MARK: - URLSession delegates

extension WebSocketClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        guard webSocketTask === task else { return }
        onOpen()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard webSocketTask === task else { return }
        handleDisconnect()
    }
}

extension WebSocketClient {
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let ws = task as? URLSessionWebSocketTask, ws === self.task else { return }
        handleDisconnect()
    }

    /// Server-trust challenge: SPKI-pin the receiver's self-signed cert (TOFU).
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        let presented = SPKIPinning.pin(for: trust)
        capturedServerPin = presented

        let expected = target?.pin
        guard let presented else {
            // Can't derive a pin → can't verify. Refuse rather than trust blindly.
            pinMismatch = (expected != nil)
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        if let expected, presented != expected {
            pinMismatch = true
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        // Match, or first pairing (TOFU): accept the self-signed cert.
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
