import Foundation

/// A paused scrub transaction can span several drags. Releasing a drag seeks
/// once but keeps the original pause point available for Back until confirmed.
/// Tokens fence terminal callbacks after a phone seek, replacement or cancel.
struct TouchScrubSession {
    struct Proposal: Equatable {
        let token: UUID
        let origin: Double
        let anchor: Double
        let duration: Double
        fileprivate var clickOffset = 0.0
        fileprivate var appliedPosition: Double?
        fileprivate(set) var position: Double
        fileprivate(set) var isPanning = true
    }

    struct Commit: Equatable {
        let position: Double
        let requiresSeek: Bool
    }

    static let panSlopPoints = 12.0
    private(set) var proposal: Proposal?

    static func canBegin(chrome: TransportChrome, actionFocused: Bool,
                         translationX: Double, translationY: Double) -> Bool {
        TransportKeyRoute.canTouchScrub(chrome: chrome) && !actionFocused
            && translationX.isFinite && translationY.isFinite
            && abs(translationX) >= panSlopPoints && abs(translationX) > abs(translationY)
    }

    @discardableResult
    mutating func begin(position: Double, duration: Double) -> UUID? {
        guard position.isFinite, duration.isFinite, duration > 0,
              proposal?.isPanning != true else { return nil }
        let anchor = min(max(position, 0), duration)
        let origin = proposal?.origin ?? anchor
        let token = UUID()
        proposal = Proposal(token: token, origin: origin, anchor: anchor,
                            duration: duration, position: anchor)
        return token
    }

    @discardableResult
    mutating func update(token: UUID, translationX: Double, velocityX: Double) -> Bool {
        guard var current = proposal, current.token == token, current.isPanning else { return false }
        let target = TransportScrub.proposal(origin: current.anchor, translationX: translationX,
                                            velocityX: velocityX, duration: current.duration)
        current.position = min(max(target + current.clickOffset, 0), current.duration)
        proposal = current
        return true
    }

    mutating func click(by seconds: Double) {
        guard var current = proposal, seconds.isFinite else { return }
        let target = min(max(current.position + seconds, 0), current.duration)
        current.clickOffset += target - current.position
        current.position = target
        proposal = current
    }

    /// Applying the release does not resume; confirmation can avoid a second seek.
    mutating func release(token: UUID) -> Double? {
        guard var current = proposal, current.token == token else { return nil }
        current.isPanning = false
        current.appliedPosition = current.position
        proposal = current
        return current.position
    }

    mutating func commit() -> Commit? {
        guard let current = proposal else { return nil }
        proposal = nil
        return Commit(position: current.position, requiresSeek: current.appliedPosition != current.position)
    }

    mutating func cancel() -> Double? {
        let origin = proposal?.origin
        proposal = nil
        return origin
    }

    /// A phone seek/new item wins without a compensating seek to the old origin.
    mutating func discard() { proposal = nil }
}
