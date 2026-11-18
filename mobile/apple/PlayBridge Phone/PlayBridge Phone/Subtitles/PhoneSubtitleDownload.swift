import Foundation

/// Downloads a subtitle ticked in the cast sheet for the phone player. The phone fetches it with
/// the subtitle's own headers and gives mpv a local file, so mpv never waits on the network,
/// never sends the video's headers to the subtitle's server, and no proxy is involved (a
/// blob subtitle served from the phone's own LAN address can't go through the checked proxy).
enum PhoneSubtitleDownload {
    static let maxBytes = 8 * 1024 * 1024

    enum Failure: String, Error {
        case network, http, tooLarge, notSubtitles, playlist, write
    }

    /// Files live here until the playback session that uses them closes.
    static var directory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("phone-subtitles", isDirectory: true)
    }

    static func download(url string: String, headers: [String: String]) async -> Result<URL, Failure> {
        guard let url = URL(string: string), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
            return .failure(.network)
        }
        var request = URLRequest(url: url, timeoutInterval: 20)
        for (name, value) in headers where name.caseInsensitiveCompare("Range") != .orderedSame {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        let data: Data
        do {
            let (bytes, response) = try await session.bytes(for: request)
            defer { bytes.task.cancel() }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return .failure(.http) }
            if http.expectedContentLength > Int64(maxBytes) { return .failure(.tooLarge) }
            var buffer = Data()
            for try await byte in bytes {
                guard buffer.count < maxBytes else { return .failure(.tooLarge) }
                buffer.append(byte)
            }
            data = buffer
        } catch {
            return .failure(.network)
        }
        return save(data, name: url.lastPathComponent)
    }

    /// Checks the content, stores it as UTF-8 and names it by its format.
    static func save(_ data: Data, name: String) -> Result<URL, Failure> {
        let text = SubtitleContentCheck.decodeHead(data)
        let start = text.drop { $0 == "\u{FEFF}" || $0.isWhitespace }
        if start.hasPrefix("#EXTM3U") { return .failure(.playlist) }
        guard SubtitleContentCheck.looksLikeSubtitles(String(text.prefix(8 * 1024))) else { return .failure(.notSubtitles) }
        let known = ["vtt", "srt", "ass", "ssa", "ttml"]
        let named = (name as NSString).pathExtension.lowercased()
        let ext: String
        if known.contains(named) { ext = named }
        else if start.hasPrefix("WEBVTT") { ext = "vtt" }
        else if start.lowercased().hasPrefix("[script info]") { ext = "ass" }
        else if start.hasPrefix("<") { ext = "ttml" }
        else { ext = "srt" }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let file = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
            try Data(text.utf8).write(to: file, options: [.atomic, .completeFileProtection])
            return .success(file)
        } catch {
            return .failure(.write)
        }
    }

    static func remove(_ files: [URL]) {
        for file in files { try? FileManager.default.removeItem(at: file) }
    }
}
