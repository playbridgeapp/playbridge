import Foundation

struct ReceiverCapabilitySubscription<Connection: Hashable> {
    private(set) var connections: Set<Connection> = []

    mutating func subscribe(_ connection: Connection, authorized: Bool) -> [String: Any]? {
        guard authorized else { return nil }
        connections.insert(connection)
        return ["type": "receiver_capabilities", "revision": 1, "capabilities": TvCapabilities.currentDescriptor]
    }

    mutating func remove(_ connection: Connection) { connections.remove(connection) }
    mutating func removeAll() { connections.removeAll() }
}
