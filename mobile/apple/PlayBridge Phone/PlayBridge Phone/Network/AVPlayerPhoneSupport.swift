import AVFoundation
import Foundation

/// Decisions for the phone's AVPlayer engine that don't need a player: routing, error
/// classification and failure wording. Never keeps or returns URLs, header values or raw
/// error text; only fixed categories leave this type.
enum PhoneAVPlayerSupport {
    static func isLoopback(_ url: URL) -> Bool {
        ["127.0.0.1", "localhost", "::1", "[::1]"].contains(url.host?.lowercased() ?? "")
    }

    /// AVPlayer can't attach request headers to every HLS child request, and App Transport
    /// Security blocks plain HTTP outside the local network. Both go through the phone's
    /// loopback proxy, which sends the headers itself. Loopback addresses are already the proxy.
    static func needsLoopback(url: URL, headers: [String: String]) -> Bool {
        guard !isLoopback(url) else { return false }
        return !headers.isEmpty || url.scheme?.lowercased() == "http"
    }

    /// The proxy is told when a stream is HLS so it rewrites the playlist's children.
    static func contentTypeHint(url: URL, contentType: String?) -> String? {
        if let contentType, !contentType.isEmpty { return contentType }
        return ["m3u8", "m3u"].contains(url.pathExtension.lowercased()) ? "application/vnd.apple.mpegurl" : nil
    }

    private static let coreMediaDomain = "CoreMediaErrorDomain"

    /// The network cause of an AVPlayer error, from its error chain. CoreMedia reports HTTP
    /// failures of HLS requests as its own codes (-12938 is HTTP 404, -12660 HTTP 403).
    static func networkIssue(from error: Error?) -> PhonePlaybackNetworkIssue? {
        var current = error as NSError?
        for _ in 0..<5 {
            guard let error = current else { break }
            if error.domain == NSURLErrorDomain {
                switch URLError.Code(rawValue: error.code) {
                case .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
                     .serverCertificateNotYetValid, .clientCertificateRejected:
                    return .certificate
                case .secureConnectionFailed: return .tls
                case .cannotFindHost, .dnsLookupFailed: return .dns
                case .timedOut: return .timeout
                case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet: return .connection
                case .userAuthenticationRequired: return .http(401)
                default: break
                }
            }
            if error.domain == coreMediaDomain {
                if error.code == -12938 { return .http(404) }
                if error.code == -12660 { return .http(403) }
            }
            if let status = httpStatus(in: error.localizedDescription) { return .http(status) }
            current = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return nil
    }

    /// An `AVPlayerItemErrorLogEvent`: its status code and comment ("HTTP 403: Forbidden").
    static func networkIssue(logStatusCode: Int, domain: String, comment: String?) -> PhonePlaybackNetworkIssue? {
        if domain == NSURLErrorDomain {
            return networkIssue(from: NSError(domain: domain, code: logStatusCode))
        }
        if (400...599).contains(logStatusCode) { return .http(logStatusCode) }
        if let comment, let status = httpStatus(in: comment) { return .http(status) }
        if domain == coreMediaDomain { return networkIssue(from: NSError(domain: domain, code: logStatusCode)) }
        return nil
    }

    private static func httpStatus(in text: String) -> Int? {
        guard let range = text.range(of: #"HTTP (\d{3})"#, options: .regularExpression),
              let code = Int(text[range].dropFirst(5)), (400...599).contains(code) else { return nil }
        return code
    }

    private static let formatCodes: Set<Int> = [
        AVError.Code.fileFormatNotRecognized.rawValue, AVError.Code.decoderNotFound.rawValue,
        AVError.Code.operationNotSupportedForAsset.rawValue, AVError.Code.failedToParse.rawValue,
        AVError.Code.decodeFailed.rawValue,
    ]

    /// AVPlayer can't open this container or codec (MKV, most ASS-styled files, some codecs).
    static func isUnsupportedFormat(_ error: Error?) -> Bool {
        var current = error as NSError?
        for _ in 0..<5 {
            guard let error = current else { break }
            if error.domain == AVFoundationErrorDomain, formatCodes.contains(error.code) { return true }
            current = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    private static func isServerMisconfigured(_ error: Error?) -> Bool {
        var current = error as NSError?
        for _ in 0..<5 {
            guard let error = current else { break }
            if error.domain == AVFoundationErrorDomain, error.code == AVError.Code.serverIncorrectlyConfigured.rawValue { return true }
            current = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    static let loopbackUnavailableMessage = "AVPlayer needs the phone’s stream proxy for this stream, and the proxy couldn’t prepare it. Try again, or switch back to mpv."

    static func failureMessage(error: Error?, networkIssue: PhonePlaybackNetworkIssue?) -> String {
        if isUnsupportedFormat(error) {
            return "AVPlayer can’t play this video format. MKV files, styled (ASS) subtitles and some codecs need mpv. Switch back to mpv to play it."
        }
        if isServerMisconfigured(error) {
            return "The stream server doesn’t answer the requests AVPlayer needs. Switch back to mpv to play it."
        }
        switch networkIssue {
        case .certificate: return "AVPlayer could not verify the stream server’s TLS certificate. The video has not been opened."
        case .tls: return "AVPlayer could not establish a secure connection to the stream server."
        case .http(let status): return PlaybackFailure.describe(nil, httpStatus: status).message
        case .dns: return "AVPlayer could not resolve the stream server’s hostname."
        case .timeout: return "The stream server took too long to respond. Try again."
        case .connection: return "AVPlayer could not connect to the stream server. Check your network and try again."
        case nil: return "AVPlayer could not play this stream. Try again, switch back to mpv, or choose another stream."
        }
    }

    /// A two- or three-letter language code from a media selection option.
    static func languageCode(extendedTag: String?, localeCode: String?) -> String? {
        let tag = extendedTag ?? localeCode
        guard let tag, !tag.isEmpty, tag.lowercased() != "und" else { return nil }
        return tag
    }
}

/// Ticked subtitle files the AVPlayer engine draws as an overlay caption, so they work
/// whatever the stream's own subtitles are. Track IDs start at `firstID` to stay apart
/// from the stream's own (legible) tracks.
struct PhoneOverlaySubtitles {
    static let firstID = 1000
    typealias Cue = (start: Double, end: Double, text: String)
    private(set) var tracks: [PhonePlaybackTrack] = []
    private var cues: [Int: [Cue]] = [:]

    /// Adds one file's text as a track; false when it has no SRT/WebVTT cues (ASS, bitmap, empty).
    @discardableResult
    mutating func add(_ subtitle: PhoneExternalSubtitle, contents: String) -> Bool {
        let parsed = WebsiteCaptionParser.parse(contents)
        guard !parsed.isEmpty else { return false }
        let id = Self.firstID + tracks.count
        let label = [subtitle.title, subtitle.language].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        tracks.append(PhonePlaybackTrack(id: id, label: label.isEmpty ? "Subtitle \(tracks.count + 1)" : label,
                                         language: subtitle.language, externalURL: subtitle.mpvPath))
        cues[id] = parsed
        return true
    }

    static func isOverlay(_ id: Int?) -> Bool { (id ?? 0) >= firstID }

    func caption(id: Int?, position: Double, delay: Double) -> String {
        guard let id, let cues = cues[id], position.isFinite else { return "" }
        return WebsiteCaptionParser.caption(cues, position: position, delay: delay)
    }
}
