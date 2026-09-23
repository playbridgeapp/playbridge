import Foundation

/// Outbound message encoders, mirroring `IncomingMessage.kt`'s `create*Json` helpers.
///
/// Command payloads use the canonical envelope `{"type":"command","action":<a>,"payload":<json>}`.
/// Payload keys match the proto `json_name` annotations exactly, so the receiver (which decodes
/// them with SwiftProtobuf `init(jsonString:)` on tvOS, or Wire+Moshi on Android TV) accepts them.
/// Standalone messages (ping/auth/pairing_request) are not wrapped in the command envelope.
enum WireProtocol {

    /// Apply at the transport boundary so replay and queue additions follow the preference.
    static func applyingHistoryPreference(_ text: String, prevent: Bool) -> String {
        guard prevent, let data = text.data(using: .utf8),
              var command = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              command["type"] as? String == "command",
              var payload = command["payload"] as? [String: Any] else { return text }
        switch command["action"] as? String {
        case "playlist":
            guard let items = payload["items"] as? [[String: Any]] else { return text }
            payload["items"] = items.map { item in
                var marked = item; marked["skipHistory"] = true; return marked
            }
        case "queue_add":
            guard var item = payload["item"] as? [String: Any] else { return text }
            item["skipHistory"] = true
            payload["item"] = item
        default: return text
        }
        command["payload"] = payload
        return encode(command)
    }

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

    // MARK: - SAS pairing handshake (commit → challenge → reveal → confirmation)
    // Keys match the proto `json_name` annotations so receivers decode them with
    // SwiftProtobuf / Wire+Moshi.

    static func pairingCommit(commit: String, deviceName: String, deviceUUID: String) -> String {
        encode([
            "type": "pairing_commit",
            "commit": commit,
            "deviceName": deviceName,
            "deviceUUID": deviceUUID,
        ])
    }

    static func pairingReveal(senderEphPub: String, nonceS: String) -> String {
        encode([
            "type": "pairing_reveal",
            "senderEphPub": senderEphPub,
            "nonceS": nonceS,
        ])
    }

    static func pairingConfirmation(mac: String) -> String {
        encode(["type": "pairing_confirmation", "mac": mac])
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
        detectedBy: String? = nil,
        playerMode: String? = nil
    ) -> String {
        var item: [String: Any] = ["url": url]
        if let title, !title.isEmpty { item["title"] = title }
        if let contentType, !contentType.isEmpty { item["contentType"] = contentType }
        if !subtitles.isEmpty { item["subtitles"] = subtitles }
        if !headers.isEmpty { item["headers"] = headers }
        if let detectedBy, !detectedBy.isEmpty { item["detectedBy"] = detectedBy }
        if let playerMode, playerMode != "tv" { item["playerMode"] = playerMode }
        let payload: [String: Any] = ["items": [item], "startIndex": 0]
        return envelope(action: "playlist", payload: payload)
    }

    static func queueVideoCommand(
        url: String,
        title: String? = nil,
        contentType: String? = nil,
        subtitles: [String] = [],
        headers: [String: String] = [:],
        detectedBy: String? = nil,
        playerMode: String? = nil,
        playbackId: String? = nil,
        useQueueV1: Bool = false
    ) -> String {
        var item: [String: Any] = ["url": url]
        if let title, !title.isEmpty { item["title"] = title }
        if let contentType, !contentType.isEmpty { item["contentType"] = contentType }
        if !subtitles.isEmpty { item["subtitles"] = subtitles }
        if !headers.isEmpty { item["headers"] = headers }
        if let detectedBy, !detectedBy.isEmpty { item["detectedBy"] = detectedBy }
        if let playerMode, playerMode != "tv" { item["playerMode"] = playerMode }
        var payload: [String: Any] = useQueueV1 ? ["items": [item]] : ["item": item]
        if let playbackId { payload["ifPlaybackId"] = playbackId }
        return envelope(
            action: "queue_add", payload: payload,
            requestID: useQueueV1 ? UUID().uuidString : nil
        )
    }

    static func browserCommand(
        url: String,
        browserMode: String? = nil,
        desktopMode: Bool = false
    ) -> String {
        var payload: [String: Any] = ["url": url]
        if let browserMode, !browserMode.isEmpty { payload["browserMode"] = browserMode }
        if desktopMode { payload["desktopMode"] = true }
        return envelope(action: "browser", payload: payload)
    }

    static func browserControlCommand(_ action: String) -> String {
        envelope(action: "browser_control", payload: ["action": action])
    }

    static func controlCommand(_ command: String) -> String {
        envelope(action: "control", payload: ["command": command])
    }

    static func remoteCommand(key: String) -> String {
        envelope(action: "remote", payload: ["key": key])
    }

    static func playlistJumpCommand(
        index: Int,
        itemId: String? = nil,
        playbackId: String? = nil,
        useQueueV1: Bool = false
    ) -> String {
        var payload: [String: Any] = itemId.map { ["itemId": $0] } ?? ["index": index]
        if let playbackId { payload["ifPlaybackId"] = playbackId }
        return envelope(
            action: "playlist_jump", payload: payload,
            requestID: useQueueV1 ? UUID().uuidString : nil
        )
    }

    static func queueQuery() -> String {
        envelope(action: "queue_query", payload: [:], requestID: UUID().uuidString)
    }

    static func queueRemove(itemIds: [String], playbackId: String?) -> String {
        var payload: [String: Any] = ["itemIds": itemIds]
        if let playbackId { payload["ifPlaybackId"] = playbackId }
        return envelope(action: "queue_remove", payload: payload, requestID: UUID().uuidString)
    }

    static func queueMove(itemId: String, beforeItemId: String?, playbackId: String?) -> String {
        var payload: [String: Any] = ["itemId": itemId]
        if let beforeItemId { payload["beforeItemId"] = beforeItemId }
        if let playbackId { payload["ifPlaybackId"] = playbackId }
        return envelope(action: "queue_move", payload: payload, requestID: UUID().uuidString)
    }

    static func queueClear(playbackId: String?) -> String {
        var payload: [String: Any] = [:]
        if let playbackId { payload["ifPlaybackId"] = playbackId }
        return envelope(action: "queue_clear", payload: payload, requestID: UUID().uuidString)
    }

    static func mouseCommand(event: String, dx: Float = 0, dy: Float = 0) -> String {
        envelope(action: "mouse", payload: ["event": event, "dx": dx, "dy": dy])
    }

    static func contextQuery() -> String {
        encode(["type": "command", "action": "context_query"])
    }

    static func userScriptQuery() -> String { encode(["type": "user_script_query"]) }

    static func userScript(name: String, content: String) -> String {
        encode(["type": "user_script", "name": name, "content": content])
    }

    static func userAgentQuery() -> String { encode(["type": "user_agent_query"]) }

    static func userAgent(name: String, value: String, save: Bool) -> String {
        encode(["type": "user_agent", "name": name, "value": value, "save": save])
    }

    // MARK: - Helpers

    private static func envelope(
        action: String, payload: [String: Any], requestID: String? = nil
    ) -> String {
        var envelope: [String: Any] = [
            "type": "command",
            "action": action,
            "payload": payload,
        ]
        if let requestID { envelope["requestId"] = requestID }
        return encode(envelope)
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
        case "zoom": type = 5
        case "reset": type = 6
        case "rotate": type = 7
        case "transform_anchor": type = 8
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
