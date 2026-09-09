import Foundation
import Combine

/// All access to the native handle, including polling and freeing, is serialized.
private final class GoogleCastWorker {
    private let queue = DispatchQueue(label: "com.playbridge.google-cast")
    private let native: GoogleCastSessionTransport
    private var generation = 0
    private var running = false
    var event: ((Int, [String: Any]) -> Void)?

    init(native: GoogleCastSessionTransport) { self.native = native }

    func connect(_ device: ExternalReceiverDevice, generation: Int) {
        queue.async { [self] in
            native.close()
            self.generation = generation
            running = true
            do {
                try native.start(device: device)
                poll(generation)
            } catch {
                running = false
                native.close()
                event?(generation, ["event": "finished", "reason": "connection_failed"])
            }
        }
    }

    private func poll(_ generation: Int) {
        guard running, self.generation == generation else { return }
        if let value = native.nextEvent(waitMilliseconds: 100) {
            event?(generation, value)
            if value["event"] as? String == "finished" {
                running = false
                native.close()
                return
            }
        }
        queue.asyncAfter(deadline: .now() + 0.02) { [weak self] in self?.poll(generation) }
    }

    func submit(_ command: [String: Any], generation: Int) {
        queue.async { [self] in
            guard running, self.generation == generation else { return }
            do { try native.submit(command) }
            catch {
                event?(generation, ["event": "error", "request_id": command["request_id"] ?? "", "operation": command["command"] ?? ""])
            }
        }
    }

    func close() {
        queue.async { [self] in
            running = false
            native.close()
        }
    }
}

private final class CastRequestCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
}

/// Main-thread state reducer over Rust ready-state sessions. Async requests hop
/// to the main queue before touching state; native work stays on the worker.
final class GoogleCastController: ObservableObject {
    @Published private(set) var state: ConnectionState = .disconnected
    @Published private(set) var playback: TvPlaybackStatus?
    private let worker: GoogleCastWorker
    private var generation = 0
    private var device: ExternalReceiverDevice?
    private var title: String?
    private var receiverAppAvailable: Bool?
    private var pending: [String: CheckedContinuation<[String: Any], Error>] = [:]
    private var deadlines: [String: DispatchWorkItem] = [:]
    private var connectDeadline: DispatchWorkItem?
    private var statusPoll: DispatchWorkItem?

    init(native: GoogleCastSessionTransport = GoogleCastNativeSession()) {
        worker = GoogleCastWorker(native: native)
        worker.event = { [weak self] generation, event in
            DispatchQueue.main.async { self?.receive(event, generation: generation) }
        }
    }

    func connect(_ device: ExternalReceiverDevice) {
        disconnect()
        self.device = device
        state = .connecting
        let current = generation
        worker.connect(device, generation: current)
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, generation == current, state == .connecting else { return }
            terminate("The receiver did not become ready. Check the receiver and Wi-Fi, then reconnect.")
        }
        connectDeadline = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 25, execute: timeout)
    }

    func disconnect() {
        generation += 1
        connectDeadline?.cancel()
        statusPoll?.cancel()
        worker.close()
        failPending("Receiver disconnected.")
        state = .disconnected
        playback = nil
        title = nil
        receiverAppAvailable = nil
        device = nil
    }

    private func terminate(_ message: String) {
        disconnect()
        state = .error(message: message)
    }

    func load(url: URL, title: String?, contentType: String?) async throws {
        let current = try await MainActor.run {
            if self.device?.protocolID == "roku", self.receiverAppAvailable != true {
                throw StreamRoutingError.message("This Roku does not report the Play on Roku receiver app required for video sending.")
            }
            return self.generation
        }
        var command: [String: Any] = ["command": "load", "url": url.absoluteString]
        if let title { command["title"] = title }
        if let contentType { command["content_type"] = contentType }
        _ = try await request(command, expectedGeneration: current)
        try await MainActor.run {
            guard self.generation == current, self.state.isConnected else { throw CancellationError() }
            self.title = title
            self.playback = .init(state: "buffering", positionMs: 0, durationMs: 0, title: title)
        }
    }

    func control(_ command: String) async throws {
        let current = await MainActor.run { self.generation }
        let payload: [String: Any]
        switch command {
        case "play", "pause", "stop", "end_receiver": payload = ["command": command]
        case "seek_back": payload = ["command": "relative_seek", "forward": false]
        case "seek_forward": payload = ["command": "relative_seek", "forward": true]
        default: throw StreamRoutingError.message("This control is unavailable on this receiver.")
        }
        _ = try await request(payload, expectedGeneration: current)
        if command == "stop" {
            await MainActor.run {
                guard self.generation == current else { return }
                self.playback = .init(state: "stopped", positionMs: 0, durationMs: 0, title: self.title)
            }
        }
        if command == "end_receiver" {
            await MainActor.run { if self.generation == current { self.disconnect() } }
        }
    }

    func setVolume(_ level: Double) async throws {
        _ = try await request(["command": "set_volume", "level": min(1, max(0, level))])
    }

    private func request(_ payload: [String: Any], expectedGeneration: Int? = nil) async throws -> [String: Any] {
        let current = await MainActor.run { expectedGeneration ?? self.generation }
        let cancellation = CastRequestCancellation()
        try Task.checkCancellation()
        let id = UUID().uuidString
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                DispatchQueue.main.async { [self] in
                    guard !cancellation.isCancelled, generation == current else {
                        continuation.resume(throwing: CancellationError())
                        return
                    }
                    guard state.isConnected else {
                        continuation.resume(throwing: StreamRoutingError.message("Connect to a receiver first."))
                        return
                    }
                    var payload = payload
                    payload["request_id"] = id
                    pending[id] = continuation
                    let deadline = DispatchWorkItem { [weak self] in
                        self?.complete(id, result: .failure(StreamRoutingError.message("The receiver did not respond. Try again or reconnect.")))
                    }
                    deadlines[id] = deadline
                    DispatchQueue.main.asyncAfter(deadline: .now() + 25, execute: deadline)
                    worker.submit(payload, generation: generation)
                }
            }
        } onCancel: {
            cancellation.cancel()
            DispatchQueue.main.async { [weak self] in self?.complete(id, result: .failure(CancellationError())) }
        }
    }

    private func complete(_ id: String, result: Result<[String: Any], Error>) {
        deadlines.removeValue(forKey: id)?.cancel()
        pending.removeValue(forKey: id)?.resume(with: result)
    }

    private func failPending(_ message: String) {
        let ids = Array(pending.keys)
        for id in ids { complete(id, result: .failure(StreamRoutingError.message(message))) }
    }

    private func receive(_ event: [String: Any], generation: Int) {
        guard self.generation == generation else { return }
        switch event["event"] as? String {
        case "connected":
            guard let device, state == .connecting else { return }
            connectDeadline?.cancel()
            receiverAppAvailable = (event["capabilities"] as? [String: Any])?["receiver_app_available"] as? Bool
            let name = (event["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? device.name
            state = .connected(serverName: name, secure: false)
            scheduleStatus()
        case "operation":
            if let id = event["request_id"] as? String {
                if event["ok"] as? Bool == true { complete(id, result: .success(event)) }
                else { complete(id, result: .failure(StreamRoutingError.message("The receiver rejected the command."))) }
            }
        case "status":
            if let id = event["request_id"] as? String, pending[id] != nil,
               let status = event["status"] as? [String: Any] {
                func milliseconds(_ key: String) -> Int64 {
                    let seconds = (status[key] as? NSNumber)?.doubleValue ?? 0
                    return seconds.isFinite ? Int64(max(0, min(seconds * 1000, Double(Int64.max / 2)))) : 0
                }
                playback = .init(state: status["state"] as? String ?? "unknown", positionMs: milliseconds("position_seconds"),
                    durationMs: milliseconds("duration_seconds"), title: title)
                complete(id, result: .success(event))
            }
        case "error":
            if let id = event["request_id"] as? String {
                let operation = event["operation"] as? String
                let message = operation == "load" ? "The receiver couldn’t load this stream. Try another route or video format." : "The receiver command failed. Try again or reconnect."
                complete(id, result: .failure(StreamRoutingError.message(message)))
            }
            // Rust emits Finished when the application/transport is actually
            // gone. A request-scoped timeout must not mark a healthy session dead.
        case "finished":
            terminate("The receiver session ended. Reconnect to the receiver to send again.")
        default: break
        }
    }

    private func scheduleStatus() {
        guard state.isConnected else { return }
        let current = generation
        let poll = DispatchWorkItem { [weak self] in
            guard let self, generation == current, state.isConnected else { return }
            Task { [weak self] in
                guard let self else { return }
                _ = try? await request(["command": "status"], expectedGeneration: current)
                await MainActor.run {
                    if self.generation == current { self.scheduleStatus() }
                }
            }
        }
        statusPoll = poll
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: poll)
    }

    deinit {
        worker.close()
        connectDeadline?.cancel()
        statusPoll?.cancel()
        deadlines.values.forEach { $0.cancel() }
        pending.values.forEach { $0.resume(throwing: CancellationError()) }
    }
}
