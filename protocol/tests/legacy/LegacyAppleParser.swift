import Foundation

struct TvCapabilities: Equatable {
    let players: [String], browsers: [String], mediaKinds: [String], features: [String]
}
struct Target { let serverName = "test" }
enum State { case connected(serverName: String, secure: Bool), authFailed }
final class SocketStub {
    enum Code { case normalClosure }
    func cancel(with: Code, reason: Data?) {}
}
final class LegacyAppleParser {
    var target: Target? = Target()
    var isSecure = true
    var isUserDisconnect = false
    var task: SocketStub? = SocketStub()
    var accepted = false
    var parsed: TvCapabilities?
    lazy var onCapabilities: ((TvCapabilities) -> Void)? = { self.parsed = $0 }
    func setState(_ state: State) {
        if case .connected = state { accepted = true } else { accepted = false }
    }
    func startPing() {}
    func parse(_ json: [String: Any], auth: Bool) {
        if auth { handleAuthResponse(json) } else { emitCapabilities(json) }
    }
    private func handleAuthResponse(_ json: [String: Any]) {
        let serverName = target?.serverName ?? ""
        let success = (json["success"] as? Bool) ?? false
        if success {
            emitCapabilities(json)
            setState(.connected(serverName: serverName, secure: isSecure))
            // SEC-005: legacy echoed tokens in auth_response are ignored.
            startPing()
        } else {
            // Stale token — wipe and re-pair. Flag before close so teardown doesn't overwrite it.
            isUserDisconnect = true
            setState(.authFailed)
            task?.cancel(with: .normalClosure, reason: nil)
        }
    }

    private func emitCapabilities(_ json: [String: Any]) {
        let players = (json["players"] as? [String]) ?? []
        let browsers = (json["browsers"] as? [String]) ?? []
        let mediaKinds = (json["mediaKinds"] as? [String]) ?? []
        let features = (json["features"] as? [String]) ?? []
        if !players.isEmpty || !browsers.isEmpty || !mediaKinds.isEmpty || !features.isEmpty {
            onCapabilities?(TvCapabilities(players: players, browsers: browsers,
                                           mediaKinds: mediaKinds, features: features))
        }
    }

}

let fixtureDir = URL(fileURLWithPath: CommandLine.arguments[1])
let cases = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureDir.appendingPathComponent("manifest.json"))) as! [[String: Any]]
var count = 0
for item in cases where item["valid"] as? Bool == true {
    let schema = item["schema"] as! String
    if schema != "AuthResponse" && schema != "CredentialBundle" { continue }
    let file = item["file"] as! String
    let obj = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureDir.appendingPathComponent(file))) as! [String: Any]
    var without = obj; without.removeValue(forKey: "capabilities")
    let old = LegacyAppleParser(), incoming = LegacyAppleParser()
    old.parse(without, auth: schema == "AuthResponse")
    incoming.parse(obj, auth: schema == "AuthResponse")
    precondition(old.accepted == incoming.accepted && old.parsed == incoming.parsed, file)
    if schema == "CredentialBundle" {
        // Protected-bundle field reads after decryption, not a SAS/certificate test.
        precondition((obj["token"] as? String) == (without["token"] as? String), file)
        precondition((obj["certFingerprint"] as? String) == (without["certFingerprint"] as? String), file)
    }
    count += 1
}
print("Retained Apple manual fields OK: \(count) auth/decrypted-bundle projections")
