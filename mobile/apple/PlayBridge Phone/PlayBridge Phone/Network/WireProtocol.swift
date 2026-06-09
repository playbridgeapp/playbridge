import Foundation

/// Outbound message encoders, mirroring `IncomingMessage.kt`'s `create*Json` helpers.
///
/// Command payloads use the canonical envelope `{"type":"command","action":<a>,"payload":<json>}`.
/// Payload keys match the proto `json_name` annotations exactly, so the receiver (which decodes
/// them with SwiftProtobuf `init(jsonString:)` on tvOS, or Wire+Moshi on Android TV) accepts them.
/// Standalone messages (ping/auth/pairing_request) are not wrapped in the command envelope.
enum WireProtocol {

    // MARK: - Standalone messages

    static func ping() -> String { #"{"type":"ping"}"# }
    static func pong() -> String { #"{"type":"pong"}"# }

    static func auth(token: String) -> String {
        encode(["type": "auth", "token": token])
    }

    static func pairingRequest(deviceName: String, deviceUUID: String) -> String {
        encode([
            "type": "pairing_request",
            "deviceName": deviceName,
            "deviceUUID": deviceUUID,
        ])
    }

    // MARK: - Commands

    /// A single video is just a one-item playlist — there is no standalone `play` command
    /// (see the note in `IncomingMessage.kt::createSingleVideoCommandJson`).
    static func singleVideoCommand(
        url: String,
        title: String? = nil,
        contentType: String? = nil,
        subtitles: [String] = [],
        headers: [String: String] = [:],
        detectedBy: String? = nil
    ) -> String {
        var item: [String: Any] = ["url": url]
        if let title, !title.isEmpty { item["title"] = title }
        if let contentType, !contentType.isEmpty { item["contentType"] = contentType }
        if !subtitles.isEmpty { item["subtitles"] = subtitles }
        if !headers.isEmpty { item["headers"] = headers }
        if let detectedBy, !detectedBy.isEmpty { item["detectedBy"] = detectedBy }
        let payload: [String: Any] = ["items": [item], "startIndex": 0]
        return envelope(action: "playlist", payload: payload)
    }

    static func controlCommand(_ command: String) -> String {
        envelope(action: "control", payload: ["command": command])
    }

    static func remoteCommand(key: String) -> String {
        envelope(action: "remote", payload: ["key": key])
    }

    static func playlistJumpCommand(index: Int) -> String {
        envelope(action: "playlist_jump", payload: ["index": index])
    }

    static func mouseCommand(event: String, dx: Float = 0, dy: Float = 0) -> String {
        envelope(action: "mouse", payload: ["event": event, "dx": dx, "dy": dy])
    }

    static func contextQuery() -> String {
        encode(["type": "command", "action": "context_query"])
    }

    // MARK: - Helpers

    private static func envelope(action: String, payload: [String: Any]) -> String {
        encode([
            "type": "command",
            "action": action,
            "payload": payload,
        ])
    }

    private static func encode(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }
}

/// Compact 9-byte binary mouse packet, matching `BinaryProtocol.kt`'s `MousePacket`.
/// `[0] type(u8)` then `[1-4] dx float32 BE`, `[5-8] dy float32 BE`.
enum MousePacket {
    static func pack(event: String, dx: Float, dy: Float) -> Data {
        let type: UInt8
        switch event {
        case "move": type = 0
        case "click": type = 1
        case "scroll": type = 2
        case "down": type = 3
        case "up": type = 4
        default: type = 0
        }
        var data = Data(capacity: 9)
        data.append(type)
        data.append(contentsOf: dx.bitPattern.bigEndianBytes)
        data.append(contentsOf: dy.bitPattern.bigEndianBytes)
        return data
    }
}

private extension UInt32 {
    /// Big-endian 4-byte representation.
    var bigEndianBytes: [UInt8] {
        [UInt8((self >> 24) & 0xFF),
         UInt8((self >> 16) & 0xFF),
         UInt8((self >> 8) & 0xFF),
         UInt8(self & 0xFF)]
    }
}
