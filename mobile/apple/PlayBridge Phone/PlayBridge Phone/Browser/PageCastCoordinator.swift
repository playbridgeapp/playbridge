import Foundation
import Combine
import CoreFoundation

protocol PageCastTransport: AnyObject {
    var destinationID: String? { get }
    var isConnected: Bool { get }
    var isAirPlay: Bool { get }
    var isExternalReceiver: Bool { get }
    var canReconnectWebsiteReceiver: Bool { get }
    var websitePlayback: TvPlaybackStatus? { get }
    var websitePlaylist: PlaylistUiState? { get }
    var websiteContext: String { get }
    func reconnectWebsiteReceiver()
    func websiteMatchesReceiver(_ id: String) -> Bool
    func sendWebsitePlaylist(_ request: PageCastRequest, allowedPrivateOrigins: Set<String>) async throws
    func sendWebsiteCommand(action: String, payload: [String: Any]) -> Bool
    func queryContext()
    func queryWebsiteState()
}

extension PageCastTransport {
    func websiteMatchesReceiver(_ id: String) -> Bool { destinationID == id }
    func queryWebsiteState() { queryContext() }
}

/// One website owns the linked queue at a time. All callbacks and state live on the main actor.
@MainActor
final class PageCastCoordinator: ObservableObject {
    enum Stage { case website, privateServers(Set<String>), device, connecting }
    struct Presentation: Identifiable {
        let id: UUID
        let origin: String
        var stage: Stage
    }
    @Published private(set) var presentation: Presentation?
    @Published private(set) var controllerName: String?
    var isLinked: Bool { controllerName != nil }
    var onError: ((String) -> Void)?

    private final class Request {
        let identity = UUID()
        weak var source: PageCastSource?
        let documentID: UUID
        let documentToken: String
        let origin: String
        let requestID: String
        let operation: String
        let payload: Any
        let sessionID: String?
        init(source: PageCastSource, message: [String: Any], origin: String) {
            self.source = source; documentID = source.pageCastDocumentID
            self.origin = origin; documentToken = message["documentToken"] as? String ?? ""
            requestID = message["requestId"] as? String ?? ""
            operation = message["operation"] as? String ?? ""
            payload = message["payload"] ?? [:]
            sessionID = message["sessionId"] as? String
        }
        func isCurrent(requireActive: Bool = false) -> Bool {
            guard let source else { return false }
            return source.pageCastDocumentID == documentID && source.pageCastOrigin == origin &&
                (!requireActive || source.pageCastCanRequest)
        }
        func deliver(_ message: [String: Any]) {
            guard isCurrent() else { return }
            var message = message
            message["documentToken"] = documentToken
            source?.deliverPageCast(message, documentID: documentID)
        }
    }
    private final class Session {
        let id = UUID().uuidString
        let owner: Request
        let receiverID: String
        var ids: [String]
        var grants: Set<String>
        let created = Date()
        var lastActivity = Date()
        var ready = false
        var awaitingPlaylist = true
        var hasSeenPlayer = false
        var endOfList = false
        var need: (id: String, count: Int, sent: Date)?
        var lastAcceptedNeed: String?
        var lastState: Data?
        var lastStateAt = Date.distantPast
        init(owner: Request, receiverID: String, items: [[String: Any]], grants: Set<String>) {
            self.owner = owner; self.receiverID = receiverID
            ids = items.compactMap { $0["id"] as? String }; self.grants = grants
        }
    }

    private weak var transport: PageCastTransport?
    private let permissions: PageCastPermissions
    private let resolveOrigins: ([[String: Any]], Set<String>, [String: Any]?) async throws -> Set<String>
    private let useTimer: Bool
    private var pending: Request?
    private var task: Task<Void, Never>?
    private var decision: CheckedContinuation<Bool, Never>?
    private var selectedReceiverID: String?
    private var active: Session?
    private var timer: AnyCancellable?
    private var permissionObserver: AnyCancellable?
    private var lastContextQuery = Date.distantPast
    private var recentRequests: [String] = []

    init(permissions: PageCastPermissions = .shared, useTimer: Bool = true,
         resolveOrigins: @escaping ([[String: Any]], Set<String>, [String: Any]?) async throws -> Set<String> = {
             try await PageCastRequest.requestedPrivateOrigins(items: $0, declared: $1, metadata: $2)
         }) {
        self.permissions = permissions; self.useTimer = useTimer; self.resolveOrigins = resolveOrigins
        permissionObserver = NotificationCenter.default.publisher(for: .pageCastPermissionsChanged)
            .receive(on: RunLoop.main).sink { [weak self] note in
                Task { @MainActor in self?.permissionsChanged(note) }
            }
    }

    func attach(_ transport: PageCastTransport) { self.transport = transport }

    func receive(_ message: [String: Any], from source: PageCastSource) {
        guard let origin = source.pageCastOrigin,
              let requestID = message["requestId"] as? String, !requestID.isEmpty, requestID.utf8.count <= 128,
              let token = message["documentToken"] as? String, !token.isEmpty, token.utf8.count <= 128 else { return }
        let request = Request(source: source, message: message, origin: origin)
        guard JSONSerialization.isValidJSONObject(message),
              let data = try? JSONSerialization.data(withJSONObject: message), data.count <= 66 * 1024 else {
            reply(request, error: "resource_limit"); return
        }
        if request.operation == "cancel" {
            if let target = (request.payload as? [String: Any])?["requestId"] as? String {
                if let pending, pending.requestID == target, sameOwner(pending, request) { cancelPending("user_cancelled") }
                if let active, active.owner.requestID == target, sameOwner(active.owner, request) { unlink(reason: "user_cancelled") }
            }
            return
        }
        if request.operation == "ping" || request.operation == "unlink" {
            guard let session = ownedSession(request) else { reply(request, error: "session_ended"); return }
            session.lastActivity = Date()
            if request.operation == "unlink" {
                reply(request); unlink(reason: "unlinked")
            } else {
                if (request.payload as? [String: Any])?["ready"] as? Bool == true { session.ready = true }
                reply(request); refresh()
            }
            return
        }
        let key = "\(source.pageCastDocumentID):\(token):\(requestID)"
        guard !recentRequests.contains(key) else { reply(request, error: "stale_request"); return }
        recentRequests.append(key)
        if recentRequests.count > 64 { recentRequests.removeFirst() }
        let opening = ["cast", "open"].contains(request.operation)
        if opening {
            guard request.isCurrent(requireActive: true) else { reply(request, error: "not_allowed"); return }
            cancelPending("superseded")
        } else {
            guard ownedSession(request) != nil else { reply(request, error: "session_ended"); return }
            guard pending == nil else { reply(request, error: "resource_limit"); return }
        }
        pending = request
        task = Task { [weak self] in
            guard let self else { return }
            do {
                if opening { try await open(request) }
                else { try await operate(request) }
                guard pending === request else { return }
                completePending()
            } catch {
                guard pending === request else { return }
                let code = (error as? PageCastError)?.code ?? (error is CancellationError ? "session_ended" : "cast_failed")
                reply(request, error: code)
                completePending()
                // Legacy cast() has no promise. Report a useful native error as well.
                if request.operation == "cast", !["not_allowed", "user_cancelled", "session_ended"].contains(code) {
                    onError?(Self.message(for: code))
                }
            }
        }
    }

    private func check(_ request: Request) throws {
        try Task.checkCancellation()
        guard pending === request, request.isCurrent(requireActive: ["cast", "open"].contains(request.operation)) else {
            throw PageCastError(code: "session_ended")
        }
    }

    private func authorize(_ request: Request, items: [[String: Any]], declared: Set<String>, metadata: [String: Any]?) async throws -> Set<String> {
        // Obtain website consent before resolving any website-supplied hostname.
        if !permissions.isApproved(request.origin) {
            guard await ask(request, stage: .website) else { throw PageCastError(code: "not_allowed") }
            try check(request)
            permissions.approve(request.origin)
        }
        let origins = try await resolveOrigins(items, declared, metadata)
        try check(request)
        let missing = origins.subtracting(permissions.privateOrigins(for: request.origin))
        if !missing.isEmpty {
            guard await ask(request, stage: .privateServers(missing)) else { throw PageCastError(code: "not_allowed") }
            try check(request)
            permissions.approvePrivateOrigins(missing, for: request.origin)
        }
        guard permissions.isApproved(request.origin), origins.isSubset(of: permissions.privateOrigins(for: request.origin)) else {
            throw PageCastError(code: "not_allowed")
        }
        return origins
    }

    private func open(_ request: Request) async throws {
        var parsed = try PageCastRequest.parse(request.payload, linked: request.operation == "open")
        for index in parsed.items.indices where parsed.items[index]["title"] == nil {
            if let title = request.source?.pageCastTitle, !title.isEmpty, title != "New Tab" {
                parsed.items[index]["title"] = String(title.prefix(4096))
            }
        }
        let grants = try await authorize(request, items: parsed.items, declared: parsed.privateOrigins, metadata: parsed.metadata)
        try await ensureReceiver(request)
        try check(request)
        guard permissions.isApproved(request.origin), grants.isSubset(of: permissions.privateOrigins(for: request.origin)) else { throw PageCastError(code: "not_allowed") }
        guard let transport, let receiverID = transport.destinationID else { throw PageCastError(code: "no_receiver") }
        // Replace an old linked authority only once this request is ready to send.
        endSession("superseded")
        let session = request.operation == "open" ? Session(owner: request, receiverID: receiverID, items: parsed.items, grants: grants) : nil
        try await transport.sendWebsitePlaylist(parsed, allowedPrivateOrigins: grants)
        try check(request)
        guard transport.destinationID == receiverID else { throw PageCastError(code: "receiver_changed") }
        if let session {
            active = session
            controllerName = Self.displayName(request.origin)
            startTimer()
            reply(request, sessionID: session.id)
        } else { reply(request) }
    }

    private func ensureReceiver(_ request: Request) async throws {
        guard let transport else { throw PageCastError(code: "no_receiver") }
        let native = !transport.isAirPlay && !transport.isExternalReceiver
        if transport.isConnected && native { return }
        selectedReceiverID = nil
        var timeout: TimeInterval = 8
        if native && transport.canReconnectWebsiteReceiver {
            selectedReceiverID = transport.destinationID
            show(request, stage: .connecting)
            transport.reconnectWebsiteReceiver()
        } else {
            guard await ask(request, stage: .device) else { throw PageCastError(code: "user_cancelled") }
            timeout = 120 // Includes entering the pairing code and approving on the TV.
        }
        try check(request)
        guard let expected = selectedReceiverID else { throw PageCastError(code: "no_receiver") }
        show(request, stage: .connecting)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try check(request)
            if transport.isConnected && !transport.isAirPlay && !transport.isExternalReceiver && transport.websiteMatchesReceiver(expected) { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw PageCastError(code: "connect_failed")
    }

    func chooseReceiver(id: String, connect: () -> Void) {
        guard pending != nil, case .device = presentation?.stage else { return }
        selectedReceiverID = id
        connect()
        resolvePrompt(true)
    }
    func resolvePrompt(_ allowed: Bool) {
        let continuation = decision; decision = nil
        continuation?.resume(returning: allowed)
    }
    func dismissPresentation() { cancelPending("user_cancelled") }
    private func ask(_ request: Request, stage: Stage) async -> Bool {
        guard pending === request, request.isCurrent() else { return false }
        show(request, stage: stage)
        return await withCheckedContinuation { decision = $0 }
    }
    private func show(_ request: Request, stage: Stage) {
        presentation = Presentation(id: request.identity, origin: request.origin, stage: stage)
    }

    private func operate(_ request: Request) async throws {
        guard let session = ownedSession(request), let transport else { throw PageCastError(code: "session_ended") }
        guard transport.isConnected else { throw PageCastError(code: "connect_failed") }
        session.lastActivity = Date()
        let payload = request.payload as? [String: Any] ?? [:]
        if request.operation == "jump" {
            guard let index = Self.integer(payload["index"]), session.ids.indices.contains(index) else { throw PageCastError(code: "invalid_request") }
            guard transport.sendWebsiteCommand(action: "playlist_jump", payload: ["index": index]) else { throw PageCastError(code: "connect_failed") }
            session.need = nil
            reply(request); return
        }
        guard ["replace", "append", "supply"].contains(request.operation) else { throw PageCastError(code: "invalid_request") }
        var suppliedID: String?
        var endOfList = false
        if request.operation == "supply" {
            guard let id = payload["requestId"] as? String else { throw PageCastError(code: "invalid_request") }
            if id == session.lastAcceptedNeed { reply(request); return }
            guard id == session.need?.id else { throw PageCastError(code: "stale_request") }
            suppliedID = id
            if let value = payload["endOfList"] {
                guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw PageCastError(code: "invalid_request") }
                endOfList = number.boolValue
            }
        }
        let items = try PageCastRequest.parseItems(payload["items"] ?? [], linked: true, allowEmpty: request.operation == "supply" && endOfList)
        // Re-parse the page shape (the normalized wire items use visualMetadata).
        let parsed: PageCastRequest?
        if request.operation == "replace" { parsed = try PageCastRequest.parse(payload, linked: true) }
        else { parsed = nil }
        let declared = try PageCastRequest.parsePrivateOrigins(payload["privateNetworkOrigins"])
        let grants = try await authorize(request, items: items, declared: declared, metadata: parsed?.metadata)
        try check(request)
        guard active === session, ownedSession(request) === session, transport.isConnected else { throw PageCastError(code: "session_ended") }
        let combined = session.grants.union(grants)
        guard combined.count <= 16 else { throw PageCastError(code: "resource_limit") }
        let ids = items.compactMap { $0["id"] as? String }
        if let parsed {
            try await transport.sendWebsitePlaylist(parsed, allowedPrivateOrigins: combined)
            try check(request)
            guard active === session else { throw PageCastError(code: "session_ended") }
            session.ids = ids; session.need = nil; session.lastAcceptedNeed = nil
            session.endOfList = false; session.awaitingPlaylist = true; session.hasSeenPlayer = false
        } else {
            guard session.ids.count + ids.count <= 200 else { throw PageCastError(code: "resource_limit") }
            guard Set(session.ids).isDisjoint(with: ids) else { throw PageCastError(code: "invalid_request") }
            if suppliedID != nil {
                guard session.need?.id == suppliedID else { throw PageCastError(code: "stale_request") }
                guard items.count <= (session.need?.count ?? 0) else { throw PageCastError(code: "invalid_request") }
            }
            for var item in items {
                item.removeValue(forKey: "id")
                item["allowedPrivateOrigins"] = combined.sorted()
                guard transport.sendWebsiteCommand(action: "queue_add", payload: ["item": item]) else {
                    endSession("queue_update_failed"); throw PageCastError(code: "connect_failed")
                }
            }
            session.ids += ids
            if !ids.isEmpty { session.awaitingPlaylist = true }
            if let suppliedID { session.lastAcceptedNeed = suppliedID; session.need = nil; session.endOfList = endOfList }
        }
        session.grants = combined
        reply(request)
    }

    func refresh(now: Date = Date()) {
        if let pending, !pending.isCurrent() { cancelPending("navigation") }
        guard let session = active, let transport else { return }
        guard session.owner.isCurrent() else { unlink(reason: "navigation"); return }
        guard !transport.isAirPlay, !transport.isExternalReceiver, transport.destinationID == session.receiverID else { unlink(reason: "receiver_changed"); return }
        guard now.timeIntervalSince(session.created) <= 7200, now.timeIntervalSince(session.lastActivity) <= 600 else { unlink(reason: "session_expired"); return }
        guard session.ready, transport.isConnected else { return }
        let playlist = transport.websitePlaylist
        if let playlist, playlist.totalCount == session.ids.count { session.awaitingPlaylist = false }
        // Ignore the previous player's context while the replacement playlist is
        // starting, but never let a pending append echo mask a subsequent Stop.
        if transport.websiteContext == "player", !session.awaitingPlaylist { session.hasSeenPlayer = true }
        if transport.websiteContext == "idle", session.hasSeenPlayer { unlink(reason: "receiver_stopped"); return }
        let index = playlist?.currentIndex ?? 0
        let count = playlist?.totalCount ?? session.ids.count
        let playback = transport.websitePlayback
        let queue: [[String: Any]] = session.ids.prefix(count).enumerated().map { offset, id in
            var item: [String: Any] = ["index": offset, "id": id]
            item["title"] = playlist?.items.first { $0.index == offset }?.title
            return item
        }
        let state: [String: Any] = ["state": playback?.state ?? "connecting", "positionMs": playback?.positionMs ?? 0,
            "durationMs": playback?.durationMs ?? 0, "title": playback?.title ?? "", "currentIndex": index, "totalCount": count, "items": queue]
        let encoded = try? JSONSerialization.data(withJSONObject: state, options: .sortedKeys)
        if encoded != session.lastState, now.timeIntervalSince(session.lastStateAt) >= 1 {
            event(session, "statechange", state); session.lastState = encoded; session.lastStateAt = now
        }
        if now.timeIntervalSince(lastContextQuery) >= 2 { lastContextQuery = now; transport.queryWebsiteState() }
        guard !session.awaitingPlaylist, !session.endOfList else { return }
        if let need = session.need {
            if now.timeIntervalSince(need.sent) >= 5 {
                session.need?.sent = now; needItems(session, count: count)
            }
        } else {
            let stored = UserDefaults.standard.object(forKey: "website_cast_prefetch") as? Int ?? 3
            let demand = max(0, min(10, max(1, stored)) - max(0, count - index - 1))
            if demand > 0 {
                session.need = (UUID().uuidString, demand, now)
                needItems(session, count: count)
            }
        }
    }

    private func needItems(_ session: Session, count: Int) {
        guard let need = session.need else { return }
        var detail: [String: Any] = ["requestId": need.id, "count": need.count, "afterIndex": max(0, count - 1)]
        if session.ids.indices.contains(count - 1) { detail["afterItemId"] = session.ids[count - 1] }
        event(session, "needitems", detail)
    }
    private func startTimer() {
        guard useTimer else { return }
        timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect().sink { [weak self] date in self?.refresh(now: date) }
    }
    func sourceInvalidated(_ source: PageCastSource) {
        if pending?.source === source { cancelPending("navigation") }
        if active?.owner.source === source { unlink(reason: "navigation") }
    }
    func userStartedCast() { cancelPending("superseded"); endSession("superseded") }
    func unlink(reason: String = "unlinked") {
        if let session = active, let pending, pending.sessionID == session.id { cancelPending("session_ended") }
        endSession(reason)
    }
    private func endSession(_ reason: String) {
        if let active { event(active, "ended", ["reason": reason]) }
        active = nil; controllerName = nil; timer = nil
    }
    private func permissionsChanged(_ notification: Notification) {
        guard notification.object as AnyObject? === permissions,
              let reason = notification.userInfo?["reason"] as? String, reason != "approve" else { return }
        let origin = notification.userInfo?["origin"] as? String
        if let pending, origin == nil || origin == pending.origin { cancelPending("not_allowed") }
        if let active, origin == nil || origin == active.owner.origin {
            unlink(reason: "permission_reset")
        }
    }
    private func sameOwner(_ lhs: Request, _ rhs: Request) -> Bool {
        lhs.source === rhs.source && lhs.documentID == rhs.documentID && lhs.documentToken == rhs.documentToken && lhs.origin == rhs.origin
    }
    private func ownedSession(_ request: Request) -> Session? {
        guard let active, active.id == request.sessionID, sameOwner(active.owner, request), request.isCurrent(),
              permissions.isApproved(request.origin), active.grants.isSubset(of: permissions.privateOrigins(for: request.origin)),
              let transport, !transport.isAirPlay, !transport.isExternalReceiver, transport.destinationID == active.receiverID else { return nil }
        return active
    }
    private func reply(_ request: Request, error: String? = nil, sessionID: String? = nil) {
        var message: [String: Any] = ["requestId": request.requestID, "ok": error == nil]
        if let error { message["error"] = error; message["message"] = Self.message(for: error) }
        if let sessionID { message["sessionId"] = sessionID }
        request.deliver(message)
    }
    private func event(_ session: Session, _ event: String, _ detail: [String: Any]) {
        session.owner.deliver(["sessionId": session.id, "event": event, "detail": detail])
    }
    private func cancelPending(_ reason: String) {
        guard let request = pending else { return }
        reply(request, error: reason)
        task?.cancel(); completePending()
    }
    private func completePending() {
        pending = nil; task = nil; presentation = nil; selectedReceiverID = nil
        let continuation = decision; decision = nil
        continuation?.resume(returning: false)
    }
    static func displayName(_ origin: String) -> String {
        guard let url = URL(string: origin), let host = url.host else { return origin }
        return host + (url.port.map { ":\($0)" } ?? "")
    }
    private static func integer(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite, number.doubleValue.rounded() == number.doubleValue else { return nil }
        return Int(exactly: number.doubleValue)
    }
    static func message(for code: String) -> String {
        switch code {
        case "not_allowed": return "Website casting permission was not granted."
        case "connect_failed", "no_receiver": return "Couldn’t connect to the receiver. Choose a device and try again."
        case "unsupported_target": return "This request needs a PlayBridge receiver."
        case "invalid_request", "resource_limit": return "The website sent an unsupported cast request."
        case "receiver_changed": return "The selected device changed. Start the cast again."
        case "network_unavailable": return "Couldn’t reach the website’s media server. Check your connection and try again."
        case "private_network_denied": return "The website requested a local address that cannot be used for casting."
        case "session_ended", "navigation", "superseded": return "This website’s cast session has ended."
        case "stale_request": return "This playlist request is no longer current."
        default: return "Couldn’t start the website cast. Try again."
        }
    }
}
