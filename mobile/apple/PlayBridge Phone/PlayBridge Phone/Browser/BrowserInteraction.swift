import Foundation
import WebKit

/// A pending website interaction. Every exit path resolves its WebKit callback once.
final class BrowserPrompt: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let acceptLabel: String
    let defaultText: String?
    let showsCancel: Bool
    private var completion: ((Bool, String?) -> Void)?

    init(title: String, message: String, acceptLabel: String = "OK", defaultText: String? = nil,
         showsCancel: Bool = true, completion: @escaping (Bool, String?) -> Void) {
        self.title = title
        self.message = String(message.prefix(4000))
        self.acceptLabel = acceptLabel
        self.defaultText = defaultText
        self.showsCancel = showsCancel
        self.completion = completion
    }
    func finish(_ accepted: Bool, text: String? = nil) {
        let callback = completion
        completion = nil
        callback?(accepted, text)
    }
    deinit { completion?(false, nil) }
}

struct BrowserNavigationFailure {
    let address: String
    let message: String
}

enum BrowserSitePolicy {
    static func origin(_ url: URL?) -> String? {
        guard let url, let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host?.lowercased() else { return nil }
        var parts = URLComponents()
        parts.scheme = scheme; parts.host = host
        if let port = url.port, port != (scheme == "https" ? 443 : 80) { parts.port = port }
        return parts.string
    }
    static func popupsAllowed(_ url: URL?) -> Bool {
        guard let origin = origin(url) else { return false }
        return (UserDefaults.standard.stringArray(forKey: "pb_popup_origins") ?? []).contains(origin)
    }
    static func setPopupsAllowed(_ allowed: Bool, url: URL?) {
        guard let origin = origin(url) else { return }
        var origins = Set(UserDefaults.standard.stringArray(forKey: "pb_popup_origins") ?? [])
        if allowed { origins.insert(origin) } else { origins.remove(origin) }
        UserDefaults.standard.set(origins.sorted(), forKey: "pb_popup_origins")
    }
}

/// A separate content world and handler keep popup grants out of page scripts.
final class BrowserPopupInteraction: NSObject, WKScriptMessageHandler {
    static let world = WKContentWorld.world(name: "PlayBridge.PopupInteraction")
    weak var tab: BrowserTab?
    private var grant: (origin: String, document: URL?, mainFrame: Bool, time: TimeInterval)?

    static func originURL(_ frame: WKFrameInfo) -> URL? {
        let origin = frame.securityOrigin
        return originURL(scheme: origin.protocol, host: origin.host, port: origin.port)
    }

    static func originURL(scheme: String, host: String, port: Int) -> URL? {
        // Opaque/sandboxed frames can report an empty security-origin protocol.
        // Foundation traps on invalid scheme assignments instead of returning nil.
        // Only web origins can receive a popup grant; never substitute the top page.
        let scheme = scheme.lowercased()
        guard ["http", "https"].contains(scheme), !host.isEmpty,
              (0...65535).contains(port) else { return nil }
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        if port != 0 { components.port = port }
        return components.url
    }

    func clear() { grant = nil }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "popupInteraction", message.world == Self.world,
              let tab, message.webView === tab.loadedWebView,
              tab.isActive(), !tab.isPickingElement,
              let origin = BrowserSitePolicy.origin(Self.originURL(message.frameInfo)) else { return }
        if message.body as? String == "click" {
            grant = (origin, message.frameInfo.request.url, message.frameInfo.isMainFrame,
                     ProcessInfo.processInfo.systemUptime)
        } else if message.body as? String == "clear" {
            clear()
        }
    }

    func consume(for frame: WKFrameInfo) -> Bool {
        guard let grant else { return false }
        // A different origin/document cannot spend another frame's interaction.
        guard grant.origin == BrowserSitePolicy.origin(Self.originURL(frame)),
              grant.document == frame.request.url, grant.mainFrame == frame.isMainFrame else { return false }
        clear()
        return ProcessInfo.processInfo.systemUptime - grant.time < 1
    }

    static let source = #"""
    (() => {
        const send = value => window.webkit.messageHandlers.popupInteraction.postMessage(value);
        window.addEventListener('click', event => {
            // UserActivation shipped in Safari 16.4; older supported WebKit still
            // supplies the unforgeable isTrusted flag on real clicks.
            if (event.isTrusted && (!navigator.userActivation || navigator.userActivation.isActive)) send('click');
        }, true);
        window.addEventListener('pagehide', () => send('clear'), true);
        document.addEventListener('visibilitychange', () => send('clear'), true);
    })();
    """#
}
