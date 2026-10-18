import Foundation

/// A conservative site approximation, not a Public Suffix List. Unknown multi-part
/// suffixes/private hosting suffixes can need an explicit per-origin exception.
enum PopupSite {
    private static let multipart: Set<String> = [
        "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au",
        "co.nz", "org.nz", "co.jp", "ne.jp", "co.in", "com.br", "com.cn", "com.sg",
        "co.za", "com.mx", "com.tr", "co.kr", "com.tw", "com.hk",
        // Common shared hosting boundaries must not grant sibling tenants access.
        "github.io", "pages.dev", "appspot.com", "blogspot.com", "vercel.app", "netlify.app"
    ]
    static func site(_ url: URL?) -> String? {
        guard let url, ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".")),
              !host.isEmpty else { return nil }
        let labels = host.split(separator: ".")
        if host.contains(":") || labels.allSatisfy({ $0.allSatisfy(\.isNumber) }) || labels.count < 2 { return host }
        let count = multipart.contains(labels.suffix(2).joined(separator: ".")) ? 3 : 2
        return labels.suffix(count).joined(separator: ".")
    }
    static func same(_ a: URL?, _ b: URL?) -> Bool {
        guard let a = site(a), let b = site(b) else { return false }
        return a == b
    }
}

struct PopupAnchor {
    let href: URL
    let newWindow: Bool
    let visible: Bool
    let viewportFraction: Double
    let opacity: Double
    let pointerSafe: Bool

    var trustworthy: Bool {
        newWindow && visible && pointerSafe && viewportFraction.isFinite && viewportFraction >= 0 &&
        viewportFraction < 0.5 && opacity.isFinite && opacity > 0.1
    }
}

struct PopupClickGrant {
    let origin: String
    let document: URL?
    let mainFrame: Bool
    let time: TimeInterval
    let anchor: PopupAnchor?
    func matches(origin: String?, document: URL?, mainFrame: Bool, at now: TimeInterval) -> Bool {
        self.origin == origin && self.document == document && self.mainFrame == mainFrame &&
        now >= time && now - time < 1
    }
}

enum PopupPolicy {
    static func allowsWindow(blockPopups: Bool, siteAllowed: Bool, trustedClick: Bool,
                             anchor: PopupAnchor?, destination: URL?, page: URL?) -> Bool {
        if siteAllowed { return true }
        guard trustedClick else { return false }
        if !blockPopups { return true } // Previous one-window-per-click policy.
        guard let anchor, anchor.trustworthy, anchor.href == destination else { return false }
        return PopupSite.same(destination, page)
    }
}

/// Independent of the spendable popup grant: selecting an allowed child must not
/// erase the opener's recent window request. Clear only on app navigation/commit.
struct TabUnderPolicy {
    enum Navigation { case other, link, form, backForward, reload }
    private(set) var interactionTime: TimeInterval?
    private(set) var clickedHref: URL?
    private(set) var interactionProducedWindow = false
    private(set) var windowTime: TimeInterval?

    mutating func interact(at time: TimeInterval, href: URL?) {
        interactionTime = time; clickedHref = href; interactionProducedWindow = false
    }
    mutating func requestedWindow(at time: TimeInterval) {
        windowTime = time
        if recent(interactionTime, at: time, interval: 1.5) { interactionProducedWindow = true }
    }
    mutating func clear() { self = TabUnderPolicy() }
    func shouldBlock(destination: URL, committedPage: URL?, navigation: Navigation,
                     mainFrame: Bool, appInitiated: Bool, siteAllowed: Bool, at time: TimeInterval) -> Bool {
        guard mainFrame, !appInitiated, !siteAllowed,
              navigation != .form, navigation != .backForward, navigation != .reload,
              PopupSite.site(committedPage) != nil, PopupSite.site(destination) != nil,
              !PopupSite.same(destination, committedPage) else { return false }
        if navigation == .link, clickedHref == destination, recent(interactionTime, at: time, interval: 1.5) { return false }
        return recent(windowTime, at: time, interval: 1.5) ||
            (interactionProducedWindow && recent(interactionTime, at: time, interval: 1.5))
    }
    private func recent(_ then: TimeInterval?, at now: TimeInterval, interval: TimeInterval) -> Bool {
        guard let then else { return false }
        return now >= then && now - then < interval
    }
}

struct BrowserBlockedNotice: Identifiable {
    enum Kind { case popup, redirect, message }
    enum OpenTarget { case newTab, currentTab }
    let id = UUID()
    let kind: Kind
    let url: URL?
    let origin: URL?
    let openTarget: OpenTarget?
    let message: String?
    var count = 1
    var title: String {
        message ?? (kind == .popup ? "Pop-up blocked" : "Redirect blocked")
    }
    var destinationHost: String { url?.host ?? (url?.absoluteString == "about:blank" ? "a blank page" : "Website") }
    var destinationPath: String {
        guard let url, url.host != nil, url.path != "/" else { return "" }
        return String(url.path.prefix(160)) // Never display credentials/query/fragment.
    }
    static func canOpen(_ request: URLRequest) -> Bool {
        (request.httpMethod ?? "GET").uppercased() == "GET" &&
        ["http", "https", "about"].contains(request.url?.scheme?.lowercased() ?? "")
    }
}

struct BrowserNoticeCountdown {
    private(set) var remaining = 5.0
    var progress: Double { remaining / 5 }
    mutating func tick(elapsed: TimeInterval, paused: Bool) {
        if !paused && elapsed.isFinite { remaining = max(0, remaining - max(0, elapsed)) }
    }
}
