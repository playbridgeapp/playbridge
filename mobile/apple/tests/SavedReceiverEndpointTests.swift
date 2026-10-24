import Foundation

@main struct SavedReceiverEndpointTests {
    static func main() {
        let saved = PairedDevice(ip: "192.0.2.10", port: 8765, name: "Living room", uuid: "receiver-a",
                                 wssPort: 8766, token: "fixture-token", certFingerprint: "fixture-pin",
                                 players: ["mpv"], browsers: ["gecko"], lastConnected: Date(timeIntervalSince1970: 123))
        let live = DiscoveredDevice(ip: "192.0.2.20", port: 9000, name: "Living room TV", uuid: "receiver-a", wssPort: 9001)
        let updated = SavedReceiverEndpoint.refresh(saved, from: [live])
        precondition(updated.ip == live.ip && updated.port == 9000 && updated.wssPort == 9001)
        precondition(updated.name == live.name && updated.uuid == saved.uuid)
        precondition(updated.token == saved.token && updated.certFingerprint == saved.certFingerprint)
        precondition(updated.players == saved.players && updated.browsers == saved.browsers && updated.lastConnected == saved.lastConnected)
        precondition((try? JSONDecoder().decode(PairedDevice.self, from: JSONEncoder().encode(saved)))?.mediaKinds == nil,
                     "Older paired-device records without mediaKinds must remain readable")
        precondition(!SavedReceiverEndpoint.sameAddress(saved, updated))
        let reusedAddress = DiscoveredDevice(ip: saved.ip, port: saved.port, name: saved.name, uuid: "different-receiver")
        precondition(SavedReceiverEndpoint.refresh(saved, from: [reusedAddress]) == saved)
        var unnamed = live
        unnamed.uuid = ""
        precondition(SavedReceiverEndpoint.refresh(saved, from: [unnamed]) == saved)
        var legacy = saved
        legacy.uuid = ""
        precondition(SavedReceiverEndpoint.refresh(legacy, from: [live]) == legacy)
        var partial = live
        partial.wssPort = nil
        precondition(SavedReceiverEndpoint.refresh(saved, from: [partial]).wssPort == saved.wssPort)
        let invalid = DiscoveredDevice(ip: live.ip, port: 70000, name: live.name, uuid: live.uuid)
        precondition(SavedReceiverEndpoint.refresh(saved, from: [invalid]) == saved)
        precondition(SavedReceiverEndpoint.refresh(updated, from: [live]) == updated)
        precondition(SavedReceiverEndpoint.refresh(updated, from: []) == updated)
        precondition(SavedReceiverEndpoint.isLive(saved, in: [live]), "UUID discovery wins over the saved address")
        precondition(!SavedReceiverEndpoint.isLive(saved, in: [reusedAddress]))
        precondition(!SavedReceiverEndpoint.isLive(saved, in: [invalid]))
        precondition(!SavedReceiverEndpoint.isLive(legacy, in: [live]))
        let sameLegacy = DiscoveredDevice(ip: legacy.ip, port: legacy.port, name: legacy.name)
        precondition(SavedReceiverEndpoint.isLive(legacy, in: [sameLegacy]))
        let occupant = DiscoveredDevice(ip: saved.ip, port: saved.port, name: "MacBook Pro.local", uuid: "mac-uuid")
        precondition(!SavedReceiverEndpoint.sameIdentity(saved, occupant), "two ids at one address are different receivers")
        precondition(SavedReceiverEndpoint.refresh(saved, from: [occupant]) == saved, "a stranger must not rename or move the saved TV")
        precondition(!SavedReceiverEndpoint.isLive(saved, in: [occupant]))
        var unnamedAtSaved = occupant
        unnamedAtSaved.uuid = ""
        precondition(SavedReceiverEndpoint.sameIdentity(saved, unnamedAtSaved), "address matches when one side has no id")
        precondition(SavedReceiverEndpoint.refresh(saved, from: [unnamedAtSaved]) == saved,
                     "an id-less advertiser does not rename or move the saved receiver")
        precondition(SavedReceiverEndpoint.isLive(saved, in: [unnamedAtSaved]))
        let strangerAtLegacy = DiscoveredDevice(ip: legacy.ip, port: legacy.port, name: "MacBook Pro.local", uuid: "mac-uuid")
        precondition(SavedReceiverEndpoint.refresh(legacy, from: [strangerAtLegacy]) == legacy,
                     "a saved receiver with no id keeps its name when another receiver sits at its address")
        precondition(SavedReceiverEndpoint.refreshHistory([legacy], from: [strangerAtLegacy]) == [legacy])
        precondition(SavedReceiverEndpoint.isLive(legacy, in: [strangerAtLegacy]),
                     "reachability still matches a uuid-less saved receiver by address")
        let portOnly = DiscoveredDevice(ip: saved.ip, port: saved.port + 1, name: unnamedAtSaved.name, uuid: "")
        precondition(!SavedReceiverEndpoint.sameIdentity(saved, portOnly))
        var first = saved
        first.uuid = "other"; first.lastConnected = Date(timeIntervalSince1970: 456)
        let history = SavedReceiverEndpoint.refreshHistory([first, saved], from: [live])
        precondition(history == [first, updated], "Address refresh preserves ordering, credentials and lastConnected")
        checkReachability(saved: saved, refreshed: updated)
        print("PASS: UUID endpoint updates, credential/capability preservation, no IP/name identity fallback, WSS preservation and invalid advertisements")
    }

    private static func checkReachability(saved: PairedDevice, refreshed: PairedDevice) {
        final class Work {
            let action: () -> Void
            var cancelled = false
            init(_ action: @escaping () -> Void) { self.action = action }
        }
        var work: [Work] = []
        var probes: [PairedDevice] = []
        var replies: [(Bool) -> Void] = []
        var cancellations = 0
        var published: [String: Bool] = [:]
        var publications = 0
        let reachability = SavedReceiverReachability(keyFor: { $0.uuid }, probe: { device, reply in
            probes.append(device); replies.append(reply)
            return { cancellations += 1; reply(false) }
        }, publish: { published = $0; publications += 1 }, schedule: { delay, action in
            precondition(delay == 0.35)
            let item = Work(action); work.append(item)
            return { item.cancelled = true }
        })

        reachability.recheck([saved])
        reachability.recheck([refreshed])
        precondition(work[0].cancelled && probes.isEmpty, "Discovery bursts debounce before opening sockets")
        work.removeFirst().action()
        precondition(probes.isEmpty, "A cancelled queued batch cannot probe the stale address")
        work.removeFirst().action()
        precondition(probes == [refreshed], "Checks use the live endpoint and retain pairing data")
        replies[0](false)
        precondition(published == [saved.uuid: false])

        reachability.invalidate()
        precondition(published.isEmpty && cancellations == 1, "A new path clears old failures and cancels probes immediately")
        let count = publications
        replies[0](true)
        precondition(publications == count && published.isEmpty, "An old path callback cannot republish even at the same IP")
        reachability.recheck([refreshed])
        work.removeFirst().action()
        replies[1](true)
        precondition(published == [saved.uuid: true])
        reachability.recheck([])
        precondition(published.isEmpty, "Forgetting a receiver removes its cached result")
        replies[1](false)
        precondition(published.isEmpty, "Forgotten receivers reject late probes")
        print("PASS: debounced live-address probes, path invalidation, cancellation, stale callbacks and forgotten receivers")
    }
}
