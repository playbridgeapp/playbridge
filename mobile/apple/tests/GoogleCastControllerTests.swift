import Foundation

enum StreamRoutingError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case .message(let value): return value } }
}

final class FakeCastSession: GoogleCastSessionTransport {
    private let lock = NSLock()
    private var events: [[String: Any]] = []
    private var commands: [[String: Any]] = []
    private var starts = 0
    func start(addresses: [String], port: UInt16) throws { lock.lock(); starts += 1; lock.unlock() }
    func submit(_ command: [String: Any]) throws { lock.lock(); commands.append(command); lock.unlock() }
    func nextEvent(waitMilliseconds: UInt64) -> [String: Any]? {
        lock.lock(); defer { lock.unlock() }
        return events.isEmpty ? nil : events.removeFirst()
    }
    func close() { lock.lock(); events.removeAll(); lock.unlock() }
    func emit(_ event: [String: Any]) { lock.lock(); events.append(event); lock.unlock() }
    var startCount: Int { lock.lock(); defer { lock.unlock() }; return starts }
    func last(_ command: String) -> [String: Any]? {
        lock.lock(); defer { lock.unlock() }; return commands.last { $0["command"] as? String == command }
    }
    func acknowledge(_ command: [String: Any]) {
        emit(["event": "operation", "request_id": command["request_id"]!, "ok": true])
    }
}

@main struct GoogleCastChecks {
    @MainActor static func wait(_ predicate: () -> Bool) async {
        for _ in 0..<400 {
            if predicate() { return }
            try! await Task.sleep(nanoseconds: 10_000_000)
        }
        fatalError("Timed out waiting for Cast fixture")
    }
    @MainActor static func main() async throws {
        let device = ExternalReceiverDevice.parse(serviceName: "service", addresses: ["192.0.2.1", "192.0.2.1"], port: 8009,
            txt: ["id": "receiver", "fn": "Living room", "md": "Chromecast"])!
        precondition(device.name == "Living room" && device.addresses.count == 1)
        precondition(ExternalReceiverDevice.parse(serviceName: "bad", addresses: [], port: 8009, txt: [:]) == nil)
        precondition(ExternalReceiverDevice.parse(serviceName: "bad", addresses: ["192.0.2.1"], port: 65536, txt: [:]) == nil)
        let native = FakeCastSession()
        let controller = GoogleCastController(native: native)
        controller.connect(device)
        await wait { native.startCount == 1 }
        do {
            try await controller.load(url: URL(string: "https://example.test/video.mp4")!, title: nil, contentType: nil)
            fatalError("Load accepted before readiness")
        } catch { precondition(native.last("load") == nil) }
        native.emit(["event": "connected", "protocol": "google_cast"])
        await wait { controller.state.isConnected }
        let load = Task { try await controller.load(url: URL(string: "https://example.test/video.m3u8")!, title: "Fixture", contentType: "application/x-mpegURL") }
        await wait { native.last("load") != nil }
        let command = native.last("load")!
        precondition(command["content_type"] as? String == "application/x-mpegURL")
        native.acknowledge(command)
        try await load.value
        precondition(controller.playback?.title == "Fixture")
        await wait { native.last("status") != nil }
        native.emit(["event": "status", "request_id": native.last("status")!["request_id"]!,
            "status": ["state": "playing", "position_seconds": 1.25, "duration_seconds": 60]])
        await wait { controller.playback?.positionMs == 1250 }
        precondition(controller.playback?.durationMs == 60000)
        let seek = Task { try await controller.control("seek_to:12500") }
        await wait { native.last("seek") != nil }
        precondition(native.last("seek")?["position_seconds"] as? Double == 12.5)
        native.acknowledge(native.last("seek")!)
        try await seek.value
        let volume = Task { try await controller.adjustVolume(up: false) }
        await wait { native.last("adjust_volume") != nil }
        precondition(native.last("adjust_volume")?["delta"] as? Double == -0.05)
        precondition(native.last("set_volume") == nil, "Relative volume must not send a guessed absolute level")
        native.acknowledge(native.last("adjust_volume")!)
        try await volume.value
        do {
            try await controller.control("seek_to:-1")
            fatalError("Accepted a negative seek")
        } catch {}
        let stop = Task { try await controller.control("stop") }
        await wait { native.last("stop") != nil }
        native.acknowledge(native.last("stop")!)
        try await stop.value
        precondition(controller.state.isConnected && controller.playback?.state == "stopped")
        precondition(native.last("end_receiver") == nil)
        let rejected = Task { try await controller.control("pause") }
        await wait { native.last("pause") != nil }
        native.emit(["event": "error", "request_id": native.last("pause")!["request_id"]!, "operation": "pause", "message": "https://secret.test/?token=secret"])
        do { try await rejected.value; fatalError("Rejected command succeeded") }
        catch { precondition(!error.localizedDescription.contains("secret")) }
        precondition(controller.state.isConnected)
        let end = Task { try await controller.control("end_receiver") }
        await wait { native.last("end_receiver") != nil }
        native.acknowledge(native.last("end_receiver")!)
        try await end.value
        precondition(!controller.state.isConnected)
        controller.connect(device)
        await wait { native.startCount == 2 }
        native.emit(["event": "connected"])
        await wait { controller.state.isConnected }
        let cancelled = Task { try await controller.control("play") }
        await wait { native.last("play") != nil }
        cancelled.cancel()
        do { try await cancelled.value; fatalError("Cancellation ignored") } catch is CancellationError {}
        native.acknowledge(native.last("play")!)
        native.emit(["event": "finished", "reason": "receiver_exited"])
        await wait { !controller.state.isConnected }
        do { try await controller.control("play"); fatalError("Dead session accepted command") } catch {}
        controller.disconnect()
        print("Google Cast discovery parsing, readiness, load acknowledgement, status, controls, errors, cancellation and reconnect checks passed")
    }
}
