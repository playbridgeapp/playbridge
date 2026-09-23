import Foundation
import WebKit
import Combine

final class BrowserDownload: ObservableObject, Identifiable {
    let id: UUID
    @Published var filename: String
    @Published var state: String
    @Published var fraction: Double = 0
    @Published var message: String?
    var fileURL: URL?
    fileprivate var download: WKDownload?
    fileprivate var webView: WKWebView?
    fileprivate var request: URLRequest?
    fileprivate var resumeData: Data?
    fileprivate var observation: NSKeyValueObservation?
    var canRetry: Bool { ["Failed", "Cancelled"].contains(state) && webView != nil && (resumeData != nil || request != nil) }

    init(id: UUID = UUID(), filename: String = "Download", state: String = "Downloading") {
        self.id = id; self.filename = filename; self.state = state
    }
}

/// WebKit owns cookie/authenticated request handling. Only file metadata is persisted;
/// request URLs, headers and opaque resume data remain in memory for this session.
final class BrowserDownloads: NSObject, ObservableObject, WKDownloadDelegate {
    @Published private(set) var items: [BrowserDownload] = []
    private let directory: URL
    private var manifest: URL { directory.appendingPathComponent("index.json") }
    private struct Saved: Codable { let id: UUID; let filename: String; let complete: Bool }

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("BrowserDownloads", isDirectory: true)
        super.init()
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: manifest), let saved = try? JSONDecoder().decode([Saved].self, from: data) {
            items = saved.map { record in
                let item = BrowserDownload(id: record.id, filename: Self.safeFilename(record.filename), state: "Interrupted")
                let path = destination(item)
                if record.complete && FileManager.default.fileExists(atPath: path.path) {
                    item.fileURL = path; item.state = "Complete"; item.fraction = 1
                } else {
                    item.message = "Open the website to download this file again."
                    try? FileManager.default.removeItem(at: path)
                }
                return item
            }
        }
    }
    static func safeFilename(_ input: String) -> String {
        let name = String((input as NSString).lastPathComponent.prefix(180))
            .components(separatedBy: .controlCharacters).joined()
        return name.isEmpty || name == "." || name == ".." ? "Download" : name
    }
    private func destination(_ item: BrowserDownload) -> URL {
        directory.appendingPathComponent(item.id.uuidString, isDirectory: true).appendingPathComponent(item.filename)
    }
    private func save() {
        let saved = items.map { Saved(id: $0.id, filename: $0.filename, complete: $0.state == "Complete") }
        if let data = try? JSONEncoder().encode(saved) { try? data.write(to: manifest, options: .atomic) }
    }
    func adopt(_ download: WKDownload, webView: WKWebView) {
        let item = BrowserDownload()
        item.webView = webView
        item.request = download.originalRequest
        items.insert(item, at: 0)
        attach(download, to: item)
        save()
    }
    private func attach(_ download: WKDownload, to item: BrowserDownload) {
        item.download = download
        item.state = "Downloading"; item.message = nil
        download.delegate = self
        item.observation = download.progress.observe(\.fractionCompleted, options: [.initial, .new]) { [weak item] progress, _ in
            let fraction = progress.fractionCompleted
            DispatchQueue.main.async { item?.fraction = fraction }
        }
    }
    private func item(for download: WKDownload) -> BrowserDownload? { items.first { $0.download === download } }
    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
        guard let item = item(for: download) else { completionHandler(nil); return }
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            item.state = "Failed"; item.message = "The server refused the download (HTTP \(http.statusCode))."
            completionHandler(nil)
            return
        }
        item.filename = Self.safeFilename(suggestedFilename)
        let path = destination(item)
        do {
            try FileManager.default.createDirectory(at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
            item.fileURL = path
            save()
            completionHandler(path)
        } catch {
            item.state = "Failed"; item.message = "Couldn’t create a file. Check available storage."
            completionHandler(nil)
        }
    }
    func downloadDidFinish(_ download: WKDownload) {
        guard let item = item(for: download) else { return }
        item.state = "Complete"; item.fraction = 1
        item.download = nil; item.observation = nil; item.webView = nil
        item.resumeData = nil; item.request = nil
        save()
    }
    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        guard let item = item(for: download) else { return }
        item.resumeData = resumeData
        item.state = "Failed"
        item.message = "The download stopped. Check your connection and available storage, then retry."
        item.download = nil; item.observation = nil
        save()
    }
    func cancel(_ item: BrowserDownload) {
        guard let download = item.download else { return }
        // Detach first so a late failure callback cannot overwrite cancellation.
        item.download = nil; item.observation = nil
        item.state = "Cancelling"
        download.cancel { [weak self, weak item] data in
            guard let self, let item, self.items.contains(where: { $0 === item }) else { return }
            item.resumeData = data
            item.state = "Cancelled"
            self.save()
        }
        save()
    }
    func retry(_ item: BrowserDownload) {
        guard item.download == nil, item.state != "Starting", let view = item.webView else { return }
        item.state = "Starting"; item.message = nil
        let completion: (WKDownload) -> Void = { [weak self, weak item] download in
            guard let self, let item, self.items.contains(where: { $0 === item }) else { download.cancel { _ in }; return }
            self.attach(download, to: item)
        }
        if let data = item.resumeData {
            item.resumeData = nil
            view.resumeDownload(fromResumeData: data, completionHandler: completion)
        } else if let request = item.request {
            if let path = item.fileURL { try? FileManager.default.removeItem(at: path) }
            item.fraction = 0
            view.startDownload(using: request, completionHandler: completion)
        }
    }
    func remove(_ item: BrowserDownload) {
        cancel(item)
        items.removeAll { $0 === item }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(item.id.uuidString))
        save()
    }
}
