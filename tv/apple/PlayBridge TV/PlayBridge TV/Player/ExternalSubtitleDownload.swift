import Foundation

enum ExternalSubtitleDownloadError: Error {
    case invalidURL
    case invalidResponse
    case httpStatus(Int)
    case tooLarge
    case unsupportedFormat
    case attachmentFailed

    var message: String {
        switch self {
        case .invalidURL: return "The subtitle URL is not valid."
        case .invalidResponse: return "The subtitle server did not return a valid response."
        case .httpStatus(let status): return "The subtitle server returned HTTP \(status)."
        case .tooLarge: return "The subtitle file is too large."
        case .unsupportedFormat: return "This subtitle is not a WebVTT or SRT file."
        case .attachmentFailed: return "VLC could not attach this subtitle. Playback will continue."
        }
    }
}

enum ExternalSubtitleDownload {
    static let maximumBytes = 8 * 1024 * 1024

    static func request(for url: URL, playbackHeaders: [String: String]?) -> URLRequest {
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("text/vtt, text/plain;q=0.9, */*;q=0.5", forHTTPHeaderField: "Accept")

        // A subtitle URL can be on a different host than the video. Carry only non-credential
        // request context; never forward Authorization, Cookie, or arbitrary custom headers.
        for (name, value) in playbackHeaders ?? [:] {
            switch name.lowercased() {
            case "user-agent", "accept-language":
                request.setValue(value, forHTTPHeaderField: name)
            case "origin":
                if let origin = URLComponents(string: value),
                   ["http", "https"].contains(origin.scheme?.lowercased() ?? ""),
                   origin.host != nil,
                   origin.user == nil,
                   origin.password == nil,
                   origin.query == nil,
                   origin.fragment == nil,
                   origin.path.isEmpty || origin.path == "/" {
                    request.setValue(value, forHTTPHeaderField: "Origin")
                }
            default: break
            }
        }
        return request
    }

#if canImport(SwiftProtobuf)
    static func isValid(_ resource: Playbridge_SubtitleResource) -> Bool {
        guard let url = URL(string: resource.url),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil, url.user == nil, url.password == nil,
              resource.headers.count <= 32,
              resource.label.count <= 256, resource.language.count <= 64 else { return false }
        var headerBytes = 0
        for (name, value) in resource.headers {
            guard name.range(of: #"^[A-Za-z0-9-]{1,64}$"#, options: .regularExpression) != nil,
                  name.lowercased() != "host", value.count <= 4096,
                  !value.contains("\r"), !value.contains("\n") else { return false }
            headerBytes += name.count + value.count
        }
        return headerBytes <= 16_384
    }

    static func request(for resource: Playbridge_SubtitleResource) -> URLRequest? {
        guard isValid(resource), let url = URL(string: resource.url) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        for (name, value) in resource.headers { request.setValue(value, forHTTPHeaderField: name) }
        return request
    }
#endif

    static func subtitleExtension(for data: Data) -> String? {
        let start = String(decoding: data.prefix(4096), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\u{FEFF}", with: "")
        if start.hasPrefix("WEBVTT") { return "vtt" }
        if start.range(
            of: #"(?m)^\d{2}:\d{2}:\d{2}[,.]\d{3}\s+-->\s+\d{2}:\d{2}:\d{2}[,.]\d{3}"#,
            options: .regularExpression
        ) != nil { return "srt" }
        return nil
    }

    static func prepare(file: URL, response: URLResponse?) throws -> URL {
        guard let response = response as? HTTPURLResponse else {
            throw ExternalSubtitleDownloadError.invalidResponse
        }
        guard (200...299).contains(response.statusCode) else {
            throw ExternalSubtitleDownloadError.httpStatus(response.statusCode)
        }
        let size = try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber
        guard let size, size.uint64Value <= UInt64(maximumBytes) else {
            throw ExternalSubtitleDownloadError.tooLarge
        }
        let data = try Data(contentsOf: file)
        guard let ext = subtitleExtension(for: data) else {
            throw ExternalSubtitleDownloadError.unsupportedFormat
        }
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("playbridge-subtitle-\(UUID().uuidString)")
            .appendingPathExtension(ext)
        try FileManager.default.moveItem(at: file, to: destination)
        return destination
    }
}

#if canImport(SwiftProtobuf)
/// Keeps a subtitle's own headers on same-origin redirects and strips them elsewhere.
final class ScopedSubtitleDownload: NSObject, URLSessionTaskDelegate, URLSessionDownloadDelegate {
    private let resource: Playbridge_SubtitleResource
    private var session: URLSession?
    private var task: URLSessionDownloadTask?
    private var completion: ((Result<URL, Error>) -> Void)?

    init(resource: Playbridge_SubtitleResource) { self.resource = resource }

    func start(_ completion: @escaping (Result<URL, Error>) -> Void) {
        guard let request = ExternalSubtitleDownload.request(for: resource) else {
            DispatchQueue.main.async { completion(.failure(ExternalSubtitleDownloadError.invalidURL)) }
            return
        }
        self.completion = completion
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.downloadTask(with: request)
        self.task = task
        task.resume()
    }

    func cancel() { task?.cancel(); session?.invalidateAndCancel() }

    private func finish(_ result: Result<URL, Error>) {
        guard let completion else { return }
        self.completion = nil
        task = nil
        session?.finishTasksAndInvalidate()
        session = nil
        DispatchQueue.main.async { completion(result) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        finish(Result {
            try ExternalSubtitleDownload.prepare(file: location, response: downloadTask.response)
        })
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
        else { finish(.failure(ExternalSubtitleDownloadError.invalidResponse)) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > Int64(ExternalSubtitleDownload.maximumBytes) ||
            totalBytesExpectedToWrite > Int64(ExternalSubtitleDownload.maximumBytes) {
            downloadTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        guard let source = URL(string: resource.url), let target = request.url,
              ["http", "https"].contains(target.scheme?.lowercased() ?? ""),
              target.host != nil, target.user == nil, target.password == nil else {
            completionHandler(nil)
            return
        }
        func port(_ url: URL) -> Int? {
            url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80)
        }
        let sameOrigin = source.scheme?.lowercased() == target.scheme?.lowercased()
            && source.host?.lowercased() == target.host?.lowercased()
            && port(source) == port(target)
        if sameOrigin { completionHandler(request); return }
        var stripped = request
        for name in resource.headers.keys { stripped.setValue(nil, forHTTPHeaderField: name) }
        for name in ["Authorization", "Cookie", "Origin", "Referer"] {
            stripped.setValue(nil, forHTTPHeaderField: name)
        }
        completionHandler(stripped)
    }
}
#endif
