import Foundation

@main struct PageCastRequestChecks {
    static func rejects(_ value: Any, linked: Bool = false) {
        do { _ = try PageCastRequest.parse(value, linked: linked); preconditionFailure("Accepted invalid request") }
        catch { precondition(error is PageCastError) }
    }

    static func main() async throws {
        let media = "https://media.example/movie.m3u8"
        let request = try PageCastRequest.parse([
            "items": [["url": media, "title": "Movie", "headers": ["Authorization": "test-token"],
                       "subtitleResources": [["url": "https://media.example/sub.vtt", "headers": ["Origin": "https://example.com"], "language": "en"]],
                       "metadata": ["title": "Movie", "posterUrl": "https://media.example/poster.jpg"]]],
            "startIndex": 20, "skipPreplay": true, "metadata": ["title": "Series"],
        ])
        precondition(request.startIndex == 0 && request.skipPreplay)
        let data = Data(request.playlistCommand(allowedPrivateOrigins: ["http://192.168.1.1:80"]).utf8)
        let envelope = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let payload = envelope["payload"] as! [String: Any]
        let item = (payload["items"] as! [[String: Any]])[0]
        precondition(item["headers"] as? [String: String] == ["Authorization": "test-token"])
        precondition((item["visualMetadata"] as? [String: Any])?["title"] as? String == "Movie")
        precondition((payload["visualMetadata"] as? [String: Any])?["title"] as? String == "Series")
        precondition(item["allowedPrivateOrigins"] as? [String] == ["http://192.168.1.1:80"])
        precondition((item["subtitleResources"] as? [[String: Any]])?.first?["language"] as? String == "en")
        let array = try PageCastRequest.parse([["url": media], ["url": media]])
        precondition(array.items.count == 2)
        let mixed = try PageCastRequest.parse(["items": [["url": media, "mediaKind": "audio"],
            ["url": "https://media.example/image.jpg", "mediaKind": "image", "displayDurationMs": 5000]]])
        precondition(mixed.items[0]["mediaKind"] as? String == "audio")
        precondition(mixed.items[1]["displayDurationMs"] as? Int == 5000)
        rejects(["url": media, "mediaKind": "script"])
        rejects(["url": media, "displayDurationMs": true])
        rejects(["url": media, "displayDurationMs": -1])
        let boolIndex = try PageCastRequest.parse(["items": [["url": media], ["url": media]], "startIndex": true])
        precondition(boolIndex.startIndex == 0, "Boolean must not become numeric index 1")
        rejects(["url": media, "skipPreplay": 1])
        rejects(["url": media, "headers": ["Host": "bad.example"]])
        rejects(["url": media, "headers": ["Origin": "file:///private/test"]])
        rejects(["url": media, "headers": ["Cookie": "one\r\ntwo"]])
        rejects(["url": media, "headers": ["Accept": "a", "accept": "b"]])
        rejects(["url": media, "headers": ["Cookie": String(repeating: "x", count: 17000)]])
        rejects(["url": "https://user:password@example.com/video"])
        rejects(["url": "file:///private/test"])
        rejects(["url": media, "subtitles": Array(repeating: media, count: 17)])
        rejects(["url": media, "subtitles": Array(repeating: media, count: 16), "subtitleResources": [["url": media]]])
        rejects(["url": media, "subtitleResources": [["url": media, "headers": ["Connection": "close"]]]])
        rejects(["url": media, "metadata": ["title": "Movie", "episode": true]])
        rejects(["url": media, "metadata": ["posterUrl": "file:///test"]])
        rejects(["url": media, "metadata": ["title": String(repeating: "a", count: 4097)]])
        rejects(["items": Array(repeating: ["url": media], count: 51)])
        rejects(["url": media, "unused": String(repeating: "a", count: 65536)])
        rejects(["url": media, "privateNetworkOrigins": ["http://192.168.1.1/path"]])
        rejects(["url": media, "privateNetworkOrigins": ["http://localhost"]])
        rejects(["url": media, "privateNetworkOrigins": ["http://127.0.0.1"]])
        rejects(["url": media, "privateNetworkOrigins": ["http://169.254.169.254"]])
        rejects(["items": [["url": media, "id": "a"], ["url": media, "id": "a"]]], linked: true)
        rejects(["items": [["url": media]]], linked: true)
        rejects(["items": [["url": media, "id": "a"]], "startIndex": 1], linked: true)
        let linked = try PageCastRequest.parse(["items": [["url": media, "id": "episode-1"]]], linked: true)
        precondition(linked.items[0]["id"] as? String == "episode-1")
        precondition(!linked.playlistCommand(allowedPrivateOrigins: []).contains("episode-1"))
        let empty = try PageCastRequest.parseItems([], linked: true, allowEmpty: true)
        precondition(empty.isEmpty)
        let privateOrigins = try await PageCastRequest.requestedPrivateOrigins(items: [
            ["url": "http://192.168.1.1/video", "subtitleResources": [["url": "https://10.0.0.1/sub.vtt"]]]
        ], declared: [], metadata: ["posterUrl": "http://172.16.0.2/poster.jpg"])
        precondition(privateOrigins == ["http://192.168.1.1:80", "https://10.0.0.1:443", "http://172.16.0.2:80"])
        for url in ["http://127.0.0.1/x", "http://[::1]/x", "http://[::ffff:127.0.0.1]/x", "http://169.254.1.2/x", "http://localhost/x"] {
            do {
                _ = try await PageCastRequest.requestedPrivateOrigins(items: [["url": url]], declared: [])
                preconditionFailure("Forbidden network accepted")
            } catch { precondition((error as? PageCastError)?.code == "private_network_denied") }
        }
        precondition(PageCastNetworkPolicy.literalClass("192.168.1.1") == .privateLAN)
        precondition(PageCastNetworkPolicy.literalClass("[fd00::123]") == .privateLAN)
        precondition(PageCastNetworkPolicy.literalClass("8.8.8.8") == .publicInternet)
        let suite = "PageCastRequestTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let permissions = PageCastPermissions(defaults: defaults)
        precondition(PageCastPermissions.origin(URL(string: "https://EXAMPLE.com:443/a?b")) == "https://example.com")
        precondition(PageCastPermissions.origin(URL(string: "https://example.com:8443/a")) == "https://example.com:8443")
        precondition(PageCastPermissions.origin(URL(string: "https://user@example.com")) == nil)
        permissions.approve("https://EXAMPLE.com:443")
        precondition(permissions.isApproved("https://example.com"))
        precondition(!permissions.isApproved("http://example.com"))
        precondition(!permissions.isApproved("https://example.com:8443"))
        precondition(!permissions.isApproved("https://sub.example.com"))
        permissions.approvePrivateOrigins(["http://192.168.1.1"], for: "https://example.com")
        precondition(permissions.privateOrigins(for: "https://example.com") == ["http://192.168.1.1:80"])
        precondition(permissions.privateOrigins(for: "https://other.example").isEmpty)
        let reloaded = PageCastPermissions(defaults: defaults)
        precondition(reloaded.isApproved("https://example.com"))
        precondition(reloaded.privateOrigins(for: "https://example.com") == ["http://192.168.1.1:80"])
        permissions.revoke("https://example.com")
        precondition(!permissions.isApproved("https://example.com") && permissions.privateOrigins(for: "https://example.com").isEmpty)
        permissions.approve("https://example.com")
        permissions.approvePrivateOrigins(["http://192.168.1.1"], for: "https://example.com")
        permissions.clearPrivateOrigins()
        precondition(permissions.isApproved("https://example.com") && permissions.privateOrigins(for: "https://example.com").isEmpty)
        for number in 1...20 {
            permissions.approvePrivateOrigins(["http://192.168.1.\(number)"], for: "https://example.com")
        }
        precondition(permissions.privateOrigins(for: "https://example.com").count == 20, "Separate approvals remain valid beyond one request's grant limit")
        permissions.clear()
        precondition(permissions.approvedOrigins.isEmpty)
        print("Website cast payload, private-origin and permission checks passed")
    }
}
