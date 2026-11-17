// SPDX-License-Identifier: MPL-2.0
import Foundation

@main struct BrowserModelTests {
    static func main() throws {
        try BrowserStep3ModelTests.run()
        try BrowserStep4ModelTests.run()
        try NetworkLogCategoryModelTests.run()
#if PB_DEVELOPER_TOOLS
        try DetectedMediaExportModelTests.run()
#endif
        try AdblockSiteSettingsModelTests.run()
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
        try verifyTabStatePersistence(directory.appendingPathComponent("tab-states"))
        verifyKeyboardVisibility()
        print("PASS: address normalization, process recovery loop guard, coalesced background saves, flush/clear ordering, debounce, and keyboard visibility")
    }

    private static func verifyKeyboardVisibility() {
        let portrait = CGRect(x: 0, y: 0, width: 393, height: 852)
        // Soft keyboard in portrait (~301pt total, 34pt bottom safe area) hides the toolbar.
        precondition(BrowserKeyboardVisibility.isSoftKeyboardVisible(
            endFrame: CGRect(x: 0, y: 551, width: 393, height: 301),
            screenBounds: portrait,
            bottomSafeAreaInset: 34
        ))
        // Hardware keyboard shortcut bar (~78pt total including 34pt home indicator) keeps the toolbar visible.
        precondition(!BrowserKeyboardVisibility.isSoftKeyboardVisible(
            endFrame: CGRect(x: 0, y: 774, width: 393, height: 78),
            screenBounds: portrait,
            bottomSafeAreaInset: 34
        ))
        // Landscape soft keyboard (~200pt, 21pt safe area) hides toolbar; slim landscape shortcut bar (~55pt) does not.
        let landscape = CGRect(x: 0, y: 0, width: 852, height: 393)
        precondition(BrowserKeyboardVisibility.isSoftKeyboardVisible(
            endFrame: CGRect(x: 0, y: 193, width: 852, height: 200),
            screenBounds: landscape,
            bottomSafeAreaInset: 21
        ))
        precondition(!BrowserKeyboardVisibility.isSoftKeyboardVisible(
            endFrame: CGRect(x: 0, y: 338, width: 852, height: 55),
            screenBounds: landscape,
            bottomSafeAreaInset: 21
        ))
        // Offscreen / dismissed keyboard frame restores the toolbar.
        precondition(!BrowserKeyboardVisibility.isSoftKeyboardVisible(
            endFrame: CGRect(x: 0, y: 852, width: 393, height: 301),
            screenBounds: portrait,
            bottomSafeAreaInset: 34
        ))
    }

    private static func verifyTabStatePersistence(_ directory: URL) throws {
        var writes = 0
        let persistence = BrowserTabStatePersistence(directory: directory, delay: 60) { data, file in
            precondition(!Thread.isMainThread)
            writes += 1
            try data.write(to: file, options: .atomic)
        }
        let id = UUID().uuidString
        let first = Data([1, 2]), latest = Data([3, 4])
        persistence.save(first, url: "https://example.test/first", id: id)
        persistence.save(latest, url: "https://example.test/latest", id: id)
        persistence.flush()
        precondition(writes == 1 && persistence.load(id, matching: "https://example.test/latest") == latest)
        precondition(persistence.load(id, matching: "https://example.test/first") == nil)
        let orphan = directory.appendingPathComponent(UUID().uuidString)
        try first.write(to: orphan)
        persistence.prune(keeping: [id])
        persistence.flush()
        precondition(!FileManager.default.fileExists(atPath: orphan.path))
        persistence.save(first, url: "https://example.test/first", id: id)
        persistence.remove(id)
        persistence.flush()
        precondition(!FileManager.default.fileExists(atPath: directory.appendingPathComponent(id).path))
        persistence.save(Data(repeating: 0, count: BrowserTabStatePersistence.maximumBytes + 1), url: "https://example.test", id: id)
        persistence.flush()
        precondition(persistence.load(id, matching: "https://example.test") == nil)
        try Data("corrupt".utf8).write(to: directory.appendingPathComponent(id))
        precondition(persistence.load(id, matching: "https://example.test") == nil)
        precondition(persistence.load("../outside", matching: "https://example.test") == nil)
        persistence.save(first, url: "https://example.test", id: "../outside")
        persistence.flush()
        let debouncedID = UUID().uuidString
        let debounced = BrowserTabStatePersistence(directory: directory, delay: 0.05)
        debounced.save(first, url: "https://example.test", id: debouncedID)
        let deadline = Date().addingTimeInterval(2)
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent(debouncedID).path), Date() < deadline {
            Thread.sleep(forTimeInterval: 0.01)
        }
        precondition(debounced.load(debouncedID, matching: "https://example.test") == first)
        debounced.flush()
        print("CHECK: tab-state background/coalesced writes, debounce, cap, corrupt/mismatch reads and prune/delete ordering")
    }
}
