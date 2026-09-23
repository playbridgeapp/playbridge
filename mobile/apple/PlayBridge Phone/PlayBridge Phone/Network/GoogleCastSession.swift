import Foundation

#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

enum GoogleCastConfiguration {
    static let defaultMediaReceiverApplicationID = "30FDC6BC"

    static var applicationID: String {
        let configured = Bundle.main.object(
            forInfoDictionaryKey: "PlayBridgeGoogleCastApplicationID"
        ) as? String
        guard let configured, !configured.isEmpty else {
            return defaultMediaReceiverApplicationID
        }
        return configured
    }
}

enum GoogleCastNativeAvailability {
    static var isAvailable: Bool {
#if canImport(PlayBridgeCastCore)
        pb_cast_core_abi_version() == 2
#else
        false
#endif
    }
}

/// Apple-side entry point for Google Cast and DLNA over Cast Core ABI v2.
///
/// The generated XCFramework is optional while the iOS sender UI is being
/// migrated. Once linked, `start` launches/joins the configured receiver and
/// the first `connected` event means its media channel has answered
/// `GET_STATUS`; it does not mean that media has already been loaded.
/// DLNA readiness means Rust loaded the device description and found AVTransport.
protocol GoogleCastSessionTransport: AnyObject {
    func start(device: ExternalReceiverDevice) throws
    func start(addresses: [String], port: UInt16) throws
    func submit(_ command: [String: Any]) throws
    func nextEvent(waitMilliseconds: UInt64) -> [String: Any]?
    func close()
}

extension GoogleCastSessionTransport {
    func start(device: ExternalReceiverDevice) throws { try start(addresses: device.addresses, port: device.port) }
}

final class GoogleCastNativeSession: GoogleCastSessionTransport {
#if canImport(PlayBridgeCastCore)
    private var handle: OpaquePointer?

    deinit {
        close()
    }

    func start(addresses: [String], port: UInt16 = 8009) throws {
        try start(device: .init(id: "", name: "", addresses: addresses, port: port, model: "Google Cast"))
    }

    func start(device: ExternalReceiverDevice) throws {
        close()
        guard pb_cast_core_abi_version() == 2 else {
            throw SessionError.unsupportedABI
        }
        var target: [String: Any] = [
            "protocol": device.protocolID,
            "addresses": device.addresses,
            "port": device.port,
            "application_id": GoogleCastConfiguration.applicationID,
            "launch_policy": "reuse_or_launch",
        ]
        if let location = device.location { target["location"] = location }
        let data = try JSONSerialization.data(withJSONObject: target)
        guard let json = String(data: data, encoding: .utf8) else {
            throw SessionError.invalidJSON
        }
        handle = json.withCString { pb_session_start($0, 20_000) }
        guard handle != nil else {
            throw SessionError.startFailed
        }
    }

    func submit(_ command: [String: Any]) throws {
        guard let handle else { throw SessionError.notStarted }
        let data = try JSONSerialization.data(withJSONObject: command)
        guard let json = String(data: data, encoding: .utf8) else {
            throw SessionError.invalidJSON
        }
        let accepted = json.withCString { pb_session_submit_json(handle, $0) }
        guard accepted else { throw SessionError.commandRejected }
    }

    func nextEvent(waitMilliseconds: UInt64 = 200) -> [String: Any]? {
        guard let handle, let pointer = pb_session_next_json(handle, waitMilliseconds) else {
            return nil
        }
        defer { pb_string_free(pointer) }
        guard let data = String(cString: pointer).data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    func close() {
        guard let handle else { return }
        pb_session_cancel(handle)
        pb_session_free(handle)
        self.handle = nil
    }
#else
    func start(addresses: [String], port: UInt16 = 8009) throws {
        throw SessionError.frameworkNotLinked
    }

    func submit(_ command: [String: Any]) throws {
        throw SessionError.frameworkNotLinked
    }

    func nextEvent(waitMilliseconds: UInt64 = 200) -> [String: Any]? { nil }
    func close() {}
#endif

    enum SessionError: LocalizedError {
        case frameworkNotLinked
        case unsupportedABI
        case invalidJSON
        case startFailed
        case notStarted
        case commandRejected

        var errorDescription: String? {
            switch self {
            case .frameworkNotLinked:
                "PlayBridgeCastCore.xcframework is not linked"
            case .unsupportedABI:
                "The linked Cast Core does not support ready-state sessions"
            case .invalidJSON:
                "Could not encode the Cast Core request"
            case .startFailed:
                "Could not start the native Google Cast session"
            case .notStarted:
                "The native Google Cast session has not started"
            case .commandRejected:
                "The native Google Cast command queue rejected the request"
            }
        }
    }
}
