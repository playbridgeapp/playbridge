import Foundation
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

/// A registration owns access to one remote URL, including rewritten HLS
/// children. Dropping it revokes the registration.
nonisolated final class TVProxyGrant: PageMediaGrant, @unchecked Sendable {
    let url: URL
    private let id: String
    private let host: TVProxyHost
    private let generation: UUID
    private let renewal = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))

    fileprivate init(url: URL, id: String, host: TVProxyHost, generation: UUID) {
        self.url = url
        self.id = id
        self.host = host
        self.generation = generation
        renewal.schedule(deadline: .now() + 60, repeating: 60)
        renewal.setEventHandler { [weak self] in
            guard self != nil else { return }
            host.renew(id, generation: generation)
        }
        renewal.resume()
    }

    deinit {
        renewal.cancel()
        host.revoke(id, generation: generation)
    }
}

/// Embedded Rust stream proxy for page-controlled media. It listens on loopback
/// only, starts with the first page item and stops after it has been idle. All C
/// calls and event polling run on one serial worker, never on the main thread.
/// Origin reads use AppleProxyUpstream, so the proxy's network policy applies at
/// connect time. Without the Cast Core library every registration is refused.
nonisolated final class TVProxyHost: PageMediaProxying, @unchecked Sendable {
    static let shared = TVProxyHost()
    /// Page media is only ever offered to players on this address.
    static let loopbackHost = "127.0.0.1"
    private static let idleStopDelay: TimeInterval = 30
    private static let commandTimeout: TimeInterval = 15

    private let worker = DispatchQueue(label: "com.playbridge.tv-proxy-host")
    private let workerMarker = DispatchSpecificKey<UInt8>()
    private var generation = UUID()
    private var pumping = false
    private var waiters: [String: CommandWaiter] = [:]
    private var grants = 0
    private var idleStop: DispatchWorkItem?
#if canImport(PlayBridgeCastCore)
    private var handle: OpaquePointer?
#endif

    private final class CommandWaiter {
        let continuation: CheckedContinuation<[String: Any], Error>
        init(_ continuation: CheckedContinuation<[String: Any], Error>) { self.continuation = continuation }
    }

    init() { worker.setSpecific(key: workerMarker, value: 1) }

    deinit {
        if DispatchQueue.getSpecific(key: workerMarker) != nil {
            retireHost()
        } else {
            worker.sync { retireHost() }
        }
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            worker.async { [self] in
                do {
                    try ensureStarted()
                    if grants == 0 { scheduleIdleStop() }
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func register(url: String, headers: [String: String], contentType: String?,
                  allowedPrivateOrigins: [String]) async throws -> any PageMediaGrant {
        var command: [String: Any] = [
            "command": "proxy_register_url", "host": Self.loopbackHost, "url": url,
            "headers": headers, "allowed_private_origins": allowedPrivateOrigins,
            // The URL came from a website, never from this device's user.
            "remote_origin": true,
        ]
        if let contentType { command["content_type"] = contentType }
        let data = try await submit(command)
        guard let id = data["id"] as? String, let raw = data["url"] as? String,
              let generation = data["hostGeneration"] as? UUID,
              let proxied = URL(string: raw), proxied.scheme == "http",
              proxied.host == Self.loopbackHost else {
            throw PageMediaError.refused
        }
        worker.async { [self] in
            guard self.generation == generation else { return }
            grants += 1
            idleStop?.cancel()
            idleStop = nil
        }
        return TVProxyGrant(url: proxied, id: id, host: self, generation: generation)
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
            grants = max(0, grants - 1)
            if grants == 0 { scheduleIdleStop() }
        }
    }

    private func scheduleIdleStop() {
        idleStop?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.grants == 0, self.waiters.isEmpty else { return }
            self.retireHost()
        }
        idleStop = item
        worker.asyncAfter(deadline: .now() + Self.idleStopDelay, execute: item)
    }

    /// Submit one command and wait for its operation result. The result carries
    /// the generation of the host that ran it, so a later revoke cannot hit a newer host.
    private func submit(_ command: [String: Any]) async throws -> [String: Any] {
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            worker.async { [self] in begin(command, continuation: continuation) }
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
            worker.asyncAfter(deadline: .now() + Self.commandTimeout) { [self] in
                guard let waiter = waiters.removeValue(forKey: requestID) else { return }
                // The worker accepted a command whose result was lost. Retire this host,
                // including its registrations, before allowing a retry.
                retireHost()
                waiter.continuation.resume(throwing: PageMediaError.unavailable)
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
            guard pb_sender_services_abi_version() == 2 else { throw PageMediaError.unavailable }
            do { try AppleProxyUpstream.install() } catch { throw PageMediaError.unavailable }
            guard let started = pb_sender_services_start() else { throw PageMediaError.unavailable }
            handle = started
        }
#else
        throw PageMediaError.unavailable
#endif
    }

    private func write(_ request: [String: Any]) throws {
#if canImport(PlayBridgeCastCore)
        guard let handle else { throw PageMediaError.unavailable }
        let bytes = try JSONSerialization.data(withJSONObject: request)
        let json = String(decoding: bytes, as: UTF8.self)
        guard json.withCString({ pb_sender_services_submit_json(handle, $0) }) else {
            throw PageMediaError.unavailable
        }
#else
        throw PageMediaError.unavailable
#endif
    }

    /// One pump drains the native event queue until it is empty, so a stale
    /// renew result can never crowd out a registration result.
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
        if drained || !waiters.isEmpty {
            worker.asyncAfter(deadline: .now() + .milliseconds(50)) { [self] in pump() }
        } else {
            pumping = false
        }
#endif
    }

#if canImport(PlayBridgeCastCore)
    private func consume(_ pointer: UnsafeMutablePointer<CChar>) {
        let eventBytes = Data(String(cString: pointer).utf8)
        pb_string_free(pointer)
        guard let event = try? JSONSerialization.jsonObject(with: eventBytes) as? [String: Any] else { return }
        deliver(event)
    }
#endif

    private func deliver(_ event: [String: Any]) {
        if event["event"] as? String == "error", event["operation"] as? String == "start" {
            retireHost()
            return
        }
        guard let requestID = event["requestId"] as? String,
              let waiter = waiters.removeValue(forKey: requestID) else { return }
        if event["event"] as? String == "operation" {
            var result = event["data"] as? [String: Any] ?? [:]
            result["hostGeneration"] = generation
            waiter.continuation.resume(returning: result)
        } else {
            // The proxy refused this registration.
            waiter.continuation.resume(throwing: PageMediaError.refused)
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
        grants = 0
        idleStop?.cancel()
        idleStop = nil
        generation = UUID()
        let pending = waiters
        waiters.removeAll()
        pending.values.forEach { $0.continuation.resume(throwing: PageMediaError.unavailable) }
    }
}
