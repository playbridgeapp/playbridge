import Foundation
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

/// A registration owns access to one remote stream, including rewritten HLS
/// children. Retain it for as long as the player/receiver can request media.
final class PhoneProxyRegistration {
    let url: URL
    private let id: String
    private let services: PhoneSenderServices
    private let generation: UUID

    fileprivate init(url: URL, id: String, services: PhoneSenderServices, generation: UUID) {
        self.url = url
        self.id = id
        self.services = services
        self.generation = generation
    }

    deinit { services.revoke(id, generation: generation) }
}

/// Shared Rust proxy host. All C calls and event polling run on this serial
/// worker, never on the UI thread. Native origin reads use AppleProxyUpstream.
final class PhoneSenderServices: @unchecked Sendable {
    static let shared = PhoneSenderServices()
    private let worker = DispatchQueue(label: "com.playbridge.sender-services")
    private var generation = UUID()
    private let listenerCheck: (URL) async throws -> Void

    init(listenerCheck: @escaping (URL) async throws -> Void = PhoneSenderServices.checkListener) {
        self.listenerCheck = listenerCheck
    }
#if canImport(PlayBridgeCastCore)
    private var handle: OpaquePointer?
#endif

    enum ServiceError: LocalizedError {
        case unavailable, failed, timedOut
        var errorDescription: String? {
            switch self {
            case .unavailable: return "The native streaming service is unavailable in this build."
            case .failed: return "Couldn’t prepare the stream for playback. Please try again."
            case .timedOut: return "The streaming service did not respond in time."
            }
        }
    }

    func register(url: String, headers: [String: String], contentType: String?, allowedPrivateOrigins: [String] = []) async throws -> PhoneProxyRegistration {
        let host = LocalFileServer.lanIPAddress() ?? "127.0.0.1"
        var command: [String: Any] = ["command": "proxy_register_url", "host": host,
                                      "url": url, "headers": headers,
                                      "allowed_private_origins": allowedPrivateOrigins]
        if let contentType { command["content_type"] = contentType }
        for attempt in 0...1 {
            let data = try await submit(command)
            guard let id = data["id"] as? String, let rawURL = data["url"] as? String,
                  let url = URL(string: rawURL), let generation = data["hostGeneration"] as? UUID else {
                throw ServiceError.failed
            }
            let registration = PhoneProxyRegistration(url: url, id: id, services: self, generation: generation)
            do {
                try await listenerCheck(url)
                try Task.checkCancellation()
                return registration
            } catch {
                try Task.checkCancellation()
                guard Self.isListenerFailure(error) else { throw error }
                await retire(generation: generation)
                guard attempt == 0 else { throw error }
            }
        }
        throw ServiceError.failed
    }

    /// Probe only the local listener, never the authenticated upstream media.
    static func checkListener(_ url: URL) async throws {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.path = "/health"
        components.query = nil
        components.fragment = nil
        var request = URLRequest(url: components.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 3)
        request.httpMethod = "HEAD"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.connectionProxyDictionary = [:]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let (_, response) = try await session.data(for: request)
        guard response is HTTPURLResponse else { throw ServiceError.failed }
    }

    private static func isListenerFailure(_ error: Error) -> Bool {
        let error = error as NSError
        return error.domain == NSURLErrorDomain &&
            [URLError.cannotConnectToHost.rawValue, URLError.networkConnectionLost.rawValue,
             URLError.timedOut.rawValue].contains(error.code)
    }

    private func retire(generation expected: UUID) async {
        await withCheckedContinuation { continuation in
            worker.async { [self] in
                if generation == expected { retireHost() }
                continuation.resume()
            }
        }
    }

    private func retireHost() {
#if canImport(PlayBridgeCastCore)
        if let handle {
            pb_sender_services_cancel(handle)
            pb_sender_services_free(handle)
            self.handle = nil
        }
#endif
        generation = UUID()
    }

    fileprivate func revoke(_ id: String, generation expected: UUID) {
        worker.async { [self] in
            guard generation == expected else { return }
            _ = try? execute(["command": "proxy_revoke", "id": id])
        }
    }

    private func submit(_ command: [String: Any]) async throws -> [String: Any] {
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            worker.async { [self] in
                do { continuation.resume(returning: try execute(command)) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    deinit { retireHost() }

    private func execute(_ command: [String: Any]) throws -> [String: Any] {
#if canImport(PlayBridgeCastCore)
        if handle == nil {
            guard pb_sender_services_abi_version() == 2 else { throw ServiceError.unavailable }
            try AppleProxyUpstream.install()
            guard let started = pb_sender_services_start() else { throw ServiceError.failed }
            handle = started
        }
        guard let handle else { throw ServiceError.unavailable }
        let requestID = UUID().uuidString
        var request = command
        request["request_id"] = requestID
        let bytes = try JSONSerialization.data(withJSONObject: request)
        let json = String(decoding: bytes, as: UTF8.self)
        guard json.withCString({ pb_sender_services_submit_json(handle, $0) }) else {
            throw ServiceError.failed
        }
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            guard let pointer = pb_sender_services_next_json(handle, 200) else { continue }
            let eventBytes = Data(String(cString: pointer).utf8)
            pb_string_free(pointer)
            guard let event = try JSONSerialization.jsonObject(with: eventBytes) as? [String: Any] else { continue }
            if event["event"] as? String == "error", event["operation"] as? String == "start" {
                retireHost()
                throw ServiceError.failed
            }
            guard event["requestId"] as? String == requestID else { continue }
            guard event["event"] as? String == "operation", let data = event["data"] as? [String: Any] else {
                throw ServiceError.failed
            }
            var result = data
            result["hostGeneration"] = generation
            return result
        }
        // The worker might have accepted a registration whose result was lost.
        // Retire this failed host, including its grants, before allowing retry.
        retireHost()
        throw ServiceError.timedOut
#else
        throw ServiceError.unavailable
#endif
    }
}
