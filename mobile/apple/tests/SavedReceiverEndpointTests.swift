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
        print("PASS: UUID endpoint updates, credential/capability preservation, no IP/name identity fallback, WSS preservation and invalid advertisements")
    }
}
