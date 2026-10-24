import Foundation

/// One TV browser waiting for the phone to approve its pairing code.
struct BrowserPairingRequest: Equatable, Identifiable {
    var id: String { sessionId }
    var sessionId: String
    var receiverId: String
    var name: String
    var expiresAt: Date
}

/// An approved browser session that can take a cast.
struct BrowserReadySession: Equatable, Identifiable {
    var id: String { sessionId }
    var sessionId: String
    var receiverId: String
    var name: String
}

/// Playback reported by the browser page. States match the remote's status words.
struct BrowserPlaybackSnapshot: Equatable {
    var state: String
    var positionMs: Int64
    var durationMs: Int64
    var title: String?
}

struct BrowserHostSnapshot: Equatable {
    var running = false
    var urls: [String] = []
    var port = 0
    var pending: [BrowserPairingRequest] = []
    var ready: [BrowserReadySession] = []
    var lastError: String?
    var busy = false
}

struct BrowserReduceResult: Equatable {
    var activate: BrowserReadySession?
    var playbackSessionId: String?
    var playback: BrowserPlaybackSnapshot?
}

/// Pure host snapshot. Event JSON matches `BrowserReceiverEvent` from sender services.
enum BrowserReceiverState {
    static let preferredPort = 8770
    static let keepOpenNote = "Keep PlayBridge open while the TV browser is connected. Playback pauses if you leave the app."

    /// Page address for the TV browser. Prefer the Wi-Fi IPv4 host, and never attach a stream or credential.
    static func pageURL(urls: [String], port: Int, lanHost: String?) -> String? {
        guard let host = pageHost(urls: urls, port: port, lanHost: lanHost) else { return nil }
        let resolvedPort = pagePort(urls: urls, port: port)
        guard (1...65535).contains(resolvedPort) else { return nil }
        return "http://\(literalHost(host)):\(resolvedPort)"
    }

    static func pageHost(urls: [String], port: Int, lanHost: String?) -> String? {
        let lan = normalizedHost(lanHost)
        if let lan, urls.contains(where: { normalizedHost(host(of: $0)) == lan }) || (1...65535).contains(port) || pagePort(urls: urls, port: 0) > 0 {
            return lan
        }
        let hosts = urls.compactMap { host(of: $0) }.map { normalizedHost($0) ?? "" }.filter { !$0.isEmpty && !isLoopback($0) }
        return hosts.first
    }

    static func humanizeStartError(_ raw: String?) -> String {
        let message = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let lower = message.lowercased()
        if lower.contains("port") || lower.contains("address already in use") || lower.contains("bind") {
            return "Couldn't start browser host (ports 8770–8779 busy)"
        }
        if message.isEmpty { return "Couldn't start browser host" }
        return publicError(message)
    }

    /// Drop anything that could carry a stream URL or a credential into the setup UI.
    static func publicError(_ raw: String?) -> String {
        let message = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if message.isEmpty { return "Couldn't complete that browser action" }
        let lower = message.lowercased()
        if message.contains("://") || lower.contains("bearer") || lower.contains("token=") || lower.contains("cookie") {
            return "Couldn't complete that browser action"
        }
        return message
    }

    static func playbackState(_ raw: String) -> String {
        switch raw.lowercased() {
        case "playing": return "playing"
        case "paused": return "paused"
        case "buffering": return "buffering"
        case "error", "autoplay_blocked": return "error"
        case "stopped", "ended", "idle": return "stopped"
        default: return "stopped"
        }
    }

    static func reduce(_ state: inout BrowserHostSnapshot, event: [String: Any], now: Date, pendingActivation: inout String?) -> BrowserReduceResult {
        state.pending.removeAll { $0.expiresAt <= now }
        let kind = event["event"] as? String ?? ""
        switch kind {
        case "pairing_requested":
            guard let request = pairing(event, now: now) else { return BrowserReduceResult() }
            state.pending.removeAll {
                $0.sessionId == request.sessionId || (!request.receiverId.isEmpty && $0.receiverId == request.receiverId)
            }
            state.pending.append(request)
        case "connected", "capabilities", "status":
            guard let ready = readySession(event) else { return BrowserReduceResult() }
            state.pending.removeAll {
                $0.sessionId == ready.sessionId || (!ready.receiverId.isEmpty && $0.receiverId == ready.receiverId)
            }
            state.ready.removeAll {
                $0.sessionId == ready.sessionId || (!ready.receiverId.isEmpty && $0.receiverId == ready.receiverId)
            }
            state.ready.append(ready)
            let playback = playbackSnapshot(event)
            var activate: BrowserReadySession?
            if kind == "connected", pendingActivation == ready.sessionId {
                pendingActivation = nil
                activate = ready
            }
            return BrowserReduceResult(activate: activate, playbackSessionId: ready.sessionId, playback: playback)
        case "ended", "error":
            let playback = playbackSnapshot(event)
            let sessionId = sessionID(event)
            if kind == "error", event["session"] != nil {
                state.lastError = "TV browser couldn’t play this stream"
            }
            return BrowserReduceResult(playbackSessionId: sessionId, playback: playback)
        case "disconnected":
            guard let sessionId = sessionID(event) else { return BrowserReduceResult() }
            if pendingActivation == sessionId { pendingActivation = nil }
            state.pending.removeAll { $0.sessionId == sessionId }
            state.ready.removeAll { $0.sessionId == sessionId }
            return BrowserReduceResult(
                playbackSessionId: sessionId,
                playback: BrowserPlaybackSnapshot(state: "stopped", positionMs: 0, durationMs: 0, title: nil))
        default:
            break
        }
        return BrowserReduceResult()
    }

    private static func pairing(_ event: [String: Any], now: Date) -> BrowserPairingRequest? {
        guard let session = event["session"] as? [String: Any], let sessionId = string(session["sessionId"]) else { return nil }
        let expires = int(event["expires_in_ms"]) ?? int(event["expiresInMs"]) ?? 120_000
        return BrowserPairingRequest(
            sessionId: sessionId,
            receiverId: string(session["receiverId"]) ?? "",
            name: string(session["name"]) ?? "Browser",
            expiresAt: now.addingTimeInterval(TimeInterval(expires) / 1000))
    }

    private static func readySession(_ event: [String: Any]) -> BrowserReadySession? {
        guard let session = event["session"] as? [String: Any], let sessionId = string(session["sessionId"]) else { return nil }
        return BrowserReadySession(
            sessionId: sessionId,
            receiverId: string(session["receiverId"]) ?? "",
            name: string(session["name"]) ?? "Browser")
    }

    private static func playbackSnapshot(_ event: [String: Any]) -> BrowserPlaybackSnapshot? {
        let kind = event["event"] as? String ?? ""
        let session = event["session"] as? [String: Any]
        let status = session?["status"] as? [String: Any]
        let raw = string(status?["state"]) ?? (kind == "ended" ? "ended" : kind == "error" ? "error" : kind == "disconnected" ? "stopped" : nil)
        guard let raw else { return nil }
        return BrowserPlaybackSnapshot(
            state: kind == "error" ? "error" : playbackState(raw),
            positionMs: int64(status?["positionMs"]),
            durationMs: int64(status?["durationMs"]),
            title: string(status?["title"]))
    }

    private static func sessionID(_ event: [String: Any]) -> String? {
        if let session = event["session"] as? [String: Any], let id = string(session["sessionId"]) { return id }
        return string(event["session_id"]) ?? string(event["sessionId"])
    }

    private static func pagePort(urls: [String], port: Int) -> Int {
        if (1...65535).contains(port) { return port }
        for url in urls {
            if let value = URL(string: url)?.port, (1...65535).contains(value) { return value }
        }
        return 0
    }

    private static func host(of url: String) -> String? {
        guard let host = URL(string: url)?.host else { return nil }
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func normalizedHost(_ host: String?) -> String? {
        let trimmed = host?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty, !isLoopback(trimmed) else { return nil }
        return trimmed
    }

    private static func isLoopback(_ host: String) -> Bool {
        let lower = host.lowercased()
        return lower == "127.0.0.1" || lower == "localhost" || lower == "::1" || lower == "[::1]"
    }

    private static func literalHost(_ host: String) -> String {
        host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
    }

    static func stringList(_ value: Any?) -> [String] {
        if let list = value as? [String] { return list }
        if let list = value as? [Any] { return list.compactMap { $0 as? String } }
        return []
    }

    static func int(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        return nil
    }

    private static func int64(_ value: Any?) -> Int64 {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        return 0
    }

    private static func string(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
