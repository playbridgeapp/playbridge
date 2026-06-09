import SwiftUI
import WebKit

/// Hosts a tab's existing `WKWebView` in SwiftUI. The view is created once per tab and reused,
/// so navigation/scroll state survives tab switches (key the container by `tab.id`).
struct WebViewContainer: UIViewRepresentable {
    let tab: BrowserTab

    func makeUIView(context: Context) -> WKWebView { tab.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
