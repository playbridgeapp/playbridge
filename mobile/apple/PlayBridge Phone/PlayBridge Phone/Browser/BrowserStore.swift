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

    init() {
        newTab()
        Task { @MainActor in
            // Compile whatever is already cached so blocking is active immediately
            // (curated fallback on first launch).
            ruleLists = await ContentBlocker.compileAll()
            applyRulesToAllTabs()
            // Then fetch/refresh the full filter lists in the background and
            // recompile, upgrading from the curated fallback to EasyList et al.
            await ContentBlocker.ensureListsDownloaded()
            ruleLists = await ContentBlocker.compileAll()
            applyRulesToAllTabs()
        }
    }

    var activeTab: BrowserTab? { tabs.first { $0.id == activeID } }

    @discardableResult
    func newTab(loading url: String? = nil) -> BrowserTab {
        let handler = TabScriptHandler()
        let tab = BrowserTab(configuration: makeConfiguration(), handler: handler)
        handler.tab = tab
        tab.onPageCast = { [weak self] payload in self?.onPageCast?(payload) }
        tabs.append(tab)
        activeID = tab.id
        applyRules(to: tab)
        tab.load(url ?? BrowserStore.homeURL)
        return tab
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

    private func applyRulesToAllTabs() { tabs.forEach { applyRules(to: $0) } }

    private func applyRules(to tab: BrowserTab) {
        let cc = tab.webView.configuration.userContentController
        cc.removeAllContentRuleLists()
        if adBlockEnabled {
            for list in ruleLists {
                cc.add(list)
            }
        }
    }

    func select(_ id: UUID) { activeID = id }

    func closeTab(_ id: UUID) {
        guard let idx = tabs.firstIndex(where: { $0.id == id }) else { return }
        tabs[idx].webView.stopLoading()
        tabs.remove(at: idx)
        if tabs.isEmpty {
            newTab()
        } else if activeID == id {
            activeID = tabs[min(idx, tabs.count - 1)].id
        }
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
        default:
            break
        }
    }
}
