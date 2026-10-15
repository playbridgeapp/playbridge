import Foundation
import CoreFoundation

/// Authenticated receiver metadata. Never include this value in diagnostics.
enum ReceiverCapabilities: Equatable {
    case legacy
    case malformedV1
    case descriptor(ReceiverDescriptor)

    static let operationFields: Set<String> = ["transport", "keys", "pointer", "imageTransforms", "text", "settings", "tracks", "queue", "browser", "reporting"]

    static func parse(_ raw: Any?) -> Self {
        // Select the version before inspecting any future version's shape.
        guard let object = raw as? [String: Any], let version = object["version"] as? NSNumber,
              CFGetTypeID(version) != CFBooleanGetTypeID(), version.decimalValue == 1 else { return .legacy }
        guard let data = try? JSONSerialization.data(withJSONObject: object), data.count <= 64 * 1024 else { return .malformedV1 }
        do {
            try only(object, ["version", "surfaces", "volume", "host", "companionHints"])
            let surfaces = try map(object["surfaces"]) { surface in
                try only(surface, ["engines"])
                return try map(surface["engines"]) { engine in
                    try only(engine, operationFields)
                    return try engine.mapValues { try identifiers($0) }
                }
            }
            var volume: ReceiverVolume?
            if let rawVolume = object["volume"] {
                guard let value = rawVolume as? [String: Any] else { throw Invalid.shape }
                try only(value, ["scope", "operations", "step", "fallbackScope"])
                volume = try ReceiverVolume(scope: identifier(value["scope"]), operations: identifiers(value["operations"]),
                    step: value["step"].map { try normalized($0, positive: true) },
                    fallbackScope: value["fallbackScope"].map { try identifier($0) })
            }
            var host: [String: String] = [:]
            if let rawHost = object["host"] {
                guard let value = rawHost as? [String: Any] else { throw Invalid.shape }
                try only(value, ["osFamily", "deviceClass"])
                host = try value.mapValues { try identifier($0) }
            }
            return .descriptor(try ReceiverDescriptor(surfaces: surfaces, volume: volume, host: host,
                companionHints: identifiers(object["companionHints"]), storage: data))
        } catch { return .malformedV1 }
    }

    static func restore(_ data: Data?) -> Self {
        guard let data, data.count <= 64 * 1024, let raw = try? JSONSerialization.jsonObject(with: data) else { return .legacy }
        return parse(raw)
    }

    var storage: Data? {
        switch self {
        case .legacy: return nil
        case .malformedV1: return Data(#"{"version":1}"#.utf8)
        case .descriptor(let value): return value.storage
        }
    }

    static func normalized(_ raw: Any, positive: Bool = false) throws -> Double {
        guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite, number.decimalValue >= 0, number.decimalValue <= 1,
              !positive || number.decimalValue > 0, !positive || number.doubleValue > 0 else { throw Invalid.shape }
        return number.doubleValue
    }

    private enum Invalid: Error { case shape }
    private static func only(_ value: [String: Any], _ fields: Set<String>) throws {
        guard Set(value.keys).isSubset(of: fields) else { throw Invalid.shape }
    }
    private static func identifier(_ raw: Any?) throws -> String {
        guard let id = raw as? String, (1...64).contains(id.unicodeScalars.count) else { throw Invalid.shape }
        return id
    }
    private static func identifiers(_ raw: Any?) throws -> Set<String> {
        guard let raw else { return [] }
        guard let array = raw as? [Any], array.count <= 64 else { throw Invalid.shape }
        let ids = try array.map { try identifier($0) }
        guard Set(ids).count == ids.count else { throw Invalid.shape }
        return Set(ids)
    }
    private static func map<T>(_ raw: Any?, decode: ([String: Any]) throws -> T) throws -> [String: T] {
        guard let object = raw as? [String: Any], object.count <= 16 else { throw Invalid.shape }
        return try object.reduce(into: [:]) { result, entry in
            let key = try identifier(entry.key)
            guard let value = entry.value as? [String: Any] else { throw Invalid.shape }
            result[key] = try decode(value)
        }
    }
}

struct ReceiverDescriptor: Equatable {
    typealias Operations = [String: Set<String>]
    let surfaces: [String: [String: Operations]]
    let volume: ReceiverVolume?
    let host: [String: String]
    let companionHints: Set<String>
    fileprivate let storage: Data

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.surfaces == rhs.surfaces && lhs.volume == rhs.volume && lhs.host == rhs.host && lhs.companionHints == rhs.companionHints
    }
}

struct ReceiverVolume: Equatable {
    let scope: String
    let operations: Set<String>
    let step: Double?
    let fallbackScope: String?
}

/// Socket-local metadata, independent of credential persistence and UI delivery.
/// The lock also lets execution consult an update before the main queue publishes it.
final class ReceiverCapabilitySession {
    struct Snapshot {
        let id: UUID
        let authenticated: Bool
        let capabilities: ReceiverCapabilities
    }

    private let lock = NSLock()
    private var id = UUID()
    private var authenticated = false
    private var supportsUpdates = false
    private var subscribed = false
    private var revision: UInt64 = 0
    private var capabilities: ReceiverCapabilities = .legacy

    var snapshot: Snapshot {
        locked { Snapshot(id: id, authenticated: authenticated, capabilities: capabilities) }
    }

    @discardableResult func begin() -> UUID {
        locked {
            id = UUID()
            authenticated = false
            supportsUpdates = false
            subscribed = false
            revision = 0
            capabilities = .legacy
            return id
        }
    }

    /// Call only after successful auth or verified protected first-pairing credentials.
    /// Repeated auth on one socket cannot restore operations removed by a live update.
    func acceptAuthenticated(_ metadata: [String: Any], sessionID: UUID) -> Bool {
        locked {
            guard sessionID == id, !authenticated else { return false }
            authenticated = true
            supportsUpdates = (metadata["features"] as? [Any])?.contains { ($0 as? String) == "receiver_capabilities_v1" } == true
            capabilities = .parse(metadata["capabilities"])
            return true
        }
    }

    func subscription(sessionID: UUID) -> String? {
        locked {
            guard sessionID == id, authenticated, supportsUpdates, !subscribed else { return nil }
            subscribed = true
            return #"{"type":"command","action":"receiver_capabilities_subscribe","payload":{}}"#
        }
    }

    /// Nil means ignored or an opaque future version; neither is a UI/persistence event.
    func observe(_ text: String, sessionID: UUID) -> ReceiverCapabilities? {
        locked {
            guard sessionID == id, authenticated, subscribed,
                  let data = text.data(using: .utf8),
                  let frame = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  frame["type"] as? String == "receiver_capabilities",
                  Set(frame.keys) == ["type", "revision", "capabilities"],
                  let token = Self.revisionToken(in: data), let next = Self.parseRevision(token),
                  next > revision, let raw = frame["capabilities"] as? [String: Any],
                  let version = raw["version"],
                  version is NSNumber || version is String || version is NSNull else { return nil }
            revision = next
            let parsed = ReceiverCapabilities.parse(raw)
            guard parsed != .legacy else { return nil }
            capabilities = parsed
            return parsed
        }
    }

    /// JSONSerialization/NSNumber (and fixed-precision Decimal) can round fractions.
    /// Extract only the top-level revision from already validated JSON, respecting
    /// escaped keys, strings and nested objects. Duplicate envelope keys are rejected.
    private static func revisionToken(in data: Data) -> String? {
        let bytes = Array(data)
        var index = 0
        func whitespace() { while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 } }
        func skipString() {
            index += 1
            while index < bytes.count {
                if bytes[index] == 92 { index += 2 }
                else if bytes[index] == 34 { index += 1; return }
                else { index += 1 }
            }
        }
        whitespace()
        guard index < bytes.count, bytes[index] == 123 else { return nil }
        index += 1
        var keys: Set<String> = []
        var revision: String?
        while index < bytes.count {
            whitespace()
            guard index < bytes.count, bytes[index] == 34 else { return nil }
            let keyStart = index
            skipString()
            guard let key = (try? JSONSerialization.jsonObject(with: Data(bytes[keyStart..<index]), options: .fragmentsAllowed)) as? String,
                  keys.insert(key).inserted else { return nil }
            whitespace()
            guard index < bytes.count, bytes[index] == 58 else { return nil }
            index += 1
            whitespace()
            let start = index
            var depth = 0
            while index < bytes.count {
                let byte = bytes[index]
                if byte == 34 { skipString(); continue }
                if depth == 0 && (byte == 44 || byte == 125) { break }
                if byte == 123 || byte == 91 { depth += 1 }
                if byte == 125 || byte == 93 { depth -= 1 }
                index += 1
            }
            if key == "revision" { revision = String(bytes: bytes[start..<index], encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard index < bytes.count else { return nil }
            if bytes[index] == 125 { return revision }
            index += 1
        }
        return nil
    }

    /// Exact base-10 integer check, including 1.0/1e0, without floating point.
    private static func parseRevision(_ token: String) -> UInt64? {
        let parts = token.lowercased().split(separator: "e", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count) else { return nil }
        let mantissa = parts[0].split(separator: ".", omittingEmptySubsequences: false)
        guard (1...2).contains(mantissa.count) else { return nil }
        let digits = mantissa.joined()
        guard !digits.isEmpty, digits.utf8.allSatisfy({ (48...57).contains($0) }) else { return nil }
        var significant = String(digits.drop(while: { $0 == "0" }))
        guard !significant.isEmpty else { return nil }
        let exponent: Int
        if parts.count == 2 {
            guard let value = Int(parts[1]), value >= -token.count, value <= token.count else { return nil }
            exponent = value
        } else { exponent = 0 }
        let scale = exponent - (mantissa.count == 2 ? mantissa[1].count : 0)
        if scale >= 0 {
            guard significant.count + scale <= 16 else { return nil }
            significant += String(repeating: "0", count: scale)
        } else {
            guard -scale < significant.count, significant.suffix(-scale).allSatisfy({ $0 == "0" }) else { return nil }
            significant.removeLast(-scale)
        }
        guard let value = UInt64(significant), (1...9_007_199_254_740_991).contains(value) else { return nil }
        return value
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
