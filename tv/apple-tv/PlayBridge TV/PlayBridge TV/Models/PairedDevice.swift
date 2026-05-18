import Foundation

struct PairedDevice: Codable, Identifiable {
    var id: String { deviceUUID }
    let deviceUUID: String
    let deviceName: String
    let token: String
    var lastConnected: Date
}
