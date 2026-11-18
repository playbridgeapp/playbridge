import Foundation
import Combine
import CoreFoundation

protocol PageCastTransport: AnyObject {
    var playbackDestination: [String: Any] { get }
    @MainActor var websiteLocalState: [String: Any]? { get }
    @MainActor func startWebsiteExternalPlayback(_ request: PageCastRequest) async throws
    @MainActor func selectWebsiteLocalDestination()
    @MainActor func startWebsiteLocalPlayback(_ request: PageCastRequest, event: @escaping (String, [String: Any]) -> Void) async throws
    @MainActor func commitWebsiteLocalPlayback()
    @MainActor func cancelWebsiteLocalPreparation()
    @MainActor func appendWebsiteLocalItems(_ items: [[String: Any]], endOfList: Bool)
    @MainActor func jumpWebsiteLocalItem(_ index: Int) async throws
    @MainActor func detachWebsiteLocalPlayback()
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
    /// The watch party is the selected target.
    @MainActor var websitePartySelected: Bool { get }
    /// Plays the request's items in the party from its start index: the first plays, the rest go to Up next.
    @MainActor func sendWebsiteToParty(_ request: PageCastRequest) -> Bool
    /// Starts a linked session in the party with the request's items. Throws `PageCastError`.
    @MainActor func startWebsitePartyLink(_ request: PageCastRequest) throws -> PageCastPartyLink
    /// Tells cast history which page the next cast comes from.
    func noteCastOrigin(_ origin: CastOrigin)
}

extension PageCastTransport {
    @MainActor var websitePartySelected: Bool { false }
    @MainActor func sendWebsiteToParty(_ request: PageCastRequest) -> Bool { false }
    @MainActor func startWebsitePartyLink(_ request: PageCastRequest) throws -> PageCastPartyLink {
        throw PageCastError(code: "party_unsupported")
    }
    func noteCastOrigin(_ origin: CastOrigin) {}
    var playbackDestination: [String: Any] { ["id": destinationID ?? "this-device", "name": destinationID ?? "This device", "kind": destinationID == nil ? "local" : "native", "connected": destinationID == nil || isConnected] }
    var websiteLocalState: [String: Any]? { nil }
    @MainActor func startWebsiteExternalPlayback(_ request: PageCastRequest) async throws { throw PageCastError(code: "unsupported_target") }
    @MainActor func selectWebsiteLocalDestination() {}
    @MainActor func startWebsiteLocalPlayback(_ request: PageCastRequest, event: @escaping (String, [String: Any]) -> Void) async throws { throw PageCastError(code: "unsupported_target") }
    @MainActor func commitWebsiteLocalPlayback() {}
    @MainActor func cancelWebsiteLocalPreparation() {}
    @MainActor func appendWebsiteLocalItems(_ items: [[String: Any]], endOfList: Bool) {}
    @MainActor func jumpWebsiteLocalItem(_ index: Int) async throws { throw PageCastError(code: "unsupported_target") }
    @MainActor func detachWebsiteLocalPlayback() {}
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
    /// A website controls playback on a receiver (not on this phone or in the party).
    var isLinkedToReceiver: Bool { isLinked && active.map { !$0.local && $0.party == nil } == true }
    var onError: ((String) -> Void)?
    var onChooseDestination: (() -> Void)?

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
        let local: Bool
        var external = false
        var playbackID: String?
        var externalState: [String: Any]?
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
        /// The website's session runs in the watch party.
        var party: PageCastPartyLink?
        /// Tied to the destination ID (this phone, an external device or the party), not a TV link.
        var byDestination: Bool { local || external || party != nil }
        init(owner: Request, receiverID: String, items: [[String: Any]], grants: Set<String>, local: Bool = false) {
            self.local = local; self.owner = owner; self.receiverID = receiverID
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

    /// A website asked for this cast; its page is the tab's own address, never one it supplied.
    private static func castOrigin(_ request: Request) -> CastOrigin {
        CastOrigin(pageURL: request.source?.pageCastPageURL, pageInitiated: true,
                   bridgedApp: request.source?.pageCastIsBridgedApp ?? false)
    }

    func receive(_ message: [String: Any], from source: PageCastSource) {
        guard let origin = source.pageCastOrigin,
              let requestID = message["requestId"] as? String, !requestID.isEmpty, requestID.utf8.count <= 128,
              let token = message["documentToken"] as? String, !token.isEmpty, token.utf8.count <= 128 else { return }
        let request = Request(source: source, message: message, origin: origin)
        guard !BrowserCastingPolicy.blocks(URL(string: origin)) else {
            reply(request, error: "not_allowed")
            return
        }
        guard JSONSerialization.isValidJSONObject(message),
              let data = try? JSONSerialization.data(withJSONObject: message), data.count <= 66 * 1024 else {
            reply(request, error: "resource_limit"); return
        }
        do {
            try PageCastRequest.rejectSenderOnlyFields(message)
            try PageCastRequest.rejectSenderOnlyFields(request.payload)
        } catch {
            reply(request, error: "invalid_request"); return
        }
        if request.operation == "destination" {
            guard request.isCurrent(requireActive: true), transport != nil else { reply(request, error: "not_allowed"); return }
            request.deliver(["requestId": request.requestID, "ok": true, "destination": websiteDestination(for: request.origin)])
            return
        }
        if request.operation == "choose_destination" {
            guard request.isCurrent(requireActive: true), transport != nil else { reply(request, error: "not_allowed"); return }
            // Set only by the isolated broker from navigator.userActivation (nil where
            // WebKit lacks it). Page scripts cannot reach that handler or this field.
            let activation = message["userActivation"] as? Bool
            if let destination = (request.payload as? [String: Any])?["destinationId"] {
                guard destination as? String == "this-device" else { reply(request, error: "invalid_request"); return }
                guard activation == true else { reply(request, error: "user_gesture_required"); return }
            } else {
                guard activation != false else { reply(request, error: "user_gesture_required"); return }
            }
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
        let opening = ["cast", "open", "play", "choose_destination"].contains(request.operation)
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
                if request.operation == "choose_destination" { try await chooseDestination(request) }
                else if opening { try await open(request) }
                else { try await operate(request) }
                guard pending === request else { return }
                completePending()
            } catch {
                guard pending === request else { return }
                let code = (error as? PageCastError)?.code ?? (error is CancellationError ? "session_ended" : "cast_failed")
                if request.operation == "play" { transport?.cancelWebsiteLocalPreparation() }
                reply(request, error: code)
                completePending()
                // Legacy cast() has no promise. Report a useful native error as well.
                if code == "party_unsupported" || (request.operation == "cast"
                    && !["not_allowed", "user_cancelled", "session_ended", "party_refused"].contains(code)) {
                    onError?(Self.message(for: code))
                }
            }
        }
    }

    private func check(_ request: Request) throws {
        try Task.checkCancellation()
        guard pending === request, request.isCurrent(requireActive: ["cast", "open", "play", "choose_destination"].contains(request.operation)) else {
            throw PageCastError(code: "session_ended")
        }
    }

    private func authorizeWebsite(_ request: Request) async throws {
        if !permissions.isApproved(request.origin) {
            let allowed = await ask(request, stage: .website)
            try check(request)
            guard allowed else { throw PageCastError(code: "not_allowed") }
            permissions.approve(request.origin)
        }
    }

    private func chooseDestination(_ request: Request) async throws {
        try check(request)
        try await authorizeWebsite(request)
        try check(request)
        guard let transport else { throw PageCastError(code: "session_ended") }
        if (request.payload as? [String: Any])?["destinationId"] as? String == "this-device" {
            // A website never tears down a live receiver: the user confirms in the native picker.
            if transport.isConnected && transport.destinationID != nil { onChooseDestination?() }
            else { transport.selectWebsiteLocalDestination() }
        } else {
            onChooseDestination?()
        }
        try check(request)
        request.deliver(["requestId": request.requestID, "ok": true, "destination": websiteDestination(for: request.origin)])
    }

    private func authorize(_ request: Request, items: [[String: Any]], declared: Set<String>, metadata: [String: Any]?) async throws -> Set<String> {
        // Obtain website consent before resolving any website-supplied hostname.
        try await authorizeWebsite(request)
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
        var parsed = try PageCastRequest.parse(request.payload, linked: request.operation != "cast")
        for index in parsed.items.indices where parsed.items[index]["title"] == nil {
            if let title = request.source?.pageCastTitle, !title.isEmpty, title != "New Tab" {
                parsed.items[index]["title"] = String(title.prefix(4096))
            }
        }
        if let transport, transport.websitePartySelected {
            guard request.operation == "cast" else {
                try await openInParty(request, parsed: parsed, transport: transport)
                return
            }
            _ = try await authorize(request, items: parsed.items, declared: parsed.privateOrigins, metadata: parsed.metadata)
            try check(request)
            endSession("superseded")
            // The party explains any link it refuses.
            guard transport.sendWebsiteToParty(parsed) else { throw PageCastError(code: "party_refused") }
            reply(request)
            return
        }
        let expected: String?
        if request.operation == "play" {
            guard let id = (request.payload as? [String: Any])?["destinationId"] as? String,
                  !id.isEmpty, id.utf16.count <= 256 else { throw PageCastError(code: "invalid_request") }
            expected = id
            try checkDestination(id, origin: request.origin)
        } else { expected = nil }
        let grants = try await authorize(request, items: parsed.items, declared: parsed.privateOrigins, metadata: parsed.metadata)
        if let expected { try checkDestination(expected, origin: request.origin) }
        // This phone and non-PlayBridge devices fetch through the phone's proxy, limited to these grants.
        let phoneFetched = parsed.withAllowedPrivateOrigins(grants)
        if request.operation == "play", transport?.playbackDestination["kind"] as? String == "local", let transport {
            endSession("superseded")
            let session = Session(owner: request, receiverID: "this-device", items: parsed.items, grants: grants, local: true)
            try await transport.startWebsiteLocalPlayback(phoneFetched) { [weak self, weak session] name, detail in
                guard let self, let session, self.active === session, session.ready else { return }
                var detail = detail
                detail["items"] = session.ids.enumerated().map { ["index": $0.offset, "id": $0.element] }
                self.event(session, name, detail)
            }
            try check(request)
            try checkDestination("this-device", origin: request.origin)
            guard permissions.isApproved(request.origin), grants.isSubset(of: permissions.privateOrigins(for: request.origin)) else { throw PageCastError(code: "not_allowed") }
            active = session
            transport.commitWebsiteLocalPlayback()
            controllerName = Self.displayName(request.origin)
            session.awaitingPlaylist = false
            startTimer(); reply(request, sessionID: session.id)
            return
        }
        if request.operation == "play", transport?.playbackDestination["kind"] as? String == "external", let transport, let expected {
            endSession("superseded")
            // Keep the raw route id. The page's HMAC must not become the session's receiver identity.
            guard let rawId = transport.playbackDestination["id"] as? String else { throw PageCastError(code: "receiver_changed") }
            let session = Session(owner: request, receiverID: rawId, items: parsed.items, grants: grants)
            session.external = true
            transport.noteCastOrigin(Self.castOrigin(request))
            try await transport.startWebsiteExternalPlayback(phoneFetched)
            try check(request); try checkDestination(expected, origin: request.origin)
            session.playbackID = transport.websitePlayback?.playbackId
            active = session; controllerName = Self.displayName(request.origin)
            startTimer(); reply(request, sessionID: session.id)
            return
        }
        if request.operation != "play" { try await ensureReceiver(request) }
        try check(request)
        guard permissions.isApproved(request.origin), grants.isSubset(of: permissions.privateOrigins(for: request.origin)) else { throw PageCastError(code: "not_allowed") }
        guard let transport, let receiverID = transport.destinationID else { throw PageCastError(code: "no_receiver") }
        // Replace an old linked authority only once this request is ready to send.
        endSession("superseded")
        let session = request.operation != "cast" ? Session(owner: request, receiverID: receiverID, items: parsed.items, grants: grants) : nil
        transport.noteCastOrigin(Self.castOrigin(request))
        try await transport.sendWebsitePlaylist(parsed, allowedPrivateOrigins: grants)
        try check(request)
        if let expected { try checkDestination(expected, origin: request.origin) }
        guard transport.destinationID == receiverID else { throw PageCastError(code: "receiver_changed") }
        if let session {
            active = session
            controllerName = Self.displayName(request.origin)
            startTimer()
            reply(request, sessionID: session.id)
        } else { reply(request) }
    }

    /// A linked session in the watch party: the first video plays, the rest go to Up next,
    /// and the website hears the party's state and supplies more videos as they're needed.
    private func openInParty(_ request: Request, parsed: PageCastRequest, transport: PageCastTransport) async throws {
        let expected: String?
        if request.operation == "play" {
            guard let id = (request.payload as? [String: Any])?["destinationId"] as? String,
                  !id.isEmpty, id.utf16.count <= 256 else { throw PageCastError(code: "invalid_request") }
            expected = id
            try checkDestination(id, origin: request.origin)
        } else { expected = nil }
        let grants = try await authorize(request, items: parsed.items, declared: parsed.privateOrigins, metadata: parsed.metadata)
        try check(request)
        if let expected { try checkDestination(expected, origin: request.origin) }
        guard transport.websitePartySelected, let rawId = transport.playbackDestination["id"] as? String else {
            throw PageCastError(code: "receiver_changed")
        }
        endSession("superseded")
        let link = try transport.startWebsitePartyLink(parsed)
        let session = Session(owner: request, receiverID: rawId, items: [], grants: grants)
        session.party = link
        session.awaitingPlaylist = false
        link.onChange = { [weak self, weak session] evaluateDemand in
            guard let self, let session else { return }
            self.partyChanged(session, evaluateDemand: evaluateDemand)
        }
        link.onFinish = { [weak self, weak session] reason in
            guard let self, let session, self.active === session else { return }
            self.unlink(reason: reason)
        }
        active = session
        controllerName = Self.displayName(request.origin)
        startTimer()
        reply(request, sessionID: session.id)
    }

    private func operateParty(_ request: Request, session: Session, link: PageCastPartyLink) async throws {
        session.lastActivity = Date()
        let payload = request.payload as? [String: Any] ?? [:]
        func refuse(_ failure: PageCastPartyFailure) throws -> Never {
            if let reason = failure.endSession { endSession(reason) }
            throw PageCastError(code: failure.code)
        }
        if request.operation == "jump" {
            guard let index = Self.integer(payload["index"]) else { throw PageCastError(code: "invalid_request") }
            if let failure = link.jump(index) { try refuse(failure) }
            session.need = nil
            reply(request)
            partyChanged(session, evaluateDemand: true)
            return
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
        let parsed = request.operation == "replace" ? try PageCastRequest.parse(payload, linked: true) : nil
        let declared = try PageCastRequest.parsePrivateOrigins(payload["privateNetworkOrigins"])
        let grants = try await authorize(request, items: items, declared: declared, metadata: parsed?.metadata)
        try check(request)
        guard active === session, ownedSession(request) === session else { throw PageCastError(code: "session_ended") }
        let combined = session.grants.union(grants)
        guard combined.count <= 16 else { throw PageCastError(code: "resource_limit") }
        let ids = items.compactMap { $0["id"] as? String }
        if let parsed {
            if let failure = link.replace(parsed.items, startIndex: parsed.startIndex) { try refuse(failure) }
            session.need = nil; session.lastAcceptedNeed = nil; session.endOfList = false
        } else {
            guard link.knownIds.count + ids.count <= 200 else { throw PageCastError(code: "resource_limit") }
            guard link.knownIds.isDisjoint(with: ids) else { throw PageCastError(code: "invalid_request") }
            if let suppliedID {
                guard let need = session.need, need.id == suppliedID else { throw PageCastError(code: "stale_request") }
                guard items.count <= need.count else { throw PageCastError(code: "invalid_request") }
                if !items.isEmpty, let failure = link.supply(items) {
                    if failure.clearNeed {
                        session.need = nil; session.lastAcceptedNeed = suppliedID
                        if endOfList { session.endOfList = true }
                    }
                    try refuse(failure)
                }
                session.need = nil; session.lastAcceptedNeed = suppliedID; session.endOfList = endOfList
            } else if let failure = link.append(items) {
                try refuse(failure)
            }
        }
        session.grants = combined
        reply(request)
        partyChanged(session, evaluateDemand: request.operation != "append")
    }

    private func partyChanged(_ session: Session, evaluateDemand: Bool, now: Date = Date()) {
        guard active === session, session.ready, let link = session.party else { return }
        let snapshot = link.snapshot()
        emitPartyState(session, snapshot, now: now, heartbeat: false)
        if evaluateDemand { requestPartyItems(session, link: link, snapshot: snapshot, now: now) }
    }

    /// Sends `statechange` when the list or state changed, and otherwise at most once a
    /// second (only while playing, for the heartbeat).
    private func emitPartyState(_ session: Session, _ snapshot: PageCastPartySnapshot, now: Date, heartbeat: Bool) {
        let encoded = try? JSONSerialization.data(withJSONObject: snapshot.structure, options: .sortedKeys)
        if encoded == session.lastState {
            guard now.timeIntervalSince(session.lastStateAt) >= 1, !heartbeat || snapshot.state == "playing" else { return }
        }
        session.lastState = encoded
        session.lastStateAt = now
        event(session, "statechange", snapshot.detail)
    }

    private func requestPartyItems(_ session: Session, link: PageCastPartyLink, snapshot: PageCastPartySnapshot, now: Date) {
        guard !link.isDemandSuppressed, !session.endOfList else { return }
        if let need = session.need {
            guard now.timeIntervalSince(need.sent) >= 5 else { return }
            session.need?.sent = now
        } else {
            let stored = UserDefaults.standard.object(forKey: "website_cast_prefetch") as? Int ?? 3
            let demand = max(0, min(10, max(1, stored)) - max(0, snapshot.totalCount - snapshot.currentIndex - 1))
            guard demand > 0 else { return }
            session.need = (UUID().uuidString, demand, now)
        }
        guard let need = session.need else { return }
        var detail: [String: Any] = ["requestId": need.id, "count": need.count, "afterIndex": max(0, snapshot.totalCount - 1)]
        if let last = snapshot.items.last { detail["afterItemId"] = last.id }
        event(session, "needitems", detail)
    }

    /// Identity the page may display. Consent-gated and per-origin; internal route ids stay on the transport.
    func websiteDestination(for origin: String) -> [String: Any] {
        let raw = transport?.playbackDestination ?? ["id": PageDestinationPrivacy.unavailableId, "name": "TV", "kind": "native", "connected": false]
        return PageDestinationPrivacy.project(raw, origin: origin, approved: permissions.isApproved(origin), secret: permissions.installSecret())
    }

    private func checkDestination(_ id: String, origin: String) throws {
        guard let rawId = transport?.playbackDestination["id"] as? String,
              PageDestinationPrivacy.matches(pageId: id, rawId: rawId, origin: origin, secret: permissions.installSecret()) else {
            throw PageCastError(code: "receiver_changed")
        }
        guard transport?.playbackDestination["connected"] as? Bool == true else { throw PageCastError(code: "connect_failed") }
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
    func dismissPresentation() {
        if pending?.operation == "choose_destination", case .website = presentation?.stage { resolvePrompt(false) }
        else { cancelPending("user_cancelled") }
    }
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
        if let link = session.party {
            try await operateParty(request, session: session, link: link)
            return
        }
        guard !session.external else { throw PageCastError(code: "unsupported_target") }
        guard session.local || transport.isConnected else { throw PageCastError(code: "connect_failed") }
        session.lastActivity = Date()
        let payload = request.payload as? [String: Any] ?? [:]
        if request.operation == "jump" {
            guard let index = Self.integer(payload["index"]), session.ids.indices.contains(index) else { throw PageCastError(code: "invalid_request") }
            if session.local { try await transport.jumpWebsiteLocalItem(index) }
            else if !transport.sendWebsiteCommand(action: "playlist_jump", payload: ["index": index]) { throw PageCastError(code: "connect_failed") }
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
        guard active === session, ownedSession(request) === session, session.local || transport.isConnected else { throw PageCastError(code: "session_ended") }
        let combined = session.grants.union(grants)
        guard combined.count <= 16 else { throw PageCastError(code: "resource_limit") }
        let ids = items.compactMap { $0["id"] as? String }
        if let parsed {
            if session.local { throw PageCastError(code: "unsupported_target") }
            else {
                transport.noteCastOrigin(Self.castOrigin(request))
                try await transport.sendWebsitePlaylist(parsed, allowedPrivateOrigins: combined)
            }
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
            if session.local {
                transport.appendWebsiteLocalItems(items.map { var item = $0; item["allowedPrivateOrigins"] = combined.sorted(); return item },
                                                  endOfList: endOfList)
            }
            for var item in session.local ? [] : items {
                item.removeValue(forKey: "id")
                item["allowedPrivateOrigins"] = combined.sorted()
                guard transport.sendWebsiteCommand(action: "queue_add", payload: ["item": item]) else {
                    endSession("queue_update_failed"); throw PageCastError(code: "connect_failed")
                }
            }
            session.ids += ids
            if !ids.isEmpty && !session.local { session.awaitingPlaylist = true }
            if let suppliedID { session.lastAcceptedNeed = suppliedID; session.need = nil; session.endOfList = endOfList }
        }
        session.grants = combined
        reply(request)
    }

    func refresh(now: Date = Date()) {
        if let pending, !pending.isCurrent() { cancelPending("navigation") }
        guard let session = active, let transport else { return }
        guard session.owner.isCurrent() else { unlink(reason: "navigation"); return }
        if let link = session.party {
            if let failure = link.validateAvailable() { unlink(reason: failure.endSession ?? failure.code); return }
            guard transport.playbackDestination["id"] as? String == session.receiverID else { unlink(reason: "receiver_changed"); return }
            guard now.timeIntervalSince(session.created) <= 7200, now.timeIntervalSince(session.lastActivity) <= 600 else { unlink(reason: "session_expired"); return }
            guard session.ready else { return }
            let snapshot = link.snapshot()
            emitPartyState(session, snapshot, now: now, heartbeat: true)
            requestPartyItems(session, link: link, snapshot: snapshot, now: now)
            return
        }
        guard (session.local || session.external) ? transport.playbackDestination["id"] as? String == session.receiverID : (!transport.isAirPlay && !transport.isExternalReceiver && transport.destinationID == session.receiverID) else { unlink(reason: "receiver_changed"); return }
        guard now.timeIntervalSince(session.created) <= 7200, now.timeIntervalSince(session.lastActivity) <= 600 else { unlink(reason: "session_expired"); return }
        if session.external && !transport.isConnected { unlink(reason: "receiver_disconnected"); return }
        guard session.ready, session.local || transport.isConnected else { return }
        if session.external {
            guard transport.isConnected else { unlink(reason: "receiver_disconnected"); return }
            guard let playback = transport.websitePlayback else {
                if var state = session.externalState, transport.websiteContext == "idle" {
                    state["state"] = "stopped"; event(session, "statechange", state); unlink(reason: "receiver_stopped")
                }
                return
            }
            guard playback.playbackId == session.playbackID else { unlink(reason: "receiver_media_changed"); return }
            let state: [String: Any] = ["state": playback.state, "positionMs": playback.positionMs,
                "durationMs": playback.durationMs, "currentIndex": 0, "totalCount": 1,
                "items": [["index": 0, "id": session.ids[0]]]]
            session.externalState = state; event(session, "statechange", state)
            if ["stopped", "error"].contains(playback.state) { unlink(reason: "receiver_stopped") }
            return
        }
        if session.local {
            guard var state = transport.websiteLocalState else { unlink(reason: "player_closed"); return }
            let index = state["currentIndex"] as? Int ?? 0
            state["items"] = session.ids.enumerated().map { ["index": $0.offset, "id": $0.element] }
            state["totalCount"] = session.ids.count
            event(session, "statechange", state)
            if state["closed"] as? Bool == true { unlink(reason: "player_closed"); return }
            if state["finished"] as? Bool == true { unlink(reason: "queue_finished"); return }
            if let need = session.need, now.timeIntervalSince(need.sent) >= 5 {
                session.need?.sent = now; needItems(session, count: session.ids.count)
            } else if !session.endOfList && session.need == nil && session.ids.count - index <= 2 {
                session.need = (UUID().uuidString, 1, now); needItems(session, count: session.ids.count)
            }
            return
        }
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
        timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect().sink { [weak self] date in self?.refresh(now: date) }
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
        if let active {
            if active.local {
                if var state = transport?.websiteLocalState {
                    state["items"] = active.ids.enumerated().map { ["index": $0.offset, "id": $0.element] }
                    event(active, "statechange", state)
                }
                transport?.detachWebsiteLocalPlayback()
            }
            active.party?.close()
            event(active, "ended", ["reason": reason])
        }
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
              let transport, active.byDestination ? transport.playbackDestination["id"] as? String == active.receiverID : (!transport.isAirPlay && !transport.isExternalReceiver && transport.destinationID == active.receiverID) else { return nil }
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
        reply(request, error: request.operation == "choose_destination" && reason != "not_allowed" ? "session_ended" : reason)
        task?.cancel()
        if request.operation == "play" { transport?.cancelWebsiteLocalPreparation() }
        completePending()
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
        case "unsupported_target": return "This device does not support the requested playback or queue action."
        case "unsupported_subtitles": return "This receiver does not support external subtitles. Choose another playback device."
        case "invalid_request", "resource_limit": return "The website sent an unsupported cast request."
        case "receiver_changed": return "The selected device changed. Start the cast again."
        case "network_unavailable": return "Couldn’t reach the website’s media server. Check your connection and try again."
        case "private_network_denied": return "The website requested a local address that cannot be used for casting."
        case "session_ended", "navigation", "superseded": return "This website’s cast session has ended."
        case "stale_request": return "This playlist request is no longer current."
        case "unsupported_queue": return "Only people who can change the party's video can add to its Up next."
        case "receiver_stopped": return "The watch party ended."
        case "party_unsupported": return "This site can't control a watch party yet. Pick a TV or This phone in Play on to cast from it."
        default: return "Couldn’t start the website cast. Try again."
        }
    }
}
