import Foundation

enum MediaImageRequest {
    static let maximumBytes = 64 * 1024 * 1024

    static func origin(_ url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host?.lowercased(), !host.isEmpty,
              url.user == nil, url.password == nil else { return nil }
        return "\(scheme)://\(host):\(url.port ?? (scheme == "https" ? 443 : 80))"
    }

    /// The embedded proxy's listener, the only address a page-controlled image may load from.
    static func isProxyURL(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "http" && url.host == "127.0.0.1" && url.user == nil && url.password == nil
    }

    static func request(url: URL, headerOrigin: URL, headers: [String: String],
                        detectedBy: String = "", allowedPrivateOrigins: [String] = [],
                        viaProxy: Bool = false) -> URLRequest? {
        guard origin(url) != nil, origin(headerOrigin) != nil else { return nil }
        // URLSession cannot enforce DNS/connected-peer grants for untrusted pages, so a
        // page-controlled image loads only from the proxy, which applies the item's
        // network policy, headers and approved origins when it fetches the upstream.
        if PageMediaPolicy.isPageControlled(detectedBy: detectedBy) {
            guard viaProxy, isProxyURL(url), isProxyURL(headerOrigin) else { return nil }
            var request = URLRequest(url: url)
            request.timeoutInterval = 20
            return request
        }
        guard allowedPrivateOrigins.count <= 16,
              allowedPrivateOrigins.allSatisfy({ value in
                  guard let entry = URL(string: value) else { return false }
                  return origin(entry) != nil && (entry.path.isEmpty || entry.path == "/")
                      && entry.query == nil && entry.fragment == nil
              }) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        if origin(url) == origin(headerOrigin) {
            for (name, value) in headers {
                guard name.range(of: #"^[A-Za-z0-9-]{1,64}$"#, options: .regularExpression) != nil,
                      name.lowercased() != "host", !value.contains("\r"), !value.contains("\n") else { return nil }
                request.setValue(value, forHTTPHeaderField: name)
            }
        }
        return request
    }
}
