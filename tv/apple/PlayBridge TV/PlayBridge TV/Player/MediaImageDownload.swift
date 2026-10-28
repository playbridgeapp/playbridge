import Foundation
import ImageIO
import UIKit

/// Ephemeral, bounded ImageIO download. GIFs use their first frame.
final class MediaImageDownload: NSObject, URLSessionDownloadDelegate {
    private let url: URL
    private let headerOrigin: URL
    private let headers: [String: String]
    private let detectedBy: String
    private let allowedPrivateOrigins: [String]
    private var session: URLSession?
    private var completion: ((UIImage?) -> Void)?

    init(url: URL, headerOrigin: URL, headers: [String: String], detectedBy: String,
         allowedPrivateOrigins: [String]) {
        self.url = url
        self.headerOrigin = headerOrigin
        self.headers = headers
        self.detectedBy = detectedBy
        self.allowedPrivateOrigins = allowedPrivateOrigins
    }

    func start(_ completion: @escaping (UIImage?) -> Void) {
        self.completion = completion
        guard let request = request(for: url) else { finish(nil); return }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        session.downloadTask(with: request).resume()
    }

    func cancel() { session?.invalidateAndCancel() }

    private func request(for target: URL) -> URLRequest? {
        MediaImageRequest.request(url: target, headerOrigin: headerOrigin, headers: headers,
                                  detectedBy: detectedBy, allowedPrivateOrigins: allowedPrivateOrigins)
    }

    private func finish(_ image: UIImage?) {
        guard let completion else { return }
        self.completion = nil
        session?.finishTasksAndInvalidate()
        session = nil
        DispatchQueue.main.async { completion(image) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        guard let response = downloadTask.response as? HTTPURLResponse,
              (200...299).contains(response.statusCode),
              let size = try? location.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size <= MediaImageRequest.maximumBytes,
              let source = CGImageSourceCreateWithURL(location as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: 3840,
                  kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary) else { finish(nil); return }
        finish(UIImage(cgImage: image))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(nil)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > MediaImageRequest.maximumBytes || totalBytesExpectedToWrite > MediaImageRequest.maximumBytes {
            downloadTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Rebuild instead of inheriting URLSession's credential headers across origins.
        completionHandler(newRequest.url.flatMap { request(for: $0) })
    }
}
