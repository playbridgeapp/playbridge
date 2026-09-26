import Foundation
import Combine
import CoreFoundation

private final class FakePage: PageCastSource {
    var pageCastDocumentID = UUID()
    var pageCastOrigin: String? = "https://site.example"
    var pageCastTitle = "Website title"
    var pageCastCanRequest = true
    var messages: [[String: Any]] = []
    func deliverPageCast(_ message: [String: Any], documentID: UUID) {
        precondition(documentID == pageCastDocumentID, "Must never deliver into another document")
        messages.append(message)
    }
    func reply(_ id: String) -> [String: Any]? { messages.last { $0["requestId"] as? String == id } }
    func events(_ name: String) -> [[String: Any]] { messages.filter { $0["event"] as? String == name } }
}

private final class FakeTransport: PageCastTransport {
    var destinationID: String? = "receiver-one"
    var isConnected = true
    var isAirPlay = false
    var isExternalReceiver = false
    var canReconnectWebsiteReceiver = false
    var websitePlayback: TvPlaybackStatus? = TvPlaybackStatus(state: "playing", positionMs: 1000, durationMs: 60000, title: "Video")
    var websitePlaylist: PlaylistUiState?
    var websiteContext = "player"
    var sends: [(request: PageCastRequest, grants: Set<String>)] = []
    var commands: [(action: String, payload: [String: Any])] = []
    var queryCount = 0
    var reconnectCount = 0
    var acceptCommands = true
    func reconnectWebsiteReceiver() { reconnectCount += 1; isConnected = true }
    func sendWebsitePlaylist(_ request: PageCastRequest, allowedPrivateOrigins: Set<String>) async throws {
        sends.append((request, allowedPrivateOrigins))
        websitePlaylist = PlaylistUiState(currentIndex: request.startIndex, totalCount: request.items.count,
            items: request.items.enumerated().map { PlaylistEpisode(index: $0.offset, title: $0.element["title"] as? String ?? "Item") })
    }
    func sendWebsiteCommand(action: String, payload: [String: Any]) -> Bool {
        guard acceptCommands else { return false }
        commands.append((action, payload))
        if action == "queue_add", let count = websitePlaylist?.totalCount {
            websitePlaylist?.totalCount += 1
            websitePlaylist?.items.append(PlaylistEpisode(index: count, title: "Added item"))
        }
        if action == "playlist_jump", let index = payload["index"] as? Int { websitePlaylist?.currentIndex = index }
        return true
    }
    func queryContext() { queryCount += 1 }
}

@MainActor private final class ResolveGate {
    private var waiter: CheckedContinuation<Set<String>, Never>?
    var entered: Bool { waiter != nil }
    func wait() async -> Set<String> { await withCheckedContinuation { waiter = $0 } }
    func release(_ origins: Set<String> = []) { let current = waiter; waiter = nil; current?.resume(returning: origins) }
}

@MainActor private final class Fixture {
    let page = FakePage()
    let transport = FakeTransport()
    let suite = "PageCastCoordinatorTests.\(UUID().uuidString)"
    let defaults: UserDefaults
    let permissions: PageCastPermissions
    let coordinator: PageCastCoordinator
    var sequence = 0

    init(approved: Bool = false,
         resolve: @escaping ([[String: Any]], Set<String>, [String: Any]?) async throws -> Set<String> = { _, _, _ in [] }) {
        defaults = UserDefaults(suiteName: suite)!
        permissions = PageCastPermissions(defaults: defaults)
        if approved { permissions.approve("https://site.example") }
        coordinator = PageCastCoordinator(permissions: permissions, useTimer: false, resolveOrigins: resolve)
        coordinator.attach(transport)
    }
    deinit { defaults.removePersistentDomain(forName: suite) }

    nonisolated static func item(_ id: String = "one") -> [String: Any] {
        ["id": id, "url": "https://media.example/\(id).m3u8"]
    }
    @discardableResult
    func send(_ operation: String, session: String? = nil, payload: [String: Any] = [:],
              source: FakePage? = nil, token: String = "document-token", requestID: String? = nil) -> String {
        sequence += 1
        let id = requestID ?? "request-\(sequence)"
        var message: [String: Any] = ["type": "pageCastRequest", "operation": operation,
            "requestId": id, "documentToken": token, "payload": payload]
        if let session { message["sessionId"] = session }
        coordinator.receive(message, from: source ?? page)
        return id
    }
    func wait(_ condition: () -> Bool, _ description: String) async {
        for _ in 0..<2000 {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        preconditionFailure("Timed out waiting for \(description)")
    }
    func response(_ id: String, error: String? = nil, source: FakePage? = nil) async -> [String: Any] {
        let source = source ?? page
        await wait({ source.reply(id) != nil }, "response to \(id)")
        let reply = source.reply(id)!
        if let error { precondition(reply["error"] as? String == error, "Expected \(error), got \(reply)") }
        else { precondition(reply["ok"] as? Bool == true, "Unexpected failure: \(reply)") }
        return reply
    }
    func open(items: [[String: Any]] = [Fixture.item()], ready: Bool = true) async -> String {
        permissions.approve(page.pageCastOrigin!)
        let id = send("open", payload: ["items": items])
        let reply = await response(id)
        let session = reply["sessionId"] as! String
        if ready { _ = await response(send("ping", session: session, payload: ["ready": true])) }
        return session
    }
}

@main struct PageCastCoordinatorChecks {
    @MainActor static func permissionFlow() async {
        var resolutions = 0
        let f = Fixture { _, _, _ in resolutions += 1; return [] }
        let first = f.send("cast", payload: ["url": "https://media.example/video.mp4"])
        await f.wait({ f.coordinator.presentation != nil }, "website consent")
        guard case .website = f.coordinator.presentation?.stage else { preconditionFailure("Expected website permission") }
        precondition(f.transport.sends.isEmpty && resolutions == 0, "Consent must precede DNS and sending")
        f.coordinator.resolvePrompt(true)
        let reply = await f.response(first)
        precondition(reply["documentToken"] as? String == "document-token")
        precondition(f.permissions.isApproved("https://site.example"))
        precondition(f.transport.sends.count == 1)
        precondition(f.transport.sends[0].request.items[0]["title"] as? String == "Website title")
        let second = f.send("cast", payload: ["url": "https://media.example/next.mp4"])
        _ = await f.response(second)
        precondition(f.coordinator.presentation == nil && f.transport.sends.count == 2, "Remembered approval should not prompt again")

        let denied = Fixture()
        let attempt = denied.send("cast", payload: ["url": "https://media.example/video.mp4"])
        await denied.wait({ denied.coordinator.presentation != nil }, "denial prompt")
        denied.coordinator.resolvePrompt(false)
        _ = await denied.response(attempt, error: "not_allowed")
        precondition(denied.transport.sends.isEmpty && !denied.permissions.isApproved("https://site.example"))
        print("PASS website consent, remembered permissions and denial")
    }

    @MainActor static func privatePermissionsAndPayloads() async {
        let privateOrigin = "http://192.168.1.25:80"
        let f = Fixture(approved: true) { _, _, _ in [privateOrigin] }
        let item: [String: Any] = ["id": "movie", "url": "https://media.example/movie.m3u8", "title": "Movie", "contentType": "application/x-mpegURL",
            "headers": ["Origin": "https://site.example", "Authorization": "fixture-only"],
            "subtitles": ["https://media.example/legacy.vtt"],
            "subtitleResources": [["url": "http://192.168.1.25/sub.vtt", "headers": ["Accept": "text/vtt"], "language": "en", "label": "English"]],
            "metadata": ["title": "Movie", "posterUrl": "https://media.example/poster.jpg"]]
        let request = f.send("open", payload: ["items": [item], "metadata": ["title": "Collection"], "skipPreplay": true])
        await f.wait({ f.coordinator.presentation != nil }, "private server approval")
        guard case .privateServers(let origins) = f.coordinator.presentation?.stage else { preconditionFailure("Expected separate private origin approval") }
        precondition(origins == [privateOrigin] && f.transport.sends.isEmpty)
        f.coordinator.resolvePrompt(true)
        _ = await f.response(request)
        let sent = f.transport.sends[0]
        precondition(sent.grants == [privateOrigin] && sent.request.skipPreplay)
        precondition(sent.request.metadata?["title"] as? String == "Collection")
        let sentItem = sent.request.items[0]
        precondition(sentItem["headers"] as? [String: String] == item["headers"] as? [String: String])
        precondition(sentItem["subtitles"] as? [String] == ["https://media.example/legacy.vtt"])
        let resource = (sentItem["subtitleResources"] as! [[String: Any]])[0]
        precondition(resource["language"] as? String == "en" && resource["headers"] as? [String: String] == ["Accept": "text/vtt"])
        precondition((sentItem["visualMetadata"] as? [String: Any])?["posterUrl"] as? String == "https://media.example/poster.jpg")
        let oneOff = f.send("cast", payload: ["items": [item], "metadata": ["title": "Collection"]])
        _ = await f.response(oneOff)
        precondition(f.coordinator.presentation == nil && f.transport.sends.count == 2)
        precondition((f.transport.sends[1].request.items[0]["subtitleResources"] as? [[String: Any]])?.count == 1)
        print("PASS private grants and complete basic/linked payload forwarding")
    }

    @MainActor static func demandAndSupply() async {
        let f = Fixture(approved: true)
        let session = await f.open(ready: false)
        f.coordinator.refresh()
        precondition(f.page.events("needitems").isEmpty && f.page.events("statechange").isEmpty, "Native must wait for listener-ready ping")
        _ = await f.response(f.send("ping", session: session, payload: ["ready": true]))
        let demand = f.page.events("needitems").last!["detail"] as! [String: Any]
        let demandID = demand["requestId"] as! String
        precondition(demand["afterItemId"] as? String == "one")
        precondition(demand["count"] as? Int == 3)
        let supplied: [String: Any] = ["requestId": demandID, "items": [Fixture.item("two"), Fixture.item("three")], "endOfList": false]
        _ = await f.response(f.send("supply", session: session, payload: supplied))
        precondition(f.transport.commands.count == 2)
        precondition(f.transport.commands.allSatisfy { $0.action == "queue_add" })
        precondition((f.transport.commands[0].payload["item"] as? [String: Any])?["id"] == nil, "Page IDs must not leak into receiver item schema")
        _ = await f.response(f.send("supply", session: session, payload: supplied))
        precondition(f.transport.commands.count == 2, "Repeating accepted demand must be idempotent")
        _ = await f.response(f.send("supply", session: session, payload: ["requestId": "stale", "items": [Fixture.item("four")]]), error: "stale_request")
        f.coordinator.refresh(now: Date().addingTimeInterval(2))
        let next = f.page.events("needitems").last!["detail"] as! [String: Any]
        precondition(next["requestId"] as? String != demandID)
        _ = await f.response(f.send("supply", session: session, payload: ["requestId": next["requestId"]!, "items": [], "endOfList": true]))
        let count = f.page.events("needitems").count
        f.coordinator.refresh(now: Date().addingTimeInterval(8))
        precondition(f.page.events("needitems").count == count, "End of list must stop further demand")
        print("PASS readiness, demand, idempotent supply, stale demand and end of list")
    }

    @MainActor static func ownership() async {
        let f = Fixture(approved: true)
        let session = await f.open()
        let attacker = FakePage()
        attacker.pageCastDocumentID = f.page.pageCastDocumentID
        let forged = f.send("jump", session: session, payload: ["index": 0], source: attacker)
        _ = await f.response(forged, error: "session_ended", source: attacker)
        _ = await f.response(f.send("jump", session: session, payload: ["index": 0], token: "other-token"), error: "session_ended")
        let document = f.page.pageCastDocumentID
        f.page.pageCastDocumentID = UUID()
        _ = await f.response(f.send("jump", session: session, payload: ["index": 0]), error: "session_ended")
        f.page.pageCastDocumentID = document
        f.page.pageCastOrigin = "https://other.example"
        _ = await f.response(f.send("jump", session: session, payload: ["index": 0]), error: "session_ended")
        precondition(f.transport.commands.isEmpty)
        f.page.pageCastOrigin = "https://site.example"
        _ = await f.response(f.send("jump", session: session, payload: ["index": 0]))
        precondition(f.transport.commands.count == 1)
        f.page.pageCastCanRequest = false
        _ = await f.response(f.send("open", payload: ["items": [Fixture.item()]]), error: "not_allowed")
        print("PASS source, document, origin and token ownership")
    }

    @MainActor static func cancellationDuringDNS() async {
        let gate = ResolveGate()
        let f = Fixture(approved: true) { _, _, _ in await gate.wait() }
        let request = f.send("open", payload: ["items": [Fixture.item()]])
        await f.wait({ gate.entered }, "resolver suspension")
        f.send("cancel", payload: ["requestId": request], token: "wrong-document")
        precondition(f.page.reply(request) == nil, "Another document must not cancel this request")
        f.send("cancel", payload: ["requestId": request])
        _ = await f.response(request, error: "user_cancelled")
        gate.release()
        try? await Task.sleep(nanoseconds: 5_000_000)
        precondition(f.transport.sends.isEmpty && !f.coordinator.isLinked, "Cancelled DNS must not continue to casting")
        print("PASS cancellation while asynchronous resolution is in flight")
    }

    @MainActor static func navigationAndSupersession() async {
        let gate = ResolveGate()
        var calls = 0
        let f = Fixture(approved: true) { _, _, _ in
            calls += 1
            return calls == 1 ? await gate.wait() : []
        }
        let obsolete = f.send("open", payload: ["items": [Fixture.item("obsolete")]])
        await f.wait({ gate.entered }, "old request resolution")
        let current = f.send("open", payload: ["items": [Fixture.item("current")]])
        _ = await f.response(obsolete, error: "superseded")
        _ = await f.response(current)
        gate.release()
        try? await Task.sleep(nanoseconds: 5_000_000)
        precondition(f.transport.sends.count == 1)
        precondition(f.transport.sends[0].request.items[0]["id"] as? String == "current")
        f.coordinator.sourceInvalidated(f.page)
        precondition(!f.coordinator.isLinked)

        let navigating = Fixture()
        let pending = navigating.send("open", payload: ["items": [Fixture.item()]])
        await navigating.wait({ navigating.coordinator.presentation != nil }, "permission awaiting navigation")
        navigating.page.pageCastDocumentID = UUID()
        navigating.coordinator.sourceInvalidated(navigating.page)
        try? await Task.sleep(nanoseconds: 5_000_000)
        precondition(navigating.coordinator.presentation == nil && navigating.transport.sends.isEmpty)
        precondition(navigating.page.reply(pending) == nil, "A previous document's response must never reach the new page")
        print("PASS supersession and document invalidation during permission/DNS")
    }

    @MainActor static func concurrentAndDuplicateAppend() async {
        let gate = ResolveGate()
        var calls = 0
        let f = Fixture(approved: true) { _, _, _ in
            calls += 1
            return calls == 2 ? await gate.wait() : []
        }
        let session = await f.open()
        let append = f.send("append", session: session, payload: ["items": [Fixture.item("two")]])
        await f.wait({ gate.entered }, "append validation")
        _ = await f.response(f.send("append", session: session, payload: ["items": [Fixture.item("three")]]), error: "resource_limit")
        gate.release()
        _ = await f.response(append)
        _ = await f.response(f.send("append", session: session, payload: ["items": [Fixture.item("two")]]), error: "invalid_request")
        _ = await f.response(f.send("append", session: session, payload: ["items": [Fixture.item("four")]], requestID: append), error: "stale_request")
        precondition(f.transport.commands.count == 1, "Concurrent/replayed/duplicate append must never duplicate queue entries")
        print("PASS concurrent append exclusion, request replay and duplicate item rejection")
    }

    @MainActor static func revocationAndReceiverSwitch() async {
        let f = Fixture(approved: true)
        _ = await f.open()
        f.permissions.revoke("https://site.example")
        await f.wait({ !f.coordinator.isLinked }, "revocation invalidation")
        let reason = f.page.events("ended").last?["detail"] as? [String: Any]
        precondition(reason?["reason"] as? String == "permission_reset")
        _ = await f.open()
        f.transport.destinationID = "receiver-two"
        f.coordinator.refresh()
        precondition(!f.coordinator.isLinked)
        precondition((f.page.events("ended").last?["detail"] as? [String: Any])?["reason"] as? String == "receiver_changed")
        print("PASS revocation and changing receiver end linked control")
    }

    @MainActor static func stoppedAndUnlinked() async {
        let f = Fixture(approved: true)
        let session = await f.open()
        f.transport.websiteContext = "idle"
        f.coordinator.refresh()
        precondition(!f.coordinator.isLinked)
        precondition((f.page.events("ended").last?["detail"] as? [String: Any])?["reason"] as? String == "receiver_stopped")
        _ = await f.response(f.send("jump", session: session, payload: ["index": 0]), error: "session_ended")
        f.transport.websiteContext = "player"
        let second = await f.open()
        _ = await f.response(f.send("unlink", session: second))
        precondition(!f.coordinator.isLinked && f.transport.commands.isEmpty, "Unlink removes authority without stopping receiver playback")
        _ = await f.open()
        f.coordinator.userStartedCast()
        precondition(!f.coordinator.isLinked)
        let appending = Fixture(approved: true)
        let appendSession = await appending.open()
        _ = await appending.response(appending.send("append", session: appendSession, payload: ["items": [Fixture.item("next")]]))
        appending.transport.websitePlaylist = nil
        appending.transport.websiteContext = "idle"
        appending.coordinator.refresh()
        precondition(!appending.coordinator.isLinked, "A missing append echo must not mask a receiver Stop")

        let timedOut = Fixture(approved: true)
        let pendingOpen = timedOut.send("open", payload: ["items": [Fixture.item()]])
        _ = await timedOut.response(pendingOpen)
        timedOut.send("cancel", payload: ["requestId": pendingOpen])
        precondition(!timedOut.coordinator.isLinked, "Open timeout must unlink even if native already sent its reply")
        print("PASS receiver stop, explicit unlink and manual cast replacement")
    }

    @MainActor static func replacementAndTransportFailure() async {
        let f = Fixture(approved: true)
        let session = await f.open()
        let oldNeed = (f.page.events("needitems").last!["detail"] as! [String: Any])["requestId"] as! String
        _ = await f.response(f.send("replace", session: session, payload: [
            "items": [Fixture.item("new-one"), Fixture.item("new-two")], "startIndex": 1,
            "metadata": ["title": "New season"]
        ]))
        precondition(f.transport.sends.count == 2)
        precondition(f.transport.sends.last!.request.startIndex == 1)
        precondition(f.transport.sends.last!.request.metadata?["title"] as? String == "New season")
        _ = await f.response(f.send("supply", session: session, payload: ["requestId": oldNeed, "items": [Fixture.item("stale")]]), error: "stale_request")
        _ = await f.response(f.send("jump", session: session, payload: ["index": 2]), error: "invalid_request")
        f.transport.acceptCommands = false
        _ = await f.response(f.send("append", session: session, payload: ["items": [Fixture.item("failed")]]), error: "connect_failed")
        precondition(!f.coordinator.isLinked)
        precondition((f.page.events("ended").last?["detail"] as? [String: Any])?["reason"] as? String == "queue_update_failed")
        print("PASS replace invalidates old demand and failed queue update ends authority")
    }

    @MainActor static func receiverSelection() async {
        let f = Fixture(approved: true)
        f.transport.isAirPlay = true
        let basic = f.send("cast", payload: ["url": "https://media.example/movie.mp4"])
        await f.wait({ f.coordinator.presentation != nil }, "basic cast receiver picker")
        guard case .device = f.coordinator.presentation?.stage else { preconditionFailure("Website requests need a PlayBridge receiver") }
        f.coordinator.dismissPresentation()
        _ = await f.response(basic, error: "user_cancelled")
        let linked = f.send("open", payload: ["items": [Fixture.item()]])
        await f.wait({ f.coordinator.presentation != nil }, "compatible receiver picker")
        guard case .device = f.coordinator.presentation?.stage else { preconditionFailure("Linked API must choose a PlayBridge receiver") }
        f.coordinator.chooseReceiver(id: "receiver-two") {
            f.transport.destinationID = "receiver-two"
            f.transport.isAirPlay = false
            f.transport.isConnected = true
        }
        _ = await f.response(linked)
        precondition(f.coordinator.isLinked)
        f.coordinator.unlink()
        f.transport.isConnected = false
        f.transport.canReconnectWebsiteReceiver = true
        _ = await f.open()
        precondition(f.transport.reconnectCount == 1)
        print("PASS compatible receiver selection, cancellation and reconnect")
    }

    @MainActor static func main() async {
        // Demand is deterministic regardless of the developer's configured preference.
        let previous = UserDefaults.standard.object(forKey: "website_cast_prefetch")
        UserDefaults.standard.set(3, forKey: "website_cast_prefetch")
        defer {
            if let previous { UserDefaults.standard.set(previous, forKey: "website_cast_prefetch") }
            else { UserDefaults.standard.removeObject(forKey: "website_cast_prefetch") }
        }
        await permissionFlow()
        await privatePermissionsAndPayloads()
        await demandAndSupply()
        await ownership()
        await cancellationDuringDNS()
        await navigationAndSupersession()
        await concurrentAndDuplicateAppend()
        await revocationAndReceiverSwitch()
        await stoppedAndUnlinked()
        await replacementAndTransportFailure()
        await receiverSelection()
        print("Website cast coordinator behavior checks passed")
    }
}
