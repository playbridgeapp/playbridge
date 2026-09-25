import Foundation

@main struct CastHistoryTests {
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("history.json")
        let store = CastHistoryStore(fileURL: file)
        let normal = WireProtocol.singleVideoCommand(url: "https://media.example/video.mp4", title: "Fixture",
            headers: ["Referer": "https://page.example/"], playerMode: "native")
        let marked = WireProtocol.applyingHistoryPreference(normal, prevent: true)
        precondition(marked.contains("\"skipHistory\":true"))
        precondition(WireProtocol.applyingHistoryPreference(marked, prevent: false) == marked)
        precondition(WireProtocol.applyingHistoryPreference(normal, prevent: false) == normal)
        store.record(marked, receiver: "TV")
        precondition(store.entries.isEmpty)
        let mixed = """
        {"type":"command","action":"playlist","payload":{"items":[{"url":"https://media.example/public"},{"url":"https://media.example/private","skipHistory":true}]}}
        """
        store.record(mixed, receiver: "TV")
        precondition(store.entries.isEmpty && !FileManager.default.fileExists(atPath: file.path))
        let queue = WireProtocol.queueVideoCommand(url: "https://media.example/queued")
        precondition(CastHistoryStore.items(in: WireProtocol.applyingHistoryPreference(queue, prevent: true)) == nil)
        let control = WireProtocol.controlCommand("pause")
        precondition(WireProtocol.applyingHistoryPreference(control, prevent: true) == control)
        let resourceCommand = WireProtocol.addSubtitleCommand(url: "https://subs.example/file.vtt",
            headers: ["Origin": "https://site.example"], label: "English", requestID: "fixture-request")
        let resourceJSON = try JSONSerialization.jsonObject(with: Data(resourceCommand.utf8)) as! [String: Any]
        let resourcePayload = resourceJSON["payload"] as! [String: Any]
        let resource = resourcePayload["subtitleResource"] as! [String: Any]
        precondition(resourcePayload["command"] as? String == "add_subtitle")
        precondition(resourceJSON["requestId"] as? String == "fixture-request")
        precondition((resource["headers"] as? [String: String])?["Origin"] == "https://site.example")
        let imageCommand = WireProtocol.singleVideoCommand(url: "https://media.example/photo.jpg", mediaKind: "image")
        let imageItems = CastHistoryStore.items(in: imageCommand)!
        precondition(imageItems.first?["mediaKind"] as? String == "image")
        let queuedAudio = WireProtocol.queueVideoCommand(url: "https://media.example/song.mp3", mediaKind: "audio")
        let queuedJSON = try JSONSerialization.jsonObject(with: Data(queuedAudio.utf8)) as! [String: Any]
        let queuedPayload = queuedJSON["payload"] as! [String: Any]
        precondition((queuedPayload["item"] as? [String: Any])?["mediaKind"] as? String == "audio")
        store.record(control, receiver: nil)
        store.record(normal, receiver: "TV")
        store.record(normal, receiver: "TV")
        precondition(store.entries.count == 1 && store.persistenceError == nil)
        let reloaded = CastHistoryStore(fileURL: file)
        precondition(reloaded.entries.count == 1 && reloaded.entries[0].command == normal)
        for index in 0..<105 {
            store.record(WireProtocol.singleVideoCommand(url: "https://media.example/\(index)"), receiver: nil)
        }
        precondition(store.entries.count == 100)
        store.delete(store.entries[0].id)
        precondition(CastHistoryStore(fileURL: file).entries.count == 99)
        store.clear()
        precondition(CastHistoryStore(fileURL: file).entries.isEmpty)
        print("PASS: history round trip, deduplication, bounds, deletion, mixed private playlist rejection, queue flags and unchanged controls")
    }
}
