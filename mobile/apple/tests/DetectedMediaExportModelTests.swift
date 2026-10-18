// SPDX-License-Identifier: MPL-2.0
import Foundation

enum DetectedMediaExportModelTests {
    static func run() throws {
        precondition(DetectedMediaExport.quote("it's") == "'it'\\''s'")
        precondition(DetectedMediaExport.quote("a\nb") == "'ab'", "Newlines must not break out of the command")
        let curl = DetectedMediaExport.curl(url: "https://cdn.test/v.m3u8?t=1&x='y'",
                                            headers: ["Referer": "https://site.test/", "User-Agent": "UA/1.0", "Cookie": "a=1; b=2"])
        precondition(curl == """
        curl -L 'https://cdn.test/v.m3u8?t=1&x='\\''y'\\''' \\
          -H 'Cookie: a=1; b=2' \\
          -H 'Referer: https://site.test/' \\
          -H 'User-Agent: UA/1.0'
        """, curl)
        let item = DetectedVideo(url: "https://cdn.test/v.m3u8", contentType: "application/vnd.apple.mpegurl",
                                 detectedBy: "network", originUrl: "https://site.test/watch", headers: [:], kind: .hls,
                                 timestamp: 1_700_000_000_000, title: "Episode 1")
        let json = DetectedMediaExport.json(item, headers: ["Referer": "https://site.test/"],
                                            qualities: [VideoQuality(label: "720p", bandwidth: 2_000_000, url: "https://cdn.test/720.m3u8")])
        let object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
        precondition(object["url"] as? String == "https://cdn.test/v.m3u8" && object["kind"] as? String == item.kind.rawValue)
        precondition(object["pageUrl"] as? String == "https://site.test/watch" && object["title"] as? String == "Episode 1")
        precondition((object["headers"] as? [String: String])?["Referer"] == "https://site.test/")
        precondition((object["qualities"] as? [[String: Any]])?.first?["label"] as? String == "720p")
        precondition(json.contains("https://cdn.test/v.m3u8"), "Slashes must not be escaped")
        print("CHECK: cast item JSON and shell-safe cURL export")
    }
}
