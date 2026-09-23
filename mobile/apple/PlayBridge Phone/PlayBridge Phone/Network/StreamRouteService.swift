import Foundation

enum StreamRoute: String, CaseIterable, Identifiable {
    case direct, phone, proxy
    var id: String { rawValue }
    var label: String {
        switch self { case .direct: return "Direct"; case .phone: return "Via phone"; case .proxy: return "Via proxy" }
    }
}

struct RemoteProxyConfiguration {
    var baseURL = ""
    var password = ""

    func validatedURL() throws -> URL {
        guard let url = URL(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
            throw StreamRoutingError.message("Configure a valid HTTP or HTTPS proxy URL first.")
        }
        return url
    }
}

enum StreamRoutingError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case .message(let text): return text } }
}

struct RoutedStream {
    let url: URL
    let headers: [String: String]
    var registration: PhoneProxyRegistration?
    var sourceURL: String?
    var sourceHeaders: [String: String] = [:]
}

/// Shared by local playback and receiver sends. No route silently falls back
/// to another route; the selected route is part of the user's request.
struct StreamRouteService {
    var phone: (String, [String: String], String?) async throws -> RoutedStream = { url, headers, type in
        let registration = try await PhoneSenderServices.shared.register(url: url, headers: headers, contentType: type)
        return RoutedStream(url: registration.url, headers: [:], registration: registration)
    }
    var remote: (String, [String: String], String?, RemoteProxyConfiguration) async throws -> RoutedStream = RemoteProxyClient.register

    func prepare(url: String, headers: [String: String], contentType: String?, route: StreamRoute,
                 configuration: RemoteProxyConfiguration) async throws -> RoutedStream {
        try Task.checkCancellation()
        guard let original = URL(string: url), ["http", "https"].contains(original.scheme?.lowercased() ?? "") else {
            throw StreamRoutingError.message("This stream does not have a playable HTTP or HTTPS URL.")
        }
        var result: RoutedStream
        switch route {
        case .direct: result = RoutedStream(url: original, headers: headers)
        case .phone: result = try await phone(url, headers, contentType)
        case .proxy:
            _ = try configuration.validatedURL()
            result = try await remote(url, headers, contentType, configuration)
        }
        result.sourceURL = url
        result.sourceHeaders = headers
        return result
    }
}

private final class ProxyRegistrationDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Registration contains media credentials; do not replay it to a redirect.
        completionHandler(nil)
    }
}

enum RemoteProxyClient {
    static func request(url: String, headers: [String: String], contentType: String?, configuration: RemoteProxyConfiguration) throws -> URLRequest {
        let base = try configuration.validatedURL()
        var components = URLComponents(url: base.appendingPathComponent("register"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "token", value: configuration.password)]
        // The server decodes form-style queries: a literal plus must be %2B.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        var request = URLRequest(url: components.url!, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["url": url, "headers": headers]
        if let contentType { body["content_type"] = contentType }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }

    static func register(url: String, headers: [String: String], contentType: String?, configuration: RemoteProxyConfiguration) async throws -> RoutedStream {
        let request = try request(url: url, headers: headers, contentType: contentType, configuration: configuration)
        let settings = URLSessionConfiguration.ephemeral
        settings.httpCookieStorage = nil
        settings.urlCredentialStorage = nil
        let session = URLSession(configuration: settings, delegate: ProxyRegistrationDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse else { throw StreamRoutingError.message("The proxy returned an invalid response.") }
            guard (200..<300).contains(response.statusCode) else {
                throw StreamRoutingError.message("Proxy registration failed: HTTP \(response.statusCode). Check the server URL and password.")
            }
            guard bytes.count <= 512 * 1024,
                  let json = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
                throw StreamRoutingError.message("The proxy returned an invalid response.")
            }
            let raw = (json["proxy_url"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? json["encrypted_url"] as? String ?? ""
            guard let url = URL(string: raw), ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil,
                  url.user == nil, url.password == nil else { throw StreamRoutingError.message("The proxy returned no usable stream URL.") }
            return RoutedStream(url: url, headers: [:])
        } catch is CancellationError { throw CancellationError() }
        catch let error as StreamRoutingError { throw error }
        catch {
            if Task.isCancelled { throw CancellationError() }
            // Foundation errors can contain a registration URL with its password.
            throw StreamRoutingError.message("Couldn’t reach the proxy. Check its address and network connection.")
        }
    }
}
