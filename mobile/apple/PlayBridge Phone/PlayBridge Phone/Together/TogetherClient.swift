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

    private final class HandleBox {
        let native: TogetherNativeBoundary
        private let lock = NSLock()
        private var handle: OpaquePointer?
        private var closed = false
        private var freed = false

        init(native: TogetherNativeBoundary, handle: OpaquePointer) {
            self.native = native
            self.handle = handle
        }

        func submit(_ json: String) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !closed, !freed, let current = handle else { return false }
            return native.submitJson(handle: current, commandJson: json)
        }

        func shouldContinue(_ expected: OpaquePointer) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            return !closed && !freed && handle == expected
        }

        func close() {
            lock.lock()
            guard !closed, !freed, let current = handle else {
                closed = true
                lock.unlock()
                return
            }
            closed = true
            native.cancel(handle: current)
            lock.unlock()
        }

        func finishAndFree() {
            lock.lock()
            guard !freed, let current = handle else {
                closed = true
                freed = true
                lock.unlock()
                return
            }
            if !closed {
                closed = true
                native.cancel(handle: current)
            }
            handle = nil
            freed = true
            native.free(handle: current)
            lock.unlock()
        }
    }

    private let box: HandleBox
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
        let box = HandleBox(native: native, handle: started)
        self.box = box
        self.onEvent = onEvent

        let thread = Thread { [weak self, box] in
            if let self {
                self.runPump(started: started)
            } else {
                box.finishAndFree()
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
        defer { box.finishAndFree() }

        while box.shouldContinue(started) {
            guard let text = box.native.nextEvent(handle: started, waitMs: Self.pollMs) else {
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
    func pairAccept(device: TogetherDevice, guest: Bool = false) -> Bool {
        var fields = TogetherJson.deviceFields(device)
        fields["guest"] = guest
        return submit(TogetherJson.command("pair_accept", fields: fields))
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
    func clear() -> Bool {
        submit(TogetherJson.command("clear"))
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
        box.close()
    }

    private func submit(_ json: String?) -> Bool {
        guard let json else { return false }
        return box.submit(json)
    }
}
