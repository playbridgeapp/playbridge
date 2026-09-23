import Foundation

enum StreamRoutingError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case .message(let text): return text } }
}
@main struct DLNAChecks {
    @MainActor static func main() async throws {
        let base = ProcessInfo.processInfo.environment["DLNA_FIXTURE"]!
        let device = DLNABrowser.device(from: ["protocol": "Dlna", "id": "fixture", "location": base + "/device.xml"])!
        precondition(device.isDLNA && device.identity == "dlna:fixture")
        precondition(DLNABrowser.device(from: ["protocol": "GoogleCast", "id": "fixture", "location": base]) == nil)
        precondition(DLNABrowser.device(from: ["protocol": "Dlna", "id": "fixture", "location": "file:///tmp/device.xml"]) == nil)
        let saved = try JSONDecoder().decode(ExternalReceiverDevice.self, from: JSONEncoder().encode(device))
        precondition(saved == device)
        let legacy = Data(#"{"id":"old","name":"Cast","addresses":["192.0.2.1"],"port":8009,"model":"Cast"}"#.utf8)
        let oldDevice = try JSONDecoder().decode(ExternalReceiverDevice.self, from: legacy)
        precondition(!oldDevice.isDLNA)
        let controller = GoogleCastController()
        controller.connect(device)
        for _ in 0..<500 {
            if controller.state.isConnected { break }
            if case .error(let message) = controller.state { fatalError(message) }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        precondition(controller.state == .connected(serverName: "DLNA Fixture", secure: false))
        try await controller.load(url: URL(string: "https://example.test/video.mp4?x=1&y=2")!, title: "Fixture", contentType: "video/mp4")
        try await controller.control("pause")
        try await controller.control("play")
        try await controller.control("seek_forward")
        for _ in 0..<400 {
            if controller.playback?.positionMs == 12000 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        precondition(controller.playback?.positionMs == 12000)
        try await controller.control("stop")
        precondition(controller.state.isConnected)
        controller.disconnect()
        let (data, _) = try await URLSession.shared.data(from: URL(string: base + "/actions")!)
        let actions = try JSONSerialization.jsonObject(with: data) as! [[String: String]]
        for expected in ["SetAVTransportURI", "Play", "Pause", "Seek", "Stop", "GetTransportInfo", "GetPositionInfo"] {
            precondition(actions.contains { $0["action"] == expected }, "Missing SOAP action: " + expected)
        }
        precondition(actions.first { $0["action"] == "SetAVTransportURI" }!["body"]!.contains("x=1&amp;y=2"))
        let roku = DLNABrowser.manualRoku(base)!
        precondition(roku.protocolID == "roku" && !roku.isDLNA)
        precondition(DLNABrowser.manualRoku("192.0.2.1")?.port == 8060)
        precondition(DLNABrowser.manualRoku("http://user:secret@192.0.2.1") == nil)
        precondition(DLNABrowser.manualRoku("https://192.0.2.1") == nil)
        let discovered = DLNABrowser.device(from: ["protocol": "Roku", "id": "fixture", "location": base, "port": roku.port])!
        precondition(discovered.protocolID == "roku")
        let dial = DLNABrowser.device(from: ["protocol": "Dial", "id": "fixture", "location": base])!
        precondition(dial.protocolID == "dial" && dial.identity != discovered.identity)
        controller.connect(roku)
        for _ in 0..<500 {
            if controller.state.isConnected { break }
            if case .error(let message) = controller.state { fatalError(message) }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        precondition(controller.state == .connected(serverName: "Roku Fixture", secure: false))
        let mediaURL = "https://example.test/video.mp4?token=a+b&other=1"
        try await controller.load(url: URL(string: mediaURL)!, title: "Fixture", contentType: "video/mp4")
        try await controller.control("pause")
        try await controller.control("seek_forward")
        try await controller.control("seek_back")
        try await controller.control("stop")
        controller.disconnect()
        let (rokuData, _) = try await URLSession.shared.data(from: URL(string: base + "/actions")!)
        let rokuActions = try JSONSerialization.jsonObject(with: rokuData) as! [[String: Any]]
        for expected in ["/launch/15985", "/keypress/Play", "/keypress/Fwd", "/keypress/Rev", "/keypress/Stop"] {
            precondition(rokuActions.contains { $0["action"] as? String == expected })
        }
        let launch = rokuActions.first { $0["action"] as? String == "/launch/15985" }!
        precondition((launch["query"] as? [String: [String]])?["contentID"] == [mediaURL])
        print("PASS: Roku discovery parsing, manual entry, native ECP connection, launch URL encoding and controls; DIAL protocol separation")
        print("PASS: DLNA parsing, saved-device compatibility, real Rust connect/load/status/controls and SOAP URL escaping")
    }
}
