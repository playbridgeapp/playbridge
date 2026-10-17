import Foundation

/// Socket lifetime is independent of Remote UI visibility. Timers belong to the platform owner.
struct AtvConnectionLifetime: Equatable {
    private(set) var foreground = false
    private(set) var backgroundGrace = false
    private(set) var backgroundDeadline: TimeInterval?
    var keepConnection: Bool { foreground || backgroundGrace }
    static let backgroundGraceSeconds: TimeInterval = 60

    mutating func foregroundChanged(_ value: Bool, now: TimeInterval = 0) {
        guard foreground != value else { return }
        foreground = value; backgroundGrace = !value
        backgroundDeadline = value ? nil : now + Self.backgroundGraceSeconds
    }
    mutating func graceExpired() {
        if !foreground { backgroundGrace = false; backgroundDeadline = nil }
    }
    func expired(at now: TimeInterval) -> Bool {
        !foreground && backgroundDeadline.map { now >= $0 } == true
    }
    static func waitForDiscovery(hasSelection: Bool, settled: Bool) -> Bool { !hasSelection && !settled }
}
