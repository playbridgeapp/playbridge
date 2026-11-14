import Foundation
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

/// Native FFI boundary for `cast/ffi/src/together.rs`.
protocol TogetherNativeBoundary {
    var isAvailable: Bool { get }
    func abiVersion() -> UInt32
    func start() -> OpaquePointer?
    func submitJson(handle: OpaquePointer, commandJson: String) -> Bool
    func nextEvent(handle: OpaquePointer, waitMs: UInt64) -> String?
    func cancel(handle: OpaquePointer)
    func free(handle: OpaquePointer)
}

final class RustTogetherBoundary: TogetherNativeBoundary {
    static var isCompiledWithCastCore: Bool {
        #if canImport(PlayBridgeCastCore)
        return true
        #else
        return false
        #endif
    }

    var isAvailable: Bool { Self.isCompiledWithCastCore }

    func abiVersion() -> UInt32 {
        #if canImport(PlayBridgeCastCore)
        return pb_together_abi_version()
        #else
        return 0
        #endif
    }

    func start() -> OpaquePointer? {
        #if canImport(PlayBridgeCastCore)
        return pb_together_start()
        #else
        return nil
        #endif
    }

    func submitJson(handle: OpaquePointer, commandJson: String) -> Bool {
        #if canImport(PlayBridgeCastCore)
        return commandJson.withCString { cStr in
            pb_together_submit_json(handle, cStr)
        }
        #else
        return false
        #endif
    }

    func nextEvent(handle: OpaquePointer, waitMs: UInt64) -> String? {
        #if canImport(PlayBridgeCastCore)
        guard let ptr = pb_together_next_json(handle, waitMs) else { return nil }
        defer { pb_string_free(ptr) }
        return String(cString: ptr)
        #else
        return nil
        #endif
    }

    func cancel(handle: OpaquePointer) {
        #if canImport(PlayBridgeCastCore)
        pb_together_cancel(handle)
        #endif
    }

    func free(handle: OpaquePointer) {
        #if canImport(PlayBridgeCastCore)
        pb_together_free(handle)
        #endif
    }
}

enum TogetherClientError: Error {
    case unavailable
    case abiMismatch(expected: UInt32, actual: UInt32)
}

/// One Watch Together session: at most one room and one receiver pairing code.
/// One event pump thread is the sole caller of `nextEvent` and the sole caller
/// that frees the native handle. Command JSON holds room keys and sign-in
/// headers, so it is never logged.
final class TogetherClient {
    static let expectedAbiVersion: UInt32 = 1
    private static let pollMs: UInt64 = 500

    private let native: TogetherNativeBoundary
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private var closed = false
    private let onEvent: (TogetherEvent) -> Void

    init(native: TogetherNativeBoundary = RustTogetherBoundary(), onEvent: @escaping (TogetherEvent) -> Void) throws {
        guard native.isAvailable else { throw TogetherClientError.unavailable }
        let actualAbi = native.abiVersion()
        guard actualAbi == Self.expectedAbiVersion else {
            throw TogetherClientError.abiMismatch(expected: Self.expectedAbiVersion, actual: actualAbi)
        }
        guard let started = native.start() else {
            throw TogetherClientError.unavailable
        }
        self.native = native
        self.handle = started
        self.onEvent = onEvent

        let thread = Thread { [weak self, native] in
            if let self {
                self.runPump(started: started)
            } else {
                native.cancel(handle: started)
                native.free(handle: started)
            }
        }
        thread.name = "PlayBridge.TogetherPump"
        thread.qualityOfService = .userInitiated
        thread.start()
    }

    deinit {
        close()
    }

    private func runPump(started: OpaquePointer) {
        defer {
            lock.lock()
            let shouldFree = (handle == started)
            if shouldFree {
                handle = nil
            }
            closed = true
            if shouldFree {
                native.cancel(handle: started)
                native.free(handle: started)
            }
            lock.unlock()
        }

        while true {
            lock.lock()
            let shouldContinue = !closed && handle == started
            lock.unlock()
            guard shouldContinue else { break }

            guard let text = native.nextEvent(handle: started, waitMs: Self.pollMs) else {
                continue
            }
            if let event = TogetherJson.event(from: text) {
                onEvent(event)
            }
        }
    }

    @discardableResult
    func host(relay: String, device: TogetherDevice) -> Bool {
        var fields = TogetherJson.deviceFields(device)
        fields["relay"] = relay
        return submit(TogetherJson.command("host", fields: fields))
    }

    @discardableResult
    func join(invite: String, device: TogetherDevice) -> Bool {
        var fields = TogetherJson.deviceFields(device)
        fields["invite"] = invite
        return submit(TogetherJson.command("join", fields: fields))
    }

    @discardableResult
    func pairRegister(pairing: String) -> Bool {
        submit(TogetherJson.command("pair_register", fields: ["pairing": pairing]))
    }

    @discardableResult
    func pairAccept(device: TogetherDevice) -> Bool {
        submit(TogetherJson.command("pair_accept", fields: TogetherJson.deviceFields(device)))
    }

    @discardableResult
    func pairCancel() -> Bool {
        submit(TogetherJson.command("pair_cancel"))
    }

    @discardableResult
    func pairSend(pairing: String, target: String, id: String? = nil) -> Bool {
        var fields: [String: Any] = ["pairing": pairing, "target": target]
        if let id { fields["id"] = id }
        return submit(TogetherJson.command("pair_send", fields: fields))
    }

    @discardableResult
    func snapshot(positionMs: Int64, playing: Bool, buffering: Bool) -> Bool {
        submit(TogetherJson.command("snapshot", fields: [
            "position_ms": max(0, positionMs),
            "playing": playing,
            "buffering": buffering,
        ]))
    }

    @discardableResult
    func loaded(item: String, durationMs: Int64?) -> Bool {
        var fields: [String: Any] = ["item": item]
        if let durationMs, durationMs > 0 {
            fields["duration_ms"] = UInt64(durationMs)
        }
        return submit(TogetherJson.command("loaded", fields: fields))
    }

    @discardableResult
    func load(item: TogetherItem) -> Bool {
        submit(TogetherJson.command("load", fields: ["item": TogetherJson.itemObject(item)]))
    }

    @discardableResult
    func requestPlay(item: TogetherItem) -> Bool {
        submit(TogetherJson.command("request_play", fields: ["item": TogetherJson.itemObject(item)]))
    }

    @discardableResult
    func play() -> Bool {
        submit(TogetherJson.command("play"))
    }

    @discardableResult
    func pause() -> Bool {
        submit(TogetherJson.command("pause"))
    }

    @discardableResult
    func seek(positionMs: Int64) -> Bool {
        submit(TogetherJson.command("seek", fields: ["position_ms": max(0, positionMs)]))
    }

    @discardableResult
    func lock(_ locked: Bool) -> Bool {
        submit(TogetherJson.command("lock", fields: ["locked": locked]))
    }

    @discardableResult
    func policy(_ policy: TogetherPolicy) -> Bool {
        submit(TogetherJson.command("policy", fields: ["policy": policy.rawValue]))
    }

    @discardableResult
    func reply(request: String, allowed: Bool, always: Bool = false) -> Bool {
        submit(TogetherJson.command("reply", fields: [
            "request": request,
            "allowed": allowed,
            "always": always,
        ]))
    }

    @discardableResult
    func delegate(member: String, signKey: String) -> Bool {
        submit(TogetherJson.command("delegate", fields: ["member": member, "sign_pk": signKey]))
    }

    @discardableResult
    func addOwn(member: String, boxKey: String) -> Bool {
        submit(TogetherJson.command("add_own", fields: ["member": member, "box_pk": boxKey]))
    }

    @discardableResult
    func continueWithout(member: String) -> Bool {
        submit(TogetherJson.command("continue_without", fields: ["member": member]))
    }

    @discardableResult
    func shareCredentials(member: String) -> Bool {
        submit(TogetherJson.command("share_credentials", fields: ["member": member]))
    }

    @discardableResult
    func setScreen(_ screen: Bool) -> Bool {
        submit(TogetherJson.command("screen", fields: ["screen": screen]))
    }

    @discardableResult
    func leave() -> Bool {
        submit(TogetherJson.command("leave"))
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        guard !closed, let current = handle else {
            closed = true
            return
        }
        closed = true
        native.cancel(handle: current)
    }

    private func submit(_ json: String?) -> Bool {
        guard let json else { return false }
        lock.lock()
        defer { lock.unlock() }
        guard !closed, let current = handle else { return false }
        return native.submitJson(handle: current, commandJson: json)
    }
}
