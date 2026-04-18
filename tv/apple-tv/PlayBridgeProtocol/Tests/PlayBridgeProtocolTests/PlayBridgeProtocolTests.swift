import XCTest

@testable import PlayBridgeProtocol

final class PlayBridgeProtocolTests: XCTestCase {

    func testDecodePlayCommand() {
        let json = """
            {
                "type": "command",
                "action": "play",
                "payload": {
                    "url": "http://example.com/video.mp4",
                    "title": "Test Video",
                    "headers": {"User-Agent": "Test"},
                    "contentType": "video/mp4"
                }
            }
            """
        let result = PlayBridgeProtocol.decode(json)
        if case .play(let payload) = result {
            XCTAssertEqual(payload.url, "http://example.com/video.mp4")
            XCTAssertEqual(payload.title, "Test Video")
            XCTAssertEqual(payload.headers?["User-Agent"], "Test")
            XCTAssertEqual(payload.contentType, "video/mp4")
        } else {
            XCTFail("Expected .play, got \(result)")
        }
    }

    func testDecodeControlCommand() {
        let json = """
            {
                "type": "command",
                "action": "control",
                "payload": {
                    "command": "pause"
                }
            }
            """
        let result = PlayBridgeProtocol.decode(json)
        if case .control(let payload) = result {
            XCTAssertEqual(payload.command, "pause")
        } else {
            XCTFail("Expected .control, got \(result)")
        }
    }

    func testDecodePing() {
        let json = """
            {
                "type": "ping"
            }
            """
        let result = PlayBridgeProtocol.decode(json)
        if case .ping = result {
            // Success
        } else {
            XCTFail("Expected .ping, got \(result)")
        }
    }

    func testDecodePlayContent() {
        let json = """
            {
                "type": "command",
                "action": "play_content",
                "payload": {
                    "contentId": "tt1234567",
                    "contentType": "movie",
                    "title": "Sample Movie",
                    "addonBaseUrls": ["https://addon.com"],
                    "forcePicker": false
                }
            }
            """
        let result = PlayBridgeProtocol.decode(json)
        if case .playContent(let payload) = result {
            XCTAssertEqual(payload.contentId, "tt1234567")
            XCTAssertEqual(payload.title, "Sample Movie")
            XCTAssertEqual(payload.addonBaseUrls.first, "https://addon.com")
        } else {
            XCTFail("Expected .playContent, got \(result)")
        }
    }

    func testDecodeSeriesContext() {
        let json = """
            {
                "type": "command",
                "action": "play",
                "payload": {
                    "url": "http://example.com/video.mp4",
                    "seriesContext": {
                        "imdbId": "tt0944947",
                        "season": 1,
                        "episode": 1,
                        "seriesTitle": "Game of Thrones",
                        "addonBaseUrls": ["https://torrentio.strem.fun"]
                    }
                }
            }
            """
        let result = PlayBridgeProtocol.decode(json)
        if case .play(let payload) = result {
            XCTAssertNotNil(payload.seriesContext)
            XCTAssertEqual(payload.seriesContext?.imdbId, "tt0944947")
            XCTAssertEqual(payload.seriesContext?.seriesTitle, "Game of Thrones")
        } else {
            XCTFail("Expected .play, got \(result)")
        }
    }
}
