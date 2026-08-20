import Foundation

struct PairedDevice: Codable, Identifiable {
    var id: String { deviceUUID }
    let deviceUUID: String
    let deviceName: String
    let tokenVerifier: String
    var lastConnected: Date

    init(
        deviceUUID: String,
        deviceName: String,
        tokenVerifier: String,
        lastConnected: Date
    ) {
        self.deviceUUID = deviceUUID
        self.deviceName = deviceName
        self.tokenVerifier = tokenVerifier
        self.lastConnected = lastConnected
    }

    private enum CodingKeys: String, CodingKey {
        case deviceUUID
        case deviceName
        case token
        case tokenVerifier
        case lastConnected
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceUUID = try container.decode(String.self, forKey: .deviceUUID)
        deviceName = try container.decode(String.self, forKey: .deviceName)
        lastConnected = try container.decode(Date.self, forKey: .lastConnected)

        if let verifier = try container.decodeIfPresent(String.self, forKey: .tokenVerifier),
           !verifier.isEmpty {
            tokenVerifier = verifier
        } else {
            let legacyToken = try container.decode(String.self, forKey: .token)
            tokenVerifier = PairingCredentialState.hashToken(legacyToken)
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(deviceUUID, forKey: .deviceUUID)
        try container.encode(deviceName, forKey: .deviceName)
        try container.encode(tokenVerifier, forKey: .tokenVerifier)
        try container.encode(lastConnected, forKey: .lastConnected)
    }
}
