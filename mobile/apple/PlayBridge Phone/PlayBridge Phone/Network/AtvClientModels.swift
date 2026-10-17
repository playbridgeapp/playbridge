import Foundation

/// Fixed, non-payload error descriptions. Never forward native message/decoder text.
enum AtvClientError: Error, Equatable, LocalizedError {
    case unavailable, unsupportedABI, startFailed, rejected, overflow, invalidInput, malformedEvent
    case closed, cancelled, superseded, timeout, notConnected, notPairing
    case native(AtvFailureKind)

    var errorDescription: String? {
        switch self {
        case .unavailable: return "Android TV native client is unavailable"
        case .unsupportedABI: return "Android TV native client ABI is unsupported"
        case .startFailed: return "Android TV native client could not start"
        case .rejected: return "Android TV command was not accepted"
        case .overflow: return "Android TV client limit exceeded"
        case .invalidInput: return "Invalid Android TV command"
        case .malformedEvent: return "Invalid Android TV event"
        case .closed: return "Android TV client closed"
        case .cancelled: return "Android TV operation cancelled"
        case .superseded: return "Android TV operation replaced"
        case .timeout: return "Android TV operation timed out"
        case .notConnected: return "Android TV control is not connected"
        case .notPairing: return "Android TV pairing has not started"
        case .native(let kind): return kind.message
        }
    }
}

enum AtvFailureKind: String, Decodable {
    case unreachable, tlsHandshake = "tls_handshake", notTrusted = "not_trusted"
    case pinMismatch = "pin_mismatch", badCode = "bad_code", protocolError = "protocol", timeout, closed
    var message: String {
        switch self {
        case .unreachable: return "Android TV remote is unreachable"
        case .tlsHandshake: return "Android TV TLS handshake failed"
        case .notTrusted: return "Android TV does not trust this client; pair again"
        case .pinMismatch: return "Android TV certificate does not match the stored pin"
        case .badCode: return "Android TV pairing code was rejected"
        case .protocolError: return "Invalid Android TV protocol message"
        case .timeout: return "Android TV remote timed out"
        case .closed: return "Android TV remote connection closed"
        }
    }
}

struct AtvIdentity: Equatable, Decodable, CustomStringConvertible, CustomDebugStringConvertible {
    let certPem: String
    let keyPem: String
    let fingerprint: String
    let clientName: String?
    let replacesLegacy: Bool
    init(certPem: String, keyPem: String, fingerprint: String, clientName: String? = nil, replacesLegacy: Bool = false) {
        self.certPem = certPem; self.keyPem = keyPem; self.fingerprint = fingerprint
        self.clientName = clientName; self.replacesLegacy = replacesLegacy
    }
    enum CodingKeys: String, CodingKey {
        case certPem = "cert_pem", keyPem = "key_pem", fingerprint, clientName = "client_name", replacesLegacy = "replaces_legacy"
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(certPem: try values.decode(String.self, forKey: .certPem), keyPem: try values.decode(String.self, forKey: .keyPem),
                  fingerprint: try values.decode(String.self, forKey: .fingerprint),
                  clientName: try values.decodeIfPresent(String.self, forKey: .clientName),
                  replacesLegacy: try values.decodeIfPresent(Bool.self, forKey: .replacesLegacy) ?? false)
    }
    var description: String { "AtvIdentity(redacted)" }
    var debugDescription: String { description }
}

struct AtvPairingStarted: Equatable, Decodable, CustomStringConvertible, CustomDebugStringConvertible {
    let serverName: String?
    let fingerprint: String
    let mac: String?
    enum CodingKeys: String, CodingKey { case serverName = "server_name", fingerprint, mac }
    var description: String { "AtvPairingStarted(redacted)" }
    var debugDescription: String { description }
}

struct AtvPaired: Equatable, CustomStringConvertible, CustomDebugStringConvertible {
    let serverCertificateDER: Data
    let fingerprint: String
    let mac: String?
    let serverName: String?
    var description: String { "AtvPaired(redacted)" }
    var debugDescription: String { description }
}

struct AtvDiscoveredDevice: Equatable, Decodable {
    let name: String
    let host: String
    let port: UInt16
    let addresses: [String]
    let txt: [[String]]
}

struct AtvTelevisionState: Equatable, Decodable {
    let isOn: Bool
    let volumeLevel: UInt32?
    let volumeMax: UInt32?
    let volumeMuted: Bool
    let volumeRevision: UInt64
    let currentApp: String?
    let model: String?
    let vendor: String?
    enum CodingKeys: String, CodingKey {
        case isOn = "is_on", volumeLevel = "volume_level", volumeMax = "volume_max"
        case volumeMuted = "volume_muted", volumeRevision = "volume_revision", currentApp = "current_app", model, vendor
    }
}

struct AtvClientState: Equatable {
    enum Phase { case idle, connecting, connected, disconnected, closed }
    var phase: Phase = .idle
    var television: AtvTelevisionState?
    var failure: AtvClientError?
}

enum AtvKey { case name(String), code(UInt32) }
enum AtvKeyDirection: String { case short, startLong = "start_long", endLong = "end_long" }
enum AtvVolumeAction: String { case up, down, mute }

// Wire values never leave this layer. In particular, native error messages and raw IME keys
// are not retained or published, even if a future native library includes them.
enum AtvEvent {
    case devices(String, [AtvDiscoveredDevice]), identity(String, AtvIdentity)
    case pairingStarted(String, AtvPairingStarted), paired(String, AtvPaired)
    case connected(String), state(String, AtvTelevisionState), operation(String, String)
    case error(String?, AtvClientError), closed(String, AtvFailureKind)

    var requestID: String? {
        switch self {
        case .devices(let id, _), .identity(let id, _), .pairingStarted(let id, _), .paired(let id, _),
             .state(let id, _), .operation(let id, _), .closed(let id, _), .connected(let id): return id
        case .error(let id, _): return id
        }
    }

    /// Inspect only correlation before decoding a payload: retired requests cannot damage a new
    /// connection with an obsolete state shape. Unrouteable/malformed envelopes still fail closed.
    static func correlationID(_ data: Data, maximumBytes: Int) throws -> String? {
        guard data.count <= maximumBytes else { throw AtvClientError.overflow }
        guard String(data: data, encoding: .utf8) != nil else { throw AtvClientError.malformedEvent }
        struct Header: Decodable {
            let event: String
            let requestID: String?
            enum CodingKeys: String, CodingKey { case event, requestID = "request_id" }
        }
        do {
            let header = try JSONDecoder().decode(Header.self, from: data)
            guard header.event.utf8.count <= 32,
                  header.requestID.map({ !$0.isEmpty && $0.utf8.count <= 128 }) ?? (header.event == "error") else { throw AtvClientError.malformedEvent }
            return header.requestID
        } catch let error as AtvClientError { throw error }
        catch { throw AtvClientError.malformedEvent }
    }

    static func decode(_ data: Data, maximumBytes: Int) throws -> AtvEvent {
        guard data.count <= maximumBytes else { throw AtvClientError.overflow }
        guard String(data: data, encoding: .utf8) != nil else { throw AtvClientError.malformedEvent }
        do {
            return try JSONDecoder().decode(Envelope.self, from: data).event
        } catch let error as AtvClientError { throw error }
        catch { throw AtvClientError.malformedEvent }
    }

    private struct Envelope: Decodable {
        let event: AtvEvent
        enum Keys: String, CodingKey {
            case event, requestID = "request_id", devices, state, operation, ok, kind
            case serverCertificate = "server_cert_der_b64", fingerprint, mac, serverName = "server_name"
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            let type = try c.decode(String.self, forKey: .event)
            let id = try c.decodeIfPresent(String.self, forKey: .requestID)
            guard type.utf8.count <= 32, id.map({ !$0.isEmpty && $0.utf8.count <= 128 }) ?? (type == "error") else { throw AtvClientError.malformedEvent }
            func requiredID() throws -> String {
                guard let id else { throw AtvClientError.malformedEvent }
                return id
            }
            switch type {
            case "devices":
                let devices = try c.decode([AtvDiscoveredDevice].self, forKey: .devices)
                guard devices.count <= 256, devices.allSatisfy({
                    bounded($0.name, 1024) && bounded($0.host, 1024) && $0.port > 0 &&
                    $0.addresses.count <= 32 && $0.addresses.allSatisfy { bounded($0, 1024) } &&
                    $0.txt.count <= 64 && $0.txt.allSatisfy { $0.count == 2 && $0.allSatisfy { bounded($0, 4096) } }
                }) else { throw AtvClientError.overflow }
                event = .devices(try requiredID(), devices)
            case "identity":
                let value = try AtvIdentity(from: decoder)
                guard !value.certPem.isEmpty, !value.keyPem.isEmpty, bounded(value.certPem, 65_536), bounded(value.keyPem, 65_536), atvValidFingerprint(value.fingerprint) else { throw AtvClientError.malformedEvent }
                event = .identity(try requiredID(), value)
            case "pairing_started":
                let value = try AtvPairingStarted(from: decoder)
                guard atvValidFingerprint(value.fingerprint), bounded(value.serverName, 1024), bounded(value.mac, 128) else { throw AtvClientError.malformedEvent }
                event = .pairingStarted(try requiredID(), value)
            case "paired":
                let encoded = try c.decode(String.self, forKey: .serverCertificate)
                let fingerprint = try c.decode(String.self, forKey: .fingerprint)
                let mac = try c.decodeIfPresent(String.self, forKey: .mac)
                let name = try c.decodeIfPresent(String.self, forKey: .serverName)
                guard bounded(encoded, 90_000), let der = Data(base64Encoded: encoded), !der.isEmpty, der.count <= 65_536,
                      atvValidFingerprint(fingerprint), bounded(mac, 128), bounded(name, 1024) else { throw AtvClientError.malformedEvent }
                event = .paired(try requiredID(), AtvPaired(serverCertificateDER: der, fingerprint: fingerprint, mac: mac, serverName: name))
            case "connected": event = .connected(try requiredID())
            case "state":
                let value = try c.decode(AtvTelevisionState.self, forKey: .state)
                guard bounded(value.currentApp, 4096), bounded(value.model, 1024), bounded(value.vendor, 1024) else { throw AtvClientError.overflow }
                event = .state(try requiredID(), value)
            case "operation":
                guard try c.decode(Bool.self, forKey: .ok) else { throw AtvClientError.malformedEvent }
                let operation = try c.decode(String.self, forKey: .operation)
                guard ["cancel_pairing", "disconnect", "key", "volume", "text", "launch_app", "power"].contains(operation) else { throw AtvClientError.malformedEvent }
                event = .operation(try requiredID(), operation)
            case "error", "closed":
                let kind = AtvFailureKind(rawValue: try c.decode(String.self, forKey: .kind)) ?? .protocolError
                if type == "error" { event = .error(id, .native(kind)) }
                else { event = .closed(try requiredID(), kind) }
            default: throw AtvClientError.malformedEvent
            }
        }
    }
}

private func bounded(_ string: String?, _ bytes: Int) -> Bool { string.map { $0.utf8.count <= bytes } ?? true }
func atvValidFingerprint(_ value: String) -> Bool {
    value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }
}
