import Foundation

/// Base message envelope for WebSocket protocol
public struct MessageEnvelope: Codable {
    public let type: String
    public let action: String?
    public let payload: [String: AnyCodable]?
    public let state: String?
    public let position: Int64?
    public let duration: Int64?
    public let title: String?

    public init(
        type: String,
        action: String? = nil,
        payload: [String: AnyCodable]? = nil,
        state: String? = nil,
        position: Int64? = nil,
        duration: Int64? = nil,
        title: String? = nil
    ) {
        self.type = type
        self.action = action
        self.payload = payload
        self.state = state
        self.position = position
        self.duration = duration
        self.title = title
    }
}

/// A type-erased Codable value for decoding unknown JSON structures in the envelope.
public enum AnyCodable: Codable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case dictionary([String: AnyCodable])
    case array([AnyCodable])
    case nilValue

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode([String: AnyCodable].self) {
            self = .dictionary(value)
        } else if let value = try? container.decode([AnyCodable].self) {
            self = .array(value)
        } else {
            self = .nilValue
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .dictionary(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .nilValue: try container.encodeNil()
        }
    }
}
