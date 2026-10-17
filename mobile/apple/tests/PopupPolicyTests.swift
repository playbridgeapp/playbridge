import Foundation

@main struct PopupPolicyChecks {
    static func main() {
        let page = URL(string: "https://watch.example.co.uk/video")!
        let same = URL(string: "https://help.example.co.uk/help")!
        let other = URL(string: "https://ads.example.net/landing")!
        func anchor(_ url: URL, fraction: Double = 0.01, opacity: Double = 1,
                    visible: Bool = true, pointer: Bool = true, newWindow: Bool = true) -> PopupAnchor {
            PopupAnchor(href: url, newWindow: newWindow, visible: visible,
                        viewportFraction: fraction, opacity: opacity, pointerSafe: pointer)
        }
        func window(_ url: URL, click: Bool = true, link: PopupAnchor? = nil,
                    allowed: Bool = false, blocking: Bool = true) -> Bool {
            PopupPolicy.allowsWindow(blockPopups: blocking, siteAllowed: allowed, trustedClick: click,
                                     anchor: link, destination: url, page: page)
        }
        precondition(!window(other) && !window(same)) // A trusted button is not an anchor.
        precondition(!window(same, click: false, link: anchor(same)))
        precondition(window(same, link: anchor(same)))
        precondition(!window(other, link: anchor(other)))
        precondition(!window(same, link: anchor(other)))
        precondition(!window(same, link: anchor(same, fraction: 0.5)))
        precondition(!window(same, link: anchor(same, opacity: 0.01)))
        precondition(!window(same, link: anchor(same, visible: false)))
        precondition(!window(same, link: anchor(same, pointer: false)))
        precondition(!window(same, link: anchor(same, newWindow: false)))
        precondition(window(other, click: false, allowed: true))
        precondition(window(other, blocking: false))
        precondition(!window(other, click: false, blocking: false))
        precondition(!PopupSite.same(URL(string: "https://a.co.uk"), URL(string: "https://b.co.uk")))
        precondition(!PopupSite.same(URL(string: "https://one.github.io"), URL(string: "https://two.github.io")))
        precondition(PopupSite.same(URL(string: "http://localhost:8000"), URL(string: "http://localhost:9000")))
        precondition(!PopupSite.same(URL(string: "http://127.0.0.1:8000"), URL(string: "http://localhost:8000")))
        precondition(!PopupSite.same(URL(string: "about:blank"), page))
        let grant = PopupClickGrant(origin: "https://example.test", document: page, mainFrame: true, time: 10, anchor: anchor(same))
        precondition(grant.matches(origin: grant.origin, document: page, mainFrame: true, at: 10.99))
        precondition(!grant.matches(origin: grant.origin, document: page, mainFrame: true, at: 11))
        precondition(!grant.matches(origin: grant.origin, document: page, mainFrame: true, at: 9))
        precondition(!grant.matches(origin: "https://other.test", document: page, mainFrame: true, at: 10.2))
        precondition(!grant.matches(origin: grant.origin, document: same, mainFrame: true, at: 10.2))
        precondition(!grant.matches(origin: grant.origin, document: page, mainFrame: false, at: 10.2))
        var policy = TabUnderPolicy()
        func block(_ target: URL = other, type: TabUnderPolicy.Navigation = .other,
                   at: TimeInterval = 10.3, main: Bool = true, app: Bool = false, exception: Bool = false) -> Bool {
            policy.shouldBlock(destination: target, committedPage: page, navigation: type,
                mainFrame: main, appInitiated: app, siteAllowed: exception, at: at)
        }
        policy.interact(at: 10, href: other)
        precondition(!block()) // Sign-in script redirect without a window.
        // Identical window tracking for allowed and blocked requests.
        for _ in ["allowed", "blocked"] {
            policy.requestedWindow(at: 10.1)
            precondition(block())
            precondition(!block(type: .link)) // Clicked anchor's own href.
            precondition(block(same, type: .link) == false)
            precondition(!block(type: .form))
            precondition(!block(type: .backForward))
            precondition(!block(type: .reload))
            precondition(!block(main: false))
            precondition(!block(app: true))
            precondition(!block(exception: true))
            precondition(!block(same))
            precondition(block(at: 11.59))
            precondition(!block(at: 11.6))
            precondition(!block(at: 9))
        }
        policy.clear()
        policy.requestedWindow(at: 10)
        precondition(block()) // Window-then-redirect with no interaction.
        precondition(block(type: .link)) // No grant for that link.
        precondition(!block(at: 11.5))
        policy.clear()
        precondition(!block()) // User typed/bookmark load or document commit retires state.
        var request = URLRequest(url: other)
        precondition(BrowserBlockedNotice.canOpen(request))
        request.httpMethod = "POST"
        precondition(!BrowserBlockedNotice.canOpen(request))
        precondition(!BrowserBlockedNotice.canOpen(URLRequest(url: URL(string: "javascript:alert(1)")!)))
        let notice = BrowserBlockedNotice(kind: .popup,
            url: URL(string: "https://username:secret@example.net/path?token=secret#fragment"),
            origin: page, openTarget: nil, message: nil)
        precondition(notice.destinationHost == "example.net" && notice.destinationPath == "/path")
        var timer = BrowserNoticeCountdown()
        timer.tick(elapsed: 2, paused: false)
        precondition(timer.remaining == 3 && timer.progress == 0.6)
        timer.tick(elapsed: 20, paused: true)
        precondition(timer.remaining == 3)
        timer.tick(elapsed: -5, paused: false)
        precondition(timer.remaining == 3)
        timer.tick(elapsed: 4, paused: false)
        precondition(timer.remaining == 0)
        precondition(BrowserNoticeCountdown().remaining == 5)
        print("PASS: popup default/exception/legacy toggle, anchor provenance/geometry/site bounds, tab-under timing/navigation exemptions, GET-only Open and safe destination display")
    }
}
