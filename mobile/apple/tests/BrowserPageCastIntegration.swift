import Foundation
import WebKit

private final class BrowserCastReceiver: PageCastTransport {
    var destinationID: String? = "fixture-tv"
    var isConnected = true
    var isAirPlay = false
    var isExternalReceiver = false
    var canReconnectWebsiteReceiver = false
    var websitePlayback: TvPlaybackStatus? = TvPlaybackStatus(state: "playing", positionMs: 1200, durationMs: 60000, title: "Fixture")
    var websitePlaylist: PlaylistUiState?
    var websiteContext = "player"
    var sends: [PageCastRequest] = []
    var additions = 0
    func reconnectWebsiteReceiver() {}
    func queryContext() {}
    func sendWebsitePlaylist(_ request: PageCastRequest, allowedPrivateOrigins: Set<String>) async throws {
        sends.append(request)
        websitePlaylist = PlaylistUiState(currentIndex: request.startIndex, totalCount: request.items.count, items: [])
    }
    func sendWebsiteCommand(action: String, payload: [String: Any]) -> Bool {
        if action == "queue_add" { additions += 1; websitePlaylist?.totalCount += 1 }
        return true
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyWebsiteCasting(_ tab: BrowserTab, browser: BrowserStore, base: String) async throws {
        let suite = "BrowserWebsiteCastTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let permissions = PageCastPermissions(defaults: defaults)
        let casting = PageCastCoordinator(permissions: permissions, useTimer: false, resolveOrigins: { _, _, _ in [] })
        let receiver = BrowserCastReceiver()
        casting.attach(receiver)
        var received = 0
        browser.onWebsiteCast = { source, request in
            MainActor.assumeIsolated { received += 1; casting.receive(request, from: source) }
        }
        browser.onPageCastInvalidated = { source in MainActor.assumeIsolated { casting.sourceInvalidated(source) } }
        defer {
            browser.onWebsiteCast = nil; browser.onPageCastInvalidated = nil
            casting.userStartedCast()
        }
        let view = tab.webView
        let source = view.url!
        _ = try await view.evaluateJavaScript("window.playbridge.cast({url:'https://media.example/one.mp4',headers:{Origin:'https://site.example'},subtitles:['https://media.example/en.vtt']}); void(0)")
        try await wait("website cast consent") { casting.presentation != nil }
        try check(receiver.sends.isEmpty, "Website bypassed native consent")
        casting.resolvePrompt(true)
        try await wait("website cast sent") { receiver.sends.count == 1 }
        try check(permissions.isApproved(tab.pageCastOrigin!), "Website approval not remembered")
        try check(receiver.sends[0].items[0]["subtitles"] as? [String] == ["https://media.example/en.vtt"], "Website subtitles lost")
        _ = try await view.evaluateJavaScript("window.playbridge.cast([{url:'https://media.example/two.mp4'}]); void(0)")
        try await wait("remembered array cast") { receiver.sends.count == 2 }
        try check(casting.presentation == nil, "Remembered website prompted again")

        _ = try await view.evaluateJavaScript("""
        window.pbEvents = []; window.pbError = null;
        window.playbridge.linkCast({items:[{id:'one',url:'https://media.example/one.mp4'}]}).then(function(session) {
          window.pbSession = session;
          session.addEventListener('statechange', e => window.pbEvents.push(e.type));
          session.addEventListener('ended', e => window.pbEvents.push(e.type));
          session.addEventListener('needitems', e => {
            window.pbEvents.push(e.type);
            session.provideItems(e.detail.requestId, {items:[{id:'two',url:'https://media.example/two.mp4'}],endOfList:true}).catch(e => window.pbError=e.code);
          });
        }).catch(e => window.pbError=e.code); void(0)
        """)
        try await wait("linked native send") { casting.isLinked }
        try await wait("JS needitems supply roundtrip") { receiver.additions == 1 }
        let events = try await view.evaluateJavaScript("window.pbEvents") as? [String] ?? []
        try check(events.contains("needitems") && events.contains("statechange"), "Native linked events not delivered to page")
        try check(try await view.evaluateJavaScript("window.pbError === null") as? Bool == true, "Linked page received an unexpected error")
        _ = try await view.evaluateJavaScript("history.pushState({}, '', '/parent?spa=1'); void(0)")
        casting.refresh()
        try check(casting.isLinked, "Same-document navigation broke the linked session")

        let beforeIframe = received
        _ = try await view.evaluateJavaScript("var f=document.createElement('iframe'); f.srcdoc='<script>window.webkit.messageHandlers.playbridge.postMessage({type:\"pageCastRequest\",operation:\"cast\",requestId:\"iframe\",documentToken:\"iframe\",payload:{url:\"https://media.example/video.mp4\"}})<\\/script>'; document.body.appendChild(f); void(0)")
        try await Task.sleep(nanoseconds: 200_000_000)
        try check(received == beforeIframe, "Iframe initiated website casting")

        permissions.revoke(tab.pageCastOrigin!)
        try await wait("permission revoke unlinks") { !casting.isLinked }
        try await Task.sleep(nanoseconds: 100_000_000)
        let ended = try await view.evaluateJavaScript("window.pbEvents.includes('ended')") as? Bool
        try check(ended == true, "Permission revoke was not reported to the website")

        tab.requestPageCast(["url": "https://media.example/next.mp4"], source: source)
        try await wait("new consent after revoke") { casting.presentation != nil }
        browser.select(browser.tabs.first { $0.id != tab.id }!.id)
        casting.resolvePrompt(true)
        try await wait("inactive tab rejected") { casting.presentation == nil }
        try check(!permissions.isApproved(tab.pageCastOrigin!), "Inactive page obtained approval")
        let before = received
        tab.requestPageCast(["url": "https://media.example/next.mp4"], source: source)
        try check(received == before, "Background page requested casting")
        browser.select(tab.id)
        tab.requestPageCast(["url": "https://media.example/next.mp4"], source: URL(string: "https://wrong.test")!)
        try check(received == before, "Unverified origin reached coordinator")
        tab.load(base + "/parent")
        try await wait("restore page after casting checks") { !view.isLoading && view.url?.path == "/parent" }
        print("CHECK: website permissions, WebKit API, lazy queue, revocation and frame isolation passed")
    }
}
