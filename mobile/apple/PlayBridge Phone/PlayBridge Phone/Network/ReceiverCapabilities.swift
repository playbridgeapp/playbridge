import Foundation
import CoreFoundation

/// Authenticated static metadata only. Never include this value in diagnostics.
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
}

struct ReceiverVolume: Equatable {
    let scope: String
    let operations: Set<String>
    let step: Double?
    let fallbackScope: String?
}
