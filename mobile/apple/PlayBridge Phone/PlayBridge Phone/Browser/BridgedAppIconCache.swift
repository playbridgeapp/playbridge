import Foundation
import CryptoKit
import ImageIO

/// One artwork lookup. Only definite answers are remembered across launches.
enum BridgedAppIconFetch: Sendable {
    case data(Data)
    /// The site answered, but there is no usable image (non-200, too large or undecodable).
    case missing
    /// Offline, timed out or a server error: try again on a later load.
    case unreachable
}

/// Bounded, same-origin installation artwork. Images and definite misses survive launches.
actor BridgedAppIconCache {
    static let shared = BridgedAppIconCache()
    private let directory: URL
    private let fetch: @Sendable (URL) async -> BridgedAppIconFetch
    private var pending: [String: Task<(Data?, Bool), Never>] = [:]
    private var sources: [URL: String] = [:]
    private var generations: [URL: Int] = [:]

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
         .appendingPathComponent("bridged-app-icons"),
         fetch: @escaping @Sendable (URL) async -> BridgedAppIconFetch = { await BridgedAppIconCache.download($0) }) {
        self.directory = directory
        self.fetch = fetch
    }

    nonisolated private func file(_ origin: URL, _ suffix: String) -> URL {
        let key = SHA256.hash(data: Data(origin.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(key).\(suffix)")
    }

    nonisolated func cachedData(_ app: BridgedApp) -> Data? {
        let source = "v1:\(app.iconURL?.absoluteString ?? "")"
        guard (try? String(contentsOf: file(app.origin, "source"), encoding: .utf8)) == source else { return nil }
        return try? Data(contentsOf: file(app.origin, "png"))
    }

    func load(_ app: BridgedApp) async -> Data? {
        guard !Task.isCancelled else { return nil }
        let source = "v1:\(app.iconURL?.absoluteString ?? "")"
        let image = file(app.origin, "png"), marker = file(app.origin, "source")
        if (try? String(contentsOf: marker, encoding: .utf8)) == source { return try? Data(contentsOf: image) }
        sources[app.origin] = source
        let generation = generations[app.origin, default: 0]
        let key = app.origin.absoluteString + "\n" + source + "\n\(generation)"
        if let task = pending[key] { return await task.value.0 }
        let fetch = self.fetch
        let task = Task<(Data?, Bool), Never> {
            var unreachable = false
            for url in BridgedAppAppearance.iconCandidates(app) {
                guard !Task.isCancelled else { return (nil, true) }
                switch await fetch(url) {
                case .data(let data): if let png = Self.thumbnail(data) { return (png, false) }
                case .missing: break
                case .unreachable: unreachable = true
                }
            }
            return (nil, unreachable)
        }
        pending[key] = task
        let (data, unreachable) = await task.value
        pending[key] = nil
        guard generations[app.origin, default: 0] == generation, sources[app.origin] == source else { return nil }
        // A transient failure keeps the monogram for now without recording a permanent miss.
        if data == nil && unreachable { return nil }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            if let data { try data.write(to: image, options: .atomic) }
            else { try? FileManager.default.removeItem(at: image) }
            try source.write(to: marker, atomically: true, encoding: .utf8)
        } catch { /* Artwork is optional; the view keeps its monogram. */ }
        return data
    }

    func remove(_ origin: URL) {
        generations[origin, default: 0] += 1
        sources[origin] = nil
        for (key, task) in pending where key.hasPrefix(origin.absoluteString + "\n") { task.cancel() }
        try? FileManager.default.removeItem(at: file(origin, "png"))
        try? FileManager.default.removeItem(at: file(origin, "source"))
    }

    private static func thumbnail(_ data: Data) -> Data? {
        guard data.count <= 262144,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (1...2048).contains(width), (1...2048).contains(height),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 128,
                kCGImageSourceCreateThumbnailWithTransform: true,
              ] as CFDictionary) else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    private static func download(_ url: URL) async -> BridgedAppIconFetch {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 2
        configuration.timeoutIntervalForResource = 2
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration, delegate: NoIconRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse else { return .unreachable }
            if response.statusCode == 429 || response.statusCode >= 500 { return .unreachable }
            guard response.statusCode == 200, response.expectedContentLength <= 262144 else { return .missing }
            var data = Data()
            for try await byte in bytes {
                if data.count == 262144 { return .missing }
                data.append(byte)
            }
            return .data(data)
        } catch { return .unreachable }
    }
}

private final class NoIconRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
