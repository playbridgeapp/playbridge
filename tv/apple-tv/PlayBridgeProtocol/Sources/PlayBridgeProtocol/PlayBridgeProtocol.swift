import Foundation

/// Represents any inbound message from the phone.
/// Mirrored from Kotlin's 'sealed class Command'.
public enum InboundMessage {
    case play(PlayPayload)
    case playContent(ContentPlayPayload)
    case browser(BrowserPayload)
    case control(ControlPayload)
    case remote(RemotePayload)
    case mouse(MousePayload)
    case browserControl(BrowserControlPayload)
    case contextQuery
    case playlist(PlaylistPayload)
    case queueAdd(QueueAddPayload)
    case playlistJump(PlaylistJumpPayload)
    case ping
    case requestPairing
    case auth(AuthMessage)
    case unknown(type: String, action: String?)
}

public struct PlayBridgeProtocol {
    private static let decoder = JSONDecoder()

    /**
     * Decode a JSON string into a strongly-typed InboundMessage.
     */
    public static func decode(_ jsonString: String) -> InboundMessage {
        guard let data = jsonString.data(using: .utf8) else {
            return .unknown(type: "malformed_json", action: nil)
        }

        do {
            let envelope = try decoder.decode(MessageEnvelope.self, from: data)

            switch envelope.type {
            case "ping":
                return .ping
            case "request_pairing":
                return .requestPairing
            case "auth":
                let auth = try decoder.decode(AuthMessage.self, from: data)
                return .auth(auth)
            case "command":
                guard let action = envelope.action else {
                    return .unknown(type: "command", action: nil)
                }

                switch action {
                case "play":
                    let payload = try decodePayload(PlayPayload.self, from: envelope.payload)
                    return .play(payload)
                case "play_content":
                    let payload = try decodePayload(ContentPlayPayload.self, from: envelope.payload)
                    return .playContent(payload)
                case "browser":
                    let payload = try decodePayload(BrowserPayload.self, from: envelope.payload)
                    return .browser(payload)
                case "control":
                    let payload = try decodePayload(ControlPayload.self, from: envelope.payload)
                    return .control(payload)
                case "remote":
                    let payload = try decodePayload(RemotePayload.self, from: envelope.payload)
                    return .remote(payload)
                case "mouse":
                    let payload = try decodePayload(MousePayload.self, from: envelope.payload)
                    return .mouse(payload)
                case "browser_control":
                    let payload = try decodePayload(
                        BrowserControlPayload.self, from: envelope.payload)
                    return .browserControl(payload)
                case "context_query":
                    return .contextQuery
                case "playlist":
                    let payload = try decodePayload(PlaylistPayload.self, from: envelope.payload)
                    return .playlist(payload)
                case "queue_add":
                    let payload = try decodePayload(QueueAddPayload.self, from: envelope.payload)
                    return .queueAdd(payload)
                case "playlist_jump":
                    let payload = try decodePayload(
                        PlaylistJumpPayload.self, from: envelope.payload)
                    return .playlistJump(payload)
                default:
                    return .unknown(type: "command", action: action)
                }
            default:
                return .unknown(type: envelope.type, action: envelope.action)
            }
        } catch {
            return .unknown(type: "decode_error", action: error.localizedDescription)
        }
    }

    /**
     * Helper to decode a specific payload from the AnyCodable dictionary.
     */
    private static func decodePayload<T: Decodable>(
        _ type: T.Type, from dictionary: [String: AnyCodable]?
    ) throws -> T {
        guard let dictionary = dictionary else {
            throw DecodingError.dataCorrupted(
                DecodingError.Context(codingPath: [], debugDescription: "Missing payload"))
        }
        let data = try JSONEncoder().encode(dictionary)
        return try decoder.decode(T.self, from: data)
    }
}
