import Foundation

enum RemoteTVCatalogTests {
    static func run() {
        let a = PairedDevice(ip: "192.0.2.1", port: 8765, name: "Living Room", uuid: "a", token: "fixture")
        let b = PairedDevice(ip: "192.0.2.2", port: 8765, name: "Bedroom", uuid: "b")
        let record = AtvTvRecord(fingerprint: String(repeating: "a", count: 64), mac: "AA", serverName: a.name, lastIp: a.ip, receiverIds: ["playbridge:a"])
        let second = AtvTvRecord(fingerprint: String(repeating: "b", count: 64), mac: "BB", serverName: b.name, lastIp: b.ip, receiverIds: ["playbridge:b"])
        let live = AtvDiscoveredDevice(name: a.name, host: "192.0.2.3", port: 6466, addresses: ["192.0.2.3"], txt: [["bt","AA"]])
        func rows(_ saved: [PairedDevice], selected: PairedDevice? = nil, records: [AtvTvRecord] = [record], tv: [AtvDiscoveredDevice] = [], connected: String? = nil) -> [RemoteTVRow] {
            RemoteTVCatalog.rows(saved: saved, selected: selected, records: records, discovered: [], tvDevices: tv, receiverOnline: [], connectedTV: connected)
        }
        let offline = rows([a,b])
        precondition(offline.count == 2 && offline.allSatisfy { $0.status == "Offline" })
        let moved = rows([a], tv: [live])
        precondition(moved.count == 1 && moved[0].status == "TV remote only" && moved[0].device.ip == live.host)
        precondition(moved[0].device.token == a.token, "Discovery cannot replace receiver credentials")
        let recordOnly = rows([], tv: [live])
        precondition(recordOnly.count == 1 && recordOnly[0].id == "playbridge:a" && recordOnly[0].status == "TV remote only")
        precondition(rows([a], connected: "playbridge:a")[0].tvReachable)
        precondition(!rows([a], connected: "playbridge:other")[0].tvReachable)
        let aliases = AtvTvRecord(fingerprint: record.fingerprint, mac: "AA", serverName: a.name, lastIp: a.ip, receiverIds: ["playbridge:a","playbridge:old"])
        var old = a; old.uuid = "old"
        let deduped = rows([old,a], selected: a, records: [aliases], tv: [live])
        precondition(deduped.count == 1 && deduped[0].id == "playbridge:a")
        let conflict = AtvTvRecord(fingerprint: second.fingerprint, mac: "BB", serverName: a.name, lastIp: a.ip, receiverIds: ["playbridge:a"])
        let ambiguous = rows([a], records: [record,conflict], tv: [live])
        precondition(ambiguous.count == 1 && ambiguous[0].ambiguous && !ambiguous[0].paired && !ambiguous[0].tvReachable)
        precondition(rows([], records: [record,second], tv: [live]).count == 2)
        let stalePB = DiscoveredDevice(ip: a.ip, port: a.port, name: a.name, uuid: a.uuid)
        let dead = RemoteTVCatalog.rows(saved: [a], selected: a, records: [record], discovered: [stalePB], tvDevices: [live],
            receiverOnline: [], connectedTV: nil, receiverOffline: ["playbridge:a"])
        precondition(dead[0].status == "TV remote only", "A failed receiver probe overrides sticky discovery")
        print("PASS: saved/history/ATV-only TV catalog, one row per paired identity, relocation, exact connected ownership and refused ambiguity")
    }
}
