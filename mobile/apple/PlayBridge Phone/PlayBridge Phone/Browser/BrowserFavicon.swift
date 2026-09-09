import SwiftUI
import ImageIO

/// Android's favicon service, keyed only by hostname (never page paths or tokens).
/// URLCache persists HTTP-cacheable icons across launches; decoded images and failures
/// are bounded in memory. Concurrent rows for one site share a single request.
actor BrowserFaviconCache {
    static let shared = BrowserFaviconCache()
    private nonisolated let images = FaviconMemoryCache()
    private var pending: [URL: Task<UIImage?, Never>] = [:]
    private var failures: [URL: Date] = [:]
    private let session: URLSession

    init(session: URLSession? = nil) {
        if let session { self.session = session }
        else {
            let config = URLSessionConfiguration.default
            config.urlCache = URLCache(memoryCapacity: 2 * 1024 * 1024,
                                      diskCapacity: 20 * 1024 * 1024, diskPath: "browser-favicons")
            config.httpCookieStorage = nil
            config.urlCredentialStorage = nil
            config.timeoutIntervalForRequest = 10
            self.session = URLSession(configuration: config)
        }
    }

    static func requestURL(for page: String?) -> URL? {
        guard let page, let url = URL(string: page),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host?.lowercased(), host.contains("."),
              !host.hasSuffix(".local"), !host.hasSuffix(".localhost"),
              !host.contains(":"), host.contains(where: { $0.isLetter }) else { return nil }
        var target = URLComponents(string: "https://www.google.com/s2/favicons")!
        target.queryItems = [URLQueryItem(name: "domain_url", value: host), URLQueryItem(name: "sz", value: "64")]
        return target.url
    }

    nonisolated func cachedImage(for url: URL) -> UIImage? { images.cache.object(forKey: url as NSURL) }

    func image(for url: URL) async -> UIImage? {
        if let image = cachedImage(for: url) { return image }
        if let task = pending[url] { return await task.value }
        if let until = failures[url], until > Date() { return nil }
        let session = session
        let task = Task<UIImage?, Never> {
            do {
                let (bytes, response) = try await session.bytes(from: url)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                      response.expectedContentLength <= 256 * 1024 else { return nil }
                var data = Data()
                for try await byte in bytes {
                    guard data.count < 256 * 1024 else { return nil }
                    data.append(byte)
                }
                guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceThumbnailMaxPixelSize: 84,
                        kCGImageSourceCreateThumbnailWithTransform: true
                      ] as CFDictionary) else { return nil }
                return UIImage(cgImage: image)
            } catch { return nil }
        }
        pending[url] = task
        let image = await task.value
        pending[url] = nil
        if let image { images.cache.setObject(image, forKey: url as NSURL, cost: 84 * 84 * 4) }
        else {
            failures = failures.filter { $0.value > Date() }
            if failures.count >= 200 { failures.removeAll() }
            failures[url] = Date().addingTimeInterval(60)
        }
        return image
    }
}

/// NSCache supports concurrent access; stored images are immutable after decoding.
private final class FaviconMemoryCache: @unchecked Sendable {
    let cache = NSCache<NSURL, UIImage>()
    init() {
        cache.countLimit = 200
        cache.totalCostLimit = 4 * 1024 * 1024
    }
}

struct BrowserFaviconView: View {
    let pageURL: String?
    @State private var loadedURL: URL?
    @State private var image: UIImage?
    @State private var failedURL: URL?
    private var requestURL: URL? { BrowserFaviconCache.requestURL(for: pageURL) }

    var body: some View {
        let url = requestURL
        let cached = url.flatMap { BrowserFaviconCache.shared.cachedImage(for: $0) }
        let displayed = cached ?? (loadedURL == url ? image : nil)
        Group {
            if let displayed { Image(uiImage: displayed).resizable().scaledToFit() }
            else if let url, failedURL != url {
                // Reserve the icon's space without flashing a globe while loading.
                RoundedRectangle(cornerRadius: 6).fill(Theme.onSurfaceVariant.opacity(0.08))
            } else {
                Image(systemName: pageURL == nil ? "house" : "globe")
                    .font(Theme.font(size: 24)).foregroundStyle(Theme.onSurfaceVariant)
            }
        }
        .accessibilityHidden(true)
        .task(id: url) {
            guard let url else { return }
            let loaded = await BrowserFaviconCache.shared.image(for: url)
            guard !Task.isCancelled else { return }
            loadedURL = url
            image = loaded
            failedURL = loaded == nil ? url : nil
        }
    }
}
