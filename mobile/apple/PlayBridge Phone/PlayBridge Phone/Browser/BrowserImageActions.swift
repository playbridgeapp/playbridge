// SPDX-License-Identifier: MPL-2.0
import UIKit
import WebKit
import Photos

protocol BrowserImagePhotoSaving {
    func save(_ image: BrowserImageData) async throws
}
struct BrowserImagePhotoSaver: BrowserImagePhotoSaving {
    func save(_ image: BrowserImageData) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw BrowserImageError.permission }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            PHPhotoLibrary.shared().performChanges {
                let options = PHAssetResourceCreationOptions()
                options.uniformTypeIdentifier = image.type
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: image.data, options: options)
            } completionHandler: { success, _ in
                if success { continuation.resume() } else { continuation.resume(throwing: BrowserImageError.photos) }
            }
        }
    }
}

/// URLSession's serial delegate bounds bytes before appending, including unknown
/// lengths/chunked responses. An ephemeral cookie jar applies domain/path/Secure
/// restrictions on each redirect; no browser cookies enter shared storage.
private final class BrowserImageFetch: NSObject, URLSessionDataDelegate {
    private var data = Data()
    private var failure: BrowserImageError?
    private var continuation: CheckedContinuation<Data, Error>?
    private var session: URLSession?
    func start(_ request: URLRequest, configuration: URLSessionConfiguration) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
            self.session = session
            session.dataTask(with: request).resume()
        }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if response.expectedContentLength > Int64(BrowserImageData.maximumBytes) { failure = .tooLarge }
        else if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                let url = response.url, ["http", "https"].contains(url.scheme?.lowercased() ?? "") { /* Validate actual bytes with ImageIO after completion. */ }
        else { failure = .network }
        completionHandler(failure == nil ? .allow : .cancel)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive bytes: Data) {
        guard data.count + bytes.count <= BrowserImageData.maximumBytes else { failure = .tooLarge; dataTask.cancel(); return }
        data.append(bytes)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let continuation = self.continuation
        self.continuation = nil
        if let failure { continuation?.resume(throwing: failure) }
        else if error != nil { continuation?.resume(throwing: BrowserImageError.network) }
        else { continuation?.resume(returning: data) }
        data = Data()
        session.finishTasksAndInvalidate()
        self.session = nil
    }
}

enum BrowserImageAction: CaseIterable {
    case save, share, copy, open
    var title: String {
        switch self { case .save: return "Save Image"; case .share: return "Share Image"; case .copy: return "Copy Image"; case .open: return "Open Image in New Tab" }
    }
    var symbol: String {
        switch self { case .save: return "square.and.arrow.down"; case .share: return "square.and.arrow.up"; case .copy: return "doc.on.doc"; case .open: return "plus.square.on.square" }
    }
}

final class BrowserImageActions {
    weak var tab: BrowserTab?
    var photoSaver: BrowserImagePhotoSaving = BrowserImagePhotoSaver()
    var pasteboard: UIPasteboard = .general
    private var busy = false
    init(tab: BrowserTab) { self.tab = tab }

    func menu(_ url: URL) -> [UIAction] {
        BrowserImageAction.allCases.map { action in
            UIAction(title: action.title, image: UIImage(systemName: action.symbol)) { [weak self] _ in self?.perform(action, url: url) }
        }
    }
    func standaloneMenu(_ url: URL, at point: CGPoint, in view: WKWebView) {
        guard let tab, tab.isActive(), let presenter = presenter(view), presenter.presentedViewController == nil else { return }
        let sheet = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        for action in BrowserImageAction.allCases {
            sheet.addAction(UIAlertAction(title: action.title, style: .default) { [weak self, weak sheet] _ in
                sheet?.dismiss(animated: true) { self?.perform(action, url: url) }
            })
        }
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(origin: point, size: CGSize(width: 1, height: 1))
        presenter.present(sheet, animated: true)
    }
    func perform(_ action: BrowserImageAction, url: URL) {
        guard let tab, tab.isActive(), BrowserImageData.allowedURL(url) else { return }
        if action == .open { tab.onOpenNewTab?(url, false); return }
        guard !busy, let view = tab.loadedWebView else { return }
        busy = true
        let document = tab.pageCastDocumentID
        Task { @MainActor [weak self, weak tab, weak view] in
            guard let self else { return }
            defer { self.busy = false }
            do {
                guard let view else { return }
                let image = try await Self.load(url, from: view)
                guard let tab, tab.isActive(), tab.pageCastDocumentID == document, tab.loadedWebView === view else { return }
                switch action {
                case .save:
                    try await self.photoSaver.save(image)
                    if tab.isActive(), tab.pageCastDocumentID == document { tab.showBrowserMessage("Image saved to Photos.") }
                case .copy:
                    self.pasteboard.setData(image.data, forPasteboardType: image.type)
                    tab.showBrowserMessage("Image copied.")
                case .share:
                    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("browser-image-" + UUID().uuidString, isDirectory: true)
                    let file = directory.appendingPathComponent("Image." + image.fileExtension)
                    try await Task.detached(priority: .utility) {
                        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                        try image.data.write(to: file, options: .atomic)
                    }.value
                    guard tab.isActive(), tab.pageCastDocumentID == document, tab.loadedWebView === view,
                          let presenter = self.presenter(view), presenter.presentedViewController == nil else {
                        try? FileManager.default.removeItem(at: directory)
                        return
                    }
                    let share = UIActivityViewController(activityItems: [file], applicationActivities: nil)
                    share.completionWithItemsHandler = { _, _, _, _ in try? FileManager.default.removeItem(at: directory) }
                    share.popoverPresentationController?.sourceView = view
                    share.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
                    presenter.present(share, animated: true)
                case .open: break
                }
            } catch {
                guard let tab, tab.isActive(), tab.pageCastDocumentID == document else { return }
                let message: String
                switch error as? BrowserImageError {
                case .tooLarge: message = "Image exceeds the 50 MB limit."
                case .unsupported: message = "This image format isn’t supported."
                case .permission: message = "Photos access is needed to save this image."
                case .photos: message = "Couldn’t save the image to Photos."
                default: message = "Couldn’t download this image. Try again."
                }
                tab.showBrowserMessage(message)
            }
        }
    }
    private func presenter(_ view: UIView) -> UIViewController? {
        var responder: UIResponder? = view
        while let current = responder {
            if let controller = current as? UIViewController { return controller }
            responder = current.next
        }
        return view.window?.rootViewController
    }
    @MainActor static func load(_ url: URL, from view: WKWebView) async throws -> BrowserImageData {
        guard BrowserImageData.allowedURL(url) else { throw BrowserImageError.unsupported }
        if url.scheme?.lowercased() == "data" { return try await Task.detached(priority: .utility) { try BrowserImageData.dataURL(url) }.value }
        let cookies = await withCheckedContinuation { continuation in
            view.configuration.websiteDataStore.httpCookieStore.getAllCookies { continuation.resume(returning: $0) }
        }
        let agent: String
        if let custom = view.customUserAgent { agent = custom }
        else { agent = (try? await view.evaluateJavaScript("navigator.userAgent")) as? String ?? "" }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        cookies.forEach { configuration.httpCookieStorage?.setCookie($0) }
        var request = URLRequest(url: url)
        request.setValue(agent, forHTTPHeaderField: "User-Agent")
        let data = try await BrowserImageFetch().start(request, configuration: configuration)
        return try await Task.detached(priority: .utility) { try BrowserImageData.decode(data) }.value
    }
}
