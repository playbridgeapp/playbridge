import Foundation

/// Pure Watch Together party state & reducer for Apple TV, matching Android's `TvTogether.State.InParty`.
struct TogetherTvPartyState: Equatable {
    var relay: String
    var connected: Bool = false
    var members: [String: String] = [:]
    var memberOrder: [String] = []
    var hostName: String? = nil
    var hostMemberID: String? = nil
    var title: String? = nil
    var waitingFor: [String] = []
    var notice: String? = nil

    func toPartyUi() -> TogetherPartyUi {
        let host = hostName.flatMap { $0.isEmpty ? nil : $0 }
        let hostID = hostMemberID.flatMap { $0.isEmpty ? nil : $0 }
        let status: TogetherPartyStatus
        if !connected {
            status = .connecting(relay: relay)
        } else if let title, !title.isEmpty {
            status = .nowPlaying(title: title)
        } else {
            status = .waitingForHost
        }
        let orderedKeys = memberOrder.filter { members[$0] != nil }
            + members.keys.filter { !memberOrder.contains($0) }.sorted()
        let memberUis = orderedKeys.compactMap { key -> PartyMemberUi? in
            guard let name = members[key] else { return nil }
            let isHost = hostID != nil ? (key == hostID) : (host != nil && name == host)
            return PartyMemberUi(
                id: key,
                name: name,
                isHost: isHost,
                isWaiting: waitingFor.contains(name)
            )
        }
        return TogetherPartyUi(
            partyName: host.map { "\($0)’s watch party" } ?? "Watch party",
            status: status,
            members: memberUis,
            waitingFor: waitingFor,
            notice: notice
        )
    }

    mutating func applyMember(member: String, name: String, host: Bool, selfMemberID: String?) {
        if member != selfMemberID {
            if members[member] == nil {
                memberOrder.append(member)
            }
            members[member] = name
        }
        if host {
            hostMemberID = member
            hostName = name.isEmpty ? nil : name
        } else if hostMemberID == member {
            hostMemberID = nil
            hostName = nil
        }
    }

    @discardableResult
    mutating func applyMemberLeft(member: String) -> String? {
        let departedName = members[member]
        members.removeValue(forKey: member)
        memberOrder.removeAll { $0 == member }
        if hostMemberID == member {
            hostMemberID = nil
            hostName = nil
        }
        return departedName
    }

    mutating func applyWaiting(memberIDs: [String], selfMemberID: String?) {
        waitingFor = memberIDs
            .filter { $0 != selfMemberID }
            .map { members[$0] ?? "a device" }
    }
}
