// SPDX-License-Identifier: MPL-2.0
import UIKit
import SwiftUI
import WebKit
import Security

/// Fixture-only TLS anchor, pinned to the freshly generated loopback certificate.
/// Never used by shipping code, never changes the Simulator's keychain/trust store.
private final class LoopbackTrustDelegate: NSObject, WKNavigationDelegate {
    let tab: BrowserTab
    let certificate: SecCertificate
    init(tab: BrowserTab, certificate: SecCertificate) { self.tab = tab; self.certificate = certificate }
    private func anchor(_ trust: SecTrust) {
        SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, true)
    }
    func webView(_ webView: WKWebView, didReceive challenge: URLAuthenticationChallenge,
                 completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.host == "127.0.0.1",
              challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first, SecCertificateCopyData(leaf) == SecCertificateCopyData(certificate) else {
            completionHandler(.performDefaultHandling, nil); return
        }
        anchor(trust)
        guard SecTrustEvaluateWithError(trust, nil) else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
    func webView(_ view: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { tab.webView(view, didStartProvisionalNavigation: navigation) }
    func webView(_ view: WKWebView, didCommit navigation: WKNavigation!) { tab.webView(view, didCommit: navigation) }
    func webView(_ view: WKWebView, didFinish navigation: WKNavigation!) {
        if let trust = view.serverTrust { anchor(trust) }
        tab.webView(view, didFinish: navigation)
    }
    func webView(_ view: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { tab.webView(view, didFail: navigation, withError: error) }
    func webView(_ view: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { tab.webView(view, didFailProvisionalNavigation: navigation, withError: error) }
    func webView(_ view: WKWebView, decidePolicyFor action: WKNavigationAction, preferences: WKWebpagePreferences,
                 decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void) {
        tab.webView(view, decidePolicyFor: action, preferences: preferences, decisionHandler: decisionHandler)
    }
    func webView(_ view: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        tab.webView(view, decidePolicyFor: response, decisionHandler: decisionHandler)
    }
}

private struct SiteToolsFixture: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var browser: BrowserStore
    let data: BrowserDataStore
    @ObservedObject var clipboard: BrowserClipboardSuggestions
    let pasteboard: UIPasteboard
    let trustDelegate: LoopbackTrustDelegate // Retain WebKit's weak delegate.
    let base: String
    let tls: String
    @State private var address = ""
    @FocusState private var focused: Bool
    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("HTTPS page") { focused = false; tab.load(tls + "/identity") }
                Button("HTTP page") { focused = false; tab.load(base + "/identity") }
                Button("New tab") { focused = false; tab.isHome = true; address = "" }
            }
            Button("Copy fixture link") { pasteboard.string = base + "/identity?copied=1" }
            Button("Cancel editing address") { focused = false }
            BrowserAddressField(tab: tab, address: $address, isFocused: $focused) { tab.load(address); focused = false }
            if focused {
                BrowserClipboardSuggestionRow(clipboard: clipboard) { url in focused = false; tab.load(url) }
            }
            Text(tab.urlString).accessibilityIdentifier("step3CurrentAddress").lineLimit(1)
            if tab.isHome { BrowserHomeView { tab.load($0) }.environmentObject(data) }
            else { WebViewContainer(tab: tab) }
        }
        .task(id: focused) { if focused { await clipboard.refresh() } }
        .onChange(of: tab.urlString) { if !focused { address = $0 } }
        .onAppear { address = tab.urlString }
    }
}

extension BrowserStartupChecks {
    @MainActor func showSiteToolsFixture() async throws {
        var count = 1, reads = 0
        let clipboard = BrowserClipboardSuggestions(changeCount: { count }, detect: { [\.probableWebURL] }, read: {
            reads += 1
            return "localhost:8080"
        })
        await clipboard.refresh()
        try check(reads == 0 && clipboard.offered == .link, "Clipboard focus read private contents")
        clipboard.dismiss()
        await clipboard.refresh()
        try check(reads == 0 && clipboard.offered == nil, "Dismissed generation was offered again")
        count = 2
        await clipboard.refresh()
        let target = clipboard.takeTarget(search: { "search:" + $0 })
        try check(reads == 1 && target == "http://localhost:8080", "Explicit clipboard tap did not classify/resolve")
        count = 3
        await clipboard.refresh()
        count = 4
        let changedTarget = clipboard.takeTarget(search: { $0 })
        try check(changedTarget == nil && reads == 1, "Tap read clipboard content that changed after detection")
        let changingDuringRead = BrowserClipboardSuggestions(changeCount: { count }, detect: { [\.probableWebURL] }, read: {
            count += 1
            return "https://changed.example"
        })
        await changingDuringRead.refresh()
        let racedTarget = changingDuringRead.takeTarget(search: { $0 })
        try check(racedTarget == nil, "Clipboard changed during read but still navigated")
        let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        let tls = ProcessInfo.processInfo.environment["BROWSER_TLS_FIXTURE"]!
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let browser = BrowserStore(tabsFileURL: directory.appendingPathComponent("tabs.json"))
        browser.browserVisible = true
        let tab = browser.activeTab!
        let certificateData = try Data(contentsOf: Bundle.main.url(forResource: "fixture", withExtension: "der")!)
        let certificate = SecCertificateCreateWithData(nil, certificateData as CFData)!
        let delegate = LoopbackTrustDelegate(tab: tab, certificate: certificate)
        let view = tab.webView
        view.navigationDelegate = delegate
        window!.rootViewController!.view.addSubview(view)
        view.frame = window!.bounds
        tab.load(tls + "/identity")
        try await wait("trusted loopback HTTPS") { view.title == "Identity" && !view.isLoading && tab.siteSecurity.connection == .secure }
        try check(tab.siteSecurity.certificate?.subject == "localhost" && tab.siteSecurity.certificate?.issuer == "localhost", "Certificate subject/issuer unavailable")
        if #available(iOS 18.0, *) {
            try check(tab.siteSecurity.certificate?.validFrom != nil && tab.siteSecurity.certificate?.validUntil != nil,
                      "Public certificate validity dates unavailable")
        }
        let data = BrowserDataStore(directory: directory.appendingPathComponent("home-data"))
        for _ in 0..<5 { data.recordVisit(url: base + "/parent", title: "Most visited") }
        data.addBookmark(url: base + "/parent", title: "Fixture bookmark")
        data.recordVisit(url: base + "/child", title: "Other page")
        view.removeFromSuperview()
        // Simulator host clipboard synchronization rewrites the general board
        // during XCTest taps. Isolate the board, using the same production detector
        // and tap reader, so generation dismissal is deterministic.
        let pasteboard = UIPasteboard.withUniqueName()
        let suggestions = BrowserClipboardSuggestions(changeCount: { pasteboard.changeCount }, detect: {
            try await pasteboard.detectedPatterns(for: [\.probableWebURL, \.probableWebSearch])
        }, read: { pasteboard.string })
        window?.rootViewController = UIHostingController(rootView: SiteToolsFixture(tab: tab, browser: browser,
            data: data, clipboard: suggestions, pasteboard: pasteboard, trustDelegate: delegate, base: base, tls: tls))
        print("CHECK: clipboard privacy/dismissal/change race and HTTPS certificate summary passed")
    }
}
