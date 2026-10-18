import Foundation
import WebKit
import Security
import Combine
import UIKit

enum BrowserUserAgentPreset: String, CaseIterable, Identifiable {
    case automatic
    case chromeAndroid
    case chromeWindows
    case chromeMac
    case firefoxAndroid
    case firefoxWindows
    case safariMac
    case samsungInternet
    case custom

    var id: String { rawValue }

    var label: String {
        switch self {
        case .automatic: return "Automatic"
        case .chromeAndroid: return "Chrome — Android"
        case .chromeWindows: return "Chrome — Windows"
        case .chromeMac: return "Chrome — macOS"
        case .firefoxAndroid: return "Firefox — Android"
        case .firefoxWindows: return "Firefox — Windows"
        case .safariMac: return "Safari — macOS"
        case .samsungInternet: return "Samsung Internet — Android"
        case .custom: return "Custom"
        }
    }

    var value: String? {
        switch self {
        case .automatic, .custom: return nil
        case .chromeAndroid:
            return "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        case .chromeWindows:
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        case .chromeMac:
            return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        case .firefoxAndroid:
            return "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
        case .firefoxWindows:
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        case .safariMac:
            return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        case .samsungInternet:
            return "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"
        }
    }
}

/// One browser tab: lazily owns a persistent `WKWebView`, publishes navigation state, and routes detection
/// messages to its `VideoDetector`. Rough analogue of an entry in the Android `TabManager`.
final class BrowserTab: NSObject, ObservableObject, Identifiable, WKNavigationDelegate, WKUIDelegate, UIGestureRecognizerDelegate, PageCastSource {
    let id = UUID()
    let bridgedAppOrigin: URL?
    var onBridgedAppExternalNavigation: ((URLRequest) -> Void)?

    var isBridgedApp: Bool { bridgedAppOrigin != nil }

    private func redirectBridgedAppNavigation(_ request: URLRequest) -> Bool {
        guard let origin = bridgedAppOrigin, let url = request.url,
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              BridgedAppDeclaration.origin(of: url) != origin else { return false }
        if let current = loadedWebView?.backForwardList.currentItem?.url ?? committedPageURL {
            urlString = current.absoluteString
        }
        onBridgedAppExternalNavigation?(request)
        return true
    }

    private(set) var loadedWebView: WKWebView?
    let configuration: WKWebViewConfiguration
    var onWebViewCreated: (() -> Void)?
    var webView: WKWebView {
        if let loadedWebView { return loadedWebView }
        configuration.defaultWebpagePreferences.preferredContentMode = isDesktopMode ? .desktop : .mobile
        let view = WKWebView(frame: .zero, configuration: configuration)
        loadedWebView = view
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.isFindInteractionEnabled = true
        if isBridgedApp {
            // The app surface extends behind the island/home indicator. Leave keyboard
            // avoidance to SwiftUI, without WebKit adding another safe-area inset.
            view.scrollView.contentInsetAdjustmentBehavior = .never
        }
        view.customUserAgent = effectiveUserAgent
        let imagePress = UILongPressGestureRecognizer(target: self, action: #selector(imagePressed(_:)))
        imagePress.minimumPressDuration = 0.45
        imagePress.allowableMovement = 10
        imagePress.delegate = self
        view.addGestureRecognizer(imagePress)
        imageLongPress = imagePress
        observe()
        onWebViewCreated?()
        return loadedWebView ?? view
    }
    let detector = VideoDetector()
    private var mediaDetectionSettings = BrowserMediaDetectionSettings.load()
    private var isDeclaredBridgedSite = false
    private var detectionFrames: [WKFrameInfo] = []
    var detectionEnabled: Bool {
        mediaDetectionSettings.enabled &&
            (mediaDetectionSettings.detectInBridgedSites || (!isBridgedApp && !isDeclaredBridgedSite))
    }

    func acceptsDetection(_ body: [String: Any]) -> Bool {
        detectionEnabled && mediaDetectionSettings.allows(body["mediaKind"] as? String ?? "video")
    }

    func configureMediaDetection(_ settings: BrowserMediaDetectionSettings) {
        guard settings != mediaDetectionSettings else { return }
        objectWillChange.send()
        mediaDetectionSettings = settings
        // Cancel enrichment and remove stale detections before the newly configured scan.
        detector.clear()
        installUserScripts()
        applyDetectionToLoadedFrames()
    }

    private var detectionPolicyScript: String {
        "window.__playbridgeSetDetectionOptions?.(\(mediaDetectionSettings.scriptOptions(enabled: detectionEnabled))); void 0;"
    }

    func registerDetectionFrame(_ frame: WKFrameInfo) {
        // Only document-start/pageshow requests register frames; media messages never grow this list.
        if !detectionFrames.contains(where: { $0.isEqual(frame) }) && detectionFrames.count < 256 {
            detectionFrames.append(frame)
        }
        loadedWebView?.evaluateJavaScript(detectionPolicyScript, in: frame, in: .page, completionHandler: nil)
    }

    private func applyDetectionToLoadedFrames() {
        guard let view = loadedWebView else { return }
        view.evaluateJavaScript(detectionPolicyScript, completionHandler: nil)
        detectionFrames.filter { !$0.isMainFrame }.forEach {
            view.evaluateJavaScript(detectionPolicyScript, in: $0, in: .page, completionHandler: nil)
        }
    }

    private func updateDetectionPolicy(declared: Bool) {
        guard isDeclaredBridgedSite != declared else { return }
        isDeclaredBridgedSite = declared
        installUserScripts()
        applyDetectionToLoadedFrames()
    }
    private var detectionNavigationID = UUID()
    let networkLog = BrowserNetworkLog()
    @Published private(set) var networkCaptureEnabled = false

    /// Detailed request capture injects page-world hooks and is deliberately opt-in.
    /// A reload is required both to install and to remove hooks in the current document.
    func setNetworkCaptureEnabled(_ enabled: Bool) {
        guard networkCaptureEnabled != enabled else { return }
        networkCaptureEnabled = enabled
        if enabled { networkLog.clear() }
        installUserScripts()
        if loadedWebView != nil, !isHome { reload() }
    }

    @Published var urlString: String = ""
    @Published var title: String = "New Tab"
    @Published private(set) var isMediaPlaying = false
    @Published private(set) var isMoviFullscreen = false
    private var playbackState = BrowserPlaybackState()
    private var playbackExpiryTask: Task<Void, Never>?

    func recordMoviFullscreen(_ body: Any) {
        guard isBridgedApp, let body = body as? [String: Any],
              let fullscreen = body["fullscreen"] as? Bool else { return }
        if isMoviFullscreen != fullscreen { isMoviFullscreen = fullscreen }
    }

    func recordPlaybackState(_ body: Any) {
        guard let body = body as? [String: Any], let frame = body["frame"] as? String,
              frame.count <= 100, let playing = body["playing"] as? Bool else { return }
        playbackState.update(frame: frame, playing: playing, now: ProcessInfo.processInfo.systemUptime)
        refreshPlaybackState()
    }

    func refreshPlaybackState() {
        let now = ProcessInfo.processInfo.systemUptime
        let playing = playbackState.isPlaying(now: now)
        if isMediaPlaying != playing { isMediaPlaying = playing }
        guard playing else {
            playbackExpiryTask?.cancel()
            playbackExpiryTask = nil
            return
        }
        guard playbackExpiryTask == nil, let expiry = playbackState.nextExpiry(now: now) else { return }
        playbackExpiryTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(0.01, expiry - now) * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            self.playbackExpiryTask = nil
            self.refreshPlaybackState()
        }
    }

    /// Stops page-owned playback without waking a lazily restored tab.
    /// Cast-sheet dismissal intentionally does not resume the media.
    func pauseMedia() {
        loadedWebView?.evaluateJavaScript(
            "window.__playbridgePauseMedia?.(); void 0;",
            in: nil,
            in: BrowserPlaybackScript.world,
            completionHandler: nil
        )
    }

    @Published private(set) var siteSecurity = BrowserSiteSecurity()
    private var securityGeneration = UUID()
    private var securityProvisional = false

    /// Trust evaluation may fetch chain/revocation information; keep it off the UI
    /// thread and fence the result against newer navigation/KVO snapshots.
    private func refreshSiteSecurity() {
        guard let view = loadedWebView, navigationFailure == nil else { return }
        let generation = UUID()
        securityGeneration = generation
        let url = view.url
        let onlySecure = view.hasOnlySecureContent
        siteSecurity = BrowserSiteSecurity(url: url, connection: .derive(url: url, isHome: isHome,
            trustValid: nil, onlySecureContent: onlySecure))
        guard !securityProvisional, !isHome, url?.scheme?.lowercased() == "https", let trust = view.serverTrust else { return }
        DispatchQueue.global(qos: .utility).async { [weak self, weak view] in
            let valid = SecTrustEvaluateWithError(trust, nil)
            let certificate = BrowserCertificateSummary.copy(from: trust)
            DispatchQueue.main.async {
                guard let self, let view, self.loadedWebView === view,
                      self.securityGeneration == generation, view.url == url else { return }
                self.siteSecurity = BrowserSiteSecurity(url: url,
                    connection: .derive(url: url, isHome: self.isHome, trustValid: valid, onlySecureContent: onlySecure),
                    certificate: certificate)
            }
        }
    }

    @Published var isLoading: Bool = false
    @Published var progress: Double = 0
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false
    @Published var isDesktopMode: Bool = false
    @Published private(set) var userAgentPreset: BrowserUserAgentPreset = .automatic
    @Published private(set) var customUserAgent: String?
    @Published var isBrowserChromeHidden = false
    @Published private(set) var blockedNotice: BrowserBlockedNotice?
    private var tabUnder = TabUnderPolicy()
    private var appRequestedURL: URL?

    func showBlockedNotice(_ notice: BrowserBlockedNotice) {
        var next = notice
        if notice.kind != .message { next.count = (blockedNotice?.kind == .message ? 0 : blockedNotice?.count ?? 0) + 1 }
        blockedNotice = next
    }
    func dismissBlockedNotice() { blockedNotice = nil }
    func showBrowserMessage(_ message: String) {
        showBlockedNotice(BrowserBlockedNotice(kind: .message, url: nil, origin: nil, openTarget: nil, message: message))
    }
    func recordPopupInteraction(at time: TimeInterval, href: URL?) { tabUnder.interact(at: time, href: href) }
    private func recordWindowRequest() { tabUnder.requestedWindow(at: ProcessInfo.processInfo.systemUptime) }

    func openBlockedNotice() {
        guard isActive(), !isPickingElement, let notice = blockedNotice,
              let url = notice.url, let target = notice.openTarget else { return }
        dismissBlockedNotice()
        switch target {
        case .newTab: onOpenBlockedPopup?(URLRequest(url: url))
        case .currentTab: load(URLRequest(url: url))
        }
    }
    /// True for a fresh tab showing the home/new-tab page (no page loaded yet).
    @Published var isHome: Bool = false

    private var processRecovery = BrowserProcessRecovery()
    @Published var navigationFailure: BrowserNavigationFailure?
    @Published var prompt: BrowserPrompt?
    @Published private(set) var isPickingElement = false
    @Published private(set) var pickerSelector: String?
    @Published private(set) var pickerHasSource = false
    private var pickerGeneration = UUID()
    var isActive: () -> Bool = { false }
    var onDownload: ((WKDownload, WKWebView) -> Void)?
    var onMetadataChanged: (() -> Void)?
    private var requestedAddress = ""
    private var documentID = UUID()
    var onWebsiteCast: (([String: Any]) -> Void)?
    var onPageCastInvalidated: (() -> Void)?
    var pageCastDocumentID: UUID { documentID }
    var pageCastOrigin: String? { BrowserSitePolicy.origin(loadedWebView?.url) }
    var pageCastTitle: String { title }
    var pageCastCanRequest: Bool { isActive() }

    func deliverPageCast(_ message: [String: Any], documentID: UUID) {
        guard self.documentID == documentID else { return }
        loadedWebView?.callAsyncJavaScript("window.__playbridgePageCastDeliver?.(message)",
            arguments: ["message": message], in: nil, in: PageCastScript.world, completionHandler: nil)
    }
    private let popupInteraction = BrowserPopupInteraction()
    lazy var imageActions = BrowserImageActions(tab: self)
    private weak var imageLongPress: UILongPressGestureRecognizer?

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === imageLongPress else { return true }
        return isActive() && !isPickingElement && popupInteraction.imageTarget(standaloneOnly: true) != nil
    }
    /// Run alongside WebKit's own touch handling; otherwise the image menu only
    /// appears after WebKit gives up on the touch, i.e. when the finger lifts.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        gestureRecognizer === imageLongPress
    }
    @objc private func imagePressed(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began, isActive(), !isPickingElement,
              let view = loadedWebView, let image = popupInteraction.imageTarget(standaloneOnly: true) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        imageActions.standaloneMenu(image, at: recognizer.location(in: view), in: view)
    }

    private let pageCastScripts = PageCastScript.scripts()
    var popupOpenerURL: URL?
    private(set) var hasCommittedPage = false
    private var committedPageURL: URL?
    var onAdNavigationBlocked: ((BrowserBlockedNotice) -> Void)?
    var onOpenBlockedPopup: ((URLRequest) -> Void)?

    @discardableResult
    private func blockAdNavigation(_ url: URL, source: URL?, popup: Bool, method: String = "GET") -> Bool {
        guard ContentBlocker.shouldBlockNavigation(url, source: source, popup: popup, method: method) else { return false }
        networkLog.record(url: url.absoluteString, page: source?.absoluteString ?? urlString, kind: "navigation", state: "Blocked by ad rules")
        let notice = BrowserBlockedNotice(kind: popup ? .popup : .redirect, url: url,
            origin: source, openTarget: nil, message: nil)
        showBlockedNotice(notice)
        if let committedPageURL { urlString = committedPageURL.absoluteString }
        onAdNavigationBlocked?(notice)
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
    func allowPopupsForSite() {
        guard isActive(), !isPickingElement, let origin = blockedNotice?.origin,
              BrowserSitePolicy.origin(origin) != nil else { return }
        BrowserSitePolicy.setPopupsAllowed(true, url: origin)
        showBrowserMessage("Pop-ups allowed for \(origin.host ?? "this site"). Try again.")
    }

    /// Invoked when the page calls `window.playbridge.cast(payload)`.
    var onPageCast: (([String: Any], String) -> Void)?

    /// Invoked when a new main-frame document commits, so the owner can re-evaluate
    /// per-site ad blocking (e.g. exempting YouTube whose anti-adblock breaks playback).
    var onMainFrameCommit: ((URL?) -> Void)?

    /// Invoked when a page finishes loading — used to record history + persist tabs.
    var onPageFinished: ((URL?, String?) -> Void)?
    /// The page changed its title, possibly after its visit was recorded.
    var onTitleChanged: ((URL, String) -> Void)?
    private var inPageVisitTask: Task<Void, Never>?

    /// Records an in-page navigation once its title has had time to update.
    private func recordInPageVisit() {
        inPageVisitTask?.cancel()
        inPageVisitTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard !Task.isCancelled, let self, let view = self.loadedWebView,
                  !view.isLoading, let url = view.url else { return }
            self.onPageFinished?(url, view.title)
        }
    }

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

    /// On iPhone, WebKit's desktop content mode still honors width=device-width.
    /// Give responsive pages a desktop layout viewport while this mode is on.
    private static let desktopViewportScript = #"""
    (() => {
        const head = document.head;
        if (!head) return;
        const apply = () => {
            let viewport = head.querySelector('meta[name="viewport"]');
            if (!viewport) {
                viewport = document.createElement('meta');
                viewport.name = 'viewport';
                head.appendChild(viewport);
            }
            if (viewport.content !== 'width=980') viewport.content = 'width=980';
        };
        new MutationObserver(apply).observe(head, {
            childList: true, subtree: true, attributes: true,
            attributeFilter: ['name', 'content']
        });
        apply();
    })();
    """#

    static let adblockCosmeticWorld = WKContentWorld.world(name: "PlayBridgeAdblockCosmetic")
    private var adblockSiteScripts = AdblockSiteScripts.empty

    func applyAdblockSiteScripts(_ scripts: AdblockSiteScripts, evaluateCosmeticsInLoadedPage: Bool = false) {
        if adblockSiteScripts != scripts {
            adblockSiteScripts = scripts
            installUserScripts()
        }
        if evaluateCosmeticsInLoadedPage,
           let cosmetic = scripts.cosmetic, !cosmetic.isEmpty,
           let view = loadedWebView, !isHome, view.url != nil {
            view.evaluateJavaScript(cosmetic, in: nil, in: Self.adblockCosmeticWorld, completionHandler: nil)
        }
    }

    func refreshAdblockSiteScripts(for url: URL? = nil, enabled: Bool = ContentBlocker.isEnabled, evaluateCosmeticsInLoadedPage: Bool = false) {
        let targetURL = url
            ?? loadedWebView?.url
            ?? (requestedAddress.isEmpty ? nil : URL(string: requestedAddress))
            ?? (urlString.isEmpty ? nil : URL(string: urlString))
        let scripts = AdblockSnapshot.shared.siteScripts(for: targetURL, enabled: enabled, isBridgedApp: isBridgedApp)
        applyAdblockSiteScripts(scripts, evaluateCosmeticsInLoadedPage: evaluateCosmeticsInLoadedPage)
    }

    private func installUserScripts() {
        let cc = configuration.userContentController
        cc.removeAllUserScripts()
        cc.addUserScript(WKUserScript(source: "window.__playbridgeDetectionEnabled = \(detectionEnabled); window.__playbridgeDetectionOptions = \(mediaDetectionSettings.scriptOptions(enabled: detectionEnabled));\n" + DetectionScript.source,
                                      injectionTime: .atDocumentStart,
                                      forMainFrameOnly: false))
        cc.addUserScript(WKUserScript(source: pageCastScripts.page,
                                      injectionTime: .atDocumentStart,
                                      forMainFrameOnly: true))
        cc.addUserScript(WKUserScript(source: pageCastScripts.broker,
            injectionTime: .atDocumentStart, forMainFrameOnly: true, in: PageCastScript.world))
        cc.addUserScript(WKUserScript(source: BrowserPlaybackScript.source,
            injectionTime: .atDocumentStart, forMainFrameOnly: false, in: BrowserPlaybackScript.world))
        if isBridgedApp {
            cc.addUserScript(WKUserScript(source: BrowserMoviFullscreenScript.source,
                injectionTime: .atDocumentStart, forMainFrameOnly: true, in: BrowserMoviFullscreenScript.world))
        }
        if networkCaptureEnabled {
            cc.addUserScript(WKUserScript(source: BrowserNetworkScript.source,
                injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
        cc.addUserScript(WKUserScript(source: BrowserPopupInteraction.source,
            injectionTime: .atDocumentStart, forMainFrameOnly: false,
            in: BrowserPopupInteraction.world))
        if isDesktopMode && UIDevice.current.userInterfaceIdiom == .phone {
            cc.addUserScript(WKUserScript(source: Self.desktopViewportScript,
                injectionTime: .atDocumentEnd, forMainFrameOnly: true, in: .defaultClient))
        }
        if let scriptlet = adblockSiteScripts.scriptlet, !scriptlet.isEmpty {
            cc.addUserScript(WKUserScript(
                source: scriptlet,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false,
                in: .page
            ))
        }
        if let cosmetic = adblockSiteScripts.cosmetic, !cosmetic.isEmpty {
            cc.addUserScript(WKUserScript(
                source: cosmetic,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false,
                in: Self.adblockCosmeticWorld
            ))
        }
    }

    init(configuration: WKWebViewConfiguration, handler: WKScriptMessageHandler, bridgedAppOrigin: URL? = nil) {
        self.bridgedAppOrigin = bridgedAppOrigin
        // Each tab installs the detection script + message handler into its own content controller,
        // so detections are attributed to this tab.
        let cc = configuration.userContentController
        cc.add(handler, name: "playbridge")
        cc.add(handler, contentWorld: PageCastScript.world, name: PageCastScript.handlerName)
        cc.add(handler, name: "networkLog")
        cc.add(handler, contentWorld: BrowserPlaybackScript.world, name: "playbackState")
        if bridgedAppOrigin != nil {
            cc.add(handler, contentWorld: BrowserMoviFullscreenScript.world, name: "moviFullscreen")
        }

        self.configuration = configuration
        super.init()
        installUserScripts()
        popupInteraction.tab = self
        cc.add(popupInteraction, contentWorld: BrowserPopupInteraction.world, name: "popupInteraction")
        // Surface detector changes (new videos) on the tab so views observing the tab refresh.
        detector.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    private func observe() {
        observations = [
            webView.observe(\.hasOnlySecureContent, options: [.new]) { [weak self] view, _ in
                DispatchQueue.main.async { [weak view] in
                    guard let self, self.loadedWebView === view else { return }
                    self.refreshSiteSecurity()
                }
            },
            webView.observe(\.serverTrust, options: [.new]) { [weak self] view, _ in
                DispatchQueue.main.async { [weak view] in
                    guard let self, self.loadedWebView === view else { return }
                    self.refreshSiteSecurity()
                }
            },
            webView.observe(\.estimatedProgress, options: [.new]) { [weak self] wv, _ in
                let value = wv.estimatedProgress
                DispatchQueue.main.async { [weak wv] in
                    guard let self, self.loadedWebView === wv else { return }
                    self.progress = value
                }
            },
            webView.observe(\.isLoading, options: [.new]) { [weak self] wv, _ in
                let value = wv.isLoading
                DispatchQueue.main.async { [weak wv] in
                    guard let self, self.loadedWebView === wv else { return }
                    self.isLoading = value
                }
            },
            webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
                guard let title = wv.title, !title.isEmpty else { return }
                let url = wv.url
                DispatchQueue.main.async { [weak wv] in
                    guard let self, self.loadedWebView === wv else { return }
                    self.title = title
                    self.onMetadataChanged?()
                    if let url { self.onTitleChanged?(url, title) }
                }
            },
            webView.observe(\.url, options: [.new]) { [weak self] wv, _ in
                guard let url = wv.url?.absoluteString else { return }
                // A change while not loading is an in-page (pushState) navigation,
                // e.g. YouTube switching videos; no didFinish follows it.
                let inPage = !wv.isLoading
                DispatchQueue.main.async { [weak wv] in
                    guard let self, self.loadedWebView === wv else { return }
                    self.urlString = url
                    self.refreshSiteSecurity()
                    self.onMetadataChanged?()
                    if inPage { self.recordInPageVisit() }
                }
            },
            webView.observe(\.canGoBack, options: [.new]) { [weak self] wv, _ in
                let value = wv.canGoBack
                DispatchQueue.main.async { [weak wv] in
                    guard let self, self.loadedWebView === wv else { return }
                    self.canGoBack = value
                }
            },
            webView.observe(\.canGoForward, options: [.new]) { [weak self] wv, _ in
                let value = wv.canGoForward
                DispatchQueue.main.async { [weak wv] in
                    guard let self, self.loadedWebView === wv else { return }
                    self.canGoForward = value
                }
            },
        ]
    }

    // MARK: - Navigation

    /// Load a URL, or run a Google search when the text isn't a URL.
    func load(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let target = BrowserTab.resolveInput(trimmed)
        guard let url = URL(string: target) else { return }
        load(URLRequest(url: url))
    }

    func load(_ request: URLRequest) {
        guard let url = request.url else { return }
        processRecovery.cancelPendingReload()
        cancelPrompt()
        tabUnder.clear()
        appRequestedURL = url
        dismissBlockedNotice()
        stopElementPicker()
        navigationFailure = nil
        isHome = false
        let target = url.absoluteString
        urlString = target
        requestedAddress = target
        refreshAdblockSiteScripts(for: url)
        onBeforeLoad?()
        webView.load(request)
    }

    /// A fresh view restores its complete back/forward list, rather than adding a URL load.
    /// interactionState is available on iOS 15; the phone's deployment target is iOS 16.
    func restoreInteractionState(_ state: Data, url: URL) -> Bool {
        processRecovery.cancelPendingReload()
        appRequestedURL = url
        requestedAddress = url.absoluteString
        navigationFailure = nil
        refreshAdblockSiteScripts(for: url)
        onBeforeLoad?()
        let view = webView
        view.interactionState = state
        guard view.backForwardList.currentItem?.url == url else {
            // A rejected/mismatched blob must not leave a foreign history entry in
            // the URL fallback. This view has not been handed to the UI yet.
            view.stopLoading()
            observations.removeAll()
            view.navigationDelegate = nil
            view.uiDelegate = nil
            loadedWebView = nil
            return false
        }
        return true
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
        pickerSelector = nil
        pickerHasSource = false
        isPickingElement = true
        webView.evaluateJavaScript(ContentBlocker.elementPickerJS) { [weak self] _, error in
            guard let self, self.pickerGeneration == generation, error != nil else { return }
            self.isPickingElement = false
            self.showBrowserMessage("Couldn’t open the element picker on this page.")
        }
    }
    func pickerDidFinish() {
        let generation = pickerGeneration
        // Keep WebKit navigation guarded through the synthetic click after touchend.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            guard let self, self.pickerGeneration == generation else { return }
            self.isPickingElement = false
            self.pickerSelector = nil
            self.pickerHasSource = false
        }
    }
    func pickerDidSelect(_ selector: String, hasSource: Bool) {
        guard isPickingElement else { return }
        pickerSelector = selector
        pickerHasSource = hasSource
    }
    func pickElement(atNormalizedX x: Double, y: Double) {
        guard isPickingElement, x.isFinite, y.isFinite else { return }
        webView.evaluateJavaScript("window.__pb_picker_select_at?.(\(x),\(y));", completionHandler: nil)
    }
    func blockPickedElement() {
        guard isPickingElement, pickerSelector != nil else { return }
        webView.evaluateJavaScript("window.__pb_picker_block?.();", completionHandler: nil)
    }
    func pickerAction(_ action: String) {
        guard isPickingElement, ["up", "down", "preview", "source"].contains(action) else { return }
        webView.evaluateJavaScript("window.__pb_picker_action?.('\(action)');", completionHandler: nil)
    }
    func stopElementPicker() {
        guard isPickingElement else { return }
        pickerGeneration = UUID()
        isPickingElement = false
        pickerSelector = nil
        pickerHasSource = false
        loadedWebView?.evaluateJavaScript("if(window.__pb_picker_cleanup) window.__pb_picker_cleanup();", completionHandler: nil)
    }

    func goBack() {
        processRecovery.cancelPendingReload()
        stopElementPicker()
        cancelPrompt()
        navigationFailure = nil
        if let backURL = loadedWebView?.backForwardList.backItem?.url {
            refreshAdblockSiteScripts(for: backURL)
        }
        webView.goBack()
    }
    func goForward() {
        processRecovery.cancelPendingReload()
        stopElementPicker()
        cancelPrompt()
        navigationFailure = nil
        if let forwardURL = loadedWebView?.backForwardList.forwardItem?.url {
            refreshAdblockSiteScripts(for: forwardURL)
        }
        webView.goForward()
    }
    func reload() {
        processRecovery.cancelPendingReload()
        stopElementPicker()
        if let failure = navigationFailure { load(failure.address) }
        else {
            cancelPrompt()
            refreshAdblockSiteScripts(for: loadedWebView?.url ?? URL(string: urlString))
            webView.reload()
        }
    }
    func stop() {
        detectionNavigationID = UUID()
        playbackExpiryTask?.cancel()
        playbackExpiryTask = nil
        loadedWebView?.stopLoading()
    }

    static let desktopUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15"

    var effectiveUserAgent: String? {
        if userAgentPreset == .custom { return customUserAgent }
        return userAgentPreset.value ?? (isDesktopMode ? Self.desktopUA : nil)
    }

    func restoreUserAgent(preset: BrowserUserAgentPreset, custom: String?) {
        let sanitized = Self.validCustomUserAgent(custom)
        userAgentPreset = preset == .custom && sanitized == nil ? .automatic : preset
        customUserAgent = sanitized
        applyBrowserIdentity(reload: false)
    }

    @discardableResult
    func selectUserAgent(_ preset: BrowserUserAgentPreset, custom: String? = nil) -> Bool {
        let sanitized = Self.validCustomUserAgent(custom)
        guard preset != .custom || sanitized != nil else { return false }
        guard preset != userAgentPreset || (preset == .custom && sanitized != customUserAgent) else { return true }
        userAgentPreset = preset
        if preset == .custom { customUserAgent = sanitized }
        applyBrowserIdentity(reload: true)
        onMetadataChanged?()
        return true
    }

    static func validCustomUserAgent(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= 512,
              !trimmed.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { return nil }
        return trimmed
    }

    private func applyBrowserIdentity(reload: Bool) {
        configuration.defaultWebpagePreferences.preferredContentMode = isDesktopMode ? .desktop : .mobile
        loadedWebView?.customUserAgent = effectiveUserAgent
        installUserScripts()
        // A site may cache distinct mobile/desktop responses at the same URL.
        if reload && !isHome { loadedWebView?.reloadFromOrigin() }
    }

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
        applyBrowserIdentity(reload: true)
        onMetadataChanged?()
    }

    /// Turn an address-bar entry into a URL (add scheme) or a Google search query.
    static func resolveInput(_ text: String) -> String {
        BrowserAddressResolver.resolve(text, search: SearchEngine.current.searchURL)
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        navigationFailure = nil
        securityProvisional = true
        refreshSiteSecurity()
        detectionFrames.removeAll()
        detectionNavigationID = UUID()
        onPageCastInvalidated?()
        navigationFailure = nil
        dismissBlockedNotice()
        cancelPrompt()
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        securityProvisional = false
        refreshSiteSecurity()
        onPageCastInvalidated?()
        documentID = UUID()
        tabUnder.clear()
        appRequestedURL = nil
        playbackState = BrowserPlaybackState()
        playbackExpiryTask?.cancel()
        playbackExpiryTask = nil
        isMediaPlaying = false
        isMoviFullscreen = false
        if let url = webView.url, url.scheme != "about" {
            hasCommittedPage = true
            committedPageURL = url
        }
        // New main-frame document — reset detections for this tab.
        detector.clear()
        navigationFailure = nil
        dismissBlockedNotice()
        cancelPrompt()
        onMainFrameCommit?(webView.url)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        securityProvisional = false
        refreshSiteSecurity()
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
        securityGeneration = UUID()
        siteSecurity = BrowserSiteSecurity(url: URL(string: address), connection: .derive(
            url: URL(string: address), isHome: isHome, trustValid: false, onlySecureContent: false))
        navigationFailure = BrowserNavigationFailure(address: address, message: message)
        isLoading = false
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        onPageCastInvalidated?()
        isMoviFullscreen = false
        isPickingElement = false
        pickerSelector = nil
        pickerHasSource = false
        pickerGeneration = UUID()
        cancelPrompt()
        detector.clear()
        navigationFailure = nil
        isLoading = false
        processRecovery.markTerminated()
        reloadTerminatedProcessIfNeeded()
    }

    /// Called on selection and when Browser becomes visible again. Hidden tabs stay unloaded.
    func reloadTerminatedProcessIfNeeded() {
        guard isActive(), let view = loadedWebView,
              let shouldReload = processRecovery.takeRecovery(now: ProcessInfo.processInfo.systemUptime) else { return }
        if shouldReload {
            navigationFailure = nil
            if let currentURL = view.url {
                refreshAdblockSiteScripts(for: currentURL)
                view.reload()
            } else {
                load(urlString)
            }
        } else {
            navigationFailure = BrowserNavigationFailure(address: view.url?.absoluteString ?? urlString,
                message: "This page stopped responding. Reload it to continue.")
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 preferences: WKWebpagePreferences,
                 decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        // The configuration's default is captured when WKWebView is created. A live
        // Desktop Site toggle needs preferences on every main-frame navigation/reload.
        if action.targetFrame?.isMainFrame == true {
            preferences.preferredContentMode = isDesktopMode ? .desktop : .mobile
        }
        guard !isPickingElement else { decisionHandler(.cancel, preferences); return }
        guard let url = action.request.url else { decisionHandler(.cancel, preferences); return }
        if action.targetFrame == nil { recordWindowRequest() }
        if action.targetFrame == nil || action.targetFrame?.isMainFrame == true {
            let source = committedPageURL ?? popupOpenerURL ?? action.sourceFrame.request.url
            if blockAdNavigation(url, source: source, popup: action.targetFrame == nil || (popupOpenerURL != nil && !hasCommittedPage), method: action.request.httpMethod ?? "GET") {
                decisionHandler(.cancel, preferences)
                return
            }
        }
        if action.targetFrame?.isMainFrame == true {
            let appInitiated = appRequestedURL == url
            appRequestedURL = nil
            let navigation: TabUnderPolicy.Navigation
            switch action.navigationType {
            case .linkActivated: navigation = .link
            case .formSubmitted, .formResubmitted: navigation = .form
            case .backForward: navigation = .backForward
            case .reload: navigation = .reload
            default: navigation = .other
            }
            if tabUnder.shouldBlock(destination: url, committedPage: committedPageURL,
                navigation: navigation, mainFrame: true, appInitiated: appInitiated,
                siteAllowed: BrowserSitePolicy.popupsAllowed(committedPageURL), at: ProcessInfo.processInfo.systemUptime) {
                networkLog.record(url: url.absoluteString, page: committedPageURL?.absoluteString ?? urlString,
                    kind: "navigation", state: "Blocked tab-under redirect")
                showBlockedNotice(BrowserBlockedNotice(kind: .redirect, url: url, origin: committedPageURL,
                    openTarget: BrowserBlockedNotice.canOpen(action.request) ? .currentTab : nil, message: nil))
                if let committedPageURL { urlString = committedPageURL.absoluteString }
                decisionHandler(.cancel, preferences)
                return
            }
        }
        networkLog.record(url: url.absoluteString, page: action.sourceFrame.request.url?.absoluteString ?? urlString,
            kind: action.targetFrame == nil ? "popup" : (action.targetFrame?.isMainFrame == false ? "iframe navigation" : "navigation"), method: action.request.httpMethod ?? "GET", state: "Requested")
        let scheme = url.scheme?.lowercased() ?? ""
        if !["http", "https", "about", "blob", "data"].contains(scheme) {
            decisionHandler(.cancel, preferences)
            guard action.targetFrame == nil || action.targetFrame?.isMainFrame == true else { return }
            guard !["javascript", "file", "", "intent"].contains(scheme) else {
                showBrowserMessage("This type of link cannot be opened on iPhone.")
                return
            }
            present(BrowserPrompt(title: "Open another app?", message: "Open \(url.absoluteString) outside PlayBridge?", acceptLabel: "Open") { [weak self] accepted, _ in
                guard accepted else { return }
                UIApplication.shared.open(url, options: [:]) { success in
                    if !success { self?.showBrowserMessage("No app could open this link.") }
                }
            })
            return
        }
        if action.shouldPerformDownload {
            confirmDownload(filename: nil, url: url, bytes: -1) { accepted in
                decisionHandler(accepted ? .download : .cancel, preferences)
            }
            return
        }
        if action.targetFrame?.isMainFrame == true, redirectBridgedAppNavigation(action.request) {
            decisionHandler(.cancel, preferences)
            return
        }
        if action.targetFrame?.isMainFrame == true {
            requestedAddress = url.absoluteString
            detectionNavigationID = UUID()
            let navigationID = detectionNavigationID
            let isBridged = isBridgedApp
            let adblockOn = ContentBlocker.isEnabled
            Task { @MainActor [weak self, weak webView] in
                async let declaredTask: Bool = isBridged ? true : await BridgedAppDeclarationCache.shared.isDeclared(url)
                async let scriptsTask = Task.detached(priority: .userInitiated) {
                    AdblockSnapshot.shared.siteScripts(for: url, enabled: adblockOn, isBridgedApp: isBridged)
                }.value
                let (declared, scripts) = await (declaredTask, scriptsTask)
                guard let self, let webView, self.loadedWebView === webView,
                      self.detectionNavigationID == navigationID else {
                    decisionHandler(.cancel, preferences)
                    return
                }
                self.applyAdblockSiteScripts(scripts)
                self.updateDetectionPolicy(declared: declared)
                // Apply before navigation, including Back/Forward cache restores.
                decisionHandler(.allow, preferences)
            }
            return
        }
        decisionHandler(.allow, preferences)
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
        if response.isForMainFrame, let url = response.response.url,
           redirectBridgedAppNavigation(URLRequest(url: url)) {
            decisionHandler(.cancel)
            return
        }
        let attachment = (response.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition")?.lowercased().hasPrefix("attachment") == true
        if !attachment && response.canShowMIMEType {
            guard response.isForMainFrame, let url = response.response.url else {
                decisionHandler(.allow)
                return
            }
            let navigationID = detectionNavigationID
            let isBridged = isBridgedApp
            let adblockOn = ContentBlocker.isEnabled
            Task { @MainActor [weak self, weak webView] in
                async let declaredTask: Bool = isBridged ? true : await BridgedAppDeclarationCache.shared.isDeclared(url)
                async let scriptsTask = Task.detached(priority: .userInitiated) {
                    AdblockSnapshot.shared.siteScripts(for: url, enabled: adblockOn, isBridgedApp: isBridged)
                }.value
                let (declared, scripts) = await (declaredTask, scriptsTask)
                guard let self, let webView, self.loadedWebView === webView,
                      self.detectionNavigationID == navigationID else {
                    decisionHandler(.cancel)
                    return
                }
                self.applyAdblockSiteScripts(scripts)
                self.updateDetectionPolicy(declared: declared)
                decisionHandler(.allow)
            }
            return
        }
        confirmDownload(filename: response.response.suggestedFilename, url: response.response.url,
                        bytes: response.response.expectedContentLength) { accepted in
            decisionHandler(accepted ? .download : .cancel)
        }
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
            recordWindowRequest()
            if let url = navigationAction.request.url,
               blockAdNavigation(url, source: navigationAction.sourceFrame.request.url, popup: true, method: navigationAction.request.httpMethod ?? "GET") { return nil }
            let interaction = popupInteraction.consume(for: navigationAction.sourceFrame)
            let origin = BrowserPopupInteraction.originURL(navigationAction.sourceFrame)
            let allowed = PopupPolicy.allowsWindow(blockPopups: BrowserSitePolicy.blockPopups,
                siteAllowed: BrowserSitePolicy.popupsAllowed(origin), trustedClick: interaction.trusted,
                anchor: interaction.anchor, destination: navigationAction.request.url, page: committedPageURL)
            guard allowed else {
                if let url = navigationAction.request.url {
                    networkLog.record(url: url.absoluteString, page: navigationAction.sourceFrame.request.url?.absoluteString ?? urlString, kind: "popup", state: "Blocked by popup policy")
                }
                showBlockedNotice(BrowserBlockedNotice(kind: .popup, url: navigationAction.request.url,
                    origin: origin, openTarget: BrowserBlockedNotice.canOpen(navigationAction.request) ? .newTab : nil, message: nil))
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

    /// Safari-style confirmation for files a website sends: name the file, its size and source.
    private func confirmDownload(filename: String?, url: URL?, bytes: Int64, _ decide: @escaping (Bool) -> Void) {
        let name = BrowserDownloads.safeFilename(filename ?? url?.lastPathComponent ?? "")
        let size = bytes > 0 ? ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) : nil
        let detail = [size, url?.host].compactMap { $0 }.joined(separator: " from ")
        let message = (detail.isEmpty ? "" : detail + ". ") + "It will appear in Downloads, where you can save it to Files."
        present(BrowserPrompt(title: "Download “\(name)”?", message: message, acceptLabel: "Download") { accepted, _ in decide(accepted) })
    }

    /// Custom long-press menu for links (replaces the default Safari menu), mirroring
    /// the Android browser's link options.
    func webView(_ webView: WKWebView,
                 contextMenuConfigurationForElement elementInfo: WKContextMenuElementInfo,
                 completionHandler: @escaping (UIContextMenuConfiguration?) -> Void) {
        let trackedLink = popupInteraction.consumeContextLink()
        let url = elementInfo.linkURL ?? trackedLink
        let image = popupInteraction.imageTarget() ?? url.flatMap(BrowserImageData.imageLink)
        guard url != nil || image != nil else { completionHandler(nil); return }
        let config = UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self else { return nil }
            var actions: [UIMenuElement] = []
            if let image { actions.append(UIMenu(options: .displayInline, children: self.imageActions.menu(image))) }
            guard let url else { return UIMenu(children: actions) }
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
            actions.append(UIMenu(options: .displayInline, children: [cast, newTab, bgTab, copy]))
            return UIMenu(title: url.absoluteString, children: actions)
        }
        completionHandler(config)
    }
}
