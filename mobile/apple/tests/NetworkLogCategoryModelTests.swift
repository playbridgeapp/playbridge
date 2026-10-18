// SPDX-License-Identifier: MPL-2.0
import Foundation

enum NetworkLogCategoryModelTests {
    static func run() throws {
        typealias C = BrowserNetworkLog.Category
        func of(_ kind: String, _ url: String) -> C { C.of(kind: kind, url: URL(string: url)) }
        precondition(of("navigation", "https://a.test/") == .document)
        precondition(of("navigation response", "https://a.test/page") == .document)
        precondition(of("popup", "https://ads.test/x") == .document)
        precondition(of("iframe", "https://a.test/embed") == .document)
        precondition(of("fetch", "https://api.test/v1/items") == .fetch)
        precondition(of("XHR", "https://api.test/v1/items") == .fetch)
        precondition(of("xmlhttprequest", "https://api.test/q") == .fetch)
        // Players fetch playlists and segments with XHR/fetch: those are Media.
        precondition(of("XHR", "https://cdn.test/live/index.m3u8?token=1") == .media)
        precondition(of("fetch", "https://cdn.test/seg/0001.m4s") == .media)
        precondition(of("fetch", "https://cdn.test/a/manifest.mpd") == .media)
        precondition(of("video", "https://cdn.test/movie") == .media)
        precondition(of("track", "https://cdn.test/en") == .media)
        precondition(of("img", "https://cdn.test/pic") == .image)
        precondition(of("css", "https://cdn.test/bg.webp") == .image)
        precondition(of("css", "https://cdn.test/f/inter.woff2") == .font)
        precondition(of("link", "https://cdn.test/site.css") == .style)
        precondition(of("link", "https://cdn.test/preload.js") == .script)
        precondition(of("script", "https://cdn.test/app") == .script)
        precondition(of("other", "https://cdn.test/app.mjs") == .script)
        precondition(of("other", "https://cdn.test/data") == .other)
        precondition(of("resource", "https://cdn.test/f.TTF") == .font)

        let log = BrowserNetworkLog()
        log.record(url: "https://cdn.test/\(String(repeating: "a", count: 48)).ts", page: "https://a.test/", kind: "XHR", state: "Response")
        log.record(url: "https://ads.test/x", page: "https://a.test/", kind: "navigation", state: "Blocked by ad rules")
        precondition(log.entries[0].category == .media, "Category must come from the unredacted URL")
        precondition(log.entries[0].url.hasSuffix(".ts"), "The in-app view keeps the full URL")
        precondition(log.entries[0].redactedURL.contains("redacted") && !log.entries[0].isBlocked)
        log.record(url: "https://cdn.test/v.m3u8?token=secret-value", page: "https://a.test/watch?id=1", kind: "XHR", state: "Response")
        precondition(!log.exportText.contains("secret-value") && !log.exportText.contains("id=1"), "Default export must stay redacted")
        precondition(log.fullExportText.contains("token=secret-value"), "Full export keeps the URL")
        precondition(log.entries[1].isBlocked && log.entries[1].category == .document)
        print("CHECK: network log filter categories (media by extension, CSS-initiated fonts/images, blocked state)")
    }
}
