import SwiftUI
import WebKit
import UIKit

/// Hosts a tab's existing `WKWebView` in SwiftUI. The view is created once per tab and reused,
/// so navigation/scroll state survives tab switches (key the container by `tab.id`).
struct WebViewContainer: UIViewRepresentable {
    let tab: BrowserTab

    func makeUIView(context: Context) -> WKWebView { tab.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

/// Receives picker taps outside WebKit so a page overlay or cross-origin iframe
/// cannot intercept selection before the browser identifies the element.
struct PickerTouchOverlay: UIViewRepresentable {
    var onTap: (CGPoint, CGSize) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = PickerTouchView()
        view.onTap = onTap
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        (uiView as? PickerTouchView)?.onTap = onTap
    }
}

private final class PickerTouchView: UIView {
    var onTap: ((CGPoint, CGSize) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let point = touches.first?.location(in: self), bounds.width > 0, bounds.height > 0 else { return }
        onTap?(point, bounds.size)
    }
}
