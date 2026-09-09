import Foundation
import WebKit

/// Owns the set of browser tabs and the active selection. Tabs share a `WKProcessPool` +
/// default data store so cookies/logins persist across tabs (like a normal browser).
final class BrowserStore: ObservableObject {
    @Published private(set) var tabs: [BrowserTab] = []
    @Published private(set) var activeID: UUID?

    /// Forwarded when any tab's page calls `window.playbridge.cast(...)`.
    var onPageCast: (([String: Any], String) -> Void)?
    let downloads = BrowserDownloads()
    var browserVisible = false {
        didSet { if !browserVisible { activeTab?.cancelPrompt() } }
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

    init(tabsFileURL: URL? = nil) {
        self.tabsFileURL = tabsFileURL ?? Self.defaultTabsFileURL()
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
    private func makeTab(url: String?, activate: Bool = true, after openerID: UUID? = nil, windowConfiguration: WKWebViewConfiguration? = nil) -> BrowserTab {
        let handler = TabScriptHandler()
        let configuration = windowConfiguration ?? makeConfiguration()
        // WebKit may share the opener's content controller. Each tab needs its own
        // message handler without changing the supplied process pool/data store.
        if windowConfiguration != nil { configuration.userContentController = WKUserContentController() }
        let tab = BrowserTab(configuration: configuration, handler: handler)
        handler.tab = tab
        tab.isActive = { [weak self, weak tab] in
            guard let self, let tab else { return false }
            return self.browserVisible && self.activeID == tab.id
        }
        tab.onPageCast = { [weak self] payload, origin in self?.onPageCast?(payload, origin) }
        tab.onDownload = { [weak self] download, view in self?.downloads.adopt(download, webView: view) }
        tab.onMetadataChanged = { [weak self] in self?.saveTabs() }
        tab.onBeforeLoad = { [weak self, weak tab] in
            guard let self, let tab else { return }
            self.pendingInitialLoads.removeValue(forKey: tab.id)
            self.applyRules(to: tab)
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
        if activate { activeTab?.cancelPrompt(); activeID = tab.id }
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
    }
    private struct SavedTabs: Codable { var tabs: [SavedTab]; var activeIndex: Int }
    private struct LegacyTabs: Codable { var urls: [String]; var activeIndex: Int }

    private func restoreTabs() {
        isRestoring = true
        let saved = loadSavedTabs()
        if saved.tabs.isEmpty { makeTab(url: nil) }
        else {
            for item in saved.tabs {
                let tab = makeTab(url: item.isHome ? nil : item.url, activate: false)
                tab.title = item.title.isEmpty ? (URL(string: item.url)?.host ?? "New Tab") : item.title
                tab.isDesktopMode = item.desktop
            }
            activeID = tabs[tabs.indices.contains(saved.activeIndex) ? saved.activeIndex : 0].id
        }
        isRestoring = false
        saveTabs()
    }

    private func saveTabs() {
        guard !isRestoring else { return }
        let items = tabs.map { SavedTab(url: $0.urlString, title: $0.title, isHome: $0.isHome, desktop: $0.isDesktopMode) }
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
        // Skip blocking on anti-adblock sites (e.g. YouTube) so playback isn't broken.
        guard adBlockEnabled else { return }
        let exempt = isExempt(URL(string: tab.urlString))
        for list in ruleLists where !exempt || ContentBlocker.isUserDomainRuleList(list) {
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
        // A deliberate user selection outranks the rules-ready deferral — load now.
        if let url = pendingInitialLoads.removeValue(forKey: id),
           let tab = tabs.first(where: { $0.id == id }) {
            tab.load(url)
        }
        saveTabs()
    }

    @discardableResult
    func duplicateTab(_ id: UUID) -> BrowserTab? {
        guard let source = tabs.first(where: { $0.id == id }) else { return nil }
        let duplicate = makeTab(url: source.isHome ? nil : source.urlString, activate: false, after: id)
        duplicate.title = source.title
        duplicate.isDesktopMode = source.isDesktopMode
        saveTabs()
        return duplicate
    }

    func bookmarkTabs(_ ids: Set<UUID>) {
        for tab in tabs where ids.contains(tab.id) && !tab.isHome && !tab.urlString.isEmpty {
            data.addBookmark(url: tab.urlString, title: tab.title)
        }
    }

    func closeTab(_ id: UUID) { closeTabs([id]) }

    func closeTabs(_ ids: Set<UUID>) {
        let closing = tabs.filter { ids.contains($0.id) }
        guard !closing.isEmpty else { return }
        let oldActiveIndex = tabs.firstIndex { $0.id == activeID } ?? 0
        let survivors = tabs.filter { !ids.contains($0.id) }
        let next = tabs.dropFirst(oldActiveIndex).first { !ids.contains($0.id) } ?? survivors.last
        for tab in closing {
            pendingInitialLoads.removeValue(forKey: tab.id)
            tab.detector.clear()
            tab.cancelPrompt()
            tab.stopElementPicker()
            tab.stop()
        }
        tabs = survivors
        if tabs.isEmpty { makeTab(url: nil) }
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
        if message.name == "playbackState" {
            guard message.webView === tab?.loadedWebView else { return }
            tab?.recordPlaybackState(message.body)
            return
        }
        if message.name == "networkLog" {
            guard message.webView === tab?.loadedWebView else { return }
            tab?.networkLog.ingest(message.body, page: message.frameInfo.request.url?.absoluteString ?? "", isSubframe: !message.frameInfo.isMainFrame)
            return
        }
        guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
        switch type {
        case "mediaLifecycle":
            if message.frameInfo.isMainFrame { tab?.detector.beginMediaLifecycle() }
        case "video":
            tab?.detector.ingest(body)
        case "cast":
            guard message.frameInfo.isMainFrame, let payload = body["payload"] as? [String: Any],
                  let source = message.frameInfo.request.url else { return }
            tab?.requestPageCast(payload, source: source)
        case "pickerState":
            if message.frameInfo.isMainFrame, body["active"] as? Bool == false { tab?.pickerDidFinish() }
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
