import Foundation
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

/// Injectable boundary. The client owns call ordering and joins polling before free.
/// Implementations must bound the C string before copying it and never log either direction.
protocol AtvNativeTransport: AnyObject {
    func abiVersion() -> UInt32
    func start() -> Bool
    func submit(_ command: Data) -> Bool
    func nextEvent(waitMilliseconds: UInt64, maximumBytes: Int) throws -> Data?
    func cancel()
    func free()
}

final class AtvNativeSession: AtvNativeTransport {
#if canImport(PlayBridgeCastCore)
    private let lock = NSLock()
    private var handle: OpaquePointer?

    func abiVersion() -> UInt32 { pb_atv_abi_version() }
    func start() -> Bool {
        lock.withLock {
            guard handle == nil else { return false }
            handle = pb_atv_start()
            return handle != nil
        }
    }
    func submit(_ command: Data) -> Bool {
        lock.withLock {
            guard let handle, let json = String(data: command, encoding: .utf8) else { return false }
            return json.withCString { pb_atv_submit_json(handle, $0) }
        }
    }
    func nextEvent(waitMilliseconds: UInt64, maximumBytes: Int) throws -> Data? {
        guard (1...1_048_576).contains(maximumBytes) else { throw AtvClientError.overflow }
        return try lock.withLock {
            guard let handle, let pointer = pb_atv_next_json(handle, waitMilliseconds) else { return nil }
            defer { pb_string_free(pointer) }
            let length = strnlen(pointer, maximumBytes + 1)
            guard length <= maximumBytes else { throw AtvClientError.overflow }
            return Data(bytes: pointer, count: length)
        }
    }
    func cancel() { lock.withLock { if let handle { pb_atv_cancel(handle) } } }
    func free() {
        lock.withLock {
            if let handle { pb_atv_free(handle) }
            handle = nil
        }
    }
    deinit {
        if let handle { pb_atv_cancel(handle); pb_atv_free(handle) }
    }
#else
    // A source checkout remains buildable without the optional native framework.
    func abiVersion() -> UInt32 { 0 }
    func start() -> Bool { false }
    func submit(_ command: Data) -> Bool { false }
    func nextEvent(waitMilliseconds: UInt64, maximumBytes: Int) throws -> Data? { nil }
    func cancel() {}
    func free() {}
#endif
}
