import Foundation

/// Changes that bring the TV's queue in line with the phone's list. Applied in this order.
struct WindowPlan: Equatable {
    var removes: [String] = []
    var adds: [String] = []
    /// `queue_move` pairs of item id and the item it goes before (nil: the end).
    var moves: [Move] = []
    var isEmpty: Bool { removes.isEmpty && adds.isEmpty && moves.isEmpty }

    struct Move: Equatable {
        let itemId: String
        let before: String?
    }
}

/// The phone holds the whole list (any length); the TV holds a window of it: a few played
/// items, the current one and the next ones. Pure logic, matching Android's PhoneQueueWindow:
/// `adopt` takes in edits made on the TV, `plan` works out what to send to the TV.
///
/// Ids are the item ids the phone gave each entry; the TV keeps them as its queue item ids.
/// Ids on the TV that the phone doesn't know (added by another controller) are left alone.
enum PhoneQueueWindow {
    struct Limits: Equatable {
        /// Items kept queued ahead of the current one once topped up.
        var ahead = 20
        /// Top up when this many or fewer are left ahead.
        var aheadLow = 10
        /// Played items kept for Previous after a trim.
        var behindKeep = 10
        /// Trim played items once more than this many are queued.
        var behindMax = 15
        /// Queued items further ahead than this (e.g. after a jump back) no longer belong to the window.
        var reachMax = 50
    }

    /// PlayBridge TVs reject queues larger than this.
    static let tvLimit = 200

    /// Takes in changes made on the TV ahead of the current item: sent items that are gone
    /// were removed there, and sent items in a new order were reordered there.
    /// `cursor` must already point at the TV's current item.
    static func adopt(order: [String], cursor: Int, sent: Set<String>, tv: [String], tvCurrent: String) -> [String] {
        guard let currentAt = tv.firstIndex(of: tvCurrent), order.indices.contains(cursor) else { return order }
        let onTv = Set(tv)
        let kept = order.enumerated().filter { $0.offset <= cursor || !sent.contains($0.element) || onTv.contains($0.element) }
            .map(\.element)
        var position: [String: Int] = [:]
        for (index, id) in kept.enumerated() { position[id] = index }
        guard let newCursor = position[order[cursor]] else { return kept }
        let tvAhead = tv[(currentAt + 1)...].filter { (position[$0] ?? -1) > newCursor }
        guard !tvAhead.isEmpty else { return kept }
        let slots = tvAhead.map { position[$0]! }.sorted()
        var result = kept
        for (k, slot) in slots.enumerated() { result[slot] = tvAhead[k] }
        return result
    }

    /// What to send so the TV holds the window around `cursor`, which is the TV's current item.
    static func plan(order: [String], cursor: Int, tv: [String], tvCurrent: String, limits: Limits = Limits()) -> WindowPlan {
        guard let currentAt = tv.firstIndex(of: tvCurrent), order.indices.contains(cursor) else { return WindowPlan() }
        var removes: [String] = []
        let behind = Array(tv[..<currentAt])
        if behind.count > limits.behindMax { removes += behind.prefix(behind.count - limits.behindKeep) }

        var position: [String: Int] = [:]
        position.reserveCapacity(order.count)
        for (index, id) in order.enumerated() { position[id] = index }
        let ours = tv[(currentAt + 1)...].filter { position[$0] != nil }
        // Ours but not just ahead in the phone's list (after a jump either way): drop them.
        let stale = ours.filter { !(1...limits.reachMax).contains(position[$0]! - cursor) }
        removes += stale
        let staleSet = Set(stale)
        let valid = ours.filter { !staleSet.contains($0) }
        let reach = valid.map { position[$0]! - cursor }.max() ?? 0
        let wanted = valid.count <= limits.aheadLow ? max(reach, limits.ahead) : reach
        let count = min(wanted, order.count - cursor - 1)
        let target = Array(order[(cursor + 1)..<(cursor + 1 + count)])

        let validSet = Set(valid)
        let room = tvLimit - (tv.count - removes.count)
        let adds = Array(target.filter { !validSet.contains($0) }.prefix(max(0, room)))

        // Simulate the TV's order of our items (adds land at the end) and move each into place.
        let addSet = Set(adds)
        var simulated = valid + adds
        let placed = target.filter { validSet.contains($0) || addSet.contains($0) }
        var moves: [WindowPlan.Move] = []
        for (i, id) in placed.enumerated() where simulated[i] != id {
            simulated.removeAll { $0 == id }
            simulated.insert(id, at: i)
            moves.append(WindowPlan.Move(itemId: id, before: i + 1 < simulated.count ? simulated[i + 1] : nil))
        }
        return WindowPlan(removes: removes, adds: adds, moves: moves)
    }
}
