import Foundation

#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore

/// Process-lifetime callbacks. Rust invokes open/read on its blocking workers,
/// never on the main thread; URLSession delivers bytes on a separate queue.
enum AppleProxyUpstream {
    enum SetupError: LocalizedError {
        case unsupportedABI(UInt32)
        case registrationFailed

        var errorDescription: String? {
            switch self {
            case .unsupportedABI(let version):
                return "Unsupported stream proxy upstream ABI \(version); expected 1. Rebuild the Apple Cast Core artifact."
            case .registrationFailed:
                return "The native stream proxy did not accept Apple networking callbacks."
            }
        }
    }

    private static let handlesLock = NSLock()
    private static var nextHandle: Int64 = 1
    private static var handles: [Int64: Response] = [:]

    static func install() throws {
        let version = pb_proxy_upstream_abi_version()
        guard version == 1 else { throw SetupError.unsupportedABI(version) }
        pb_proxy_upstream_set_callbacks(PbUpstreamCallbacks(
            open: { url, headers, status, responseHeaders, error in
                AppleProxyUpstream.open(url, headers, status, responseHeaders, error)
            },
            read: { handle, bytes, count, error in
                error?.pointee = nil
                guard let bytes, count > 0 else { return -1 }
                AppleProxyUpstream.handlesLock.lock()
                let response = AppleProxyUpstream.handles[handle]
                AppleProxyUpstream.handlesLock.unlock()
                guard let response else {
                    error?.pointee = strdup("Upstream response already closed")
                    return -1
                }
                return response.read(into: bytes, count: Int(count), error: error)
            },
            close: { handle in
                AppleProxyUpstream.handlesLock.lock()
                let response = AppleProxyUpstream.handles.removeValue(forKey: handle)
                AppleProxyUpstream.handlesLock.unlock()
                response?.close()
            },
            free_string: { pointer in free(pointer) }
        ))
        guard pb_proxy_upstream_callbacks_registered() == 1 else {
            throw SetupError.registrationFailed
        }
    }

    private static func open(
        _ urlPointer: UnsafePointer<CChar>?,
        _ headersPointer: UnsafePointer<CChar>?,
        _ status: UnsafeMutablePointer<Int32>?,
        _ responseHeaders: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?,
        _ error: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
    ) -> Int64 {
        error?.pointee = nil
        responseHeaders?.pointee = nil
        guard let urlPointer, let url = URL(string: String(cString: urlPointer)),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else {
            error?.pointee = strdup("Invalid upstream HTTP URL")
            return 0
        }
        var headers: [String: String] = [:]
        if let headersPointer {
            guard let data = String(cString: headersPointer).data(using: .utf8),
                  let decoded = try? JSONDecoder().decode([String: String].self, from: data) else {
                error?.pointee = strdup("Invalid upstream request headers")
                return 0
            }
            headers = decoded
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        for (name, value) in headers {
            // URLSession owns transport framing and the authority for redirects.
            guard !["host", "connection", "content-length", "transfer-encoding"].contains(name.lowercased()) else { continue }
            request.setValue(value, forHTTPHeaderField: name)
        }
        // Avoid compressed-length versus decompressed-body mismatches.
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let response = Response(request: request)
        guard let metadata = response.waitForResponse() else {
            error?.pointee = strdup(response.failureMessage)
            response.close()
            return 0
        }
        guard let json = try? JSONSerialization.data(withJSONObject: metadata.headers),
              let text = String(data: json, encoding: .utf8),
              let ownedHeaders = strdup(text) else {
            error?.pointee = strdup("Could not encode upstream response headers")
            response.close()
            return 0
        }
        status?.pointee = Int32(metadata.statusCode)
        responseHeaders?.pointee = ownedHeaders
        handlesLock.lock()
        while handles[nextHandle] != nil {
            nextHandle = nextHandle == Int64.max ? 1 : nextHandle + 1
        }
        let handle = nextHandle
        nextHandle = nextHandle == Int64.max ? 1 : nextHandle + 1
        handles[handle] = response
        handlesLock.unlock()
        return handle
    }

    private final class Response: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        struct Metadata {
            let statusCode: Int
            let headers: [String: String]
        }

        private let condition = NSCondition()
        private let capacity = 512 * 1024
        private var buffer = Data()
        private var metadata: Metadata?
        private var failure: String?
        private var finished = false
        private var closed = false
        private var session: URLSession!
        private var task: URLSessionDataTask!

        init(request: URLRequest) {
            super.init()
            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            configuration.httpCookieStorage = nil
            configuration.urlCredentialStorage = nil
            configuration.timeoutIntervalForRequest = 30
            configuration.timeoutIntervalForResource = 6 * 60 * 60
            let queue = OperationQueue()
            queue.maxConcurrentOperationCount = 1
            queue.qualityOfService = .utility
            session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
            task = session.dataTask(with: request)
            task.resume()
        }

        var failureMessage: String {
            condition.lock()
            defer { condition.unlock() }
            return failure ?? "Upstream response unavailable"
        }

        func waitForResponse() -> Metadata? {
            condition.lock()
            defer { condition.unlock() }
            let deadline = Date().addingTimeInterval(35)
            while metadata == nil && !finished && !closed {
                if !condition.wait(until: deadline) {
                    failure = "Upstream response timed out"
                    return nil
                }
            }
            return failure == nil && !closed ? metadata : nil
        }

        func read(into bytes: UnsafeMutablePointer<UInt8>, count: Int,
                  error: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?) -> Int32 {
            error?.pointee = nil
            condition.lock()
            defer { condition.unlock() }
            let deadline = Date().addingTimeInterval(35)
            while buffer.isEmpty && !finished && !closed {
                if !condition.wait(until: deadline) {
                    failure = "Upstream body read timed out"
                    finished = true
                    task.cancel()
                    break
                }
            }
            if closed {
                error?.pointee = strdup("Upstream response closed")
                return -1
            }
            if !buffer.isEmpty {
                let length = min(count, buffer.count)
                buffer.copyBytes(to: bytes, count: length)
                buffer.removeFirst(length)
                condition.broadcast()
                return Int32(length)
            }
            if let failure {
                error?.pointee = strdup(failure)
                return -1
            }
            return 0
        }

        func close() {
            condition.lock()
            guard !closed else { condition.unlock(); return }
            closed = true
            buffer.removeAll()
            condition.broadcast()
            condition.unlock()
            task.cancel()
            session.invalidateAndCancel()
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            guard let response = response as? HTTPURLResponse else {
                completionHandler(.cancel)
                return
            }
            var headers: [String: String] = [:]
            for name in ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Content-Encoding", "Location"] {
                if let value = response.value(forHTTPHeaderField: name) {
                    headers[name.lowercased()] = value
                }
            }
            // URLSession transparently decompresses an origin ignoring identity.
            if let encoding = headers["content-encoding"], encoding.lowercased() != "identity" {
                headers.removeValue(forKey: "content-length")
                headers.removeValue(forKey: "content-encoding")
            }
            condition.lock()
            metadata = Metadata(statusCode: response.statusCode, headers: headers)
            let cancelled = closed
            condition.broadcast()
            condition.unlock()
            completionHandler(cancelled ? .cancel : .allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            // Apply backpressure on this response's delegate queue, leaving other
            // requests independent. Never accumulate complete media segments.
            var offset = 0
            condition.lock()
            defer { condition.unlock() }
            while offset < data.count && !closed && !finished {
                let deadline = Date().addingTimeInterval(35)
                while buffer.count >= capacity && !closed && !finished {
                    if !condition.wait(until: deadline) {
                        failure = "Upstream consumer stalled"
                        finished = true
                        condition.broadcast()
                        task.cancel()
                        return
                    }
                }
                guard !closed && !finished else { return }
                let length = min(capacity - buffer.count, data.count - offset)
                buffer.append(data.subdata(in: offset..<(offset + length)))
                offset += length
                condition.broadcast()
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            // Return the 3xx and Location through the callback. Rust validates
            // each destination and scopes headers before opening another hop.
            completionHandler(nil)
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            condition.lock()
            if let error, !closed, failure == nil {
                let native = error as NSError
                // localizedDescription/userInfo may contain signed URLs.
                failure = "Apple upstream error \(native.domain) (\(native.code))"
            }
            finished = true
            condition.broadcast()
            condition.unlock()
            session.finishTasksAndInvalidate()
        }
    }
}
#endif
