// SPDX-License-Identifier: MPL-2.0
import Foundation

// Android ClipboardClassifierTest cases, with local address schemes resolved by
// the phone's shared BrowserAddressResolver and magnets intentionally omitted.
enum BrowserStep3ModelTests {
    static func run() throws {
        let cases: [(String?, String?)] = [
            (nil, nil), ("", nil), ("   ", nil), ("\n\t\r  \n", nil),
            (String(repeating: "a", count: 2001), nil), (String(repeating: "word ", count: 500), nil),
            ("https://playbridge.org", "https://playbridge.org"),
            ("http://example.com/foo/bar", "http://example.com/foo/bar"),
            ("about:blank", "about:blank"), ("about:config", "about:config"),
            ("https://example.com/path?query=val#section", "https://example.com/path?query=val#section"),
            ("example.com", "https://example.com"), ("example.com/path", "https://example.com/path"),
            ("subdomain.example.co.uk/nested/page?id=123", "https://subdomain.example.co.uk/nested/page?id=123"),
            ("hello.world", "https://hello.world"), ("localhost", "http://localhost"),
            ("localhost:8080", "http://localhost:8080"), ("localhost:3000/api/status", "http://localhost:3000/api/status"),
            ("localhost/index.html", "http://localhost/index.html"), ("192.168.1.1:8080", "http://192.168.1.1:8080"),
            ("10.0.0.1", "http://10.0.0.1"), ("127.0.0.1:3000/dashboard", "http://127.0.0.1:3000/dashboard"),
            ("172.16.0.1:80/stream", "http://172.16.0.1:80/stream"),
            ("magnet:?xt=urn:btih:123", nil), ("MAGNET:?xt=urn:btih:test.", nil),
            ("a few words", "search:a few words"), ("hello.world is fun", "search:hello.world is fun"),
            ("this is a full sentence with words", "search:this is a full sentence with words"),
            ("singleword", "search:singleword"), ("...", "search:..."), ("!!!", "search:!!!"), ("https://example.com.", "https://example.com"),
            ("example.com/path,", "https://example.com/path"), ("example.com;", "https://example.com"),
            ("https://example.com/foo!", "https://example.com/foo"), ("localhost:8080.", "http://localhost:8080"),
            ("192.168.1.1:8080,", "http://192.168.1.1:8080"),
            ("  hello\nworld\r\nfoo  \t bar  ", "search:hello world foo bar"),
            ("  https://example.com\n  ", "https://example.com"),
            (String(repeating: "a", count: 300), "search:" + String(repeating: "a", count: 200)),
            (String(repeating: "word ", count: 50), "search:" + String(String(repeating: "word ", count: 50).prefix(200))),
            ("v1.2", "search:v1.2"), ("file.tar.gz", "search:file.tar.gz"),
            ("archive.tar.bz2", "search:archive.tar.bz2"), ("document.pdf", "search:document.pdf"),
            ("[::1]", "http://[::1]"), ("[::1]:8080/path", "http://[::1]:8080/path"),
            ("[fe80::1]:8096", "http://[fe80::1]:8096"), ("[2001:db8::1]", "https://[2001:db8::1]"),
            ("[bad]", "search:[bad]"), ("example.com:0", "search:example.com:0"),
            ("example.com:65536", "search:example.com:65536"), ("[::1]:65536", "search:[::1]:65536"),
            ("localhost:abc", "search:localhost:abc"), ("999.1.1.1", "search:999.1.1.1"),
            ("javascript:alert(1)", "search:javascript:alert(1)"), ("file:///secret", "search:file:///secret"),
            ("NAS.LOCAL:8096", "http://NAS.LOCAL:8096")
        ]
        for (input, expected) in cases {
            let actual = BrowserClipboardClassifier.classify(input)?.target(search: { "search:" + $0 })
            precondition(actual == expected, "Clipboard classification case failed")
        }
        if case .text(let text) = BrowserClipboardClassifier.classify(String(repeating: "😀", count: 150)) {
            precondition(text.utf16.count <= 200)
        } else { preconditionFailure("Unicode text was not classified") }
        var policy = BrowserClipboardOfferPolicy()
        precondition(policy.allows(1))
        policy.dismiss(1)
        precondition(!policy.allows(1) && policy.allows(2))

        for scheme in ["http", "https", "about", "file"] {
            let url = URL(string: scheme + "://example.test")!
            precondition(BrowserConnectionSecurity.derive(url: url, isHome: true, trustValid: true, onlySecureContent: true) == .unavailable)
        }
        let https = URL(string: "https://example.test")!
        precondition(BrowserConnectionSecurity.derive(url: https, isHome: false, trustValid: true, onlySecureContent: true) == .secure)
        precondition(BrowserConnectionSecurity.derive(url: https, isHome: false, trustValid: true, onlySecureContent: false) == .mixedContent)
        precondition(BrowserConnectionSecurity.derive(url: https, isHome: false, trustValid: false, onlySecureContent: true) == .notSecure)
        precondition(BrowserConnectionSecurity.derive(url: https, isHome: false, trustValid: nil, onlySecureContent: true) == .checking)
        precondition(BrowserConnectionSecurity.derive(url: URL(string: "http://example.test"), isHome: false, trustValid: true, onlySecureContent: true) == .notSecure)
        precondition(BrowserConnectionSecurity.derive(url: URL(string: "about:blank"), isHome: false, trustValid: true, onlySecureContent: true) == .unavailable)
        precondition(BrowserConnectionSecurity.derive(url: nil, isHome: false, trustValid: true, onlySecureContent: true) == .unavailable)
        try topVisited()
        print("CHECK: clipboard Android parity/IPv6/lengths, dismissal generations, connection security and Top Visited ranking")
    }

    private static func topVisited() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let date = Date(timeIntervalSince1970: 1_000)
        let entries = [
            HistoryEntry(url: "https://a.test", title: "A", lastVisited: date, visitCount: 3),
            HistoryEntry(url: "https://b.test", title: "B", lastVisited: date, visitCount: 3),
            HistoryEntry(url: "https://c.test", title: "C", lastVisited: date.addingTimeInterval(1), visitCount: 3),
            HistoryEntry(url: "https://top.test", title: "Top", lastVisited: date, visitCount: 8),
            HistoryEntry(url: "https://recent.test", title: "Recent", lastVisited: date.addingTimeInterval(100), visitCount: 1),
            HistoryEntry(url: "about:blank", title: "Internal", lastVisited: date, visitCount: 99)
        ]
        try JSONEncoder().encode(entries).write(to: directory.appendingPathComponent("history.json"))
        let data = BrowserDataStore(directory: directory)
        data.addBookmark(url: "https://top.test", title: "Top bookmark")
        precondition(data.topVisited.map(\.title) == ["Top", "C", "A", "B"])
        data.removeHistory(entries[3].id)
        precondition(data.topVisited.map(\.title) == ["C", "A", "B", "Recent"])
        data.clearHistory()
        precondition(data.topVisited.isEmpty)
        data.recordVisit(url: "https://fresh.test", title: "Fresh")
        precondition(data.topVisited.first?.title == "Fresh")
        data.flushPendingSaves()
    }
}
