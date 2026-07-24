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

    // SAS pairing handshake state (sender side). Written/read on `delegateQueue`
    // (the receive handler and submitPairingCode both hop onto it), so no extra locking.
    private static let maxPairAttempts = 3
    private var senderKeyPair: SasCrypto.KeyPair?
    private var nonceS: Data?
    private var commitStr: String?
    private var tvEphPub: Data?
    private var nonceT: Data?
    private var sharedSecret: Data?
    private var calculatedSas: String?
    private var pairingAttemptsLeft = WebSocketClient.maxPairAttempts

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
        // Generous inter-data timeout: during pairing the receiver shows a ~60s code prompt
        // and sends nothing until we authenticate, so a short timeout would tear the idle
        // socket down mid-pairing. TCP-level failures (refused/unreachable) still surface
        // quickly via didCompleteWithError; keepalive pings (see onOpen) cover liveness.
        config.timeoutIntervalForRequest = 60
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
        // Keep the connection alive from the moment it opens — including the pre-auth pairing
        // window, when the receiver sends no data but does answer pings. Without this the idle
        // socket can drop while the user is reading/typing the code. startPing() is idempotent,
        // so the later approval/auth calls just reset it.
        startPing()
        _ = wsTask // handshake sends go through the active task
        if conn.token.isEmpty {
            // First-time pairing — run the SAS handshake. Commit to an ephemeral X25519
            // key + nonce, then wait for the TV's challenge (commit hides our key so the
            // TV can't pick its key adaptively).
            let keyPair = SasCrypto.generateX25519KeyPair()
            let nonce = SasCrypto.generateNonce(16)
            senderKeyPair = keyPair
            nonceS = nonce
            let commit = SasCrypto.sha256(keyPair.publicKeyBytes + nonce).base64EncodedString()
            commitStr = commit
            pairingAttemptsLeft = Self.maxPairAttempts
            _ = send(WireProtocol.pairingCommit(
                commit: commit,
                deviceName: conn.deviceName,
                deviceUUID: conn.deviceUUID
            ))
            // Stay in .connecting until the challenge arrives (a brief round-trip).
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
        case "pairing_challenge":
            handlePairingChallenge(json)
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

    /// SAS step 2→3: the TV revealed its ephemeral key. Derive the shared secret + SAS,
    /// reveal our key, and prompt the user for the 6-digit code shown on the TV.
    private func handlePairingChallenge(_ json: [String: Any]) {
        guard let keyPair = senderKeyPair,
              let nonce = nonceS,
              let commit = commitStr,
              let tvEphPubB64 = json["tvEphPub"] as? String,
              let nonceTB64 = json["nonceT"] as? String,
              let tvEphPubBytes = Data(base64Encoded: tvEphPubB64),
              let nonceTBytes = Data(base64Encoded: nonceTB64),
              let commitBytes = Data(base64Encoded: commit) else {
            setState(.error(message: "Pairing failed"))
            task?.cancel(with: .normalClosure, reason: nil)
            return
        }
        tvEphPub = tvEphPubBytes
        nonceT = nonceTBytes
        do {
            let shared = try SasCrypto.calculateECDH(privateKey: keyPair.privateKey, publicKeyBytes: tvEphPubBytes)
            sharedSecret = shared
            let transcript = pairingTranscript(
                commitBytes: commitBytes, tvEphPub: tvEphPubBytes, nonceT: nonceTBytes,
                senderEphPub: keyPair.publicKeyBytes, nonceS: nonce
            )
            calculatedSas = SasCrypto.generateSAS(sharedSecret: shared, transcript: transcript)
            _ = send(WireProtocol.pairingReveal(
                senderEphPub: keyPair.publicKeyBytes.base64EncodedString(),
                nonceS: nonce.base64EncodedString()
            ))
            setState(.waitingForCodeInput(serverName: target?.serverName ?? "",
                                          attemptsLeft: pairingAttemptsLeft, lastCodeWrong: false))
        } catch {
            setState(.error(message: "Pairing failed"))
            task?.cancel(with: .normalClosure, reason: nil)
        }
    }

    /// Submit the 6-digit code the user read off the TV. Serialized onto `delegateQueue`
    /// so it doesn't race the receive handler that owns the handshake fields.
    func submitPairingCode(_ code: String) {
        delegateQueue.addOperation { [weak self] in self?.handleSubmitPairingCode(code) }
    }

    private func handleSubmitPairingCode(_ code: String) {
        guard let keyPair = senderKeyPair, let nonce = nonceS, let commit = commitStr,
              let tvPub = tvEphPub, let nonceTv = nonceT, let shared = sharedSecret,
              let expected = calculatedSas, let commitBytes = Data(base64Encoded: commit) else { return }

        let serverName = target?.serverName ?? ""
        let clean = code.replacingOccurrences(of: " ", with: "")
        if clean != expected {
            pairingAttemptsLeft -= 1
            if pairingAttemptsLeft <= 0 {
                // Out of tries — tear down the handshake; the user must re-pair.
                isUserDisconnect = true
                setState(.pairingDenied(serverName: serverName))
                task?.cancel(with: .normalClosure, reason: nil)
                return
            }
            // Keep the socket + handshake alive (the TV is still awaiting our MAC) and re-prompt.
            setState(.waitingForCodeInput(serverName: serverName,
                                          attemptsLeft: pairingAttemptsLeft, lastCodeWrong: true))
            return
        }

        // Correct — prove key possession with the confirmation MAC.
        let transcript = pairingTranscript(
            commitBytes: commitBytes, tvEphPub: tvPub, nonceT: nonceTv,
            senderEphPub: keyPair.publicKeyBytes, nonceS: nonce
        )
        let prk = SasCrypto.hkdfExtract(salt: nil, ikm: shared)
        let confirmationKey = SasCrypto.hkdfExpand(prk: prk, info: "confirmationKey".data(using: .utf8), length: 32)
        let mac = SasCrypto.hmacSha256(key: confirmationKey, data: transcript).base64EncodedString()
        _ = send(WireProtocol.pairingConfirmation(mac: mac))
        setState(.verifyingCode(serverName: serverName))
    }

    /// The pairing transcript bound into the SAS and confirmation MAC, in the exact byte
    /// order shared by every implementation: commit ‖ tvEphPub ‖ nonceT ‖ senderEphPub ‖ nonceS.
    private func pairingTranscript(commitBytes: Data, tvEphPub: Data, nonceT: Data,
                                   senderEphPub: Data, nonceS: Data) -> Data {
        var t = Data()
        t.append(commitBytes)
        t.append(tvEphPub)
        t.append(nonceT)
        t.append(senderEphPub)
        t.append(nonceS)
        return t
    }

    private func handlePairingApproved(_ json: [String: Any]) {
        let serverName = target?.serverName ?? ""
        guard let keyPair = senderKeyPair, let senderNonce = nonceS,
              let commit = commitStr, let commitBytes = Data(base64Encoded: commit),
              let tvPub = tvEphPub, let receiverNonce = nonceT, let shared = sharedSecret,
              let nonceB64 = json["nonce"] as? String, let nonce = Data(base64Encoded: nonceB64),
              let ciphertextB64 = json["ciphertext"] as? String,
              let ciphertext = Data(base64Encoded: ciphertextB64) else {
            failPairingSecurity(serverName)
            return
        }
        let transcript = pairingTranscript(
            commitBytes: commitBytes, tvEphPub: tvPub, nonceT: receiverNonce,
            senderEphPub: keyPair.publicKeyBytes, nonceS: senderNonce
        )
        let transcriptHash = SasCrypto.sha256(transcript)
        let prk = SasCrypto.hkdfExtract(salt: nil, ikm: shared)
        let credentialKey = SasCrypto.hkdfExpand(
            prk: prk, info: "playbridgeCredentialKey-v1".data(using: .utf8), length: 32
        )
        guard let plaintext = try? SasCrypto.aesGcmDecrypt(
                  key: credentialKey, nonce: nonce, ciphertext: ciphertext, aad: transcriptHash),
              let object = try? JSONSerialization.jsonObject(with: plaintext),
              let credentials = object as? [String: Any] else {
            failPairingSecurity(serverName)
            return
        }
        guard let token = credentials["token"] as? String, !token.isEmpty else {
            failPairingSecurity(serverName)
            return
        }
        let certFp = (credentials["certFingerprint"] as? String).flatMap { $0.isEmpty ? nil : $0 }

        // Bind the delivered pin to the cert actually served this handshake.
        if let certFp, let served = capturedServerPin, certFp != served {
            pinMismatch = true
            isUserDisconnect = true
            setState(.pinMismatch(serverName: serverName))
            task?.cancel(with: .normalClosure, reason: nil)
            return
        }
        let pin = certFp ?? capturedServerPin
        target?.token = token
        target?.pin = pin
        onCredentials?(IssuedCredentials(token: token, certFingerprint: pin))
        emitCapabilities(credentials)
        clearPairingSecrets()
        setState(.connected(serverName: serverName, secure: isSecure))
        startPing()
    }

    private func failPairingSecurity(_ serverName: String) {
        isUserDisconnect = true
        clearPairingSecrets()
        setState(.error(message: "Pairing security verification failed"))
        task?.cancel(with: .policyViolation, reason: nil)
    }

    private func clearPairingSecrets() {
        senderKeyPair = nil
        nonceS = nil
        commitStr = nil
        tvEphPub = nil
        nonceT = nil
        sharedSecret = nil
        calculatedSas = nil
    }

    private func handleAuthResponse(_ json: [String: Any]) {
        let serverName = target?.serverName ?? ""
        let success = (json["success"] as? Bool) ?? false
        if success {
            emitCapabilities(json)
            setState(.connected(serverName: serverName, secure: isSecure))
            // SEC-005: legacy echoed tokens in auth_response are ignored.
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
