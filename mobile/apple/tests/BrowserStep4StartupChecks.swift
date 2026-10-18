// SPDX-License-Identifier: MPL-2.0
import UIKit
import SwiftUI
import WebKit

private final class ImagePhotoSpy: ObservableObject, BrowserImagePhotoSaving {
    @Published var saves = 0
    func save(_ image: BrowserImageData) async throws {
        guard image.type == "public.png" else { throw BrowserImageError.photos }
        saves += 1
    }
}

private struct LibraryImageFixture: View {
    @ObservedObject var browser: BrowserStore
    @ObservedObject var nav: NavigationViewModel
    let data: BrowserDataStore
    let photos: ImagePhotoSpy
    let pasteboard: UIPasteboard
    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Button("Show History") { nav.navigate(to: .history) }
                Button("Show Bookmarks") { nav.navigate(to: .bookmarks) }
                Button("Show Images") { nav.navigate(to: .browser) }
            }
            switch nav.currentScreen {
            case .history: HistoryScreen()
            case .bookmarks: BookmarksScreen()
            default:
                if let tab = browser.activeTab {
                    ImagePageFixture(tab: tab, photos: photos, pasteboard: pasteboard)
                }
            }
        }
        .environmentObject(browser).environmentObject(nav).environmentObject(data)
    }
}
private struct ImagePageFixture: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var photos: ImagePhotoSpy
    let pasteboard: UIPasteboard
    var body: some View {
        VStack(spacing: 4) {
            Text(pasteboard.hasImages ? "Copied image ready" : "No copied image").accessibilityIdentifier("imageCopyState")
            Text("Photos saved: \(photos.saves)").accessibilityIdentifier("imagePhotoState")
            Text(tab.blockedNotice?.message ?? "").accessibilityIdentifier("imageActionStatus")
            Text(tab.urlString).lineLimit(1).accessibilityIdentifier("imagePageAddress")
            WebViewContainer(tab: tab)
        }
    }
}

extension BrowserStartupChecks {
    @MainActor func showLibraryImageFixture() async throws {
        let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let browser = BrowserStore(tabsFileURL: directory.appendingPathComponent("tabs.json"))
        browser.browserVisible = true
        let tab = browser.activeTab!
        tab.selectUserAgent(.custom, custom: "PlayBridgeImageFixture/1.0")
        let view = tab.webView
        view.frame = window!.bounds
        window!.rootViewController!.view.addSubview(view)
        tab.load(base + "/image-actions")
        try await wait("image fixture ready") { view.title == "Image Actions" && !view.isLoading }
        // Exercise HTTP cookies/UA through the production loader. Invalid/oversized
        // responses must fail rather than reach Photos, Share or the pasteboard.
        let image = try await BrowserImageActions.load(URL(string: base + "/protected.png?direct=1")!, from: view)
        try check(image.type == "public.png", "Authenticated image was not decoded")
        for path in ["/oversized-image", "/invalid-image"] {
            do {
                _ = try await BrowserImageActions.load(URL(string: base + path)!, from: view)
                throw Failure(message: "Invalid image response was accepted")
            } catch is BrowserImageError { }
        }
        let data = BrowserDataStore(directory: directory.appendingPathComponent("library"))
        data.recordVisit(url: base + "/parent", title: "Needle match")
        data.recordVisit(url: base + "/child", title: "Other visit")
        data.addBookmark(url: base + "/child", title: "Fixture bookmark")
        let photos = ImagePhotoSpy()
        let pasteboard = UIPasteboard.withUniqueName()
        tab.imageActions.photoSaver = photos
        tab.imageActions.pasteboard = pasteboard
        let nav = NavigationViewModel(); nav.currentScreen = .browser
        view.removeFromSuperview()
        window?.rootViewController = UIHostingController(rootView: LibraryImageFixture(browser: browser, nav: nav,
            data: data, photos: photos, pasteboard: pasteboard))
        print("CHECK: authenticated image cookie/UA fetch and oversized/non-image rejection passed")
    }
}
