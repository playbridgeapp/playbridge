import Foundation

/// Watch Together commands, events, and pure UI/sync helpers, mirroring
/// `shared/src/commonMain/kotlin/com/playbridge/shared/together/TogetherModels.kt`
/// and `cast/ffi/src/together.rs`.
///
/// Invite links and item headers can hold secrets: never log them.

enum TogetherDeviceKind: String, Equatable {
    case tv
    case phone
    case desktop
    case cli

    static func from(_ wire: String?) -> TogetherDeviceKind {
        TogetherDeviceKind(rawValue: wire ?? "") ?? .phone
    }
}

enum TogetherPolicy: String, Equatable {
    case onlyHost = "only_host"
    case ask
    case anyone

    static func from(_ wire: String?) -> TogetherPolicy {
        TogetherPolicy(rawValue: wire ?? "") ?? .onlyHost
    }
}

struct TogetherDevice: Equatable {
    let name: String
    let kind: TogetherDeviceKind
    let screen: Bool
}

enum TogetherSource: Equatable {
    case url(String)
    case page(String)
    case app(app: String, id: String)
}

struct TogetherItem: Equatable, CustomStringConvertible {
    let id: String
    let title: String
    let site: String?
    let source: TogetherSource
    let headers: [String: String]
    let subtitles: [String]
    let startMs: Int64
    let sharedBy: String

    init(
        id: String = "",
        title: String,
        site: String? = nil,
        source: TogetherSource,
        headers: [String: String] = [:],
        subtitles: [String] = [],
        startMs: Int64 = 0,
        sharedBy: String = ""
    ) {
        self.id = id
        self.title = title
        self.site = site
        self.source = source
        self.headers = headers
        self.subtitles = subtitles
        self.startMs = startMs
        self.sharedBy = sharedBy
    }

    var description: String {
        "TogetherItem(id=\(id), site=\(site ?? "nil"))"
    }
}

enum TogetherPlayerCommand: Equatable, CustomStringConvertible {
    case load(item: TogetherItem, headers: [String: String], startMs: Int64)
    case play
    case pause
    case seek(positionMs: Int64)
    case rate(Double)
    case goLive

    var description: String {
        switch self {
        case .load(let item, _, let startMs):
            return "Load(\(item.id), startMs=\(startMs))"
        case .play:
            return "Play"
        case .pause:
            return "Pause"
        case .seek(let positionMs):
            return "Seek(\(positionMs))"
        case .rate(let rate):
            return "Rate(\(rate))"
        case .goLive:
            return "GoLive"
        }
    }
}

enum TogetherStop: String, Equatable {
    case left
    case unauthorized
    case roomFull = "room_full"
    case expired
    case replaced
    case error

    static func from(_ wire: String?) -> TogetherStop {
        TogetherStop(rawValue: wire ?? "") ?? .error
    }
}

enum TogetherEvent: Equatable {
    case room(
        roomId: String,
        invite: String,
        relay: String,
        member: String,
        boxKey: String,
        signKey: String,
        host: Bool
    )
    case connected(members: [String])
    case disconnected
    case memberJoined(member: String)
    case memberLeft(member: String)
    case member(member: String, name: String, kind: TogetherDeviceKind, host: Bool)
    case video(item: TogetherItem)
    case timeline(item: String, playing: Bool, positionMs: Int64, rev: Int64)
    case waiting(members: [String])
    case continueWithoutAvailable(members: [String])
    case playRequested(request: String, from: String, title: String, site: String?)
    case requestAnswered(request: String, allowed: Bool)
    case requestExpired(request: String)
    case locked(Bool)
    case policy(TogetherPolicy)
    case credentialsReceived(item: String)
    case warning(message: String)
    case live(Bool)
    case rejected(reason: String)
    case player(TogetherPlayerCommand)
    case pairingCode(code: String, qr: String, qrSize: Int, qrModules: String, expiresAtMs: Int64)
    case pairingInvite(from: String, relay: String)
    case pairSent(id: String?)
    case error(context: String, message: String, id: String?)
    case stopped(roomId: String, reason: TogetherStop)
}

enum TogetherJson {
    static func command(_ name: String, fields: [String: Any] = [:]) -> String? {
        var object = fields
        object["command"] = name
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        return text
    }

    static func deviceFields(_ device: TogetherDevice) -> [String: Any] {
        [
            "name": device.name,
            "kind": device.kind.rawValue,
            "screen": device.screen,
        ]
    }

    static func itemObject(_ item: TogetherItem) -> [String: Any] {
        let sourceObject: [String: Any]
        switch item.source {
        case .url(let url):
            sourceObject = ["type": "url", "url": url]
        case .page(let url):
            sourceObject = ["type": "page", "url": url]
        case .app(let app, let id):
            sourceObject = ["type": "app", "app": app, "id": id]
        }
        var object: [String: Any] = [
            "title": item.title,
            "source": sourceObject,
            "headers": item.headers,
            "subtitles": item.subtitles,
            "start_ms": item.startMs,
        ]
        if !item.id.isEmpty {
            object["id"] = item.id
        }
        if let site = item.site {
            object["site"] = site
        }
        return object
    }

    static func event(from text: String) -> TogetherEvent? {
        guard let data = text.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data),
              let object = raw as? [String: Any],
              let eventName = object["event"] as? String else {
            return nil
        }

        switch eventName {
        case "room":
            guard let invite = string(object, "invite"),
                  let member = string(object, "member"),
                  let boxKey = string(object, "box_pk"),
                  let signKey = string(object, "sign_pk") else { return nil }
            return .room(
                roomId: string(object, "room") ?? "",
                invite: invite,
                relay: string(object, "relay") ?? "",
                member: member,
                boxKey: boxKey,
                signKey: signKey,
                host: bool(object, "host")
            )
        case "connected":
            return .connected(members: strings(object, "members"))
        case "disconnected":
            return .disconnected
        case "member_joined":
            guard let member = string(object, "member") else { return nil }
            return .memberJoined(member: member)
        case "member_left":
            guard let member = string(object, "member") else { return nil }
            return .memberLeft(member: member)
        case "member":
            guard let member = string(object, "member") else { return nil }
            return .member(
                member: member,
                name: string(object, "name") ?? "",
                kind: TogetherDeviceKind.from(string(object, "kind")),
                host: bool(object, "host")
            )
        case "video":
            guard let itemObj = object["item"] as? [String: Any],
                  let item = parseItem(itemObj) else { return nil }
            return .video(item: item)
        case "timeline":
            guard let item = string(object, "item") else { return nil }
            return .timeline(
                item: item,
                playing: bool(object, "playing"),
                positionMs: int64(object, "position_ms"),
                rev: int64(object, "rev")
            )
        case "waiting":
            return .waiting(members: strings(object, "members"))
        case "continue_without_available":
            return .continueWithoutAvailable(members: strings(object, "members"))
        case "play_requested":
            guard let request = string(object, "request") else { return nil }
            return .playRequested(
                request: request,
                from: string(object, "from") ?? "",
                title: string(object, "title") ?? "",
                site: string(object, "site")
            )
        case "request_answered":
            guard let request = string(object, "request") else { return nil }
            return .requestAnswered(request: request, allowed: bool(object, "allowed"))
        case "request_expired":
            guard let request = string(object, "request") else { return nil }
            return .requestExpired(request: request)
        case "locked":
            return .locked(bool(object, "locked"))
        case "policy":
            return .policy(TogetherPolicy.from(string(object, "policy")))
        case "credentials":
            guard let item = string(object, "item") else { return nil }
            return .credentialsReceived(item: item)
        case "warning":
            return .warning(message: string(object, "message") ?? "")
        case "live":
            return .live(bool(object, "live"))
        case "rejected":
            return .rejected(reason: string(object, "reason") ?? "")
        case "player":
            guard let commandObj = object["command"] as? [String: Any],
                  let command = parsePlayer(commandObj) else { return nil }
            return .player(command)
        case "pairing_code":
            guard let code = string(object, "code"),
                  let qr = string(object, "qr") else { return nil }
            return .pairingCode(
                code: code,
                qr: qr,
                qrSize: Int(int64(object, "qr_size")),
                qrModules: string(object, "qr_modules") ?? "",
                expiresAtMs: int64(object, "expires_at")
            )
        case "pairing_invite":
            return .pairingInvite(
                from: string(object, "from") ?? "",
                relay: string(object, "relay") ?? ""
            )
        case "pair_sent":
            return .pairSent(id: string(object, "id"))
        case "error":
            return .error(
                context: string(object, "context") ?? "",
                message: string(object, "message") ?? "",
                id: string(object, "id")
            )
        case "stopped":
            return .stopped(
                roomId: string(object, "room") ?? "",
                reason: TogetherStop.from(string(object, "reason"))
            )
        default:
            return nil
        }
    }

    private static func parsePlayer(_ object: [String: Any]) -> TogetherPlayerCommand? {
        switch string(object, "action") {
        case "load":
            guard let itemObj = object["item"] as? [String: Any],
                  let item = parseItem(itemObj) else { return nil }
            return .load(
                item: item,
                headers: stringMap(object, "headers"),
                startMs: int64(object, "start_ms")
            )
        case "play":
            return .play
        case "pause":
            return .pause
        case "seek":
            return .seek(positionMs: int64(object, "position_ms"))
        case "rate":
            let rate = double(object, "rate") ?? 1.0
            return .rate(rate)
        case "go_live":
            return .goLive
        default:
            return nil
        }
    }

    private static func parseItem(_ object: [String: Any]) -> TogetherItem? {
        guard let id = string(object, "id"),
              let sourceObj = object["source"] as? [String: Any] else { return nil }
        let source: TogetherSource
        switch string(sourceObj, "type") {
        case "url":
            guard let url = string(sourceObj, "url") else { return nil }
            source = .url(url)
        case "page":
            guard let url = string(sourceObj, "url") else { return nil }
            source = .page(url)
        case "app":
            guard let app = string(sourceObj, "app"),
                  let appId = string(sourceObj, "id") else { return nil }
            source = .app(app: app, id: appId)
        default:
            return nil
        }
        return TogetherItem(
            id: id,
            title: string(object, "title") ?? "",
            site: string(object, "site"),
            source: source,
            headers: stringMap(object, "headers"),
            subtitles: strings(object, "subtitles"),
            startMs: int64(object, "start_ms"),
            sharedBy: string(object, "shared_by") ?? ""
        )
    }

    private static func string(_ object: [String: Any], _ key: String) -> String? {
        object[key] as? String
    }

    private static func bool(_ object: [String: Any], _ key: String) -> Bool {
        if let value = object[key] as? Bool { return value }
        return false
    }

    private static func int64(_ object: [String: Any], _ key: String) -> Int64 {
        if let number = object[key] as? NSNumber { return number.int64Value }
        return 0
    }

    private static func double(_ object: [String: Any], _ key: String) -> Double? {
        if let number = object[key] as? NSNumber { return number.doubleValue }
        return nil
    }

    private static func strings(_ object: [String: Any], _ key: String) -> [String] {
        (object[key] as? [String]) ?? []
    }

    private static func stringMap(_ object: [String: Any], _ key: String) -> [String: String] {
        guard let raw = object[key] as? [String: Any] else { return [:] }
        var result: [String: String] = [:]
        for (k, v) in raw {
            if let s = v as? String { result[k] = s }
        }
        return result
    }
}

// MARK: - Pure UI & Sync Helpers

struct PartyMemberUi: Equatable, Identifiable {
    let id: String
    let name: String
    let isHost: Bool
    let isWaiting: Bool
}

enum TogetherPartyStatus: Equatable {
    case connecting(relay: String)
    case nowPlaying(title: String)
    case waitingForHost
}

struct TogetherPartyUi: Equatable {
    let partyName: String
    let status: TogetherPartyStatus
    let members: [PartyMemberUi]
    let waitingFor: [String]
    let notice: String?
}

enum TogetherFormatting {
    static func waitingLabel(_ names: [String]) -> String {
        switch names.count {
        case 0:
            return ""
        case 1:
            return "Waiting for \(names[0])…"
        case 2:
            return "Waiting for \(names[0]) and \(names[1])…"
        default:
            return "Waiting for \(names.count) devices…"
        }
    }

    static func expiryLabel(remainingMs: Int64) -> String? {
        guard remainingMs > 0 else { return nil }
        let seconds = Int((remainingMs + 999) / 1000)
        if seconds >= 60 {
            let minutes = (seconds + 59) / 60
            return minutes == 1 ? "1 minute" : "\(minutes) minutes"
        }
        return seconds == 1 ? "1 second" : "\(seconds) seconds"
    }

    static func groupPartyCode(_ code: String) -> String {
        guard !code.isEmpty else { return "" }
        var chunks: [String] = []
        var index = code.startIndex
        while index < code.endIndex {
            let end = code.index(index, offsetBy: 3, limitedBy: code.endIndex) ?? code.endIndex
            chunks.append(String(code[index..<end]))
            index = end
        }
        return chunks.joined(separator: "-")
    }
}

/// Pure decision logic for reporting `loaded` with duration once known,
/// mirroring Android's `reportPartyLoaded(force:)` and late-length correction.
enum TogetherLoadedDecision: Equatable {
    case wait
    case report(durationMs: Int64?, reportedEarly: Bool)
}

enum TogetherLoadedPlanner {
    static let liveWaitSeconds: TimeInterval = 2.0

    /// Media is ready only once the active item's load has actually finished
    /// (`itemLoaded == true`, matching the active session generation). Before the
    /// item loads, stale `playing` or `0` duration frames must never arm the live
    /// fallback timer; once loaded, a party item held paused at duration 0 (e.g.
    /// live HLS awaiting initial `Play`) still arms the 2s live fallback.
    static func isMediaReady(
        state: String,
        durationMs: Int64,
        itemLoaded: Bool? = nil,
        generationMatches: Bool = true
    ) -> Bool {
        guard generationMatches else { return false }
        if let itemLoaded {
            guard itemLoaded else { return false }
            return durationMs > 0 || state != "buffering"
        }
        return durationMs > 0 || state == "playing"
    }

    static func decideLoaded(
        alreadyReported: Bool,
        ready: Bool,
        liveKnown: Bool?,
        durationMs: Int64,
        force: Bool
    ) -> TogetherLoadedDecision {
        guard !alreadyReported, ready else { return .wait }
        let live: Bool
        if let liveKnown {
            live = liveKnown
        } else if force {
            live = durationMs <= 0
        } else {
            return .wait
        }

        if !live, durationMs <= 0, !force {
            return .wait
        }

        let reportedEarly = (live && liveKnown == nil) || (!live && durationMs <= 0)
        let reportedDuration: Int64? = live ? nil : (durationMs > 0 ? durationMs : nil)
        return .report(durationMs: reportedDuration, reportedEarly: reportedEarly)
    }

    static func lateDurationCorrection(
        alreadyReported: Bool,
        reportedEarly: Bool,
        liveKnown: Bool?,
        durationMs: Int64
    ) -> Int64? {
        guard alreadyReported, reportedEarly, liveKnown != true, durationMs > 0 else {
            return nil
        }
        return durationMs
    }
}

/// Pure party-item matching and token-fenced attachment state so view lifecycle
/// ordering (`onAppear` vs `onDisappear`) cannot drop a party replacement or let
/// a retiring player detach the active party item.
enum TogetherSessionMatcher {
    static func isPartyItem(_ itemID: String?, loadingItem: String?, playerItem: String?) -> Bool {
        guard let itemID, !itemID.isEmpty else { return false }
        return itemID == loadingItem || itemID == playerItem
    }
}

struct TogetherPlayerAttachment: Equatable {
    private(set) var item: String?
    private(set) var token: UUID?

    mutating func attach(item: String, token: UUID) {
        self.item = item
        self.token = token
    }

    @discardableResult
    mutating func detach(token: UUID) -> Bool {
        guard self.token == token else { return false }
        item = nil
        self.token = nil
        return true
    }

    mutating func reset() {
        item = nil
        token = nil
    }
}

/// Pure Watch Together playback lifecycle helpers: reuse the active video
/// session when transitioning between party items, never auto-close failure
/// or leave the room on EOF, and keep leaving only for explicit user exits.
enum TogetherPlaybackPolicy {
    struct PlayRequestTransition: Equatable {
        let reuseSession: Bool
        let nextNonce: Int
    }

    static func shouldReusePlaybackSession(activeIsImage: Bool, incomingIsImage: Bool) -> Bool {
        !activeIsImage && !incomingIsImage
    }

    /// Computes whether a new play/load request should reuse the live player session
    /// and advances the monotonic request nonce so identical `itemID` reloads still trigger.
    static func transition(
        hasActiveRequest: Bool,
        activeIsImage: Bool,
        incomingIsImage: Bool,
        currentNonce: Int
    ) -> PlayRequestTransition {
        PlayRequestTransition(
            reuseSession: hasActiveRequest && shouldReusePlaybackSession(
                activeIsImage: activeIsImage,
                incomingIsImage: incomingIsImage
            ),
            nextNonce: currentNonce &+ 1
        )
    }

    static func shouldLeavePartyOnDismiss(
        partySessionActive: Bool,
        suppressLeave: Bool,
        currentPlayRequestIsNil: Bool
    ) -> Bool {
        partySessionActive && !suppressLeave && currentPlayRequestIsNil
    }

    static func shouldAutoCloseFailure(isWatchTogetherParty: Bool) -> Bool {
        !isWatchTogetherParty
    }

    /// `goLive` may seek to a live edge only after the active item is loaded AND
    /// has no known finite duration. Seeking a finite on-demand file to its end
    /// triggers EOF and exits/reloads playback.
    static func shouldSeekToLiveEdge(itemLoaded: Bool, durationSeconds: Double) -> Bool {
        guard itemLoaded else { return false }
        if durationSeconds.isFinite, durationSeconds > 0 { return false }
        return true
    }
}

/// Identifies the primary action that receives focus on entry and state transitions in `WatchTogetherView`.
enum TogetherStepFocusTarget: String, Hashable, CaseIterable {
    case unavailableBack
    case joinParty
    case cancelRegistering
    case cancelCode
    case joinInvite
    case leaveParty
    case endedOk

    static func target(isAvailable: Bool, stepKey: String) -> TogetherStepFocusTarget {
        guard isAvailable else { return .unavailableBack }
        switch stepKey {
        case "idle": return .joinParty
        case "registering": return .cancelRegistering
        case "code": return .cancelCode
        case "invited": return .joinInvite
        case "inParty": return .leaveParty
        case "ended": return .endedOk
        default: return .joinParty
        }
    }

    static func shouldRefocus(previousStepKey: String?, currentStepKey: String) -> Bool {
        previousStepKey != currentStepKey
    }
}
