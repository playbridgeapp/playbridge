import Foundation
import WebKit
import Combine

/// One browser tab: owns a persistent `WKWebView`, publishes navigation state, and routes detection
/// messages to its `VideoDetector`. Rough analogue of an entry in the Android `TabManager`.
final class BrowserTab: NSObject, ObservableObject, Identifiable, WKNavigationDelegate, WKUIDelegate {
    let id = UUID()

    let webView: WKWebView
    let detector = VideoDetector()

    @Published var urlString: String = ""
    @Published var title: String = "New Tab"
    @Published var isLoading: Bool = false
    @Published var progress: Double = 0
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false
    @Published var isDesktopMode: Bool = false
    @Published var blockedAdMessage: String? = nil

    private var pageLoaded = false

    /// Invoked when the page calls `window.playbridge.cast(payload)`.
    var onPageCast: (([String: Any]) -> Void)?

    private var observations: [NSKeyValueObservation] = []
    private var cancellables = Set<AnyCancellable>()

    init(configuration: WKWebViewConfiguration, handler: WKScriptMessageHandler) {
        // Each tab installs the detection script + message handler into its own content controller,
        // so detections are attributed to this tab.
        let cc = configuration.userContentController
        cc.removeAllUserScripts()
        cc.addUserScript(WKUserScript(source: DetectionScript.source,
                                      injectionTime: .atDocumentStart,
                                      forMainFrameOnly: false))
        cc.add(handler, name: "playbridge")

        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.customUserAgent = VideoDetector.mediaHeaders(for: DetectedVideo(
            url: "", detectedBy: "", headers: [:], kind: .other))["User-Agent"]

        observe()
        // Surface detector changes (new videos) on the tab so views observing the tab refresh.
        detector.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    private func observe() {
        observations = [
            webView.observe(\.estimatedProgress, options: [.new]) { [weak self] wv, _ in
                self?.progress = wv.estimatedProgress
            },
            webView.observe(\.isLoading, options: [.new]) { [weak self] wv, _ in
                self?.isLoading = wv.isLoading
            },
            webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
                if let t = wv.title, !t.isEmpty { self?.title = t }
            },
            webView.observe(\.url, options: [.new]) { [weak self] wv, _ in
                if let u = wv.url?.absoluteString { self?.urlString = u }
            },
            webView.observe(\.canGoBack, options: [.new]) { [weak self] wv, _ in
                self?.canGoBack = wv.canGoBack
            },
            webView.observe(\.canGoForward, options: [.new]) { [weak self] wv, _ in
                self?.canGoForward = wv.canGoForward
            },
        ]
    }

    // MARK: - Navigation

    /// Load a URL, or run a Google search when the text isn't a URL.
    func load(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let target = BrowserTab.resolveInput(trimmed)
        if let url = URL(string: target) { webView.load(URLRequest(url: url)) }
    }

    func goBack() { webView.goBack() }
    func goForward() { webView.goForward() }
    func reload() { webView.reload() }
    func stop() { webView.stopLoading() }

    func toggleDesktopMode() {
        isDesktopMode.toggle()
        if isDesktopMode {
            webView.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15"
            webView.configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        } else {
            webView.customUserAgent = VideoDetector.mediaHeaders(for: DetectedVideo(
                url: "", detectedBy: "", headers: [:], kind: .other))["User-Agent"]
            webView.configuration.defaultWebpagePreferences.preferredContentMode = .mobile
        }
        webView.reload()
    }

    /// Turn an address-bar entry into a URL (add scheme) or a Google search query.
    static func resolveInput(_ text: String) -> String {
        if text.hasPrefix("http://") || text.hasPrefix("https://") { return text }
        // Looks like a domain (has a dot, no spaces) → assume https.
        if text.contains("."), !text.contains(" ") {
            return "https://\(text)"
        }
        let q = text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? text
        return "https://www.google.com/search?q=\(q)"
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        pageLoaded = false
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        // New main-frame document — reset detections for this tab.
        detector.clear()
        pageLoaded = false
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pageLoaded = true
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        pageLoaded = true
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        pageLoaded = true
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let isMainFrame = navigationAction.targetFrame?.isMainFrame ?? false
        let isUserGesture = navigationAction.navigationType == .linkActivated ||
                            navigationAction.navigationType == .formSubmitted ||
                            navigationAction.navigationType == .backForward ||
                            navigationAction.navigationType == .reload
        
        if isMainFrame && !isUserGesture && pageLoaded {
            if let destURL = navigationAction.request.url,
               let currentURL = webView.url,
               let destHost = destURL.host,
               let currentHost = currentURL.host,
               destHost != currentHost {
                // Block cross-site automatic redirect after page load has completed
                DispatchQueue.main.async {
                    self.blockedAdMessage = "Redirect ad blocked"
                }
                decisionHandler(.cancel)
                return
            }
        }
        
        decisionHandler(.allow)
    }

    // MARK: - WKUIDelegate

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            let isUserGesture = navigationAction.navigationType == .linkActivated ||
                                navigationAction.navigationType == .formSubmitted
            if !isUserGesture {
                // Popup ad blocked!
                DispatchQueue.main.async {
                    self.blockedAdMessage = "Popup ad blocked"
                }
                return nil
            } else {
                // Legitimate link click opening in a new window/tab.
                // We navigate the current tab's webView to the URL instead of opening a new window.
                webView.load(navigationAction.request)
            }
        }
        return nil
    }
}
