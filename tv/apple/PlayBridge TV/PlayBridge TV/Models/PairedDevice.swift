import Foundation

struct PairedDevice: Codable, Identifiable {
    var id: String { deviceUUID }
    let deviceUUID: String
    let deviceName: String
    var token: String
    var tokenVerifier: String?
    var lastConnected: Date

    init(deviceUUID: String, deviceName: String, token: String, tokenVerifier: String? = nil, lastConnected: Date) {
        self.deviceUUID = deviceUUID
        self.deviceName = deviceName
        self.token = token
        self.tokenVerifier = tokenVerifier
        self.lastConnected = lastConnected
    }
}
