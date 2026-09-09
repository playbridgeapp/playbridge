import Foundation
import WebKit
import Combine
import UIKit

/// One browser tab: lazily owns a persistent `WKWebView`, publishes navigation state, and routes detection
/// messages to its `VideoDetector`. Rough analogue of an entry in the Android `TabManager`.
final class BrowserTab: NSObject, ObservableObject, Identifiable, WKNavigationDelegate, WKUIDelegate {
    let id = UUID()

    private(set) var loadedWebView: WKWebView?
    let configuration: WKWebViewConfiguration
    var webView: WKWebView {
        if let loadedWebView { return loadedWebView }
        configuration.defaultWebpagePreferences.preferredContentMode = isDesktopMode ? .desktop : .mobile
        let view = WKWebView(frame: .zero, configuration: configuration)
        loadedWebView = view
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.isFindInteractionEnabled = true
        view.customUserAgent = isDesktopMode ? Self.desktopUA : nil
        observe()
        return view
    }
    let detector = VideoDetector()
    let networkLog = BrowserNetworkLog()

    @Published var urlString: String = ""
    @Published var title: String = "New Tab"
    @Published private(set) var isMediaPlaying = false
    private var playbackState = BrowserPlaybackState()

    func recordPlaybackState(_ body: Any) {
        guard let body = body as? [String: Any], let frame = body["frame"] as? String,
              frame.count <= 100, let playing = body["playing"] as? Bool else { return }
        playbackState.update(frame: frame, playing: playing, now: ProcessInfo.processInfo.systemUptime)
        refreshPlaybackState()
    }

    func refreshPlaybackState() {
        let playing = playbackState.isPlaying(now: ProcessInfo.processInfo.systemUptime)
        if isMediaPlaying != playing { isMediaPlaying = playing }
    }

    @Published var isLoading: Bool = false
    @Published var progress: Double = 0
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false
    @Published var isDesktopMode: Bool = false
    @Published var blockedAdMessage: String? = nil {
        didSet {
            if blockedAdMessage == nil { noticeDismissal?.cancel(); noticeDismissal = nil }
            else if noticeDismissal == nil {
                // Repeated messages share the original deadline; they never extend it.
                noticeDismissal = Task { @MainActor [weak self] in
                    do { try await Task.sleep(nanoseconds: 2_500_000_000) } catch { return }
                    self?.blockedAdMessage = nil
                }
            }
        }
    }
    private var noticeDismissal: Task<Void, Never>?
    private var lastNavigationNotice = -Double.infinity

    func showBlockedNavigationNotice(_ message: String) {
        let now = ProcessInfo.processInfo.systemUptime
        guard blockedAdMessage == nil, now - lastNavigationNotice >= 8 else { return }
        lastNavigationNotice = now
        blockedAdMessage = message
    }
    /// True for a fresh tab showing the home/new-tab page (no page loaded yet).
    @Published var isHome: Bool = false

    @Published var navigationFailure: BrowserNavigationFailure?
    @Published var prompt: BrowserPrompt?
    @Published var popupBlocked = false
    @Published private(set) var blockedPopupOrigin: URL?
    @Published private(set) var isPickingElement = false
    private var pickerGeneration = UUID()
    var isActive: () -> Bool = { false }
    var onDownload: ((WKDownload, WKWebView) -> Void)?
    var onMetadataChanged: (() -> Void)?
    private var requestedAddress = ""
    private var documentID = UUID()
    private let popupInteraction = BrowserPopupInteraction()
    var popupOpenerURL: URL?
    private(set) var hasCommittedPage = false
    private var committedPageURL: URL?
    var onAdNavigationBlocked: ((String) -> Void)?

    @discardableResult
    private func blockAdNavigation(_ url: URL, source: URL?, popup: Bool) -> Bool {
        guard ContentBlocker.shouldBlockNavigation(url, source: source, popup: popup) else { return false }
        networkLog.record(url: url.absoluteString, page: source?.absoluteString ?? urlString, kind: "navigation", state: "Blocked by ad rules")
        let message = "Blocked ad navigation to \(url.host ?? "advertising site")."
        showBlockedNavigationNotice(message)
        if let committedPageURL { urlString = committedPageURL.absoluteString }
        onAdNavigationBlocked?(message)
        return true
    }

    func cancelPrompt() {
        popupInteraction.clear()
        let pending = prompt
        prompt = nil
        pending?.finish(false)
    }
    func present(_ request: BrowserPrompt) {
        guard isActive(), prompt == nil else { request.finish(false); return }
        prompt = request
    }
    func requestPageCast(_ payload: [String: Any], source: URL) {
        guard isActive(), BrowserSitePolicy.origin(source) != nil,
              BrowserSitePolicy.origin(source) == BrowserSitePolicy.origin(loadedWebView?.url),
              let raw = payload["url"] as? String, let target = URL(string: raw),
              BrowserSitePolicy.origin(target) != nil else { return }
        let generation = documentID
        // Show the actual destination host, including local-network destinations.
        present(BrowserPrompt(title: "Allow website to cast?",
            message: "\(BrowserSitePolicy.origin(source) ?? "Website") wants to play media from \(BrowserSitePolicy.origin(target) ?? "another server") on your connected device.",
            acceptLabel: "Cast") { [weak self] accepted, _ in
                guard let self, accepted, self.isActive(), self.documentID == generation else { return }
                self.onPageCast?(payload, source.absoluteString)
            })
    }
    func allowPopupsForSite() {
        BrowserSitePolicy.setPopupsAllowed(true, url: blockedPopupOrigin)
        popupBlocked = false
        blockedAdMessage = "Popups allowed for this site. Try the link again."
    }

    /// Invoked when the page calls `window.playbridge.cast(payload)`.
    var onPageCast: (([String: Any], String) -> Void)?

    /// Invoked when a new main-frame document commits, so the owner can re-evaluate
    /// per-site ad blocking (e.g. exempting YouTube whose anti-adblock breaks playback).
    var onMainFrameCommit: ((URL?) -> Void)?

    /// Invoked when a page finishes loading — used to record history + persist tabs.
    var onPageFinished: ((URL?, String?) -> Void)?

    /// Invoked when the user picks an element to block: (selector, host).
    var onElementPicked: ((String, String) -> Void)?

    /// Invoked when the user blocks a resource source domain from the picker.
    var onResourceBlock: ((String) -> Void)?

    /// Invoked when the user blocks all detected source domains at once.
    var onResourcesBlock: (([String]) -> Void)?

    /// Invoked from the long-press link menu: (url, openInBackground).
    var onOpenNewTab: ((URL, Bool) -> Void)?
    var onCreateWindow: ((WKWebViewConfiguration, URLRequest) -> WKWebView?)?
    var onBeforeLoad: (() -> Void)?

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
        cc.add(handler, name: "networkLog")
        cc.add(handler, contentWorld: BrowserPlaybackScript.world, name: "playbackState")
        cc.addUserScript(WKUserScript(source: BrowserPlaybackScript.source,
            injectionTime: .atDocumentStart, forMainFrameOnly: false, in: BrowserPlaybackScript.world))
        cc.addUserScript(WKUserScript(source: BrowserNetworkScript.source, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        cc.addUserScript(WKUserScript(source: BrowserPopupInteraction.source,
            injectionTime: .atDocumentStart, forMainFrameOnly: false,
            in: BrowserPopupInteraction.world))

        self.configuration = configuration
        super.init()
        popupInteraction.tab = self
        cc.add(popupInteraction, contentWorld: BrowserPopupInteraction.world, name: "popupInteraction")
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
        cancelPrompt()
        stopElementPicker()
        navigationFailure = nil
        isHome = false
        let target = BrowserTab.resolveInput(trimmed)
        urlString = target
        requestedAddress = target
        onBeforeLoad?()
        if let url = URL(string: target) { webView.load(URLRequest(url: url)) }
    }

    /// Present the system Find-in-page bar for the current page.
    func findInPage() {
        webView.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    /// Enter the uBlock-style element picker on the current page.
    func startElementPicker() {
        guard !isHome else { return }
        popupInteraction.clear()
        pickerGeneration = UUID()
        let generation = pickerGeneration
        isPickingElement = true
        webView.evaluateJavaScript(ContentBlocker.elementPickerJS) { [weak self] _, error in
            guard let self, self.pickerGeneration == generation, error != nil else { return }
            self.isPickingElement = false
            self.blockedAdMessage = "Couldn’t open the element picker on this page."
        }
    }
    func pickerDidFinish() {
        let generation = pickerGeneration
        // Keep WebKit navigation guarded through the synthetic click after touchend.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            guard let self, self.pickerGeneration == generation else { return }
            self.isPickingElement = false
        }
    }
    func stopElementPicker() {
        guard isPickingElement else { return }
        pickerGeneration = UUID()
        isPickingElement = false
        loadedWebView?.evaluateJavaScript("if(window.__pb_picker_cleanup) window.__pb_picker_cleanup();", completionHandler: nil)
    }

    func goBack() { stopElementPicker(); cancelPrompt(); navigationFailure = nil; webView.goBack() }
    func goForward() { stopElementPicker(); cancelPrompt(); navigationFailure = nil; webView.goForward() }
    func reload() {
        stopElementPicker()
        if let failure = navigationFailure { load(failure.address) }
        else { cancelPrompt(); webView.reload() }
    }
    func stop() { loadedWebView?.stopLoading() }

    static let desktopUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15"

    /// Kept as a safety net: force desktop on YouTube if its mobile player still
    /// misbehaves with the default UA.
    static func isYouTube(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return host == "youtube.com" || host.hasSuffix(".youtube.com")
            || host == "youtu.be" || host.hasSuffix(".youtu.be")
            || host.hasSuffix("youtube-nocookie.com")
    }

    func toggleDesktopMode() {
        isDesktopMode.toggle()
        webView.customUserAgent = isDesktopMode ? BrowserTab.desktopUA : nil
        webView.configuration.defaultWebpagePreferences.preferredContentMode = isDesktopMode ? .desktop : .mobile
        onMetadataChanged?()
        webView.reload()
    }

    /// Turn an address-bar entry into a URL (add scheme) or a Google search query.
    static func resolveInput(_ text: String) -> String {
        if text.hasPrefix("http://") || text.hasPrefix("https://") { return text }
        // Looks like a domain (has a dot, no spaces) → assume https.
        if text.contains("."), !text.contains(" ") {
            return "https://\(text)"
        }
        return SearchEngine.current.searchURL(text)
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        navigationFailure = nil
        popupBlocked = false
        blockedPopupOrigin = nil
        cancelPrompt()
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        documentID = UUID()
        playbackState = BrowserPlaybackState()
        isMediaPlaying = false
        if let url = webView.url, url.scheme != "about" {
            hasCommittedPage = true
            committedPageURL = url
        }
        // New main-frame document — reset detections for this tab.
        detector.clear()
        navigationFailure = nil
        popupBlocked = false
        blockedPopupOrigin = nil
        cancelPrompt()
        onMainFrameCommit?(webView.url)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        navigationFailure = nil
        onPageFinished?(webView.url, webView.title)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        recordFailure(error)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        recordFailure(error)
    }
    func recordFailure(_ error: Error) {
        let error = error as NSError
        // WebKit uses 102 when an intentional policy decision interrupts navigation.
        guard !(error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled),
              !(error.domain == WKError.errorDomain && error.code == 102),
              !(error.domain == "WebKitErrorDomain" && error.code == 102) else { return }
        let address = (error.userInfo[NSURLErrorFailingURLErrorKey] as? URL)?.absoluteString
            ?? (error.userInfo[NSURLErrorFailingURLStringErrorKey] as? String)
            ?? (requestedAddress.isEmpty ? urlString : requestedAddress)
        let message: String
        switch error.code {
        case NSURLErrorNotConnectedToInternet: message = "You’re offline. Check your connection and try again."
        case NSURLErrorTimedOut: message = "The website took too long to respond."
        case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed: message = "The website’s address could not be found."
        case NSURLErrorServerCertificateUntrusted, NSURLErrorSecureConnectionFailed,
             NSURLErrorServerCertificateHasBadDate, NSURLErrorServerCertificateHasUnknownRoot:
            message = "A secure connection to this website could not be established."
        default: message = "This page could not be loaded. Check the address or try again."
        }
        navigationFailure = BrowserNavigationFailure(address: address, message: message)
        isLoading = false
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        isPickingElement = false
        pickerGeneration = UUID()
        cancelPrompt()
        detector.clear()
        navigationFailure = BrowserNavigationFailure(address: webView.url?.absoluteString ?? urlString,
            message: "This page stopped responding. Reload it to continue.")
        isLoading = false
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard !isPickingElement else { decisionHandler(.cancel); return }
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if action.targetFrame == nil || action.targetFrame?.isMainFrame == true {
            let source = committedPageURL ?? popupOpenerURL ?? action.sourceFrame.request.url
            if blockAdNavigation(url, source: source, popup: action.targetFrame == nil || (popupOpenerURL != nil && !hasCommittedPage)) {
                decisionHandler(.cancel)
                return
            }
        }
        networkLog.record(url: url.absoluteString, page: action.sourceFrame.request.url?.absoluteString ?? urlString,
            kind: action.targetFrame == nil ? "popup" : (action.targetFrame?.isMainFrame == false ? "iframe navigation" : "navigation"), method: action.request.httpMethod ?? "GET", state: "Requested")
        let scheme = url.scheme?.lowercased() ?? ""
        if !["http", "https", "about", "blob", "data"].contains(scheme) {
            decisionHandler(.cancel)
            guard action.targetFrame == nil || action.targetFrame?.isMainFrame == true else { return }
            guard !["javascript", "file", "", "intent"].contains(scheme) else {
                blockedAdMessage = "This type of link cannot be opened on iPhone."
                return
            }
            present(BrowserPrompt(title: "Open another app?", message: "Open \(url.absoluteString) outside PlayBridge?", acceptLabel: "Open") { [weak self] accepted, _ in
                guard accepted else { return }
                UIApplication.shared.open(url, options: [:]) { success in
                    if !success { self?.blockedAdMessage = "No app could open this link." }
                }
            })
            return
        }
        if action.shouldPerformDownload {
            present(BrowserPrompt(title: "Download file?", message: "Save this file to PlayBridge Downloads?", acceptLabel: "Download") { accepted, _ in
                decisionHandler(accepted ? .download : .cancel)
            })
            return
        }
        if action.targetFrame?.isMainFrame == true { requestedAddress = url.absoluteString }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        // Also inspect the final response: server-side redirects do not always
        // result in another navigation-action callback before the document commits.
        if response.isForMainFrame, let url = response.response.url,
           blockAdNavigation(url, source: committedPageURL ?? popupOpenerURL,
                             popup: popupOpenerURL != nil && !hasCommittedPage) {
            decisionHandler(.cancel)
            return
        }
        if let url = response.response.url {
            networkLog.record(url: url.absoluteString, page: urlString, kind: "navigation response", state: "Response", status: (response.response as? HTTPURLResponse)?.statusCode)
        }
        let attachment = (response.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition")?.lowercased().hasPrefix("attachment") == true
        guard attachment || !response.canShowMIMEType else { decisionHandler(.allow); return }
        present(BrowserPrompt(title: "Download file?", message: "This file can be saved to Downloads and exported to Files.", acceptLabel: "Download") { accepted, _ in
            decisionHandler(accepted ? .download : .cancel)
        })
    }
    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        onDownload?(download, webView)
    }
    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        onDownload?(download, webView)
    }

    // MARK: - WKUIDelegate

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            guard !isPickingElement else { return nil }
            guard isActive() else { return nil }
            if let url = navigationAction.request.url,
               blockAdNavigation(url, source: navigationAction.sourceFrame.request.url, popup: true) { return nil }
            let interacted = popupInteraction.consume(for: navigationAction.sourceFrame)
            let allowed = BrowserSitePolicy.popupsAllowed(BrowserPopupInteraction.originURL(navigationAction.sourceFrame))
            guard interacted || allowed else {
                if let url = navigationAction.request.url {
                    networkLog.record(url: url.absoluteString, page: navigationAction.sourceFrame.request.url?.absoluteString ?? urlString, kind: "popup", state: "Blocked by popup policy")
                }
                blockedPopupOrigin = BrowserPopupInteraction.originURL(navigationAction.sourceFrame)
                popupBlocked = true
                return nil
            }
            // WebKit loads the original request, preserving POST and window ownership.
            return onCreateWindow?(configuration, navigationAction.request)
        }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        present(BrowserPrompt(title: frame.request.url?.host ?? "Website", message: message, showsCancel: false) { _, _ in completionHandler() })
    }
    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        present(BrowserPrompt(title: frame.request.url?.host ?? "Website", message: message) { accepted, _ in completionHandler(accepted) })
    }
    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        present(BrowserPrompt(title: frame.request.url?.host ?? "Website", message: prompt, defaultText: defaultText ?? "") { accepted, text in completionHandler(accepted ? text : nil) })
    }

    /// Custom long-press menu for links (replaces the default Safari menu), mirroring
    /// the Android browser's link options.
    func webView(_ webView: WKWebView,
                 contextMenuConfigurationForElement elementInfo: WKContextMenuElementInfo,
                 completionHandler: @escaping (UIContextMenuConfiguration?) -> Void) {
        guard let url = elementInfo.linkURL else { completionHandler(nil); return }
        let config = UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self else { return nil }
            let cast = UIAction(title: "Cast to TV", image: UIImage(systemName: "play.tv")) { _ in
                guard self.isActive(), BrowserSitePolicy.origin(url) != nil else { return }
                self.onPageCast?(["url": url.absoluteString], self.webView.url?.absoluteString ?? self.urlString)
            }
            let newTab = UIAction(title: "Open in New Tab", image: UIImage(systemName: "plus.square.on.square")) { _ in
                self.onOpenNewTab?(url, false)
            }
            let bgTab = UIAction(title: "Open in Background", image: UIImage(systemName: "square.on.square")) { _ in
                self.onOpenNewTab?(url, true)
            }
            let copy = UIAction(title: "Copy Link", image: UIImage(systemName: "doc.on.doc")) { _ in
                UIPasteboard.general.url = url
            }
            let download = UIAction(title: "Download Link", image: UIImage(systemName: "arrow.down.circle")) { [weak self] _ in
                guard let self, BrowserSitePolicy.origin(url) != nil else { return }
                self.present(BrowserPrompt(title: "Download file?", message: "Save the linked file to Downloads?", acceptLabel: "Download") { [weak self] accepted, _ in
                    guard let self, accepted else { return }
                    self.webView.startDownload(using: URLRequest(url: url)) { [weak self] download in
                        guard let self else { download.cancel { _ in }; return }
                        self.onDownload?(download, self.webView)
                    }
                })
            }
            return UIMenu(title: url.absoluteString, children: [cast, newTab, bgTab, copy, download])
        }
        completionHandler(config)
    }
}
