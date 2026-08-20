import CryptoKit
import Foundation

struct PairingCredentialState {
    private(set) var pairedDevices: [PairedDevice]
    private(set) var authorizedTokenVerifiers: Set<String>

    init(
        pairedDevices: [PairedDevice] = [],
        authorizedTokenVerifiers: Set<String> = []
    ) {
        self.pairedDevices = pairedDevices
        self.authorizedTokenVerifiers = authorizedTokenVerifiers
    }

    static func migrated(
        pairedDevices: [PairedDevice],
        legacyAuthorizedTokens: Set<String>,
        authorizedTokenVerifiers: Set<String>
    ) -> PairingCredentialState {
        let pairedVerifiers = Set(
            pairedDevices.map(\.tokenVerifier).filter { !$0.isEmpty }
        )
        let migratedVerifiers = Set(legacyAuthorizedTokens.map(hashToken))

        // Authorization must always remain associated with a visible paired-device
        // record so stale credentials can be revoked from the TV UI.
        let validVerifiers = authorizedTokenVerifiers
            .union(migratedVerifiers)
            .intersection(pairedVerifiers)

        return PairingCredentialState(
            pairedDevices: pairedDevices,
            authorizedTokenVerifiers: validVerifiers
        )
    }

    static func hashToken(_ token: String) -> String {
        SHA256.hash(data: Data(token.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    func isTokenAuthorized(_ token: String) -> Bool {
        authorizedTokenVerifiers.contains(Self.hashToken(token))
    }

    mutating func authorize(
        deviceUUID: String,
        deviceName: String,
        token: String,
        connectedAt: Date = Date()
    ) {
        let verifier = Self.hashToken(token)

        if let existingIndex = pairedDevices.firstIndex(where: { $0.deviceUUID == deviceUUID }) {
            authorizedTokenVerifiers.remove(pairedDevices[existingIndex].tokenVerifier)
            pairedDevices[existingIndex] = PairedDevice(
                deviceUUID: deviceUUID,
                deviceName: deviceName,
                tokenVerifier: verifier,
                lastConnected: connectedAt
            )
        } else {
            pairedDevices.append(PairedDevice(
                deviceUUID: deviceUUID,
                deviceName: deviceName,
                tokenVerifier: verifier,
                lastConnected: connectedAt
            ))
        }

        authorizedTokenVerifiers.insert(verifier)
    }

    mutating func forgetDevice(deviceUUID: String) {
        let removedVerifiers = pairedDevices
            .filter { $0.deviceUUID == deviceUUID }
            .map(\.tokenVerifier)
        pairedDevices.removeAll { $0.deviceUUID == deviceUUID }
        authorizedTokenVerifiers.subtract(removedVerifiers)
    }

    mutating func forgetAllDevices() {
        pairedDevices.removeAll()
        authorizedTokenVerifiers.removeAll()
    }

    mutating func updateLastConnected(token: String, connectedAt: Date = Date()) {
        let verifier = Self.hashToken(token)
        guard let index = pairedDevices.firstIndex(where: { $0.tokenVerifier == verifier }) else {
            return
        }
        pairedDevices[index].lastConnected = connectedAt
    }
}
