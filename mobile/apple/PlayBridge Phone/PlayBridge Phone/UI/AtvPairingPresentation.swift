import Foundation

/// Shared with host UI fixtures. No payload-derived error text or credential values.
enum AtvPairingPresentation {
    static let explanation = "Lets PlayBridge work like your TV remote: any app, Home, typing. Pair only with the TV you selected."
    static let recoveryExplanation = "If this install's TV identity cannot be recovered, pairing creates a new identity. Other TVs paired with the old identity will need to be paired again."
    static let recoveredExplanation = "TV pairing storage was recovered. Other TVs may need to be paired again with this install."
    static let networkHelp = "Check Wi-Fi and allow Local Network access for PlayBridge in Settings. An empty scan does not necessarily mean access was denied."
    static func normalizedInput(_ raw: String) -> String {
        String(raw.unicodeScalars.filter { (48...57).contains($0.value) || (65...70).contains($0.value) || (97...102).contains($0.value) }.prefix(6)).uppercased()
    }
    static func validCode(_ raw: String) -> String? {
        let code = normalizedInput(raw)
        return code.utf8.count == 6 ? code : nil
    }
    static func message(_ state: AtvPairingState) -> String {
        switch state.phase {
        case .idle: return "Pair with your selected TV"
        case .recoveryConfirmation: return "Pair again to recover TV pairing storage"
        case .starting: return "Starting pairing…"
        case .waitingForCode: return "Enter the 6 characters shown on the TV"
        case .verifying: return "Checking the code…"
        case .paired: return "TV pairing saved"
        case .failed: return state.storageFailure ? "Couldn't save the pairing. Pair again to retry." : (state.failure?.errorDescription ?? "TV pairing failed")
        }
    }
    static func pairingAction(_ state: AtvChannelState) -> String? {
        if state.phase == .repairRequired || state.failure == .native(.notTrusted) { return "Pair again" }
        return !state.paired && state.phase == .available ? "Get full control" : nil
    }
    static func connection(_ state: AtvChannelState) -> String {
        switch state.phase {
        case .idle: return "TV control inactive"
        case .discovering: return "Finding TV control…"
        case .available: return state.paired ? "TV control disconnected" : "TV control available"
        case .connecting: return "Connecting TV control…"
        case .connected: return "TV control connected"
        case .retrying: return "TV control disconnected · retry in \(state.retrySeconds ?? 1)s"
        case .repairRequired: return "TV control needs pairing again"
        case .storageUnavailable: return "TV pairing storage unavailable"
        case .unavailable: return "TV control unavailable"
        }
    }
}
