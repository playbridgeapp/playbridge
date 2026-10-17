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
    static var blockPopups: Bool {
        get { UserDefaults.standard.object(forKey: "pb_block_popups") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "pb_block_popups") }
    }

    static func clearPopupPermissions() {
        UserDefaults.standard.removeObject(forKey: "pb_popup_origins")
    }

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
    private var grant: PopupClickGrant?
    private var contextLink: (url: URL, time: TimeInterval)?

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

    func clear() {
        grant = nil
        contextLink = nil
    }

    func recordContextLink(_ rawValue: String, now: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        guard let url = URL(string: rawValue), BrowserSitePolicy.origin(url) != nil else {
            contextLink = nil
            return
        }
        contextLink = (url, now)
    }

    func consumeContextLink(now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> URL? {
        defer { contextLink = nil }
        guard let contextLink else { return nil }
        let age = now - contextLink.time
        return age >= 0 && age < 2 ? contextLink.url : nil
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "popupInteraction", message.world == Self.world,
              let tab, message.webView === tab.loadedWebView,
              tab.isActive(), !tab.isPickingElement,
              let origin = BrowserSitePolicy.origin(Self.originURL(message.frameInfo)) else { return }
        if let body = message.body as? [String: Any], let link = body["contextLink"] as? String {
            recordContextLink(link)
        } else if let body = message.body as? [String: Any], body["click"] as? Bool == true {
            let now = ProcessInfo.processInfo.systemUptime
            let href = (body["href"] as? String).flatMap(URL.init(string:))
            let anchor = href.map { PopupAnchor(href: $0, newWindow: body["newWindow"] as? Bool == true,
                visible: body["visible"] as? Bool == true, viewportFraction: body["fraction"] as? Double ?? 1,
                opacity: body["opacity"] as? Double ?? 0, pointerSafe: body["pointerSafe"] as? Bool == true) }
            grant = PopupClickGrant(origin: origin, document: message.frameInfo.request.url,
                mainFrame: message.frameInfo.isMainFrame, time: now, anchor: anchor)
            tab.recordPopupInteraction(at: now, href: href)
        } else if message.body as? String == "clear" {
            clear()
        }
    }

    func consume(for frame: WKFrameInfo) -> (trusted: Bool, anchor: PopupAnchor?) {
        guard let grant else { return (false, nil) }
        // A different origin/document cannot spend another frame's interaction.
        guard grant.matches(origin: BrowserSitePolicy.origin(Self.originURL(frame)), document: frame.request.url,
                            mainFrame: frame.isMainFrame, at: ProcessInfo.processInfo.systemUptime) else { return (false, nil) }
        clear()
        return (true, grant.anchor)
    }

    static let source = #"""
    (() => {
        const send = value => window.webkit.messageHandlers.popupInteraction.postMessage(value);
        const contextTarget = path => {
            let mediaURL = '';
            for (const node of path) {
                if (!(node instanceof Element)) continue;
                const anchor = node.closest('a[href]');
                if (anchor) return anchor.href;
                if (!mediaURL) {
                    const media = node.closest('img[src],video[src],audio[src]');
                    if (media) mediaURL = media.currentSrc || media.src || '';
                }
            }
            return mediaURL;
        };
        Object.defineProperty(window, '__playbridgeContextTarget', {value: contextTarget});
        window.addEventListener('touchstart', event => {
            let link = '';
            if (event.isTrusted && event.touches.length === 1) {
                link = contextTarget(event.composedPath());
            }
            send({contextLink: link});
        }, {capture: true, passive: true});
        window.addEventListener('click', event => {
            // UserActivation shipped in Safari 16.4; older supported WebKit still
            // supplies the unforgeable isTrusted flag on real clicks.
            if (!event.isTrusted || (navigator.userActivation && !navigator.userActivation.isActive)) return;
            const path = event.composedPath();
            const anchor = path.find(node => node instanceof Element && node.matches('a[href],area[href]'));
            const info = {click: true};
            if (anchor) {
                const rect = anchor.getBoundingClientRect();
                const width = Math.max(1, innerWidth), height = Math.max(1, innerHeight);
                let fraction = (rect.width * rect.height) / (width * height);
                // A small anchor can contain a full-screen positioned child. Inspect
                // the actual clicked descendants as well, stopping at the anchor.
                for (const node of path.slice(0, path.indexOf(anchor) + 1)) {
                    if (!(node instanceof Element)) continue;
                    const bounds = node.getBoundingClientRect();
                    fraction = Math.max(fraction, (bounds.width * bounds.height) / (width * height));
                }
                let opacity = 1, pointerSafe = true, visible = anchor.getClientRects().length > 0;
                for (let node = path.find(node => node instanceof Element); node instanceof Element;
                     node = node.parentElement || node.getRootNode().host) {
                    const style = getComputedStyle(node);
                    opacity *= Number(style.opacity);
                    visible = visible && style.display !== 'none' && style.visibility === 'visible';
                    pointerSafe = pointerSafe && style.pointerEvents !== 'none';
                }
                const hit = document.elementFromPoint(event.clientX, event.clientY);
                pointerSafe = pointerSafe && !!hit && (hit === anchor || anchor.contains(hit));
                const target = (anchor.getAttribute('target') || document.querySelector('base[target]')?.target || '').toLowerCase();
                Object.assign(info, {href: anchor.href, newWindow: !!target && !['_self','_parent','_top'].includes(target),
                    visible: visible && rect.width > 0 && rect.height > 0,
                    fraction, opacity, pointerSafe});
            }
            send(info);
        }, true);
        window.addEventListener('pagehide', () => send('clear'), true);
        document.addEventListener('visibilitychange', () => send('clear'), true);
    })();
    """#
}
