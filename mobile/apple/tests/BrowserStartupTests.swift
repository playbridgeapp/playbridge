import UIKit
import SwiftUI
import WebKit
import Combine

// Keep unrelated networking, ad-block downloads and detector enrichment out of
// this fixture; BrowserStore/BrowserTab and WKWebView are production code.
final class VideoDetector: ObservableObject {
    func clear() {}
    func ingest(_ body: [String: Any]) {}
    func beginMediaLifecycle() {}
}
enum DetectionScript { static let source = "" }
enum ContentBlocker {
    static var isEnabled = true
    static var navigationRules = NavigationAdRules()
    static func shouldBlockNavigation(_ url: URL, source: URL?, popup: Bool) -> Bool {
        isEnabled && navigationRules.decision(url: url, source: source, popup: popup) == true
    }
    static let elementPickerJS = try! String(contentsOf: Bundle.main.url(forResource: "element-picker", withExtension: "js")!, encoding: .utf8)
    static func compileAll() async -> [WKContentRuleList] { [] }
    static func ensureListsDownloaded() async {}
    static func isUserDomainRuleList(_ list: WKContentRuleList) -> Bool { false }
    static func addUserRule(domain: String, selector: String) {}
    static var lastCompilationError: String?
    static var userDomainRuleIdentifier: String { "fixture-domain" }
    static func compileUserDomainList() async throws -> WKContentRuleList? { nil }
    private static var domains: [String] = []
    static func userBlockedDomains() -> [String] { domains }
    @discardableResult static func addUserBlockedDomain(_ domain: String) -> Bool { domains.append(domain); return true }
    static func removeUserBlockedDomain(_ domain: String) { domains.removeAll { $0 == domain } }
}
@main final class BrowserStartupChecks: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var store: BrowserStore?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        Task { @MainActor in
            do {
                try await run()
                print(ProcessInfo.processInfo.environment["POPUP_AUDIT"] == "1"
                    ? "PASS: browser popup regression checks"
                    : ProcessInfo.processInfo.environment["AD_NAVIGATION"] == "1"
                        ? "PASS: browser ad navigation checks"
                        : ProcessInfo.processInfo.environment["NETWORK_LOG"] == "1"
                            ? "PASS: browser network log checks"
                            : "PASS: browser restore, navigation, consent, dialogs, popups, search and downloads")
            }
            catch { print("FAIL: \(error)"); exit(1) }
            if ProcessInfo.processInfo.environment["POPUP_TOUCH"] != "1" && ProcessInfo.processInfo.environment["NETWORK_LOG_UI"] != "1" && ProcessInfo.processInfo.environment["TABS_UI"] != "1" { exit(0) }
        }
        return true
    }
    struct Failure: Error { let message: String }
    @MainActor func check(_ condition: Bool, _ message: String) throws {
        if !condition { throw Failure(message: message) }
    }
    @MainActor func wait(_ stage: String = #function, line: Int = #line, _ condition: () -> Bool) async throws {
        for _ in 0..<500 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw Failure(message: "Timed out: \(stage) line \(line)")
    }
    @MainActor func verifyFavicons() async throws {
        let first = BrowserFaviconCache.requestURL(for: "https://example.com/private?token=secret")!
        let second = BrowserFaviconCache.requestURL(for: "https://example.com/another")!
        try check(first == second && !first.absoluteString.contains("secret"), "Favicon request leaked page data")
        try check(BrowserFaviconCache.requestURL(for: "http://192.168.1.1/video") == nil &&
                  BrowserFaviconCache.requestURL(for: "about:blank") == nil, "Local or home tab requested a favicon")
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64))
        FaviconFixtureProtocol.payload = renderer.pngData { context in
            UIColor.blue.setFill(); context.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [FaviconFixtureProtocol.self]
        let cache = BrowserFaviconCache(session: URLSession(configuration: config))
        async let a = cache.image(for: first)
        async let b = cache.image(for: second)
        let results = await (a, b)
        try check(results.0 != nil && results.1 != nil, "Favicon image failed to decode")
        try check(cache.cachedImage(for: first) != nil, "Cached favicon unavailable during initial row rendering")
        let again = await cache.image(for: first)
        try check(again != nil && FaviconFixtureProtocol.requestCount == 1, "Favicon requests were not shared/cached")
        let failed = BrowserFaviconCache.requestURL(for: "https://failed.example")!
        _ = await cache.image(for: failed)
        _ = await cache.image(for: failed)
        try check(FaviconFixtureProtocol.requestCount == 2, "Failed favicon retried without backoff")
        print("CHECK: bundled font and favicon origin/cache/failure checks passed")
    }

    @MainActor func verifyJumpDuringScrolling() async throws {
        let scroll = UIScrollView(frame: CGRect(x: 0, y: 0, width: 300, height: 300))
        scroll.contentSize = CGSize(width: 300, height: 3000)
        window?.rootViewController?.view.addSubview(scroll)
        defer { scroll.removeFromSuperview() }
        let driver = TabScrollDriver()
        driver.scrollView = scroll
        let button = TabJumpButton.JumpButton()
        var jumps = 0
        button.action = {
            jumps += 1
            driver.stopScrolling()
            scroll.setContentOffset(CGPoint(x: 0, y: 2700), animated: false)
        }
        scroll.setContentOffset(CGPoint(x: 0, y: 1000), animated: true)
        try await Task.sleep(nanoseconds: 50_000_000)
        button.sendActions(for: .touchDown)
        try check(jumps == 1 && scroll.contentOffset.y == 2700, "Jump waited for finger release during scrolling")
        try await Task.sleep(nanoseconds: 400_000_000)
        try check(scroll.contentOffset.y == 2700, "Previous scroll animation overrode the jump")
        try check(button.accessibilityActivate() && jumps == 2, "VoiceOver could not activate the jump")
        print("CHECK: jump interrupts scroll motion on finger-down and supports VoiceOver")
    }

    @MainActor func verifyTabPlayback(_ browser: BrowserStore, base: String) async throws {
        var state = BrowserPlaybackState()
        state.update(frame: "one", playing: true, now: 100)
        state.update(frame: "two", playing: true, now: 100)
        state.update(frame: "one", playing: false, now: 101)
        try check(state.isPlaying(now: 101) && !state.isPlaying(now: 103), "Frame aggregation or stale playback expiry failed")
        let first = browser.newTab(loading: base + "/playback")
        let firstView = first.webView
        firstView.frame = window!.bounds
        window?.rootViewController?.view.addSubview(firstView)
        try await wait("first media page") { firstView.title == "Playback" && !firstView.isLoading }
        _ = try await firstView.evaluateJavaScript("document.querySelector('audio').play();void(0)")
        try await wait("first tab playing") { first.refreshPlaybackState(); return first.isMediaPlaying }
        let second = browser.newTab(loading: base + "/playback")
        let secondView = second.webView
        secondView.frame = window!.bounds
        window?.rootViewController?.view.addSubview(secondView)
        try await wait("second media page") { secondView.title == "Playback" && !secondView.isLoading }
        _ = try await secondView.evaluateJavaScript("document.querySelector('audio').play();void(0)")
        try await wait("second tab playing") { second.refreshPlaybackState(); return second.isMediaPlaying }
        _ = try await firstView.evaluateJavaScript("document.querySelector('audio').pause();void(0)")
        try await wait("previous tab speaker clears") { first.refreshPlaybackState(); return !first.isMediaPlaying }
        second.refreshPlaybackState()
        try check(second.isMediaPlaying, "Pausing one tab cleared another tab's indicator")
        _ = try await secondView.evaluateJavaScript("document.querySelector('audio').pause();void(0)")
        try await wait("second tab paused") { second.refreshPlaybackState(); return !second.isMediaPlaying }
        second.load(base + "/playback-frame")
        try await wait("iframe media page") { secondView.title == "PlaybackFrame" && !secondView.isLoading }
        _ = try await secondView.evaluateJavaScript("document.querySelector('iframe').contentWindow.postMessage('play','*');void(0)")
        try await wait("cross-origin iframe playing") { second.refreshPlaybackState(); return second.isMediaPlaying }
        _ = try await secondView.evaluateJavaScript("document.querySelector('iframe').remove();void(0)")
        try await wait("removed iframe speaker clears") { second.refreshPlaybackState(); return !second.isMediaPlaying }
        try check(browser.tabs.filter { $0.loadedWebView != nil }.count == 3, "Playback tracking woke dormant tabs")
        firstView.removeFromSuperview(); secondView.removeFromSuperview()
        print("CHECK: per-tab playback, previous-tab pause, iframe playback/removal, expiry and dormant tabs passed")
    }

    @MainActor func run() async throws {
        for scheme in ["", ":", "https:", "1http", "http\n", "data", "about", "file"] {
            try check(BrowserPopupInteraction.originURL(scheme: scheme, host: "example.test", port: 0) == nil,
                      "Invalid or opaque origin must not crash or receive a popup grant")
        }
        try check(BrowserPopupInteraction.originURL(scheme: "https", host: "", port: 0) == nil,
                  "Opaque origin with no host must be rejected")
        try check(BrowserPopupInteraction.originURL(scheme: "https", host: "example.test", port: -1) == nil,
                  "Invalid port must be rejected")
        try check(BrowserPopupInteraction.originURL(scheme: "HTTPS", host: "example.test", port: 8443)?.absoluteString
                  == "https://example.test:8443", "Valid frame origin must retain its port")
        try check(UIFont(name: "Poppins-Regular", size: 17) != nil, "Bundled Android font did not register")
        try await verifyFavicons()
        try await verifyJumpDuringScrolling()
        let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("startup-tabs.json")
        let urls = (0..<20).map { "\(base)/tab/\($0)" }
        try JSONSerialization.data(withJSONObject: ["urls": urls, "activeIndex": 7]).write(to: file)
        let browser = BrowserStore(tabsFileURL: file)
        store = browser
        browser.browserVisible = true
        try check(browser.tabs.allSatisfy { $0.loadedWebView == nil }, "Restoration eagerly created webviews")
        try await wait { browser.activeTab?.loadedWebView != nil }
        try check(browser.tabs.filter { $0.loadedWebView != nil }.count == 1, "Inactive restored tabs woke up")
        try check(browser.activeID == browser.tabs[7].id, "Wrong restored active tab")
        await browser.recompileAndApply()
        try check(browser.tabs.filter { $0.loadedWebView != nil }.count == 1, "Ad-block refresh woke tabs")
        if ProcessInfo.processInfo.environment["TAB_PLAYBACK_STATE"] == "1" {
            try await verifyTabPlayback(browser, base: base)
            return
        }
        if ProcessInfo.processInfo.environment["TAB_MANAGEMENT"] == "1" {
            try verifyTabManagement(browser)
            return
        }
        let inactive = browser.tabs[2]
        browser.closeTab(inactive.id)
        try check(inactive.loadedWebView == nil, "Closing dormant tab created a webview")
        let original = browser.activeID
        browser.openInBackground("http://127.0.0.1:1/background")
        let openerIndex = browser.tabs.firstIndex { $0.id == original }!
        let background = browser.tabs[openerIndex + 1]
        try check(background.urlString.hasSuffix("/background"), "Background link was not inserted after opener")
        try check(browser.activeID == original && background.loadedWebView == nil, "Background tab loaded or stole focus")
        browser.select(background.id)
        try check(background.loadedWebView != nil, "Selected background tab stayed dormant")
        let selectedView = background.loadedWebView
        browser.select(browser.tabs[0].id)
        browser.select(background.id)
        try check(background.loadedWebView === selectedView, "Selection replaced existing webview")
        browser.closeTab(background.id)
        try check(browser.activeTab?.loadedWebView != nil, "Closing active tab failed to wake replacement")
        let menuOpener = browser.activeTab!
        menuOpener.onOpenNewTab?(URL(string: base + "/foreground")!, false)
        let menuChild = browser.activeTab!
        try check(browser.tabs.firstIndex { $0.id == menuChild.id } == browser.tabs.firstIndex { $0.id == menuOpener.id }! + 1,
            "Foreground link was not inserted after opener")
        // The callback belongs to its source tab, even if selection changes before delivery.
        menuOpener.onOpenNewTab?(URL(string: base + "/background-menu")!, true)
        let adjacent = browser.tabs[browser.tabs.firstIndex { $0.id == menuOpener.id }! + 1]
        try check(adjacent.urlString.hasSuffix("/background-menu") && adjacent.loadedWebView == nil && browser.activeID == menuChild.id,
            "Background context menu used active tab instead of opener, or stole selection")
        browser.select(menuOpener.id)
        print("CHECK: lazy tabs and link placement passed")
        let parent = browser.activeTab!
        let webView = parent.webView
        webView.configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        window?.rootViewController?.view.addSubview(webView)
        webView.frame = window!.bounds
        parent.load(base + "/parent")
        try await wait("parent load") { webView.title == "Parent" && !webView.isLoading }
        print("CHECK: parent loaded")
        if ProcessInfo.processInfo.environment["TABS_UI"] == "1" {
            parent.title = "Example video with a longer title that wraps in the selected tab"
            window?.rootViewController = UIHostingController(rootView: TabsScreen(store: browser).preferredColorScheme(.dark))
            return
        }
        if ProcessInfo.processInfo.environment["DOMAIN_BLOCK"] == "1" {
            try await verifyDomainBlocking(parent: parent, base: base)
            return
        }
        if ProcessInfo.processInfo.environment["NETWORK_LOG_UI"] == "1" {
            parent.networkLog.clear()
            parent.networkLog.record(url: "https://ads.example/banner", page: base, kind: "image", state: "Observed", isSubframe: true)
            window?.rootViewController = UIHostingController(rootView: BrowserNetworkLogView(tab: parent, store: browser))
            return
        }
        if ProcessInfo.processInfo.environment["NETWORK_LOG"] == "1" {
            try await verifyNetworkLog(browser, parent: parent, base: base)
            return
        }
        if ProcessInfo.processInfo.environment["AD_NAVIGATION"] == "1" {
            try await verifyAdNavigations(browser, parent: parent, base: base)
            return
        }
        if ProcessInfo.processInfo.environment["POPUP_TOUCH"] == "1" {
            try await verifyPopupTouches(browser, parent: parent, base: base)
            return
        }
        if ProcessInfo.processInfo.environment["POPUP_AUDIT"] == "1" {
            try await auditPopups(browser, parent: parent, base: base)
            return
        }
        let parentURL = webView.url
        let count = browser.tabs.count
        // These scripted requests test window/POST preservation with explicit permission.
        BrowserSitePolicy.setPopupsAllowed(true, url: webView.url)
        _ = try await webView.evaluateJavaScript("document.getElementById('link').click()")
        try await wait("new window") { browser.tabs.count == count + 1 }
        try check(browser.activeID != parent.id && browser.activeTab?.urlString.contains("/child") == true, "Target blank did not create/select child")
        try check(webView.url == parentURL, "Target blank navigated opener")
        try check(browser.tabs.firstIndex { $0.id == browser.activeID } == browser.tabs.firstIndex { $0.id == parent.id }! + 1,
            "New-window link was not inserted after opener")
        browser.select(parent.id)
        _ = try await webView.evaluateJavaScript("document.getElementById('form').submit()")
        try await wait("new window") { browser.tabs.count == count + 2 }
        try check(webView.url == parentURL, "New-window form navigated opener")
        try check(browser.tabs.firstIndex { $0.id == browser.activeID } == browser.tabs.firstIndex { $0.id == parent.id }! + 1,
            "New-window form was not inserted after opener")
        BrowserSitePolicy.setPopupsAllowed(false, url: webView.url)
        print("CHECK: windows passed")
        browser.select(parent.id)
        try await verifyInteractions(browser, parent: parent, base: base)

    }
}

extension BrowserStartupChecks {
    @MainActor func verifyInteractions(_ browser: BrowserStore, parent: BrowserTab, base: String) async throws {
        let view = parent.webView
        // Search values must round-trip exactly, even with form-style '+' decoding.
        for engine in SearchEngine.allCases {
            let query = "cats & dogs + 100% #雪"
            let url = engine.searchURL(query)
            let value = URLComponents(string: url)!.queryItems!.first { $0.name == "q" }!.value
            try check(value == query && !url.contains("+"), "Search query corrupted")
        }
        parent.recordFailure(NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
        try check(parent.navigationFailure == nil, "Cancellation shown as failure")
        parent.recordFailure(NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet,
            userInfo: [NSURLErrorFailingURLStringErrorKey: base + "/offline"]))
        try check(parent.navigationFailure?.address == base + "/offline", "Lost failing address")
        parent.reload()
        try await wait("retry navigation") { parent.navigationFailure == nil && !view.isLoading }
        parent.load(base + "/parent")
        try await wait("return to parent") { view.title == "Parent" && !view.isLoading }
        var casts: [(String, String)] = []
        browser.onPageCast = { payload, origin in casts.append((payload["url"] as! String, origin)) }
        let source = view.url!
        parent.requestPageCast(["url": base + "/media"], source: source)
        try check(parent.prompt != nil && casts.isEmpty, "Cast bypassed consent")
        let consent = parent.prompt!
        parent.prompt = nil
        consent.finish(true)
        consent.finish(true)
        try check(casts.count == 1 && casts[0].1 == source.absoluteString, "Duplicate cast or wrong origin")
        parent.requestPageCast(["url": base + "/media"], source: source)
        browser.select(browser.tabs.first { $0.id != parent.id }!.id)
        try check(parent.prompt == nil && casts.count == 1, "Switching tabs failed to reject consent")
        parent.requestPageCast(["url": base + "/media"], source: source)
        try check(parent.prompt == nil, "Background page requested consent")
        browser.select(parent.id)
        parent.requestPageCast(["url": base + "/media"], source: URL(string: "https://wrong.test")!)
        try check(parent.prompt == nil, "Unverified origin accepted")
        _ = try await view.evaluateJavaScript("var f=document.createElement('iframe'); f.srcdoc='<script>window.webkit.messageHandlers.playbridge.postMessage({type:\"cast\",payload:{url:\"https://media.test/video.mp4\"}})<\\/script>'; document.body.appendChild(f); void(0)")
        try await Task.sleep(nanoseconds: 300_000_000)
        try check(parent.prompt == nil, "Iframe initiated casting")
        print("CHECK: consent passed")
        try await verifyElementPicker(parent, browser: browser, base: base)


        let confirm = Task { try await view.evaluateJavaScript("confirm('Continue?')") }
        try await wait("confirm dialog") { parent.prompt != nil }
        let dialog = parent.prompt!; parent.prompt = nil; dialog.finish(true)
        let answer = try await confirm.value as? Bool
        try check(answer == true, "Confirm result lost")
        let input = Task { try await view.evaluateJavaScript("prompt('Name?', 'Initial')") }
        try await wait("text prompt") { parent.prompt != nil }
        let textPrompt = parent.prompt!; parent.prompt = nil; textPrompt.finish(true, text: "Hello")
        let entered = try await input.value as? String
        try check(entered == "Hello", "Text prompt result lost")
        let cancelled = Task { try await view.evaluateJavaScript("confirm('Cancel me')") }
        try await wait("cancel dialog") { parent.prompt != nil }
        parent.cancelPrompt()
        let cancelledAnswer = try await cancelled.value as? Bool
        try check(cancelledAnswer == false, "Dialog cancellation lost")
        let before = browser.tabs.count
        BrowserSitePolicy.setPopupsAllowed(false, url: view.url)
        _ = try await view.evaluateJavaScript("window.open('/popup', '_blank'); void(0)")
        try await wait("blocked popup") { parent.popupBlocked }
        try check(browser.tabs.count == before, "Automatic popup escaped blocker")
        parent.allowPopupsForSite()
        _ = try await view.evaluateJavaScript("window.open('/popup', '_blank'); void(0)")
        try await wait("allowed popup") { browser.tabs.count == before + 1 }
        BrowserSitePolicy.setPopupsAllowed(false, url: view.url)
        browser.select(parent.id)
        _ = try await view.evaluateJavaScript("var x=document.createElement('a');x.href='playbridge-fixture-uninstalled://example';document.body.appendChild(x);x.click();void(0)")
        try await wait("external link approval") { parent.prompt != nil }
        try check(parent.prompt?.title == "Open another app?", "External link had no approval")
        parent.cancelPrompt()
        try check(view.url == source, "External link navigated browser")
        parent.webViewWebContentProcessDidTerminate(view)
        try check(parent.navigationFailure != nil, "WebContent termination has no recovery")
        parent.reload()
        try await wait("process recovery") { parent.navigationFailure == nil && !view.isLoading }
        print("CHECK: dialogs/popups/external links passed")

        let jobCount = browser.downloads.items.count
        _ = try await view.evaluateJavaScript("document.getElementById('download').click()")
        try await wait("download approval") { parent.prompt != nil }
        try check(browser.downloads.items.count == jobCount, "Download bypassed approval")
        let approval = parent.prompt!; parent.prompt = nil; approval.finish(true)
        try await wait("download completes") { browser.downloads.items.first?.state == "Complete" && browser.downloads.items.count > jobCount }
        let job = browser.downloads.items.first!
        let bytes = try Data(contentsOf: job.fileURL!)
        try check(bytes == Data(String(repeating: "playbridge-download-fixture\n", count: 100).utf8), "Downloaded bytes differ")
        let (logs, _) = try await URLSession.shared.data(from: URL(string: base + "/requests")!)
        let requests = try JSONSerialization.jsonObject(with: logs) as! [[String: String]]
        try check(requests.contains { $0["path"] == "/post" && $0["method"] == "POST" && $0["body"] == "value=preserved" }, "New-window POST body lost")
        try check(requests.contains { $0["path"] == "/file" && ($0["cookie"] ?? "").contains("fixture=present") }, "Download lost website cookie")
        // Real interrupted response, followed by an explicit retry.
        view.startDownload(using: URLRequest(url: URL(string: base + "/retry")!)) { browser.downloads.adopt($0, webView: view) }
        try await wait("download failure") { browser.downloads.items.first?.state == "Failed" }
        let failed = browser.downloads.items.first!
        browser.downloads.retry(failed)
        try await wait("download retry") { failed.state == "Complete" }
        view.startDownload(using: URLRequest(url: URL(string: base + "/slow")!)) { browser.downloads.adopt($0, webView: view) }
        try await wait("slow download") { browser.downloads.items.first?.state == "Downloading" }
        let slow = browser.downloads.items.first!
        browser.downloads.cancel(slow)
        try await wait("download cancellation") { slow.state == "Cancelled" }
        browser.downloads.remove(slow)
        try check(!browser.downloads.items.contains { $0 === slow }, "Download removal failed")
        print("CHECK: downloads passed")

        parent.title = "Saved title"
        parent.isDesktopMode = true
        let home = browser.newTab()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("startup-tabs.json")
        let restored = BrowserStore(tabsFileURL: file)
        try check(restored.activeTab?.isHome == true && home.isHome, "Active home tab not restored")
        try check(restored.tabs.contains { $0.title == "Saved title" && $0.isDesktopMode && $0.loadedWebView == nil }, "Dormant tab metadata lost")
        try check(BrowserDownloads.safeFilename("../../sample.bin") == "sample.bin", "Unsafe download filename")
        try check(BrowserDownloads.safeFilename("..") == "Download", "Dot filename allowed")
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyElementPicker(_ tab: BrowserTab, browser: BrowserStore, base: String) async throws {
        let view = tab.webView
        let original = view.url
        var picked: [(String, String)] = []
        tab.onElementPicked = { picked.append(($0, $1)) }
        // Pages must not be able to add persistent rules outside picker mode.
        _ = try await view.evaluateJavaScript("window.webkit.messageHandlers.playbridge.postMessage({type:'pickedElement',selector:'#link',host:'forged.test'});void(0)")
        try await Task.sleep(nanoseconds: 100_000_000)
        try check(picked.isEmpty, "Page injected rule without picker")
        _ = try await view.evaluateJavaScript("window.pickerClicks=0;document.getElementById('link').onclick=function(){window.pickerClicks++;};void(0)")
        print("CHECK: picker message guard passed")
        tab.startElementPicker()
        try await wait("picker injected") { tab.isPickingElement }
        print("CHECK: picker native state started")
        // Wait for JS installation, then reproduce touchend followed by a synthetic click.
        for _ in 0..<100 {
            if (try? await view.evaluateJavaScript("!!window.__pb_picker")) as? Bool == true { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        _ = try await view.evaluateJavaScript("""
            var anchor=document.getElementById('link'), rect=anchor.getBoundingClientRect();
            var x=rect.left+rect.width/2, y=rect.top+rect.height/2;
            var shield=document.getElementById('__pb_picker_shield');
            var touch=new Event('touchend',{bubbles:true,cancelable:true});
            Object.defineProperty(touch,'changedTouches',{value:[{clientX:x,clientY:y}]});
            shield.dispatchEvent(touch);
            anchor.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:x,clientY:y}));
            void(0)
            """)
        try await Task.sleep(nanoseconds: 100_000_000)
        let clicks = try await view.evaluateJavaScript("window.pickerClicks") as? Int
        let selector = try await view.evaluateJavaScript("document.getElementById('pbsel').textContent") as? String
        try check(clicks == 0 && selector == "#link" && view.url == original, "Linked selection navigated or reached page handler")
        let count = browser.tabs.count
        _ = try await view.evaluateJavaScript("window.open('/picker-popup','_blank');void(0)")
        try check(browser.tabs.count == count, "Picker allowed a popup")
        _ = try await view.evaluateJavaScript("document.getElementById('pbblock').click()")
        try await wait("picker blocked element") { picked.count == 1 && !tab.isPickingElement }
        let hidden = try await view.evaluateJavaScript("getComputedStyle(document.getElementById('link')).display") as? String
        try check(hidden == "none" && picked[0].0 == "#link" && picked[0].1 == URL(string: base)!.host, "Block result or verified host incorrect")
        tab.startElementPicker()
        for _ in 0..<100 {
            if (try? await view.evaluateJavaScript("!!window.__pb_picker")) as? Bool == true { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        _ = try await view.evaluateJavaScript("document.getElementById('pbcancel').click()")
        try await wait("picker cancel") { !tab.isPickingElement }
        try check(picked.count == 1, "Cancel saved a rule")
        tab.load(base + "/parent")
        try await wait("navigation restored after picker") { view.title == "Parent" && !view.isLoading }
        print("CHECK: linked element selection, click suppression, blocking and cancel passed")
    }
}

extension BrowserStartupChecks {
    /// Regression checks use loopback pages, never advertising sites.
    @MainActor func auditPopups(_ browser: BrowserStore, parent: BrowserTab, base: String) async throws {
        let view = parent.webView
        try check((try await view.evaluateJavaScript("typeof window.webkit.messageHandlers.popupInteraction")) as? String == "undefined",
            "Popup authorization handler leaked into page world")
        let other = browser.tabs.first { $0.id != parent.id }!
        let frameBase = base.replacingOccurrences(of: "127.0.0.1", with: "localhost")
        let cases: [(String, String, Bool, Bool, Bool)] = [
            ("automatic window.open", "window.open('/audit-child','_blank');", false, true, true),
            ("synthetic anchor click", "var a=document.createElement('a');a.href='/audit-child';a.target='_blank';document.body.appendChild(a);a.click();", false, true, true),
            ("synthetic submit event", "document.getElementById('form').requestSubmit();", false, true, true),
            ("forged gesture", "window.dispatchEvent(new MouseEvent('click',{bubbles:true}));window.webkit.messageHandlers.playbridge.postMessage({type:'popupInteraction'});window.open('/audit-child','_blank');", false, true, true),
            ("synthetic form submit", "document.getElementById('form').submit();", false, true, true),
            ("background synthetic anchor", "document.getElementById('link').click();", false, false, true),
            ("allowed-origin window.open", "window.open('/audit-child','_blank');", true, true, true),
            ("revoked-origin window.open", "window.open('/audit-child','_blank');", false, true, true),
            ("cross-origin frame synthetic anchor", "var f=document.createElement('iframe');f.src='\(frameBase)/popup-frame-link';document.body.appendChild(f);", false, true, true),
            ("cross-origin frame inherits top-origin allowance", "var f=document.createElement('iframe');f.src='\(frameBase)/popup-frame-window';document.body.appendChild(f);", true, true, true),
            ("native auto-window restriction: anchor", "document.getElementById('link').click();", false, true, false),
            ("native auto-window restriction: form", "document.getElementById('form').submit();", false, true, false)
        ]
        for (name, action, allowed, foreground, automatic) in cases {
            browser.select(parent.id)
            parent.load(base + "/parent")
            try await wait("popup audit parent") { view.title == "Parent" && !view.isLoading }
            BrowserSitePolicy.setPopupsAllowed(allowed, url: view.url)
            view.configuration.preferences.javaScriptCanOpenWindowsAutomatically = automatic
            if !foreground { browser.select(other.id) }
            let before = Set(browser.tabs.map(\.id))
            let isFrame = name.hasPrefix("cross-origin")
            let script = """
                window.__popupAuditDone=false;window.__popupAuditActive=null;
                window.addEventListener('message',function(e){if(e.data&&e.data.popupAuditDone){window.__popupAuditDone=true;window.__popupAuditActive=e.data.active;}});
                setTimeout(function(){
                    window.__popupAuditActive=!!(navigator.userActivation&&navigator.userActivation.isActive);
                    \(action)
                    \(isFrame ? "" : "window.__popupAuditDone=true;")
                },100);void(0)
                """
            _ = try await view.evaluateJavaScript(script)
            var done = false
            for _ in 0..<200 {
                if (try? await view.evaluateJavaScript("window.__popupAuditDone")) as? Bool == true { done = true; break }
                try await Task.sleep(nanoseconds: 25_000_000)
            }
            try check(done, "Audit case did not execute: " + name)
            try await Task.sleep(nanoseconds: 150_000_000)
            let active = (try? await view.evaluateJavaScript("window.__popupAuditActive")) as? Bool
            let added = browser.tabs.filter { !before.contains($0.id) }
            print("POPUP AUDIT: \(name); new tabs=\(added.count); blocked notice=\(parent.popupBlocked); JS activation=\(String(describing: active))")
            let expected = name == "allowed-origin window.open" ? 1 : 0
            try check(active == false, "Fixture unexpectedly had activation: " + name)
            try check(added.count == expected, "Popup policy mismatch: " + name)
            for child in added { browser.closeTab(child.id) }
        }
        browser.select(parent.id)
        BrowserSitePolicy.setPopupsAllowed(false, url: view.url)
        view.configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        let before = browser.tabs.count
        _ = try await view.evaluateJavaScript("setTimeout(function(){location.href='/audit-redirect';},100);void(0)")
        try await wait("same-tab redirect audit") { view.url?.path == "/audit-redirect" && !view.isLoading }
        print("POPUP AUDIT: automatic same-tab redirect; navigated=true; new tabs=\(browser.tabs.count-before)")
        print("POPUP REGRESSIONS PASSED: no live filter lists attached")
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyPopupTouches(_ browser: BrowserStore, parent: BrowserTab, base: String) async throws {
        let label = UILabel(frame: CGRect(x: 10, y: 70, width: 360, height: 40))
        label.accessibilityIdentifier = "popupStatus"
        window!.rootViewController!.view.addSubview(label)
        parent.webView.frame = CGRect(x: 0, y: 130, width: window!.bounds.width, height: 500)
        for name in ["link", "form", "script", "burst", "expired", "iframe"] {
            browser.select(parent.id)
            parent.load(base + (name == "iframe" ? "/touch-frame" : "/touch"))
            try await wait("touch fixture") { parent.webView.title == "Touch" && !parent.webView.isLoading }
            BrowserSitePolicy.setPopupsAllowed(false, url: parent.webView.url)
            let ids = Set(browser.tabs.map(\.id))
            label.text = "Ready: " + name
            if name == "expired" {
                try await wait("expired interaction") { parent.popupBlocked }
                try check(browser.tabs.count == ids.count, "Expired interaction opened a popup")
                continue
            }
            try await wait("trusted tap " + name) { browser.tabs.count > ids.count }
            try await Task.sleep(nanoseconds: 300_000_000)
            let added = browser.tabs.filter { !ids.contains($0.id) }
            try check(added.count == 1, "A single tap opened multiple windows")
            let child = added[0]
            try await wait("child loaded") { child.webView.title == "Child" && !child.webView.isLoading }
            try check(child.webView.url?.path == (name == "form" ? "/post" : "/child"), "Wrong popup destination")
            for tab in added { browser.closeTab(tab.id) }
            print("CHECK: trusted " + name + " passed")
        }
        label.text = "PASS: trusted popups"
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyAdNavigations(_ browser: BrowserStore, parent: BrowserTab, base: String) async throws {
        let rules = NavigationAdRules(text: """
        ||ads.example^
        @@||ads.example/allowed$document,popup
        @@||ads.example/safe-path
        ||assets.example^$script
        ||scoped.example^$popup,domain=video.example|~safe.video.example
        ||disabled.example^$popup
        ||disabled.example^$popup,badfilter
        ||path.example/ad$document
        """)
        func blocks(_ target: String, source: String = "https://video.example", popup: Bool = false) -> Bool {
            rules.decision(url: URL(string: target)!, source: URL(string: source), popup: popup) == true
        }
        try check(blocks("https://ads.example/ad"), "Host-wide navigation rule missed")
        try check(!blocks("https://ads.example.evil.test/ad"), "Host boundary broadened")
        try check(!blocks("https://video.example/?next=ads.example"), "Query text treated as destination")
        try check(!blocks("https://assets.example/"), "Script-only rule blocked a document")
        try check(!blocks("https://ads.example/allowed"), "Exception ignored")
        try check(!blocks("https://ads.example/safe-path"), "Untyped path exception ignored")
        try check(blocks("https://scoped.example/", popup: true), "Scoped popup not blocked")
        try check(!blocks("https://scoped.example/", source: "https://safe.video.example", popup: true), "Negative domain ignored")
        try check(!blocks("https://scoped.example/"), "Popup-only rule blocked a document")
        try check(!blocks("https://disabled.example/", popup: true), "Disabled rule applied")
        try check(blocks("https://path.example/ad") && !blocks("https://path.example/video"), "Path rule broadened")
        ContentBlocker.navigationRules = NavigationAdRules(text: "||127.0.0.1^*/ad$document,popup")
        defer { ContentBlocker.navigationRules = NavigationAdRules(); ContentBlocker.isEnabled = true; BrowserSitePolicy.setPopupsAllowed(false, url: URL(string: base)) }
        let view = parent.webView
        let original = view.url
        let count = browser.tabs.count
        func blockedCount() -> Int { parent.networkLog.entries.filter { $0.state == "Blocked by ad rules" }.count }
        BrowserSitePolicy.setPopupsAllowed(true, url: original)
        _ = try await view.evaluateJavaScript("window.open('/ad','_blank');void(0)")
        try await wait("blocked ad popup") { blockedCount() >= 1 }
        try check(browser.tabs.count == count && view.url == original, "Ad popup left an empty tab")
        parent.blockedAdMessage = nil
        _ = try await view.evaluateJavaScript("location.href='/ad';void(0)")
        try await wait("blocked same-tab ad") { blockedCount() >= 2 }
        try check(view.url == original && view.title == "Parent", "Ad replaced video page")
        parent.blockedAdMessage = nil
        _ = try await view.evaluateJavaScript("location.href='/redirect-ad';void(0)")
        try await wait("blocked server redirect") { blockedCount() >= 3 && !view.isLoading }
        try check(view.title == "Parent" && view.url == original && parent.navigationFailure == nil, "Server redirect replaced page or showed load error")
        parent.blockedAdMessage = nil
        _ = try await view.evaluateJavaScript("window.open('/redirect-ad','_blank');void(0)")
        try await wait("discard redirected ad popup") { blockedCount() >= 4 && browser.tabs.count == count }
        try check(browser.activeID == parent.id, "Popup closure lost opener selection")
        parent.blockedAdMessage = nil
        _ = try await view.evaluateJavaScript("window.open('/parent','_blank');location.href='/ad';void(0)")
        try await wait("tab swap preserves video") { browser.tabs.count == count + 1 && blockedCount() >= 5 && browser.activeTab?.webView.title == "Parent" }
        try check(view.title == "Parent", "Tab swap discarded original video")
        browser.closeTab(browser.activeID!)
        browser.select(parent.id)
        parent.load(base + "/redirect-good")
        try await wait("legitimate server redirect") { view.title == "Child" && !view.isLoading }
        ContentBlocker.isEnabled = false
        parent.load(base + "/ad")
        try await wait("disabled ad blocker") { view.url?.path == "/ad" && !view.isLoading }
        print("CHECK: ad destinations, redirects, popup cleanup, tab swaps, exceptions and disabled blocking passed")
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyNetworkLog(_ browser: BrowserStore, parent: BrowserTab, base: String) async throws {
        parent.load(base + "/network")
        let log = parent.networkLog
        try await wait("fetch observation") { log.entries.contains { $0.kind == "fetch" && $0.url.contains("/fetch-test") && $0.status == 200 } }
        try await wait("XHR observation") { log.entries.contains { $0.kind == "XHR" && $0.method == "POST" && $0.status == 200 } }
        try await wait("image observation") { log.entries.contains { $0.kind == "img" && $0.url.contains("/image.svg") } }
        try await wait("frame observation") { log.entries.contains { $0.kind == "fetch" && $0.page.contains("/network-frame") && $0.isSubframe } }
        _ = try await parent.webView.evaluateJavaScript("var f=document.createElement('iframe');f.srcdoc=\"<img src='\(base)/image.svg?srcdoc=1'>\";document.body.appendChild(f);void(0)")
        try await wait("srcdoc frame observation") { log.entries.contains { $0.url.contains("srcdoc=") && $0.isSubframe } }
        try await wait("failed fetch observation") { log.entries.contains { $0.kind == "fetch" && $0.state == "Failed or blocked" } }
        try check(log.entries.filter { $0.kind == "fetch" && $0.url.contains("/fetch-test") }.count == 1, "Fetch update duplicated request")
        try check(!log.exportText.contains("secret-fixture"), "Query token retained")
        try check(log.exportText.contains("redacted"), "URL was not redacted")
        ContentBlocker.navigationRules = NavigationAdRules(text: "||127.0.0.1^*/ad$document,popup")
        _ = try await parent.webView.evaluateJavaScript("window.open('/ad','_blank');void(0)")
        try await wait("blocked navigation observation") { log.entries.contains { $0.state == "Blocked by ad rules" } }
        ContentBlocker.navigationRules = NavigationAdRules()
        try check(browser.tabs.filter { $0.id != parent.id }.allSatisfy { $0.networkLog.entries.allSatisfy { !$0.url.contains("/fetch-test") } }, "Log leaked across tabs")
        log.clear()
        try check(log.entries.isEmpty && log.discarded == 0, "Clear did not reset log")
        for index in 0...BrowserNetworkLog.limit {
            log.record(url: base + "/bounded/\(index)", page: base, kind: "fixture", state: "Observed")
        }
        try check(log.entries.count == BrowserNetworkLog.limit && log.discarded == 1, "Network log is unbounded")
        print("CHECK: network fetch/XHR/image/frame/failure capture, redaction, tab isolation and bounds passed")
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyDomainBlocking(parent: BrowserTab, base: String) async throws {
        let json = BrowserDomainRules.json(["127.0.0.1"], resourceTypes: ["image", "style-sheet", "script", "font", "raw", "svg-document", "media", "popup"])
        let rules: WKContentRuleList = try await withCheckedThrowingContinuation { continuation in
            WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "fixture-domains-" + UUID().uuidString, encodedContentRuleList: json) { list, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: list!) }
            }
        }
        let config = WKWebViewConfiguration()
        let view = WKWebView(frame: .zero, configuration: config)
        let source = URL(string: base.replacingOccurrences(of: "127.0.0.1", with: "localhost"))!
        view.loadHTMLString("<html><title>Control</title><iframe src='\(base)/frame-allowed'></iframe></html>", baseURL: source)
        try await wait("iframe control") { view.title == "Control" && !view.isLoading }
        config.userContentController.add(rules)
        view.loadHTMLString("<html><title>Blocked</title><iframe src='\(base)/frame-blocked'></iframe><iframe srcdoc=\"<img src='\(base)/srcdoc-image-blocked'>\"></iframe></html>", baseURL: source)
        try await wait("blocked iframe fixture") { view.title == "Blocked" && !view.isLoading }
        let (data, _) = try await URLSession.shared.data(from: URL(string: base + "/requests")!)
        let requests = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        let paths = requests.compactMap { $0["path"] as? String }
        try check(paths.contains("/frame-allowed"), "Unblocked iframe control did not request its page")
        try check(!paths.contains("/frame-blocked"), "Blocked iframe reached server")
        try check(!paths.contains("/srcdoc-image-blocked"), "Blocked resource inside srcdoc reached server")
        view.load(URLRequest(url: URL(string: base + "/top-frame-allowed")!))
        try await wait("top frame remains allowed by content rules") { view.title == "Child" && !view.isLoading }
        parent.blockedAdMessage = nil
        parent.showBlockedNavigationNotice("Blocked navigation")
        for _ in 0..<10 {
            try await Task.sleep(nanoseconds: 300_000_000)
            parent.showBlockedNavigationNotice("Another blocked navigation")
        }
        try check(parent.blockedAdMessage == nil, "Repeated navigation blocks kept notice visible")
        print("CHECK: actual WebKit domain compilation, iframe/srcdoc blocking and notice expiry passed")
    }
}

extension BrowserStartupChecks {
    @MainActor func verifyTabManagement(_ browser: BrowserStore) throws {
        let originalActive = browser.activeID
        let source = browser.tabs.first { $0.loadedWebView == nil }!
        source.title = "Restored video"; source.isDesktopMode = true
        let duplicate = browser.duplicateTab(source.id)!
        try check(browser.activeID == originalActive && duplicate.loadedWebView == nil, "Duplicate woke or selected a background tab")
        try check(duplicate.title == source.title && duplicate.isDesktopMode && duplicate.urlString == source.urlString, "Duplicate lost metadata")
        try check(browser.tabs.firstIndex { $0.id == duplicate.id } == browser.tabs.firstIndex { $0.id == source.id }! + 1, "Duplicate placement differs from Android")
        browser.bookmarkTabs([source.id, duplicate.id])
        try check(browser.data.bookmarks.filter { $0.url == source.urlString }.count == 1, "Bulk bookmarks duplicated URL")
        let closed = browser.tabs.filter { $0.id == source.id || $0.id == duplicate.id || $0.id == originalActive }
        browser.closeTabs(Set(closed.map(\.id)))
        try check(source.loadedWebView == nil && duplicate.loadedWebView == nil, "Bulk close loaded a closed background tab")
        try check(browser.tabs.filter { $0.loadedWebView != nil }.count == 1, "Bulk close loaded intermediate tabs")
        try check(!browser.tabs.contains { tab in closed.contains { $0.id == tab.id } }, "Bulk close retained selected tabs")
        browser.closeTabs(Set(browser.tabs.map(\.id)))
        try check(browser.tabs.count == 1 && browser.activeTab?.isHome == true && browser.activeTab?.loadedWebView == nil, "Close all did not leave one unloaded home tab")
        print("CHECK: tab duplication, bulk bookmarks, close selection and close all passed")
    }
}

private final class FaviconFixtureProtocol: URLProtocol {
    static var payload = Data()
    private static let lock = NSLock()
    private static var count = 0
    static var requestCount: Int { lock.lock(); defer { lock.unlock() }; return count }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.count += 1; Self.lock.unlock()
        let failed = request.url!.absoluteString.contains("failed.example")
        let response = HTTPURLResponse(url: request.url!, statusCode: failed ? 404 : 200,
            httpVersion: nil, headerFields: ["Content-Type": "image/png"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.payload)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
