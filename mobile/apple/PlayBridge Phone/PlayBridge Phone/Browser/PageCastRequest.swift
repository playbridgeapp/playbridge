import Foundation
import CoreFoundation
import Darwin

struct PageCastError: Error {
    let code: String
}

/// The native trust boundary for requests from page JavaScript. Never forward the
/// original dictionary: only the validated receiver fields below may cross it.
struct PageCastRequest {
    var items: [[String: Any]]
    var startIndex: Int
    var metadata: [String: Any]?
    var skipPreplay: Bool
    var privateOrigins: Set<String>

    static func parse(_ value: Any, linked: Bool = false) throws -> PageCastRequest {
        try checkSize(value)
        let source: [String: Any]
        if let array = value as? [Any], !linked { source = ["items": array] }
        else if let object = value as? [String: Any] { source = object }
        else { throw PageCastError(code: "invalid_request") }
        let items = try parseItems(source["items"] ?? (linked ? [] : [source]), linked: linked)
        let requestedIndex = integer(source["startIndex"]) ?? 0
        if linked && !(0..<items.count).contains(requestedIndex) { throw PageCastError(code: "invalid_request") }
        let metadata = try source["metadata"].map(parseMetadata)
        if let skip = source["skipPreplay"], !isBoolean(skip) { throw PageCastError(code: "invalid_request") }
        return PageCastRequest(items: items, startIndex: max(0, min(requestedIndex, items.count - 1)),
                               metadata: metadata, skipPreplay: source["skipPreplay"] as? Bool ?? false,
                               privateOrigins: try parsePrivateOrigins(source["privateNetworkOrigins"]))
    }

    static func parseItems(_ value: Any, linked: Bool, allowEmpty: Bool = false) throws -> [[String: Any]] {
        try checkSize(value)
        guard let values = value as? [[String: Any]], values.count <= 50,
              allowEmpty || !values.isEmpty else { throw PageCastError(code: "invalid_request") }
        var ids = Set<String>()
        return try values.map { source in
            guard let url = source["url"] as? String, validateURL(url) else { throw PageCastError(code: "invalid_request") }
            var item: [String: Any] = ["url": url, "detectedBy": linked ? "linked_page" : "page_cast"]
            if linked {
                guard let id = source["id"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      id.utf16.count <= 128, ids.insert(id).inserted else { throw PageCastError(code: "invalid_request") }
                item["id"] = id
            }
            for (key, maxLength) in [("title", 4096), ("contentType", 256)] {
                if let value = source[key] as? String, !value.isEmpty, value.utf16.count <= maxLength { item[key] = value }
            }
            if let value = source["headers"] { item["headers"] = try parseHeaders(value) }
            if let value = source["mediaKind"] {
                guard let kind = value as? String, ["video", "audio", "image"].contains(kind) else { throw PageCastError(code: "invalid_request") }
                item["mediaKind"] = kind
            }
            if let value = source["displayDurationMs"] {
                guard let duration = integer(value), duration >= 0 else { throw PageCastError(code: "invalid_request") }
                item["displayDurationMs"] = duration
            }
            var subtitleCount = 0
            if let value = source["subtitles"] {
                guard let urls = value as? [String], urls.count <= 16, urls.allSatisfy(validateURL) else { throw PageCastError(code: "invalid_request") }
                item["subtitles"] = urls
                subtitleCount += urls.count
            }
            if let value = source["subtitleResources"] {
                guard let resources = value as? [[String: Any]], resources.count <= 16 else { throw PageCastError(code: "invalid_request") }
                item["subtitleResources"] = try resources.map { source -> [String: Any] in
                    guard let url = source["url"] as? String, validateURL(url) else { throw PageCastError(code: "invalid_request") }
                    var resource: [String: Any] = ["url": url]
                    if let headers = source["headers"] { resource["headers"] = try parseHeaders(headers) }
                    for (key, limit) in [("label", 256), ("language", 64)] {
                        if let value = source[key] {
                            guard let text = value as? String, !text.isEmpty, text.utf16.count <= limit else { throw PageCastError(code: "invalid_request") }
                            resource[key] = text
                        }
                    }
                    return resource
                }
                subtitleCount += resources.count
            }
            guard subtitleCount <= 16 else { throw PageCastError(code: "invalid_request") }
            if let metadata = source["metadata"] { item["visualMetadata"] = try parseMetadata(metadata) }
            return item
        }
    }

    func playlistCommand(allowedPrivateOrigins: Set<String>) -> String {
        let wireItems = items.map { source -> [String: Any] in
            var item = source
            item.removeValue(forKey: "id")
            if !allowedPrivateOrigins.isEmpty { item["allowedPrivateOrigins"] = allowedPrivateOrigins.sorted() }
            return item
        }
        var payload: [String: Any] = ["items": wireItems, "startIndex": startIndex, "skipPreplay": skipPreplay]
        if let metadata { payload["visualMetadata"] = metadata }
        let object: [String: Any] = ["type": "command", "action": "playlist", "payload": payload]
        // Every field was validated as bounded JSON at the bridge boundary.
        guard let data = try? JSONSerialization.data(withJSONObject: object), let result = String(data: data, encoding: .utf8) else { return "" }
        return result
    }

    static func validateURL(_ raw: String) -> Bool { PageCastNetworkPolicy.components(raw) != nil }

    static func requestedPrivateOrigins(items: [[String: Any]], declared: Set<String>, metadata: [String: Any]? = nil) async throws -> Set<String> {
        var urls = Array(try parsePrivateOrigins(Array(declared)))
        if let metadata { urls += ["posterUrl", "backdropUrl", "logoUrl", "artworkUrl"].compactMap { metadata[$0] as? String } }
        for item in items {
            if let url = item["url"] as? String { urls.append(url) }
            urls += item["subtitles"] as? [String] ?? []
            urls += (item["subtitleResources"] as? [[String: Any]] ?? []).compactMap { $0["url"] as? String }
            if let metadata = item["visualMetadata"] as? [String: Any] {
                urls += ["posterUrl", "backdropUrl", "logoUrl", "artworkUrl"].compactMap { metadata[$0] as? String }
            }
        }
        let uniqueURLs = Array(Set(urls))
        let lookup = Task.detached(priority: .userInitiated) {
            var result = Set<String>()
            var classifications: [String: PageCastNetworkPolicy.AddressClass] = [:]
            for raw in uniqueURLs {
                try Task.checkCancellation()
                guard let origin = PageCastNetworkPolicy.origin(raw),
                      let host = PageCastNetworkPolicy.components(raw)?.host else { throw PageCastError(code: "invalid_request") }
                let classification: PageCastNetworkPolicy.AddressClass
                if let cached = classifications[host] { classification = cached }
                else {
                    classification = try PageCastNetworkPolicy.resolve(host)
                    classifications[host] = classification
                }
                switch classification {
                case .forbidden: throw PageCastError(code: "private_network_denied")
                case .privateLAN: result.insert(origin)
                case .publicInternet: break
                }
            }
            try Task.checkCancellation()
            guard result.count <= 16 else { throw PageCastError(code: "invalid_request") }
            return result
        }
        return try await withTaskCancellationHandler(operation: { try await lookup.value }, onCancel: { lookup.cancel() })
    }

    static func parsePrivateOrigins(_ value: Any?) throws -> Set<String> {
        guard let value else { return [] }
        guard let values = value as? [String], values.count <= 16 else { throw PageCastError(code: "invalid_request") }
        var result = Set<String>()
        for raw in values {
            guard let parts = PageCastNetworkPolicy.components(raw), ["", "/"].contains(parts.path),
                  parts.query == nil, parts.fragment == nil, let host = parts.host,
                  !PageCastNetworkPolicy.isLoopbackHost(host),
                  let origin = PageCastNetworkPolicy.origin(raw) else { throw PageCastError(code: "invalid_request") }
            if let literal = PageCastNetworkPolicy.literalClass(host), literal == .forbidden { throw PageCastError(code: "invalid_request") }
            result.insert(origin)
        }
        return result
    }

    private static func checkSize(_ value: Any) throws {
        guard JSONSerialization.isValidJSONObject(value), let data = try? JSONSerialization.data(withJSONObject: value),
              data.count <= 64 * 1024 else { throw PageCastError(code: "invalid_request") }
    }

    static func integer(_ value: Any?) -> Int? {
        guard let value, let number = value as? NSNumber, !isBoolean(number), number.doubleValue.isFinite,
              number.doubleValue.rounded(.towardZero) == number.doubleValue,
              number.doubleValue >= Double(Int.min), number.doubleValue < Double(Int.max) else { return nil }
        return number.intValue
    }

    private static func isBoolean(_ value: Any) -> Bool {
        guard let number = value as? NSNumber else { return false }
        return CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func parseHeaders(_ value: Any) throws -> [String: String] {
        guard let headers = value as? [String: String], headers.count <= 16 else { throw PageCastError(code: "invalid_request") }
        let allowed = Set(["authorization", "cookie", "referer", "origin", "user-agent", "accept", "accept-language"])
        var names = Set<String>()
        var bytes = 0
        for (name, value) in headers {
            let lower = name.lowercased()
            bytes += name.utf8.count + value.utf8.count
            guard allowed.contains(lower), names.insert(lower).inserted, bytes <= 16 * 1024,
                  !value.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }),
                  !(lower == "origin" || lower == "referer") || validateURL(value) else { throw PageCastError(code: "invalid_request") }
        }
        return headers
    }

    private static func parseMetadata(_ value: Any) throws -> [String: Any] {
        guard let object = value as? [String: Any] else { throw PageCastError(code: "invalid_request") }
        var keys = 0
        func validate(_ value: Any, depth: Int) -> Bool {
            guard depth <= 8 else { return false }
            if let text = value as? String { return text.utf16.count <= 4096 }
            if value is NSNull { return true }
            if let number = value as? NSNumber { return number.doubleValue.isFinite }
            if let array = value as? [Any] { return array.count <= 64 && array.allSatisfy { validate($0, depth: depth + 1) } }
            if let object = value as? [String: Any] {
                for (key, value) in object {
                    keys += 1
                    if keys > 64 || key.utf16.count > 256 || !validate(value, depth: depth + 1) { return false }
                }
                return true
            }
            return false
        }
        guard validate(object, depth: 0), let data = try? JSONSerialization.data(withJSONObject: object), data.count <= 16 * 1024 else { throw PageCastError(code: "invalid_request") }
        // Mirror VisualMetadata's schema instead of forwarding unknown keys to
        // strict receivers. Android's decoder similarly ignores unknown fields.
        var result: [String: Any] = [:]
        let strings = ["title", "year", "rating", "runtime", "overview", "episodeTitle", "imdbId", "tmdbId", "artist", "album", "albumArtist"]
        for key in strings {
            if let value = object[key], !(value is NSNull) {
                guard let text = value as? String else { throw PageCastError(code: "invalid_request") }
                result[key] = text
            }
        }
        if result["title"] == nil { result["title"] = "" }
        for key in ["posterUrl", "backdropUrl", "logoUrl", "artworkUrl"] {
            if let value = object[key], !(value is NSNull) {
                guard let text = value as? String, validateURL(text) else { throw PageCastError(code: "invalid_request") }
                result[key] = text
            }
        }
        for key in ["genres", "cast", "director"] {
            if let value = object[key] {
                guard let array = value as? [String] else { throw PageCastError(code: "invalid_request") }
                result[key] = array
            }
        }
        for key in ["season", "episode", "trackNumber"] {
            if let value = object[key], !(value is NSNull) {
                guard let number = integer(value), number >= 0, number <= Int(Int32.max) else { throw PageCastError(code: "invalid_request") }
                result[key] = number
            }
        }
        return result
    }
}

enum PageCastNetworkPolicy {
    enum AddressClass { case publicInternet, privateLAN, forbidden }

    static func origin(_ raw: String) -> String? {
        guard let parts = components(raw), let scheme = parts.scheme?.lowercased(), let host = parts.url?.host?.lowercased() else { return nil }
        let bracketed = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        return "\(scheme)://\(bracketed):\(parts.port ?? (scheme == "https" ? 443 : 80))"
    }

    static func components(_ raw: String) -> URLComponents? {
        guard !raw.isEmpty, raw.utf16.count <= 8192,
              !raw.unicodeScalars.contains(where: { $0.value <= 32 || $0.value == 127 }),
              !raw.contains("\\"), let parts = URLComponents(string: raw),
              ["http", "https"].contains(parts.scheme?.lowercased() ?? ""),
              let host = parts.host, !host.isEmpty, !host.contains("*"), !host.contains("%"),
              parts.user == nil, parts.password == nil, parts.url != nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true else { return nil }
        return parts
    }

    static func isLoopbackHost(_ host: String) -> Bool {
        let normalized = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return normalized == "localhost" || normalized.hasSuffix(".localhost")
    }

    static func literalClass(_ host: String) -> AddressClass? {
        let bare = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        var ipv4 = in_addr()
        if inet_pton(AF_INET, bare, &ipv4) == 1 {
            return withUnsafeBytes(of: ipv4) { classifyV4(Array($0)) }
        }
        var ipv6 = in6_addr()
        if inet_pton(AF_INET6, bare, &ipv6) == 1 {
            return withUnsafeBytes(of: ipv6) { classifyV6(Array($0)) }
        }
        return nil
    }

    static func resolve(_ host: String) throws -> AddressClass {
        if isLoopbackHost(host) { return .forbidden }
        if let literal = literalClass(host) { return literal }
        let bare = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var addresses: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(bare, nil, &hints, &addresses) == 0, let first = addresses else { throw PageCastError(code: "network_unavailable") }
        defer { freeaddrinfo(first) }
        var current: UnsafeMutablePointer<addrinfo>? = first
        let normalizedHost = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        var result: AddressClass = normalizedHost.hasSuffix(".local") ? .privateLAN : .publicInternet
        while let node = current {
            let info = node.pointee
            let classification: AddressClass
            if info.ai_family == AF_INET, let address = info.ai_addr {
                classification = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                    withUnsafeBytes(of: $0.pointee.sin_addr) { classifyV4(Array($0)) }
                }
            } else if info.ai_family == AF_INET6, let address = info.ai_addr {
                classification = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
                    withUnsafeBytes(of: $0.pointee.sin6_addr) { classifyV6(Array($0)) }
                }
            } else { classification = .forbidden }
            if classification == .forbidden { return .forbidden }
            if classification == .privateLAN { result = .privateLAN }
            current = info.ai_next
        }
        return result
    }

    private static func classifyV4(_ bytes: [UInt8]) -> AddressClass {
        let a = Int(bytes[0]), b = Int(bytes[1]), c = Int(bytes[2])
        if a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168) { return .privateLAN }
        if a == 0 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
            (a == 100 && (64...127).contains(b)) || (a == 192 && b == 0) ||
            (a == 198 && (18...19).contains(b)) || (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113) { return .forbidden }
        return .publicInternet
    }

    private static func classifyV6(_ bytes: [UInt8]) -> AddressClass {
        if bytes.prefix(10).allSatisfy({ $0 == 0 }), bytes[10] == 255, bytes[11] == 255 { return classifyV4(Array(bytes.suffix(4))) }
        if bytes.prefix(12).allSatisfy({ $0 == 0 }) { return .forbidden }
        if bytes[0] & 0xfe == 0xfc { return .privateLAN }
        if bytes[0] == 0xff || (bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80) ||
            (bytes[0] == 0xfe && bytes[1] & 0xc0 == 0xc0) || Array(bytes.prefix(4)) == [0x20, 0x01, 0x0d, 0xb8] { return .forbidden }
        return .publicInternet
    }
}
