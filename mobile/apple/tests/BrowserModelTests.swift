// SPDX-License-Identifier: MPL-2.0
import Foundation

@main struct BrowserModelTests {
    static func main() throws {
        let addresses = [
            "localhost:8080": "http://localhost:8080", "localhost/path": "http://localhost/path",
            "192.168.1.22:8080/x": "http://192.168.1.22:8080/x", "HTTP://Example.com": "HTTP://Example.com",
            "example.com": "https://example.com", "hello world": "search:hello world",
            "JavaScript:alert(document.domain)": "search:JavaScript:alert(document.domain)",
            "nas:5000": "http://nas:5000", "about:blank": "about:blank", "10.0.0.1": "http://10.0.0.1",
            "[::1]:8080": "http://[::1]:8080", "[fe80::1]/x": "http://[fe80::1]/x",
            "[fc00::1]": "http://[fc00::1]", "[fd00::1]": "http://[fd00::1]", "[2001:db8::1]": "https://[2001:db8::1]",
            "myhost.lan:8096": "http://myhost.lan:8096", "NAS.LOCAL": "http://NAS.LOCAL",
            "8.8.8.8": "http://8.8.8.8", "172.16.0.1": "http://172.16.0.1", "169.254.1.2": "http://169.254.1.2",
            "example.com:8443/path": "https://example.com:8443/path", "  localhost  ": "http://localhost",
            "[fc::1]": "https://[fc::1]", "[fe8::1]": "https://[fe8::1]",
            "[0:0:0:0:0:0:0:1]": "http://[0:0:0:0:0:0:0:1]",
            "mailto:test@example.test": "mailto:test@example.test", "[]": "https://[]",
            "hello\tworld": "search:hello\tworld", "file:///tmp/test": "file:///tmp/test", "ABOUT:blank": "ABOUT:blank"
        ]
        for (input, expected) in addresses {
            precondition(BrowserAddressResolver.resolve(input, search: { "search:" + $0 }) == expected)
        }
        var recovery = BrowserProcessRecovery()
        recovery.markTerminated()
        precondition(recovery.needsReload)
        precondition(recovery.takeRecovery(now: 100) == true)
        precondition(recovery.takeRecovery(now: 101) == nil)
        recovery.markTerminated()
        precondition(recovery.takeRecovery(now: 105) == false)
        recovery.markTerminated()
        precondition(recovery.takeRecovery(now: 110) == true)
        recovery.markTerminated()
        recovery.cancelPendingReload()
        precondition(recovery.takeRecovery(now: 120) == nil)

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var historyWrites = 0
        let store = BrowserDataStore(directory: directory, historySaveDelay: 60) { data, file in
            precondition(!Thread.isMainThread)
            if file.lastPathComponent == "history.json" { historyWrites += 1 }
            try data.write(to: file, options: .atomic)
        }
        store.recordVisit(url: "https://example.test/one", title: "First")
        store.recordVisit(url: "https://example.test/one", title: "Second")
        store.updateTitle(url: "https://example.test/one", title: "Latest")
        precondition(store.history.first?.title == "Latest" && store.history.first?.visitCount == 2)
        store.addBookmark(url: "https://example.test/one", title: "Saved")
        precondition(store.bookmarks.count == 1)
        store.flushPendingSaves()
        precondition(historyWrites == 1)
        let historyFile = directory.appendingPathComponent("history.json")
        let saved = try JSONDecoder().decode([HistoryEntry].self, from: Data(contentsOf: historyFile))
        precondition(saved.first?.title == "Latest" && saved.first?.visitCount == 2)
        store.updateTitle(url: "https://example.test/one", title: "Transient")
        store.clearHistory()
        store.removeBookmark(url: "https://example.test/one")
        store.flushPendingSaves()
        let cleared = try JSONDecoder().decode([HistoryEntry].self, from: Data(contentsOf: historyFile))
        precondition(cleared.isEmpty)
        let bookmarks = try JSONDecoder().decode([Bookmark].self, from: Data(contentsOf: directory.appendingPathComponent("bookmarks.json")))
        precondition(bookmarks.isEmpty)
        let debounceDirectory = directory.appendingPathComponent("debounce")
        let debounce = BrowserDataStore(directory: debounceDirectory, historySaveDelay: 0.05)
        debounce.recordVisit(url: "https://example.test/debounced", title: "Debounced")
        let debounceFile = debounceDirectory.appendingPathComponent("history.json")
        let deadline = Date().addingTimeInterval(2)
        while !FileManager.default.fileExists(atPath: debounceFile.path), Date() < deadline { Thread.sleep(forTimeInterval: 0.01) }
        precondition(FileManager.default.fileExists(atPath: debounceFile.path))
        debounce.flushPendingSaves()
        print("PASS: address normalization, process recovery loop guard, coalesced background saves, flush/clear ordering and debounce")
    }
}
