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
