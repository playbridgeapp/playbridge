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
    private let renewal = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))

    fileprivate init(url: URL, id: String, services: PhoneSenderServices, generation: UUID) {
        self.url = url
        self.id = id
        self.services = services
        self.generation = generation
        renewal.schedule(deadline: .now(), repeating: 60)
        renewal.setEventHandler { [weak self] in
            guard let self else { return }
            services.renew(id, generation: generation)
        }
        renewal.resume()
    }

    deinit {
        renewal.cancel()
        services.revoke(id, generation: generation)
    }
}

/// Shared Rust proxy host. All C calls and event polling run on this serial
/// worker, never on the UI thread. Native origin reads use AppleProxyUpstream.
final class PhoneSenderServices: @unchecked Sendable {
    static let shared = PhoneSenderServices()
    private let worker = DispatchQueue(label: "com.playbridge.sender-services")
    private let workerMarker = DispatchSpecificKey<UInt8>()
    private var generation = UUID()
    private let listenerCheck: (URL) async throws -> Void
    private var pumping = false
    private var handlers: [UUID: ([String: Any]) -> Void] = [:]
    private var waiters: [String: CommandWaiter] = [:]

    init(listenerCheck: @escaping (URL) async throws -> Void = PhoneSenderServices.checkListener) {
        self.listenerCheck = listenerCheck
        worker.setSpecific(key: workerMarker, value: 1)
    }

    private final class CommandWaiter {
        let continuation: CheckedContinuation<[String: Any], Error>
        init(_ continuation: CheckedContinuation<[String: Any], Error>) { self.continuation = continuation }
    }
#if canImport(PlayBridgeCastCore)
    private var handle: OpaquePointer?
#endif

    enum ServiceError: LocalizedError {
        case unavailable, failed, timedOut, rejected(String)
        var errorDescription: String? {
            switch self {
            case .unavailable: return "The native streaming service is unavailable in this build."
            case .failed: return "Couldn’t prepare the stream for playback. Please try again."
            case .timedOut: return "The streaming service did not respond in time."
            case .rejected(let message):
                let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
                return trimmed.isEmpty ? "The streaming service rejected the command." : trimmed
            }
        }
    }

    func register(url: String, headers: [String: String], contentType: String?, allowedPrivateOrigins: [String] = [], forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration {
        // Local mpv must not loop out through Wi-Fi/VPN or require LAN access to
        // reach its own proxy. Receivers still need the advertised LAN address.
        let host = forLocalPlayback ? "127.0.0.1" : LocalFileServer.lanIPAddress() ?? "127.0.0.1"
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
        pumping = false
        generation = UUID()
        let pending = waiters
        waiters.removeAll()
        pending.values.forEach { $0.continuation.resume(throwing: ServiceError.failed) }
    }

    fileprivate func renew(_ id: String, generation expected: UUID) {
        worker.async { [self] in
            guard generation == expected else { return }
            try? enqueue(["command": "proxy_renew", "id": id])
        }
    }

    fileprivate func revoke(_ id: String, generation expected: UUID) {
        worker.async { [self] in
            guard generation == expected else { return }
            try? enqueue(["command": "proxy_revoke", "id": id])
        }
    }

    /// Submit one sender-services command and wait for its operation result.
    /// Browser events that arrive in between are delivered to event handlers.
    func submitCommand(_ command: [String: Any]) async throws -> [String: Any] {
        try await submit(command)
    }

    /// Events are delivered on the sender-services queue. Hop before touching UI state.
    func addServicesEventHandler(_ handler: @escaping ([String: Any]) -> Void) -> UUID {
        let id = UUID()
        worker.async { [self] in
            handlers[id] = handler
            ensurePump()
        }
        return id
    }

    func removeServicesEventHandler(_ id: UUID) {
        worker.async { [self] in handlers.removeValue(forKey: id) }
    }

    private func submit(_ command: [String: Any]) async throws -> [String: Any] {
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            worker.async { [self] in begin(command, continuation: continuation) }
        }
    }

    deinit {
        if DispatchQueue.getSpecific(key: workerMarker) != nil {
            retireHost()
        } else {
            worker.sync { retireHost() }
        }
    }

    private func begin(_ command: [String: Any], continuation: CheckedContinuation<[String: Any], Error>) {
        let waiter = CommandWaiter(continuation)
        do {
            try ensureStarted()
            let requestID = UUID().uuidString
            var request = command
            request["request_id"] = requestID
            waiters[requestID] = waiter
            do {
                try write(request)
            } catch {
                waiters.removeValue(forKey: requestID)
                throw error
            }
            ensurePump()
            worker.asyncAfter(deadline: .now() + 15) { [self] in
                guard let waiter = waiters.removeValue(forKey: requestID) else { return }
                // The worker accepted a command whose result was lost.
                // Retire this host, including its grants, before allowing retry.
                retireHost()
                waiter.continuation.resume(throwing: ServiceError.timedOut)
            }
        } catch {
            continuation.resume(throwing: error)
        }
    }

    private func enqueue(_ command: [String: Any]) throws {
        try ensureStarted()
        var request = command
        request["request_id"] = UUID().uuidString
        try write(request)
        ensurePump()
    }

    private func ensureStarted() throws {
#if canImport(PlayBridgeCastCore)
        if handle == nil {
            guard pb_sender_services_abi_version() == 2 else { throw ServiceError.unavailable }
            try AppleProxyUpstream.install()
            guard let started = pb_sender_services_start() else { throw ServiceError.failed }
            handle = started
        }
#else
        throw ServiceError.unavailable
#endif
    }

    private func write(_ request: [String: Any]) throws {
#if canImport(PlayBridgeCastCore)
        guard let handle else { throw ServiceError.unavailable }
        let bytes = try JSONSerialization.data(withJSONObject: request)
        let json = String(decoding: bytes, as: UTF8.self)
        guard json.withCString({ pb_sender_services_submit_json(handle, $0) }) else {
            throw ServiceError.failed
        }
#else
        throw ServiceError.unavailable
#endif
    }

    /// One pump drains the shared native queue so a browser event cannot be
    /// dropped while a proxy command is waiting, and a proxy result cannot fill
    /// the queue while the browser host is idle.
    private func ensurePump() {
#if canImport(PlayBridgeCastCore)
        guard handle != nil, !pumping else { return }
        pumping = true
        worker.async { [self] in pump() }
#endif
    }

    private func pump() {
#if canImport(PlayBridgeCastCore)
        guard pumping, let handle else { pumping = false; return }
        var drained = false
        if let pointer = pb_sender_services_next_json(handle, 0) {
            consume(pointer)
            drained = true
        }
        guard pumping, self.handle != nil else { pumping = false; return }
        if drained || !waiters.isEmpty || !handlers.isEmpty {
            worker.asyncAfter(deadline: .now() + .milliseconds(50)) { [self] in pump() }
        } else {
            pumping = false
        }
#endif
    }

    private func consume(_ pointer: UnsafeMutablePointer<CChar>) {
#if canImport(PlayBridgeCastCore)
        let eventBytes = Data(String(cString: pointer).utf8)
        pb_string_free(pointer)
        guard let event = try? JSONSerialization.jsonObject(with: eventBytes) as? [String: Any] else { return }
        deliver(event)
#endif
    }

    private func deliver(_ event: [String: Any]) {
        let callbacks = Array(handlers.values)
        if event["event"] as? String == "error", event["operation"] as? String == "start" {
            callbacks.forEach { $0(event) }
            retireHost()
            return
        }
        if let requestID = event["requestId"] as? String, let waiter = waiters.removeValue(forKey: requestID) {
            if event["event"] as? String == "operation" {
                var result = event["data"] as? [String: Any] ?? [:]
                result["hostGeneration"] = generation
                waiter.continuation.resume(returning: result)
            } else {
                waiter.continuation.resume(throwing: ServiceError.rejected(event["message"] as? String ?? ""))
            }
        }
        callbacks.forEach { $0(event) }
    }
}
