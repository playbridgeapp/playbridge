import UIKit
import SwiftUI
import Combine

// Isolate unrelated connections/discovery; library, collections, local MIME types
// and the library/detail screens use production code.
final class BrowserDownload {
    var id = UUID(); var state = "Complete"; var fileURL: URL?
}
final class FixtureDownloads { var items: [BrowserDownload] = [] }
final class BrowserStore: ObservableObject { let downloads = FixtureDownloads() }
final class NavigationViewModel: ObservableObject {
    enum Screen { case dashboard, collections }
    func navigate(to: Screen) {}
}
struct DashboardNavigationButton: View {
    var body: some View { Image(systemName: "square.grid.2x2.fill").frame(width: 44, height: 44) }
}
struct ScreenBackButton: View {
    let destination: NavigationViewModel.Screen
    let accessibilityLabel: String
    var body: some View { Image(systemName: "chevron.left").frame(width: 36, height: 44) }
}
final class ConnectionViewModel: ObservableObject {
    var isConnected = false
    var destinationID: String? = "fixture"
    func castLocalMedia(url: String, title: String, contentType: String) {}
    func castMedia(url: String, title: String, headers: [String: String], contentType: String?) {}
}
struct DeviceConnectionSheet: View { var body: some View { Text("Fixture connection") } }

@main final class PhoneMediaLibraryChecks: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UIViewController(); window?.makeKeyAndVisible()
        Task { @MainActor in
            do { try await run(); print("PASS: media library persistence, classification, import, collections, downloads and UI") }
            catch { print("FAIL: \(error)"); exit(1) }
            exit(0)
        }
        return true
    }
    struct Failure: Error { let message: String }
    func check(_ condition: Bool, _ message: String) throws { if !condition { throw Failure(message: message) } }
    @MainActor func run() async throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let original = root.appendingPathComponent("Sunset.png")
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 360))
        let png = renderer.pngData { context in
            UIColor.systemIndigo.setFill(); context.fill(CGRect(x: 0, y: 0, width: 640, height: 360))
            UIColor.systemOrange.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 240, y: 80, width: 160, height: 160))
        }
        try png.write(to: original)
        let audio = root.appendingPathComponent("Recording.wav")
        // One second of valid mono 8 kHz/16-bit PCM.
        var wav = Data("RIFF".utf8)
        func u32(_ value: UInt32) { var value = value.littleEndian; withUnsafeBytes(of: &value) { wav.append(contentsOf: $0) } }
        func u16(_ value: UInt16) { var value = value.littleEndian; withUnsafeBytes(of: &value) { wav.append(contentsOf: $0) } }
        u32(16036); wav.append(Data("WAVEfmt ".utf8)); u32(16); u16(1); u16(1); u32(8000); u32(16000); u16(2); u16(16)
        wav.append(Data("data".utf8)); u32(16000); wav.append(Data(count: 16000)); try wav.write(to: audio)
        let library = PhoneMediaLibrary(directory: root.appendingPathComponent("library"))
        await library.importFiles([original, audio])
        try check(library.imported.count == 2 && library.error == nil, "File import failed")
        let image = library.imported.first { $0.kind == .image }!
        let local = try await library.resolve(image)
        try check(local != original, "Import retained temporary provider URL")
        let thumbnail = await PhoneMediaThumbnail.image(at: local, maxPixel: 320)
        try check(thumbnail != nil && thumbnail!.size.width <= 320, "Image thumbnail failed or decoded at full resolution")
        let copied = try Data(contentsOf: local)
        try check(copied == png, "Import changed bytes")
        let reopened = PhoneMediaLibrary(directory: library.directory)
        try check(reopened.imported.count == 2 && reopened.item(image.id) != nil, "Library did not survive restart")
        let collectionsFile = root.appendingPathComponent("collections.json")
        let collections = CollectionsStore(fileURL: collectionsFile)
        let group = collections.createCollection(name: "Favorites")
        collections.addLocalItem(to: group, media: image)
        collections.addLocalItem(to: group, media: image)
        collections.addItem(to: group, title: "Web stream", url: "https://example.test/video.mp4")
        let saved = CollectionsStore(fileURL: collectionsFile)
        try check(saved.collection(group)?.items.count == 2, "Collection duplicate or persistence failure")
        try check(saved.collection(group)?.items.first?.libraryItemID == image.id && saved.collection(group)?.items.first?.url == "", "Collection persisted an ephemeral LAN URL")
        let legacy = Data("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"title\":\"Legacy\",\"url\":\"https://example.test/file\",\"headers\":{},\"order\":0}".utf8)
        let decoded = try JSONDecoder().decode(CollectionItem.self, from: legacy)
        try check(decoded.libraryItemID == nil, "Legacy collection migration failed")
        let download = BrowserDownload(); download.fileURL = original
        library.refreshDownloads([download])
        try check(library.downloads.count == 1 && library.downloads[0].kind == .image, "Completed download missing")
        download.state = "Downloading"; library.refreshDownloads([download])
        try check(library.downloads.isEmpty, "Partial download shown as media")
        for (name, kind) in [("film.mkv", PhoneMedia.Kind.video), ("image.heic", .image), ("song.flac", .audio)] {
            try check(PhoneMedia.Kind.classify(URL(fileURLWithPath: name)) == kind, "Media classification failed")
        }
        try check(PhoneMedia.Kind.classify(URL(fileURLWithPath: "text.txt")) == nil, "Unsupported file classified as media")
        try check(LocalFileServer.mimeType(for: original) == "image/png", "Image cast MIME incorrect")
        UserDefaults.standard.set("Images", forKey: "pb_library_category")
        let host = UIHostingController(rootView: PhoneFilesScreen()
            .environmentObject(library).environmentObject(collections).environmentObject(BrowserStore())
            .environmentObject(ConnectionViewModel()).environmentObject(NavigationViewModel()).preferredColorScheme(.dark))
        window?.rootViewController = host
        try await Task.sleep(nanoseconds: 1_000_000_000)
        let screenshot = UIGraphicsImageRenderer(size: window!.bounds.size).pngData { _ in window!.drawHierarchy(in: window!.bounds, afterScreenUpdates: true) }
        try screenshot.write(to: fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("library.png"))
        library.removeImport(image)
        try check(fm.fileExists(atPath: original.path) && !fm.fileExists(atPath: local.path), "Removal deleted source or kept copy")
        do { _ = try await library.resolve(image); throw Failure(message: "Deleted file still resolved") }
        catch is PhoneMediaLibrary.LibraryError {}
    }
}
