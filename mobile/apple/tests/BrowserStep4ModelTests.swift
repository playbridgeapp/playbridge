// SPDX-License-Identifier: MPL-2.0
import Foundation
import CoreGraphics
import ImageIO

enum BrowserStep4ModelTests {
    static func run() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        let now = calendar.date(from: DateComponents(year: 2026, month: 3, day: 9, hour: 0, minute: 5))!
        let yesterday = calendar.date(from: DateComponents(year: 2026, month: 3, day: 8, hour: 0, minute: 5))!
        let lastNight = calendar.date(from: DateComponents(year: 2026, month: 3, day: 8, hour: 23, minute: 59))!
        let six = calendar.date(byAdding: .day, value: -6, to: now)!
        let seven = calendar.date(byAdding: .day, value: -7, to: now)!
        let entries = [now, yesterday, lastNight, six, seven].enumerated().map {
            HistoryEntry(url: "https://example.test/\($0.offset)", title: $0.offset == 0 ? "Épisode" : "Other", lastVisited: $0.element, visitCount: 1)
        }
        let groups = BrowserLibraryQuery.history(entries.reversed(), query: "", now: now, calendar: calendar, locale: Locale(identifier: "en_US"))
        precondition(groups.count == 4 && groups[0].title == "Today" && groups[1].title == "Yesterday")
        precondition(groups[1].entries.map(\.id) == [entries[2].id, entries[1].id])
        precondition(groups[2].title == "Tuesday" && groups[3].title == "Mar 2, 2026")
        let french = BrowserLibraryQuery.history([entries[3], entries[4]], query: "", now: now, calendar: calendar, locale: Locale(identifier: "fr_FR"))
        precondition(french[0].title == "mardi" && french[1].title.contains("mars"))
        precondition(BrowserLibraryQuery.history(entries, query: "EPISODE", now: now, calendar: calendar).flatMap(\.entries).map(\.id) == [entries[0].id])
        precondition(BrowserLibraryQuery.history(entries, query: "/4", now: now, calendar: calendar).flatMap(\.entries).map(\.id) == [entries[4].id])
        precondition(BrowserLibraryQuery.history(entries, query: "missing").isEmpty)
        var utc = calendar; utc.timeZone = TimeZone(secondsFromGMT: 0)!
        precondition(BrowserLibraryQuery.history([entries[0], entries[2]], query: "", now: now, calendar: utc).count == 1)
        precondition(BrowserLibraryQuery.history([], query: "").isEmpty)

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let data = BrowserDataStore(directory: directory)
        data.addBookmark(url: "https://example.test/original", title: "Original")
        let original = data.bookmarks[0]
        precondition(data.updateBookmark(original.id, title: "Updated", address: " localhost:8080/path "))
        precondition(data.bookmarks[0].id == original.id && data.bookmarks[0].addedAt == original.addedAt)
        precondition(data.bookmarks[0].url == "http://localhost:8080/path")
        for invalid in ["", " ", "some words", "javascript:alert(1)", "data:image/png;base64,xx", "file:///tmp/a", "about:blank", "https://", "https://example.test:65536"] {
            precondition(!data.updateBookmark(original.id, title: "Invalid", address: invalid))
        }
        precondition(!data.updateBookmark(UUID(), title: "Missing", address: "example.test"))
        precondition(BrowserLibraryQuery.bookmarks(data.bookmarks, query: "updated").count == 1)
        precondition(BrowserLibraryQuery.bookmarks(data.bookmarks, query: "LOCALHOST").count == 1)
        precondition(BrowserLibraryQuery.bookmarks(data.bookmarks, query: "original").isEmpty)
        data.flushPendingSaves()
        let restored = BrowserDataStore(directory: directory)
        precondition(restored.bookmarks == data.bookmarks)
        precondition(restored.updateBookmark(original.id, title: "", address: "example.test/new"))
        precondition(restored.bookmarks[0].title == "https://example.test/new")
        restored.flushPendingSaves()
        let context = CGContext(data: nil, width: 2, height: 2, bitsPerComponent: 8, bytesPerRow: 8,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let png = NSMutableData()
        let destination = CGImageDestinationCreateWithData(png, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, context.makeImage()!, nil)
        precondition(CGImageDestinationFinalize(destination))
        let image = try BrowserImageData.decode(png as Data)
        precondition(image.type == "public.png" && image.fileExtension == "png")
        let inline = URL(string: "data:image/png;base64," + image.data.base64EncodedString())!
        precondition(tryDataURL(inline) == image.data)
        for input in ["file:///tmp/a.png", "javascript:alert(1)", "blob:https://example.test/1", "data:text/html,hello"] {
            precondition(!BrowserImageData.allowedURL(URL(string: input)!))
        }
        do { _ = try BrowserImageData.decode(Data(repeating: 0, count: BrowserImageData.maximumBytes + 1)); preconditionFailure("Oversized image accepted") }
        catch BrowserImageError.tooLarge { }
        do { _ = try BrowserImageData.decode(Data("not an image".utf8)); preconditionFailure("Invalid image accepted") }
        catch BrowserImageError.unsupported { }
        print("CHECK: history midnight/DST/day-7/locale grouping, search, bookmark identity/background persistence, image scheme/data/size validation")
    }
    private static func tryDataURL(_ url: URL) -> Data? { try? BrowserImageData.dataURL(url).data }
}
