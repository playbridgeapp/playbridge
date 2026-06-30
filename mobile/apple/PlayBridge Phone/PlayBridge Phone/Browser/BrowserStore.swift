import Foundation
import WebKit

/// Owns the set of browser tabs and the active selection. Tabs share a `WKProcessPool` +
/// default data store so cookies/logins persist across tabs (like a normal browser).
final class BrowserStore: ObservableObject {
    @Published private(set) var tabs: [BrowserTab] = []
    @Published var activeID: UUID?

    /// Forwarded when any tab's page calls `window.playbridge.cast(...)`.
    var onPageCast: (([String: Any]) -> Void)?

    @Published var adBlockEnabled: Bool = ContentBlocker.isEnabled
    private var ruleLists: [WKContentRuleList] = []

    static let homeURL = "https://www.google.com"

    /// History + bookmarks, shared with the browser UI via the environment.
    let data = BrowserDataStore()

    private var isRestoring = false
    private let tabsFileURL: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("browser_tabs.json")
    }()

    init() {
        restoreTabs()
        Task { @MainActor in
            // Compile cached rules so blocking is active immediately (curated fallback).
            ruleLists = await ContentBlocker.compileAll()
            applyRulesToAllTabs()
            // Then fetch/refresh the full filter lists and recompile.
            await ContentBlocker.ensureListsDownloaded()
            ruleLists = await ContentBlocker.compileAll()
            applyRulesToAllTabs()
        }
    }

    var activeTab: BrowserTab? { tabs.first { $0.id == activeID } }

    @discardableResult
    func newTab(loading url: String? = nil) -> BrowserTab {
        makeTab(url: url)
    }

    @discardableResult
    private func makeTab(url: String?) -> BrowserTab {
        let handler = TabScriptHandler()
        let tab = BrowserTab(configuration: makeConfiguration(), handler: handler)
        handler.tab = tab
        tab.onPageCast = { [weak self] payload in self?.onPageCast?(payload) }
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
        tab.onOpenNewTab = { [weak self] url, background in
            guard let self else { return }
            if background { self.openInBackground(url.absoluteString) }
            else { self.newTab(loading: url.absoluteString) }
        }
        tabs.append(tab)
        activeID = tab.id
        applyRules(to: tab)
        if let url, !url.isEmpty {
            tab.load(url)
        } else {
            tab.isHome = true   // show the new-tab/home page until the user navigates
        }
        saveTabs()
        return tab
    }

    // MARK: - Tab persistence

    private struct SavedTabs: Codable { var urls: [String]; var activeIndex: Int }

    private func restoreTabs() {
        isRestoring = true
        let saved = loadSavedTabs()
        if saved.urls.isEmpty {
            makeTab(url: nil)
        } else {
            for u in saved.urls { makeTab(url: u) }
            if tabs.indices.contains(saved.activeIndex) {
                activeID = tabs[saved.activeIndex].id
            }
        }
        isRestoring = false
        saveTabs()
    }

    private func saveTabs() {
        guard !isRestoring else { return }
        var urls: [String] = []
        var activeIndex = 0
        for tab in tabs {
            let u = tab.urlString
            guard u.hasPrefix("http") else { continue }
            if tab.id == activeID { activeIndex = urls.count }
            urls.append(u)
        }
        let payload = SavedTabs(urls: urls, activeIndex: activeIndex)
        if let d = try? JSONEncoder().encode(payload) { try? d.write(to: tabsFileURL, options: .atomic) }
    }

    private func loadSavedTabs() -> SavedTabs {
        guard let d = try? Data(contentsOf: tabsFileURL),
              let s = try? JSONDecoder().decode(SavedTabs.self, from: d) else {
            return SavedTabs(urls: [], activeIndex: 0)
        }
        return s
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

    private func applyRulesToAllTabs() { tabs.forEach { applyRules(to: $0) } }

    private func applyRules(to tab: BrowserTab) {
        let cc = tab.webView.configuration.userContentController
        cc.removeAllContentRuleLists()
        // Skip blocking on anti-adblock sites (e.g. YouTube) so playback isn't broken.
        guard adBlockEnabled, !isExempt(tab.webView.url) else { return }
        for list in ruleLists {
            cc.add(list)
        }
    }

    /// Open a URL in a new tab without switching away from the current one.
    func openInBackground(_ url: String) {
        let previous = activeID
        makeTab(url: url)
        if let previous { activeID = previous }
        saveTabs()
    }

    func select(_ id: UUID) { activeID = id; saveTabs() }

    func closeTab(_ id: UUID) {
        guard let idx = tabs.firstIndex(where: { $0.id == id }) else { return }
        tabs[idx].webView.stopLoading()
        tabs.remove(at: idx)
        if tabs.isEmpty {
            makeTab(url: nil)
        } else if activeID == id {
            activeID = tabs[min(idx, tabs.count - 1)].id
        }
        saveTabs()
    }

    private func makeConfiguration() -> WKWebViewConfiguration {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.userContentController = WKUserContentController()
        return cfg
    }
}

/// Routes script messages to a tab without the content controller strongly retaining the tab.
final class TabScriptHandler: NSObject, WKScriptMessageHandler {
    weak var tab: BrowserTab?

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
        switch type {
        case "video":
            tab?.detector.ingest(body)
        case "cast":
            if let payload = body["payload"] as? [String: Any] { tab?.onPageCast?(payload) }
        case "pickedElement":
            if let selector = body["selector"] as? String, !selector.isEmpty {
                tab?.onElementPicked?(selector, (body["host"] as? String) ?? "")
            }
        case "pickedResource":
            if let host = body["host"] as? String, !host.isEmpty {
                tab?.onResourceBlock?(host)
            }
        case "pickedResources":
            if let hosts = body["hosts"] as? [String], !hosts.isEmpty {
                tab?.onResourcesBlock?(hosts)
            }
        default:
            break
        }
    }
}
