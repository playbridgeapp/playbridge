import Foundation

/// Identifies the primary action that receives focus on entry and state transitions in `WatchTogetherView`.
enum TogetherStepFocusTarget: String, Hashable, CaseIterable {
    case unavailableBack
    case joinParty
    case cancelRegistering
    case cancelCode
    case joinInvite
    case leaveParty
    case endedOk

    static func target(isAvailable: Bool, stepKey: String) -> TogetherStepFocusTarget {
        guard isAvailable else { return .unavailableBack }
        switch stepKey {
        case "idle": return .joinParty
        case "registering": return .cancelRegistering
        case "code": return .cancelCode
        case "invited": return .joinInvite
        case "inParty": return .leaveParty
        case "ended": return .endedOk
        default: return .joinParty
        }
    }

    static func shouldRefocus(previousStepKey: String?, currentStepKey: String) -> Bool {
        previousStepKey != currentStepKey
    }
}
