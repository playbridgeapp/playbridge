import Foundation
import Network
import SwiftUI
import UIKit
import Combine
import SwiftProtobuf
import CryptoKit

struct PairingRequest {
    let deviceName: String
    let deviceUUID: String
    let sasCode: String
    let connection: NWConnection
}

struct ConnectionHandshake {
    let deviceName: String
    let deviceUUID: String
    let commit: String
    let tvEphPriv: Curve25519.KeyAgreement.PrivateKey
    let tvEphPub: Data
    let nonceT: Data
    var senderEphPub: Data?
    var nonceS: Data?
    var sharedSecret: Data?
    var sasCode: String?
}

// MARK: - Server Logic
class WebSocketServer: ObservableObject {
    private var tlsListener: NWListener?
    private var connectedConnections: [NWConnection] = []
    private var historyStore: HistoryStore?
    var playlistStore: PlaylistStore?

    private var inProgressHandshakes: [ObjectIdentifier: ConnectionHandshake] = [:]
    private var failedAttempts: [String: Int] = [:]
    private var lockoutUntil: [String: Date] = [:]

    @Published var currentPlayRequest: Playbridge_PlayPayload? {
        didSet {
            // Keep the phone's now-playing context in sync without it having to poll.
            broadcast(["type": "context", "active": currentPlayRequest != nil ? "player" : "idle"])
        }
    }
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
    private let receiverPortKey = "pb_receiver_port"
    private static let defaultReceiverPort: UInt16 = 8765
    private static let maxReceiverPortAttempts = 32

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

    /// Re-broadcasts `playlist_status` whenever the queue changes — including index moves
    /// driven by the player UI (next/jump), which never pass through the server.
    private var playlistCancellable: AnyCancellable?

    init(historyStore: HistoryStore? = nil, playlistStore: PlaylistStore? = nil) {
        self.historyStore = historyStore
        self.playlistStore = playlistStore
        self.localIP = getIPAddress()
        self.pairedDevicesList = storedPairedDevices

        // objectWillChange fires *before* the mutation lands; the debounce hop onto the
        // main queue ensures we serialize the post-mutation queue state.
        playlistCancellable = playlistStore?.objectWillChange
            .debounce(for: .milliseconds(100), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in self?.broadcastPlaylistStatus() }

    }

    func start(port: UInt16? = nil) {
        // ContentView owns app lifecycle. Its onAppear and scenePhase callbacks can
        // occur close together, so starting must be idempotent: cancelling and
        // immediately rebinding our own fresh listener looks like EADDRINUSE and
        // would incorrectly advance the persisted receiver port on every foreground.
        guard tlsListener == nil else { return }

        let preferredPort = port ?? storedReceiverPort
        serverState = "Starting..."
        wssPort = nil

        // wss is the default and only transport. Bonjour is attached only after
        // NWListener confirms that the selected port is ready to accept connections.
        startTLSListener(
            port: preferredPort,
            attemptsRemaining: Self.maxReceiverPortAttempts
        )
        startKeepalive()
    }

    private var storedReceiverPort: UInt16 {
        let stored = UserDefaults.standard.integer(forKey: receiverPortKey)
        guard stored >= 1, let port = UInt16(exactly: stored) else {
            return Self.defaultReceiverPort
        }
        return port
    }

    private func makeBonjourService(wssPort: UInt16) -> NWListener.Service {
        let txtDict: [String: Data] = [
            "uuid": deviceUUID.data(using: .utf8)!,
            "device_name": deviceName.data(using: .utf8)!,
            "wss_port": String(wssPort).data(using: .utf8)!,
        ]
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

    /// Starts the encrypted wss:// listener on `port`. Address collisions advance
    /// through a bounded range; every other failure stops startup and is surfaced.
    private func startTLSListener(port: UInt16, attemptsRemaining: Int) {
        let identity: TLSIdentity.Result
        do {
            identity = try TLSIdentity.loadOrCreate(commonName: deviceName)
        } catch {
            print("[wss] TLS identity unavailable: \(error)")
            certFingerprint = nil
            serverState = "Secure server failed to start"
            return
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
            l.stateUpdateHandler = { [weak self, weak l] state in
                guard let self, let l, self.tlsListener === l else { return }
                self.handleTLSListenerState(
                    state,
                    listener: l,
                    port: port,
                    attemptsRemaining: attemptsRemaining
                )
            }
            l.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            tlsListener = l
            l.start(queue: .main)
        } catch {
            print("[wss] listener error: \(error)")
            if let nwError = error as? NWError, isAddressInUse(nwError) {
                retryAfterAddressInUse(port: port, attemptsRemaining: attemptsRemaining)
            } else {
                certFingerprint = nil
                serverState = "Error: \(error.localizedDescription)"
            }
        }
    }

    private func handleTLSListenerState(
        _ state: NWListener.State,
        listener: NWListener,
        port: UInt16,
        attemptsRemaining: Int
    ) {
        switch state {
        case .ready:
            // NWListener.service can be assigned after readiness. Doing so ensures a
            // collided candidate is never advertised and TXT/SRV use the bound port.
            listener.service = makeBonjourService(wssPort: port)
            wssPort = port
            UserDefaults.standard.set(Int(port), forKey: receiverPortKey)
            serverState = "Ready to Connect"
            restartAttempts = 0
            print("[wss] listening on \(port)")
        case .failed(let error):
            let failedAfterReadiness = wssPort == port
            abandonTLSListener(listener)
            if isAddressInUse(error) {
                retryAfterAddressInUse(port: port, attemptsRemaining: attemptsRemaining)
            } else {
                certFingerprint = nil
                serverState = "Error: \(error.localizedDescription)"
                print("[wss] failed: \(error)")
                if failedAfterReadiness {
                    scheduleRestart(port: port)
                }
            }
        case .waiting(let error):
            if isAddressInUse(error) {
                abandonTLSListener(listener)
                retryAfterAddressInUse(port: port, attemptsRemaining: attemptsRemaining)
            } else {
                serverState = "Waiting: \(error.localizedDescription)"
            }
        case .setup:
            serverState = "Starting..."
        case .cancelled:
            serverState = "Stopped"
        @unknown default:
            break
        }
    }

    private func abandonTLSListener(_ listener: NWListener) {
        listener.stateUpdateHandler = nil
        listener.newConnectionHandler = nil
        listener.cancel()
        tlsListener = nil
        wssPort = nil
    }

    private func isAddressInUse(_ error: NWError) -> Bool {
        if case .posix(let code) = error { return code == .EADDRINUSE }
        return false
    }

    private func retryAfterAddressInUse(port: UInt16, attemptsRemaining: Int) {
        guard attemptsRemaining > 1, port < UInt16.max else {
            certFingerprint = nil
            serverState = "No available receiver port"
            print("[wss] no available port after bounded fallback from \(storedReceiverPort)")
            return
        }
        let nextPort = port + 1
        serverState = "Port \(port) in use; trying \(nextPort)"
        startTLSListener(port: nextPort, attemptsRemaining: attemptsRemaining - 1)
    }

    func stop() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = nil
        restartWork?.cancel()
        restartWork = nil
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

    func restart(port: UInt16? = nil) {
        stop()
        start(port: port)
    }

    /// A listener that was previously ready may fail after a network transition.
    /// Recover that listener on the same selected port without treating the failure
    /// as a collision or scanning additional ports.
    private func scheduleRestart(port: UInt16) {
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

    private func getIPAddress(from connection: NWConnection) -> String {
        if case let .hostPort(host, _) = connection.endpoint {
            switch host {
            case .name(let name, _):
                return name
            case .ipv4(let address):
                return "\(address)"
            case .ipv6(let address):
                return "\(address)"
            @unknown default:
                return "unknown"
            }
        }
        return "unknown"
    }

    private func handleHandshakeFailure(for connection: NWConnection) {
        let connId = ObjectIdentifier(connection)
        guard inProgressHandshakes.removeValue(forKey: connId) != nil else { return }
        
        DispatchQueue.main.async {
            self.serverState = "Incorrect code or connection lost"
            // Wait 3 seconds and reset to "Ready to Connect"
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                if self.serverState == "Incorrect code or connection lost" {
                    self.serverState = "Ready to Connect"
                }
            }
        }
    }

    private func removeConnection(_ connection: NWConnection) {
        connection.cancel()
        handleHandshakeFailure(for: connection)
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
        case "pairing_commit":
            if let msg = try? Playbridge_PairingCommitMessage(jsonString: jsonString) {
                handlePairingCommit(msg, from: connection)
            }
        case "pairing_reveal":
            if let msg = try? Playbridge_PairingRevealMessage(jsonString: jsonString) {
                handlePairingReveal(msg, from: connection)
            }
        case "pairing_confirmation":
            if let msg = try? Playbridge_PairingConfirmationMessage(jsonString: jsonString) {
                handlePairingConfirmation(msg, from: connection)
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

    // MARK: - SAS Pairing Handshake

    private func recordPairingFailure(ip: String) {
        let attempts = (failedAttempts[ip] ?? 0) + 1
        failedAttempts[ip] = attempts
        if attempts >= 3 {
            lockoutUntil[ip] = Date().addingTimeInterval(60) // 60s lockout
            failedAttempts[ip] = 0
            print("[server] IP \(ip) locked out for 60 seconds due to 3 failed pairing attempts")
        }
    }

    private func handlePairingCommit(_ msg: Playbridge_PairingCommitMessage, from connection: NWConnection) {
        let ip = getIPAddress(from: connection)
        
        // 1. Rate-limiting check
        if let lockout = lockoutUntil[ip], lockout > Date() {
            print("[server] IP \(ip) is locked out from pairing")
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }
        
        // 2. Concurrency check (only one pairing at a time)
        if pendingPairingRequest != nil || !inProgressHandshakes.isEmpty {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }
        
        // 3. Generate TV keypair and nonceT
        let keys = SasCrypto.generateX25519KeyPair()
        let nonceT = SasCrypto.generateNonce(size: 16)
        
        let handshake = ConnectionHandshake(
            deviceName: msg.deviceName,
            deviceUUID: msg.deviceUuid,
            commit: msg.commit,
            tvEphPriv: keys.privateKey,
            tvEphPub: keys.publicKeyBytes,
            nonceT: nonceT
        )
        
        inProgressHandshakes[ObjectIdentifier(connection)] = handshake
        
        // 4. Respond with challenge
        let challenge: [String: Any] = [
            "type": "pairing_challenge",
            "tvEphPub": keys.publicKeyBytes.base64EncodedString(),
            "nonceT": nonceT.base64EncodedString()
        ]
        send(json: challenge, to: connection)
    }

    private func handlePairingReveal(_ msg: Playbridge_PairingRevealMessage, from connection: NWConnection) {
        let connId = ObjectIdentifier(connection)
        guard var handshake = inProgressHandshakes[connId] else {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }
        
        guard let senderEphPubBytes = Data(base64Encoded: msg.senderEphPub),
              let nonceSBytes = Data(base64Encoded: msg.nonceS) else {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }
        
        // Verify commitment: commit == SHA-256(senderEphPub || nonceS)
        var combined = Data()
        combined.append(senderEphPubBytes)
        combined.append(nonceSBytes)
        let calculatedCommit = SasCrypto.sha256(combined).base64EncodedString()
        
        if calculatedCommit != handshake.commit {
            print("[server] Commitment mismatch — denying pairing")
            send(json: ["type": "pairing_denied"], to: connection)
            removeConnection(connection)
            recordPairingFailure(ip: getIPAddress(from: connection))
            return
        }
        
        handshake.senderEphPub = senderEphPubBytes
        handshake.nonceS = nonceSBytes
        
        // Compute ECDH shared secret
        do {
            let sharedSecret = try SasCrypto.calculateECDH(privateKey: handshake.tvEphPriv, publicKeyBytes: senderEphPubBytes)
            handshake.sharedSecret = sharedSecret
            
            // Compute transcript and SAS code
            guard let commitBytes = Data(base64Encoded: handshake.commit) else {
                send(json: ["type": "pairing_denied"], to: connection)
                removeConnection(connection)
                return
            }
            var transcript = Data()
            transcript.append(commitBytes)
            transcript.append(handshake.tvEphPub)
            transcript.append(handshake.nonceT)
            transcript.append(senderEphPubBytes)
            transcript.append(nonceSBytes)
            
            let sas = SasCrypto.generateSAS(sharedSecret: sharedSecret, transcript: transcript)
            handshake.sasCode = sas
            inProgressHandshakes[connId] = handshake
            
            // Present the code on the screen
            let request = PairingRequest(
                deviceName: handshake.deviceName,
                deviceUUID: handshake.deviceUUID,
                sasCode: sas,
                connection: connection
            )
            DispatchQueue.main.async { self.pendingPairingRequest = request }
            
            autoTimeoutWork?.cancel()
            let uuid = handshake.deviceUUID
            let work = DispatchWorkItem { [weak self] in
                guard let self = self, self.pendingPairingRequest?.deviceUUID == uuid else { return }
                self.denyPairing()
            }
            autoTimeoutWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 60, execute: work) // 60s timeout
        } catch {
            print("[server] ECDH calculation error: \(error)")
            send(json: ["type": "pairing_denied"], to: connection)
            removeConnection(connection)
        }
    }

    private func handlePairingConfirmation(_ msg: Playbridge_PairingConfirmationMessage, from connection: NWConnection) {
        let connId = ObjectIdentifier(connection)
        guard let handshake = inProgressHandshakes[connId],
              let pending = pendingPairingRequest,
              pending.connection === connection else {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }
        
        // Derive confirmation key and expected MAC
        guard let commitBytes = Data(base64Encoded: handshake.commit),
              let senderEphPub = handshake.senderEphPub,
              let nonceS = handshake.nonceS,
              let sharedSecret = handshake.sharedSecret else {
            send(json: ["type": "pairing_denied"], to: connection)
            connection.cancel()
            return
        }
        
        var transcript = Data()
        transcript.append(commitBytes)
        transcript.append(handshake.tvEphPub)
        transcript.append(handshake.nonceT)
        transcript.append(senderEphPub)
        transcript.append(nonceS)
        
        let prk = SasCrypto.hkdfExtract(salt: nil, ikm: sharedSecret)
        let confirmationKey = SasCrypto.hkdfExpand(
            prk: prk,
            info: "confirmationKey".data(using: .utf8),
            length: 32
        )
        let expectedMac = SasCrypto.hmacSha256(key: confirmationKey, data: transcript).base64EncodedString()
        
        if msg.mac == expectedMac {
            approvePairing(handshake: handshake, connection: connection)
        } else {
            print("[server] Confirmation MAC mismatch — denying pairing")
            denyPairing()
            recordPairingFailure(ip: getIPAddress(from: connection))
        }
    }

    private func approvePairing(handshake: ConnectionHandshake, connection: NWConnection) {
        autoTimeoutWork?.cancel()
        autoTimeoutWork = nil

        let token = UUID().uuidString
        var tokens = authorizedTokens
        tokens.insert(token)
        authorizedTokens = tokens

        let device = PairedDevice(
            deviceUUID: handshake.deviceUUID,
            deviceName: handshake.deviceName,
            token: token,
            lastConnected: Date()
        )
        savePairedDevice(device)

        guard let commitBytes = Data(base64Encoded: handshake.commit),
              let senderEphPub = handshake.senderEphPub,
              let nonceS = handshake.nonceS,
              let sharedSecret = handshake.sharedSecret else {
            send(json: ["type": "pairing_denied"], to: connection)
            removeConnection(connection)
            return
        }
        var transcript = Data()
        transcript.append(commitBytes)
        transcript.append(handshake.tvEphPub)
        transcript.append(handshake.nonceT)
        transcript.append(senderEphPub)
        transcript.append(nonceS)

        do {
            let transcriptHash = SasCrypto.sha256(transcript)
            let prk = SasCrypto.hkdfExtract(salt: nil, ikm: sharedSecret)
            let credentialKey = SasCrypto.hkdfExpand(
                prk: prk,
                info: "playbridgeCredentialKey-v1".data(using: .utf8),
                length: 32
            )
            let credentialNonce = SasCrypto.generateNonce(size: 12)
            var credentials: [String: Any] = [
                "token": token,
                "players": Self.capabilityPlayers,
            ]
            if let fp = certFingerprint { credentials["certFingerprint"] = fp }
            let plaintext = try JSONSerialization.data(withJSONObject: credentials)
            let ciphertext = try SasCrypto.aesGcmEncrypt(
                key: credentialKey,
                nonce: credentialNonce,
                plaintext: plaintext,
                aad: transcriptHash
            )
            send(json: [
                "type": "pairing_approved",
                "nonce": credentialNonce.base64EncodedString(),
                "ciphertext": ciphertext.base64EncodedString(),
            ], to: connection)
        } catch {
            print("[server] Failed to protect pairing credentials")
            send(json: ["type": "pairing_denied"], to: connection)
            removeConnection(connection)
            return
        }
        
        let ip = getIPAddress(from: connection)
        failedAttempts.removeValue(forKey: ip)
        lockoutUntil.removeValue(forKey: ip)
        
        inProgressHandshakes.removeValue(forKey: ObjectIdentifier(connection))
        completeAuth(from: connection, token: token, sendAuthResponse: false)
        pendingPairingRequest = nil
    }

    func denyPairing() {
        guard let request = pendingPairingRequest else { return }
        autoTimeoutWork?.cancel()
        autoTimeoutWork = nil
        send(json: ["type": "pairing_denied"], to: request.connection)
        
        let connId = ObjectIdentifier(request.connection)
        inProgressHandshakes.removeValue(forKey: connId)
        
        request.connection.cancel()
        pendingPairingRequest = nil
    }

    // MARK: - Auth

    /// Players this receiver advertises to the phone at auth, so the phone's player picker
    /// shows "TV Default" + AVPlayer + VLC + MPV. A concrete choice is honored per cast in
    /// `PlayerView` via the play payload's `playerMode`. (No browsers — Apple TV has no web view.)
    static let capabilityPlayers = ["avplayer", "vlc", "mpv"]

    /// Posted (on main) when the phone sends a `control` command (userInfo["command"]) or a
    /// `remote` key (userInfo["key"]). The active player view observes these.
    static let controlCommand = Notification.Name("PlayBridgeControlCommand")
    static let remoteKey = Notification.Name("PlayBridgeRemoteKey")
    /// Posted when a client (re)connects; the active player re-broadcasts status + tracks.
    static let resyncRequest = Notification.Name("PlayBridgeResync")

    /// Broadcast the current queue as `playlist_status`, matching the Android receiver's
    /// format. Echoes each item's series context (season/episode/imdbId/bingeGroup) so the
    /// phone can re-attach its lazy episode queue and match watch progress. Always sends —
    /// even when empty — so the phone clears a stale episode list.
    func broadcastPlaylistStatus() {
        guard let store = playlistStore else { return }
        let items: [[String: Any]] = store.items.enumerated().map { index, item in
            var obj: [String: Any] = [
                "index": index,
                "title": item.titleOrNil ?? "Item \(index + 1)",
            ]
            if item.hasVisualMetadata {
                let vm = item.visualMetadata
                if vm.hasSeason { obj["season"] = Int(vm.season) }
                if vm.hasEpisode { obj["episode"] = Int(vm.episode) }
                if vm.hasImdbID { obj["imdbId"] = vm.imdbID }
            }
            if item.hasBingeGroup { obj["bingeGroup"] = item.bingeGroup }
            return obj
        }
        broadcast([
            "type": "playlist_status",
            "items": items,
            "currentIndex": store.items.isEmpty ? 0 : max(store.currentIndex, 0),
            "totalCount": store.items.count,
        ])
    }

    /// Send a JSON object to every connected client (now-playing status, tracks, context, …).
    func broadcast(_ json: [String: Any]) {
        guard !connectedConnections.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: json) else { return }
        let context = NWConnection.ContentContext(
            identifier: "broadcast", metadata: [NWProtocolWebSocket.Metadata(opcode: .text)])
        for connection in connectedConnections {
            connection.send(content: data, contentContext: context, isComplete: true,
                            completion: .contentProcessed({ _ in }))
        }
    }

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

    private func completeAuth(
        from connection: NWConnection,
        token: String,
        sendAuthResponse: Bool = true
    ) {
        DispatchQueue.main.async {
            self.isAuthenticated = true
            self.connectedConnections.append(connection)
            self.connectedCount = self.connectedConnections.count
            if sendAuthResponse {
                // Safe on reconnect: the sender has already pinned this TLS identity.
                var response: [String: Any] = ["type": "auth_response", "success": true]
                if let fp = self.certFingerprint { response["certFingerprint"] = fp }
                response["players"] = Self.capabilityPlayers
                self.send(json: response, to: connection)
            }
            // Re-sync now-playing for a client that connected mid-playback: context + a nudge
            // for the active player to re-broadcast its status/tracks.
            self.broadcast(["type": "context", "active": self.currentPlayRequest != nil ? "player" : "idle"])
            // Sync the queue too, so a re-connecting phone can re-attach its episode queue.
            if self.playlistStore?.items.isEmpty == false {
                self.broadcastPlaylistStatus()
            }
            NotificationCenter.default.post(name: Self.resyncRequest, object: nil)
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
        case "control":
            if let p = try? Playbridge_ControlPayload(jsonString: payloadJson), !p.command.isEmpty {
                DispatchQueue.main.async {
                    if StillWatchingGate.isPrompting {
                        if p.command == "stop" {
                            NotificationCenter.default.post(name: .playBridgeStillWatchingStop, object: nil)
                        } else {
                            NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
                        }
                        return
                    }
                    NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
                    NotificationCenter.default.post(
                        name: Self.controlCommand, object: nil, userInfo: ["command": p.command])
                }
            }
        case "remote":
            if let p = try? Playbridge_RemotePayload(jsonString: payloadJson), !p.key.isEmpty {
                DispatchQueue.main.async {
                    if StillWatchingGate.isPrompting {
                        NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
                        return
                    }
                    NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
                    NotificationCenter.default.post(
                        name: Self.remoteKey, object: nil, userInfo: ["key": p.key])
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
            TrackPreferences.shared.reset() // new cast session — drop carried track picks
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
            TrackPreferences.shared.reset() // new cast session — drop carried track picks
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
