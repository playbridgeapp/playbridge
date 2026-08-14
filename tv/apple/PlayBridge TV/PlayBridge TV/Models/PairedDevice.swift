import Foundation

struct PairedDevice: Codable, Identifiable {
    var id: String { deviceUUID }
    let deviceUUID: String
    let deviceName: String
    var token: String?
    var tokenVerifier: String?
    var lastConnected: Date
}
