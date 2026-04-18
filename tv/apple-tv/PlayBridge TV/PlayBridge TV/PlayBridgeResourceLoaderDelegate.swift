import AVFoundation
import Foundation
import UniformTypeIdentifiers

// MARK: - URL Scheme Helpers

/// Custom URL schemes used to route AVPlayer requests through the resource loader.
/// AVPlayer does not call the resource loader for standard `http://` / `https://` URLs,
/// so we rewrite them to `pb-http://` / `pb-https://` on ingestion and unwrap them here.
enum PlayBridgeScheme {
    static let https = "pb-https"
    static let http = "pb-http"

    /// Rewrite a standard URL to the custom scheme so AVAssetResourceLoader intercepts it.
    static func rewrite(_ url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        switch components.scheme {
        case "https": components.scheme = https
        case "http": components.scheme = http
        default: return nil
        }
        return components.url
    }

    /// Unwrap a custom-scheme URL back to the real HTTP(S) URL.
    static func unwrap(_ url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        switch components.scheme {
        case https: components.scheme = "https"
        case http: components.scheme = "http"
        default: return nil
        }
        return components.url
    }
}

// MARK: - Resource Loader Delegate

/// Intercepts AVPlayer network requests for `pb-https://` / `pb-http://` URLs and re-issues
/// them with the caller-supplied custom headers attached to every request — including after
/// HTTP redirects. This is the correct fix for Debrid links where `AVURLAssetHTTPHeaderFieldsKey`
/// silently drops auth headers when the CDN redirect changes domain.
final class PlayBridgeResourceLoaderDelegate: NSObject, AVAssetResourceLoaderDelegate {

    private let headers: [String: String]
    /// Active URLSessionDataTasks keyed by loading-request object identity.
    private var activeTasks: [ObjectIdentifier: URLSessionDataTask] = [:]
    private let delegateQueue = DispatchQueue(
        label: "com.playbridge.ResourceLoader", qos: .userInitiated)

    init(headers: [String: String]) {
        self.headers = headers
    }

    // MARK: AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let requestURL = loadingRequest.request.url,
            let realURL = PlayBridgeScheme.unwrap(requestURL)
        else { return false }

        fulfill(loadingRequest, realURL: realURL)
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let key = ObjectIdentifier(loadingRequest)
        delegateQueue.async {
            self.activeTasks[key]?.cancel()
            self.activeTasks.removeValue(forKey: key)
        }
    }

    // MARK: - Private

    private func fulfill(_ loadingRequest: AVAssetResourceLoadingRequest, realURL: URL) {
        var urlRequest = URLRequest(url: realURL)

        // Attach custom headers to every request (survives redirects since we control all calls)
        headers.forEach { urlRequest.setValue($1, forHTTPHeaderField: $0) }

        // Forward range request from AVPlayer (required for seeking into large MP4 files)
        if let dataRequest = loadingRequest.dataRequest {
            let offset = dataRequest.requestedOffset
            if dataRequest.requestsAllDataToEndOfResource {
                urlRequest.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
            } else {
                let end = offset + Int64(dataRequest.requestedLength) - 1
                urlRequest.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
            }
        }

        let key = ObjectIdentifier(loadingRequest)
        let task = URLSession.shared.dataTask(with: urlRequest) { [weak self] data, response, error in
            guard let self = self else { return }
            self.delegateQueue.async {
                self.activeTasks.removeValue(forKey: key)
            }

            if let error = error {
                loadingRequest.finishLoading(with: error)
                return
            }

            // Fill content-info request (needed for the first request AVPlayer issues)
            if let httpResponse = response as? HTTPURLResponse,
                let infoRequest = loadingRequest.contentInformationRequest
            {
                if let mimeType = httpResponse.value(forHTTPHeaderField: "Content-Type")?
                    .components(separatedBy: ";").first?
                    .trimmingCharacters(in: .whitespaces)
                {
                    if #available(tvOS 14.0, *) {
                        infoRequest.contentType = UTType(mimeType: mimeType)?.identifier
                    } else {
                        infoRequest.contentType = Self.mimeToUTI(mimeType)
                    }
                }
                infoRequest.contentLength = httpResponse.expectedContentLength
                infoRequest.isByteRangeAccessSupported = true
            }

            if let data = data, !data.isEmpty {
                loadingRequest.dataRequest?.respond(with: data)
            }
            loadingRequest.finishLoading()
        }

        delegateQueue.async {
            self.activeTasks[key] = task
        }
        task.resume()
    }

    /// Fallback UTI mapping for tvOS < 14 where `UTType(mimeType:)` is unavailable.
    private static func mimeToUTI(_ mime: String) -> String {
        switch mime {
        case "video/mp4": return "public.mpeg-4"
        case "video/x-m4v": return "com.apple.m4v-video"
        case "video/quicktime": return "com.apple.quicktime-movie"
        case "video/MP2T": return "public.mpeg-2-transport-stream"
        case "application/x-mpegURL", "application/vnd.apple.mpegurl": return "public.m3u-playlist"
        case "audio/mpeg", "audio/mp3": return "public.mp3"
        case "audio/aac": return "public.aac-audio"
        default: return "public.data"
        }
    }
}
