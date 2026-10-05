import Foundation
import WebKit
import Combine

/// Owns the set of browser tabs and the active selection. Tabs share a `WKProcessPool` +
/// default data store so cookies/logins persist across tabs (like a normal browser).
final class BrowserStore: ObservableObject {
    @Published private(set) var tabs: [BrowserTab] = []
    @Published private(set) var activeID: UUID?
    let bridgedApps: BridgedAppStore
    private var appSubscription: AnyCancellable?
    private var lastBrowserTabID: UUID?
    var onBridgedAppExternalNavigation: (() -> Void)?

    var browserTabs: [BrowserTab] { tabs.filter { !$0.isBridgedApp } }
    var activeBridgedApp: BridgedApp? {
        guard let tab = activeTab, let origin = tab.bridgedAppOrigin,
              let url = URL(string: tab.urlString), BridgedAppDeclaration.origin(of: url) == origin else { return nil }
        return bridgedApps.apps.first { $0.origin == origin }
    }

    /// Forwarded when any tab's page calls `window.playbridge.cast(...)`.
    var onPageCast: (([String: Any], String) -> Void)?
    var onWebsiteCast: ((BrowserTab, [String: Any]) -> Void)?
    var onPageCastInvalidated: ((BrowserTab) -> Void)?
    let downloads = BrowserDownloads()
    var browserVisible = false {
        didSet { if !browserVisible { activeTab?.cancelPrompt() } }
    }

    @Published var mediaDetectionSettings = BrowserMediaDetectionSettings.load() {
        didSet {
            guard mediaDetectionSettings != oldValue else { return }
            mediaDetectionSettings.save()
            tabs.forEach { $0.configureMediaDetection(mediaDetectionSettings) }
        }
    }

    @Published var adBlockEnabled: Bool = ContentBlocker.isEnabled
    private var ruleLists: [WKContentRuleList] = []

    /// False until the first rule compilation has been applied to the webviews.
    /// Loads requested before then (restored tabs) are deferred so the first
    /// pages of a session never load unfiltered.
    private var rulesReady = false
    private var pendingInitialLoads: [UUID: String] = [:]

    static let homeURL = "https://www.google.com"

    /// History + bookmarks, shared with the browser UI via the environment.
    let data = BrowserDataStore()

    private var isRestoring = false
    private let tabsFileURL: URL
    private static func defaultTabsFileURL() -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("browser_tabs.json")
    }

    init(tabsFileURL: URL? = nil, bridgedApps: BridgedAppStore = BridgedAppStore()) {
        self.tabsFileURL = tabsFileURL ?? Self.defaultTabsFileURL()
        self.bridgedApps = bridgedApps
        appSubscription = bridgedApps.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }
        restoreTabs()
        Task { @MainActor in
            // Compile cached rules so blocking is active immediately (curated fallback).
            ruleLists = await ContentBlocker.compileAll()
            applyRulesToAllTabs()
            // Rules are on the webviews — start the deferred restored-tab loads.
            rulesReady = true
            flushPendingLoads()
            // Then fetch/refresh the full filter lists and recompile.
            await ContentBlocker.ensureListsDownloaded()
            ruleLists = await ContentBlocker.compileAll()
            applyRulesToAllTabs()
        }
        // Safety valve: a full recompile (e.g. after a parser-version bump
        // invalidates the cache) can take a long time. Never hold restored tabs
        // blank for it — after a short grace period fall back to loading
        // immediately; the rules attach to the webviews when compilation finishes.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            rulesReady = true
            flushPendingLoads()
        }
    }

    /// Loads any tab URLs that were deferred while rules were still compiling.
    private func flushPendingLoads() {
        guard !pendingInitialLoads.isEmpty else { return }
        guard let tab = activeTab, let url = pendingInitialLoads.removeValue(forKey: tab.id) else { return }
        tab.load(url)
    }

    var activeTab: BrowserTab? { tabs.first { $0.id == activeID } }

    @discardableResult
    func newTab(loading url: String? = nil) -> BrowserTab {
        makeTab(url: url)
    }

    @discardableResult
    private func makeTab(url: String?, activate: Bool = true, after openerID: UUID? = nil, windowConfiguration: WKWebViewConfiguration? = nil, bridgedAppOrigin: URL? = nil) -> BrowserTab {
        let handler = TabScriptHandler()
        let configuration = windowConfiguration ?? makeConfiguration()
        // AirPlay is owned by the app's persistent casting player. Page players
        // (including popups) must not take over that selected system route.
        configuration.allowsAirPlayForMediaPlayback = false
        // WebKit may share the opener's content controller. Each tab needs its own
        // message handler without changing the supplied process pool/data store.
        if windowConfiguration != nil { configuration.userContentController = WKUserContentController() }
        let tab = BrowserTab(configuration: configuration, handler: handler, bridgedAppOrigin: bridgedAppOrigin)
        tab.configureMediaDetection(mediaDetectionSettings)
        handler.tab = tab
        tab.isActive = { [weak self, weak tab] in
            guard let self, let tab else { return false }
            return self.browserVisible && self.activeID == tab.id
        }
        tab.onPageCast = { [weak self, weak tab] payload, origin in
            var castPayload = payload
            if castPayload["title"] == nil { castPayload["title"] = tab?.title }
            self?.onPageCast?(castPayload, origin)
        }
        tab.onWebsiteCast = { [weak self, weak tab] message in
            guard let tab else { return }
            self?.onWebsiteCast?(tab, message)
        }
        tab.onPageCastInvalidated = { [weak self, weak tab] in
            guard let tab else { return }
            self?.onPageCastInvalidated?(tab)
        }
        tab.onDownload = { [weak self] download, view in self?.downloads.adopt(download, webView: view) }
        tab.onMetadataChanged = { [weak self] in self?.saveTabs() }
        tab.onBeforeLoad = { [weak self, weak tab] in
            guard let self, let tab else { return }
            self.pendingInitialLoads.removeValue(forKey: tab.id)
            self.applyRules(to: tab)
        }
        tab.onBridgedAppExternalNavigation = { [weak self, weak tab] request in
            guard let self, let tab, self.tabs.contains(where: { $0.id == tab.id }),
                  request.url != nil else { return }
            // Preserve the app's current document/history and the original request (including POST).
            let child = self.makeTab(url: nil, activate: self.browserVisible && self.activeID == tab.id, after: tab.id)
            child.load(request)
            if self.activeID == child.id { self.onBridgedAppExternalNavigation?() }
        }
        tab.onCreateWindow = { [weak self, weak tab] configuration, request in
            guard let self, let tab else { return nil }
            let child = self.makeTab(url: request.url?.absoluteString, after: tab.id, windowConfiguration: configuration)
            child.popupOpenerURL = tab.loadedWebView?.url
            child.onAdNavigationBlocked = { [weak self, weak child, weak tab] message in
                // Only discard a popup which never committed real content. In a
                // popunder/tab swap, both existing video pages remain intact.
                DispatchQueue.main.async {
                    guard let self, let child, !child.hasCommittedPage,
                          self.tabs.contains(where: { $0.id == child.id }) else { return }
                    let wasSelected = self.activeID == child.id
                    self.closeTab(child.id)
                    if let tab, self.tabs.contains(where: { $0.id == tab.id }) {
                        if let entry = child.networkLog.entries.last(where: { $0.state == "Blocked by ad rules" }) {
                            tab.networkLog.record(url: entry.url, page: entry.page, kind: "popup", state: entry.state)
                        }
                        tab.showBlockedNavigationNotice(message)
                        if wasSelected { self.select(tab.id) }
                    }
                }
            }
            return child.webView
        }
        tab.onMainFrameCommit = { [weak self, weak tab] _ in
            guard let self, let tab else { return }
            self.applyRules(to: tab)
        }
        tab.onPageFinished = { [weak self] finishedURL, title in
            guard let self else { return }
            if let finishedURL { self.data.recordVisit(url: finishedURL.absoluteString, title: title) }
            self.saveTabs()
        }
        tab.onElementPicked = { [weak self] selector, host in
            guard let self else { return }
            ContentBlocker.addUserRule(domain: host, selector: selector)
            Task { @MainActor in await self.recompileAndApply() }
        }
        tab.onResourceBlock = { [weak self] domain in
            guard let self else { return }
            ContentBlocker.addUserBlockedDomain(domain)
            // Reload so the now-blocked resource request is actually dropped.
            Task { @MainActor in await self.updateAdBlockRules() }
        }
        tab.onResourcesBlock = { [weak self] domains in
            guard let self else { return }
            domains.forEach { ContentBlocker.addUserBlockedDomain($0) }
            Task { @MainActor in await self.updateAdBlockRules() }
        }
        tab.onOpenNewTab = { [weak self, weak tab] url, background in
            guard let self, let tab else { return }
            self.makeTab(url: url.absoluteString, activate: !background, after: tab.id)
        }
        if let openerID, let index = tabs.firstIndex(where: { $0.id == openerID }) {
            tabs.insert(tab, at: index + 1)
        } else {
            tabs.append(tab)
        }
        if activate {
            activeTab?.cancelPrompt(); activeID = tab.id
            if !tab.isBridgedApp { lastBrowserTabID = tab.id }
        }
        if let url, !url.isEmpty {
            tab.urlString = url
            tab.title = URL(string: url)?.host ?? url
            tab.isHome = false
            applyRules(to: tab)
            if windowConfiguration != nil {
                // Returning the child view lets WebKit perform exactly one navigation.
            } else if activate && !isRestoring && rulesReady {
                tab.load(url)
            } else {
                pendingInitialLoads[tab.id] = url
            }
        } else {
            tab.isHome = windowConfiguration == nil
            applyRules(to: tab)
        }
        saveTabs()
        return tab
    }

    // MARK: - Tab persistence

    private struct SavedTab: Codable {
        var url: String
        var title: String
        var isHome: Bool
        var desktop: Bool
        var userAgentPreset: String? = nil
        var customUserAgent: String? = nil
        var bridgedAppOrigin: URL? = nil
    }
    private struct SavedTabs: Codable { var tabs: [SavedTab]; var activeIndex: Int }
    private struct LegacyTabs: Codable { var urls: [String]; var activeIndex: Int }

    private func restoreTabs() {
        isRestoring = true
        let saved = loadSavedTabs()
        if saved.tabs.isEmpty { makeTab(url: nil) }
        else {
            var restoredActive: BrowserTab?
            for (index, item) in saved.tabs.enumerated() {
                let app = bridgedApps.apps.first { $0.origin == item.bridgedAppOrigin }
                if let origin = item.bridgedAppOrigin {
                    guard app != nil, !tabs.contains(where: { $0.bridgedAppOrigin == origin }) else { continue }
                }
                // A fresh process starts apps at their saved home, never a stale deep link.
                // Keep restoration lazy and leave ordinary browser tabs unchanged.
                let url = app?.startURL.absoluteString ?? (item.isHome ? nil : item.url)
                let tab = makeTab(url: url, activate: false, bridgedAppOrigin: item.bridgedAppOrigin)
                if index == saved.activeIndex { restoredActive = tab }
                tab.title = app?.name ?? (item.title.isEmpty ? (URL(string: item.url)?.host ?? "New Tab") : item.title)
                tab.isDesktopMode = item.desktop
                tab.restoreUserAgent(
                    preset: BrowserUserAgentPreset(rawValue: item.userAgentPreset ?? "") ?? .automatic,
                    custom: item.customUserAgent
                )
            }
            if browserTabs.isEmpty { makeTab(url: nil, activate: false) }
            activeID = (restoredActive?.isBridgedApp == false ? restoredActive : browserTabs.first)?.id
        }
        lastBrowserTabID = activeID
        isRestoring = false
        saveTabs()
    }

    private func saveTabs() {
        guard !isRestoring else { return }
        let items = tabs.map {
            SavedTab(url: $0.urlString, title: $0.title, isHome: $0.isHome, desktop: $0.isDesktopMode,
                     userAgentPreset: $0.userAgentPreset.rawValue, customUserAgent: $0.customUserAgent,
                     bridgedAppOrigin: $0.bridgedAppOrigin)
        }
        let payload = SavedTabs(tabs: items, activeIndex: tabs.firstIndex { $0.id == activeID } ?? 0)
        if let data = try? JSONEncoder().encode(payload) { try? data.write(to: tabsFileURL, options: .atomic) }
    }

    private func loadSavedTabs() -> SavedTabs {
        guard let data = try? Data(contentsOf: tabsFileURL) else { return SavedTabs(tabs: [], activeIndex: 0) }
        if let saved = try? JSONDecoder().decode(SavedTabs.self, from: data) { return saved }
        if let legacy = try? JSONDecoder().decode(LegacyTabs.self, from: data) {
            return SavedTabs(tabs: legacy.urls.map {
                SavedTab(url: $0, title: URL(string: $0)?.host ?? $0, isHome: false, desktop: false)
            }, activeIndex: legacy.activeIndex)
        }
        return SavedTabs(tabs: [], activeIndex: 0)
    }

    /// Sites whose own anti-adblock breaks playback when their requests are blocked.
    /// Content blockers can't remove these ads anyway (that needs scriptlet injection
    /// which WKContentRuleList doesn't support), so we exempt them to keep video working.
    private static let adblockExemptSuffixes = [
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
    ]

    private func isExempt(_ url: URL?) -> Bool {
        guard let host = url?.host?.lowercased() else { return false }
        return BrowserStore.adblockExemptSuffixes.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    // MARK: - Ad blocking

    /// Toggle ad blocking for all tabs and reload the active page.
    func toggleAdBlock() {
        adBlockEnabled.toggle()
        ContentBlocker.isEnabled = adBlockEnabled
        applyRulesToAllTabs()
        activeTab?.reload()
    }

    /// Re-compiles rules and applies them to all active webviews
    @MainActor
    func updateAdBlockRules() async {
        ruleLists = await ContentBlocker.compileAll()
        applyRulesToAllTabs()
        activeTab?.reload()
    }

    /// Re-compiles + applies rules without reloading (used after an element-picker block,
    /// where the element is already hidden inline on the current page).
    @MainActor
    func recompileAndApply() async {
        ruleLists = await ContentBlocker.compileAll()
        applyRulesToAllTabs()
    }

    @MainActor func updateUserDomainRules(replacing identifier: String) async throws {
        let list = try await ContentBlocker.compileUserDomainList()
        ruleLists.removeAll { $0.identifier == identifier || ContentBlocker.isUserDomainRuleList($0) }
        if let list { ruleLists.append(list) }
        applyRulesToAllTabs()
    }

    private func applyRulesToAllTabs() { tabs.forEach { applyRules(to: $0) } }

    private func applyRules(to tab: BrowserTab) {
        let cc = tab.configuration.userContentController
        cc.removeAllContentRuleLists()
        // Skip automatic lists on anti-adblock sites (e.g. YouTube) so playback
        // survives, but honor rules the user explicitly created in the picker.
        guard adBlockEnabled else { return }
        let exempt = isExempt(URL(string: tab.urlString))
        for list in ruleLists where !exempt || ContentBlocker.isUserDomainRuleList(list) ||
            ContentBlocker.isUserCosmeticRuleList(list) {
            cc.add(list)
        }
    }

    /// Open a URL in a new tab without switching away from the current one.
    func openInBackground(_ url: String) {
        makeTab(url: url, activate: false, after: activeID)
        saveTabs()
    }

    func select(_ id: UUID) {
        guard tabs.contains(where: { $0.id == id }) else { return }
        if activeID != id { activeTab?.cancelPrompt() }
        activeID = id
        if activeTab?.isBridgedApp == false { lastBrowserTabID = id }
        // A deliberate user selection outranks the rules-ready deferral — load now.
        if let url = pendingInitialLoads.removeValue(forKey: id),
           let tab = tabs.first(where: { $0.id == id }) {
            tab.load(url)
        }
        saveTabs()
    }

    func showBrowser() {
        if let id = lastBrowserTabID, browserTabs.contains(where: { $0.id == id }) { select(id) }
        else if let tab = browserTabs.first { select(tab.id) }
        else { newTab() }
    }

    @discardableResult
    func openBridgedApp(_ app: BridgedApp) -> BrowserTab? {
        guard let app = bridgedApps.apps.first(where: { $0.origin == app.origin }) else { return nil }
        if let tab = tabs.first(where: { $0.bridgedAppOrigin == app.origin &&
            URL(string: $0.urlString).flatMap(BridgedAppDeclaration.origin(of:)) == app.origin }) {
            select(tab.id)
            return tab
        }
        let stale = Set(tabs.filter { $0.bridgedAppOrigin == app.origin }.map(\.id))
        closeTabs(stale)
        return makeTab(url: app.startURL.absoluteString, bridgedAppOrigin: app.origin)
    }

    @discardableResult
    func editBridgedApp(_ app: BridgedApp, name: String, homeURL: String) -> Bool {
        guard bridgedApps.edit(app.origin, name: name, homeURL: homeURL) else { return false }
        // Keep loaded sessions intact, but update lazy tabs so first open uses the new home.
        let edited = bridgedApps.apps.first { $0.origin == app.origin }!
        for tab in tabs where tab.bridgedAppOrigin == app.origin && tab.loadedWebView == nil {
            tab.urlString = edited.startURL.absoluteString
            tab.title = edited.name
            pendingInitialLoads[tab.id] = tab.urlString
        }
        saveTabs()
        return true
    }

    func removeBridgedApp(_ app: BridgedApp) {
        bridgedApps.remove(app.origin)
        closeTabs(Set(tabs.filter { $0.bridgedAppOrigin == app.origin }.map(\.id)))
    }

    func restoreBridgedApp(_ id: UUID) -> Bool {
        guard let tab = tabs.first(where: { $0.id == id }), let origin = tab.bridgedAppOrigin,
              bridgedApps.apps.contains(where: { $0.origin == origin }),
              URL(string: tab.urlString).flatMap(BridgedAppDeclaration.origin(of:)) == origin else { return false }
        select(id)
        return true
    }

    @discardableResult
    func duplicateTab(_ id: UUID) -> BrowserTab? {
        guard let source = browserTabs.first(where: { $0.id == id }) else { return nil }
        let duplicate = makeTab(url: source.isHome ? nil : source.urlString, activate: false, after: id)
        duplicate.title = source.title
        duplicate.isDesktopMode = source.isDesktopMode
        duplicate.restoreUserAgent(preset: source.userAgentPreset, custom: source.customUserAgent)
        saveTabs()
        return duplicate
    }

    func bookmarkTabs(_ ids: Set<UUID>) {
        for tab in browserTabs where ids.contains(tab.id) && !tab.isHome && !tab.urlString.isEmpty {
            data.addBookmark(url: tab.urlString, title: tab.title)
        }
    }

    func closeTab(_ id: UUID) { closeTabs([id]) }

    func closeTabs(_ ids: Set<UUID>) {
        let closing = tabs.filter { ids.contains($0.id) }
        guard !closing.isEmpty else { return }
        let oldActiveIndex = tabs.firstIndex { $0.id == activeID } ?? 0
        let survivors = tabs.filter { !ids.contains($0.id) }
        let next = tabs.dropFirst(oldActiveIndex).first { !ids.contains($0.id) && !$0.isBridgedApp }
            ?? survivors.last { !$0.isBridgedApp }
        for tab in closing {
            onPageCastInvalidated?(tab)
            pendingInitialLoads.removeValue(forKey: tab.id)
            tab.detector.clear()
            tab.cancelPrompt()
            tab.stopElementPicker()
            tab.stop()
        }
        tabs = survivors
        if browserTabs.isEmpty { makeTab(url: nil) }
        else if let activeID, ids.contains(activeID), let next { select(next.id) }
        saveTabs()
    }

    private func makeConfiguration() -> WKWebViewConfiguration {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.allowsInlineMediaPlayback = true
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.userContentController = WKUserContentController()
        return cfg
    }
}

/// Routes script messages to a tab without the content controller strongly retaining the tab.
final class TabScriptHandler: NSObject, WKScriptMessageHandler {
    weak var tab: BrowserTab?

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.userContentController(controller, didReceive: message) }
            return
        }
        if message.name == "moviFullscreen" {
            guard message.webView === tab?.loadedWebView, message.frameInfo.isMainFrame else { return }
            tab?.recordMoviFullscreen(message.body)
            return
        }
        if message.name == "playbackState" {
            guard message.webView === tab?.loadedWebView else { return }
            tab?.recordPlaybackState(message.body)
            return
        }
        if message.name == "networkLog" {
            guard message.webView === tab?.loadedWebView, tab?.networkCaptureEnabled == true else { return }
            tab?.networkLog.ingest(message.body, page: message.frameInfo.request.url?.absoluteString ?? "", isSubframe: !message.frameInfo.isMainFrame)
            return
        }
        guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
        switch type {
        case "detectionPolicyRequest":
            guard let tab, message.webView === tab.loadedWebView else { return }
            // Each frame uses its owning tab's policy, including cross-origin frames and BFCache restores.
            tab.registerDetectionFrame(message.frameInfo)
        case "pageCastRequest":
            guard let tab, message.webView === tab.loadedWebView, message.frameInfo.isMainFrame,
                  let frameOrigin = BrowserSitePolicy.origin(BrowserPopupInteraction.originURL(message.frameInfo)),
                  frameOrigin == tab.pageCastOrigin else { return }
            tab.onWebsiteCast?(body)
        case "mediaLifecycle":
            if tab?.detectionEnabled == true, message.frameInfo.isMainFrame { tab?.detector.beginMediaLifecycle() }
        case "video":
            guard message.webView === tab?.loadedWebView, tab?.acceptsDetection(body) == true else { return }
            tab?.detector.ingest(body)
        case "cast":
            guard message.webView === tab?.loadedWebView, message.frameInfo.isMainFrame,
                  BrowserSitePolicy.origin(BrowserPopupInteraction.originURL(message.frameInfo)) == tab?.pageCastOrigin,
                  let payload = body["payload"] as? [String: Any],
                  let source = message.frameInfo.request.url else { return }
            do {
                try PageCastRequest.rejectSenderOnlyFields(body)
                try PageCastRequest.rejectSenderOnlyFields(payload)
            } catch { return }
            tab?.requestPageCast(payload, source: source)
        case "pickerState":
            if message.frameInfo.isMainFrame, body["active"] as? Bool == false { tab?.pickerDidFinish() }
        case "pickerSelection":
            guard message.frameInfo.isMainFrame, let selector = body["selector"] as? String else { return }
            tab?.pickerDidSelect(selector, hasSource: body["hasSource"] as? Bool == true)
        case "pickedElement":
            guard tab?.isPickingElement == true, message.frameInfo.isMainFrame,
                  let host = message.frameInfo.request.url?.host else { return }
            if let selector = body["selector"] as? String, !selector.isEmpty {
                tab?.onElementPicked?(selector, host)
            }
        case "pickedResource":
            guard tab?.isPickingElement == true, message.frameInfo.isMainFrame else { return }
            if let host = body["host"] as? String, !host.isEmpty {
                tab?.onResourceBlock?(host)
            }
        case "pickedResources":
            guard tab?.isPickingElement == true, message.frameInfo.isMainFrame else { return }
            if let hosts = body["hosts"] as? [String], !hosts.isEmpty {
                tab?.onResourcesBlock?(hosts)
            }
        default:
            break
        }
    }
}
