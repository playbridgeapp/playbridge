import CryptoKit
import Foundation

private struct TestHistoryKeyProvider: HistoryKeyProvider {
    let key: SymmetricKey
    func loadOrCreateKey() throws -> SymmetricKey { key }
}

private struct UnavailableHistoryKeyProvider: HistoryKeyProvider {
    func loadOrCreateKey() throws -> SymmetricKey { throw NSError(domain: "HistoryTest", code: 1) }
}

@main
struct ReceiverReviewTests {
    static func main() {
        var credentials = PairingCredentialState()
        credentials.authorize(deviceUUID: "phone-a", deviceName: "A", token: "test-a")
        credentials.authorize(deviceUUID: "phone-b", deviceName: "B", token: "test-b")
        var connections = ConnectionAuthorization<Int>()
        connections.authorize(1, token: "test-a")
        assert(connections.isAuthorized(1, credentials: credentials))
        assert(!connections.isAuthorized(2, credentials: credentials), "An authenticated phone must not authorize another socket")
        connections.authorize(2, token: "test-b")
        credentials.forgetDevice(deviceUUID: "phone-a")
        assert(!connections.isAuthorized(1, credentials: credentials))
        assert(connections.isAuthorized(2, credentials: credentials))
        connections.remove(2)
        assert(!connections.isAuthorized(2, credentials: credentials))
        connections.authorize(3, token: "test-b")
        credentials.authorize(deviceUUID: "phone-b", deviceName: "B", token: "replacement")
        assert(!connections.isAuthorized(3, credentials: credentials), "Re-pairing revokes old sessions")
        connections.authorize(4, token: "replacement")
        connections.removeAll()
        assert(!connections.isAuthorized(4, credentials: credentials))
        connections.authorize(5, token: "replacement")
        credentials.forgetAllDevices()
        assert(!connections.isAuthorized(5, credentials: credentials))
        print("PASS: per-socket auth, disconnect, re-pairing, revocation and stop")

        let suite = "PlayBridgeTVReviewTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let keyProvider = TestHistoryKeyProvider(key: SymmetricKey(size: .bits256))
        let store = HistoryStore(defaults: defaults, keyProvider: keyProvider)
        let url = URL(string: "https://example.com/video.mp4?token=private-url-test-token")!
        store.addToHistory(url: url, title: "First", headers: ["Authorization": "Bearer private-test-token"])
        assert(store.history.count == 1)
        assert(defaults.data(forKey: "pb_playback_history") == nil)
        let encrypted = defaults.data(forKey: "pb_playback_history_encrypted_v1")!
        assert(encrypted.range(of: Data("private-test-token".utf8)) == nil,
               "History credentials must not be stored in plaintext")
        assert(encrypted.range(of: Data("private-url-test-token".utf8)) == nil,
               "Authenticated URLs must not be stored in plaintext")
        store.toggleFavorite(item: store.history[0])
        store.addToHistory(url: url, title: "Replay", headers: nil)
        assert(store.history.count == 1 && store.history[0].isFavorite)
        store.updateProgress(url: url, positionMs: 120_000, durationMs: 600_000)
        store.flushProgress()
        let restored = HistoryStore(defaults: defaults, keyProvider: keyProvider)
        assert(restored.history == store.history, "Restore must complete before subsequent mutations")
        assert(restored.history[0].resumePositionMs == 120_000)
        restored.updateProgress(url: url, positionMs: 29_000, durationMs: 600_000)
        assert(restored.history[0].resumePositionMs == nil)
        restored.updateProgress(url: url, positionMs: 120_000, durationMs: 600_000)
        restored.updateProgress(url: url, positionMs: 570_000, durationMs: 600_000)
        assert(restored.history[0].resumePositionMs == nil)
        restored.clearHistory()
        assert(HistoryStore(defaults: defaults, keyProvider: keyProvider).history.isEmpty)
        defaults.set(false, forKey: "enable_history")
        restored.addToHistory(url: url, title: nil, headers: nil)
        assert(restored.history.isEmpty)
        defaults.set(true, forKey: "enable_history")
        for index in 0..<110 {
            restored.addToHistory(url: URL(string: "https://example.com/\(index)")!, title: nil, headers: nil)
        }
        assert(restored.history.count == 100)
        assert(restored.history.first?.url.lastPathComponent == "109")
        restored.clearHistory()
        assert(HistoryStore(defaults: defaults, keyProvider: keyProvider).history.isEmpty)

        // A legacy plaintext record is migrated only after an encrypted copy is written.
        let legacyItem = PlaybackHistoryItem(
            url: url, title: "Legacy", timestamp: Date(), isFavorite: true,
            headers: ["Authorization": "Bearer legacy-token"], positionMs: nil, durationMs: nil)
        let encodedLegacy = try! JSONEncoder().encode([legacyItem])
        var legacyJSON = try! JSONSerialization.jsonObject(with: encodedLegacy) as! [[String: Any]]
        legacyJSON[0].removeValue(forKey: "positionMs")
        legacyJSON[0].removeValue(forKey: "durationMs")
        defaults.set(try! JSONSerialization.data(withJSONObject: legacyJSON), forKey: "pb_playback_history")
        let migrated = HistoryStore(defaults: defaults, keyProvider: keyProvider)
        assert(migrated.history.count == 1 && migrated.history[0].isFavorite)
        assert(defaults.data(forKey: "pb_playback_history") == nil)
        assert(defaults.data(forKey: "pb_playback_history_encrypted_v1") != nil)
        migrated.clearHistory()

        migrated.addToHistory(url: url, title: "Protected", headers: nil)
        let protectedData = defaults.data(forKey: "pb_playback_history_encrypted_v1")!
        let wrongKey = HistoryStore(
            defaults: defaults,
            keyProvider: TestHistoryKeyProvider(key: SymmetricKey(size: .bits256)))
        assert(wrongKey.storageUnavailable && wrongKey.history.isEmpty)
        wrongKey.addToHistory(url: url, title: "Must not overwrite", headers: nil)
        assert(defaults.data(forKey: "pb_playback_history_encrypted_v1") == protectedData)
        defaults.set(encodedLegacy, forKey: "pb_playback_history")
        let partialMigration = HistoryStore(
            defaults: defaults,
            keyProvider: TestHistoryKeyProvider(key: SymmetricKey(size: .bits256)))
        assert(partialMigration.storageUnavailable && partialMigration.history.count == 1)
        partialMigration.clearHistory()
        assert(!partialMigration.storageUnavailable)

        defaults.set(encodedLegacy, forKey: "pb_playback_history")
        let unavailable = HistoryStore(defaults: defaults, keyProvider: UnavailableHistoryKeyProvider())
        assert(unavailable.storageUnavailable && unavailable.history.count == 1)
        assert(defaults.data(forKey: "pb_playback_history") == encodedLegacy)
        unavailable.clearHistory()
        print("PASS: encrypted history, legacy migration, progress, restore/clear and bounds")

        for invalid in [Double.nan, .infinity, -.infinity, -1, Double.greatestFiniteMagnitude, Double(Int.max)] {
            assert(PlaybackTime.milliseconds(invalid) == 0)
            assert(PlaybackTime.seconds(invalid) == 0)
        }
        assert(PlaybackTime.milliseconds(1.25) == 1250)
        assert(PlaybackTime.seconds(3661.9) == 3661)
        print("PASS: unknown/overflow playback times cannot crash integer conversion")

        assert(PlaybackPauseCommand.targetPaused(for: "play", isPlaying: true) == false)
        assert(PlaybackPauseCommand.targetPaused(for: "play", isPlaying: false) == false)
        assert(PlaybackPauseCommand.targetPaused(for: "pause", isPlaying: true) == true)
        assert(PlaybackPauseCommand.targetPaused(for: "pause", isPlaying: false) == true)
        assert(PlaybackPauseCommand.targetPaused(for: "toggle", isPlaying: true) == true)
        assert(PlaybackPauseCommand.targetPaused(for: "play_pause", isPlaying: false) == false)
        assert(PlaybackPauseCommand.targetPaused(for: "stop", isPlaying: true) == nil)
        print("PASS: idempotent MPV play/pause and explicit toggle behavior")

        assert(PlaybackEngine.allCases.map(\.name) == ["AVPlayer", "VLC", "MPV"])
        assert(PlaybackEngine.allCases.map(\.menuID) == [0, 1, 2])
        assert(PlaybackEngine.menuOrder(current: .vlc) == [.vlc, .avplayer, .mpv])
        assert(PlaybackEngine(command: "native") == .avplayer)
        assert(PlaybackEngine(command: "exo") == .avplayer)
        assert(PlaybackEngine(command: "vlc") == .vlc)
        assert(PlaybackEngine(command: "mpv") == .mpv)
        assert(PlaybackEngine(command: "unsupported") == nil)
        print("PASS: explicit player targets and current-player menu options")

        let subtitles = ExternalSubtitleCatalog(urls: [
            "https://example.com/first.srt", "https://example.com/second.vtt",
            "https://example.com/first.srt", "not a valid URL"
        ])
        assert(subtitles.options.count == 2)
        assert(subtitles.options.map(\.id) == [-2, -3])
        assert(subtitles.option(for: -1) == nil)
        assert(subtitles.option(for: -3)?.name == "External subtitle 2")
        assert(subtitles.unloadedTracks(excluding: ["https://example.com/first.srt"]).map(\.id) == [-3])
        let (expanded, lateOption) = subtitles.appending(
            url: "https://example.com/late.vtt", name: "Detected English")
        assert(lateOption.id == -4 && lateOption.name == "Detected English")
        assert(expanded.option(for: -2)?.url == "https://example.com/first.srt")
        assert(expanded.appending(url: "https://example.com/late.vtt", name: "Again").0.options.count == 3)
        assert(ExternalSubtitleCatalog(urls: ["file:///tmp/local.srt"]).options.count == 1)
        assert(ExternalSubtitleCatalog(urls: ["javascript:alert(1)"]).options.isEmpty)
        print("PASS: external subtitle choices stay distinct from loaded tracks and Off")

        let subtitleRequest = ExternalSubtitleDownload.request(
            for: URL(string: "https://sub.example.com/track.vtt")!,
            playbackHeaders: [
                "Origin": "https://video.example.com",
                "User-Agent": "PlayBridge Test",
                "Authorization": "Bearer must-not-leak",
                "Cookie": "session=must-not-leak",
                "Referer": "https://video.example.com/?secret=must-not-leak"
            ])
        assert(subtitleRequest.value(forHTTPHeaderField: "Origin") == "https://video.example.com")
        assert(subtitleRequest.value(forHTTPHeaderField: "User-Agent") == "PlayBridge Test")
        assert(subtitleRequest.value(forHTTPHeaderField: "Authorization") == nil)
        assert(subtitleRequest.value(forHTTPHeaderField: "Cookie") == nil)
        assert(subtitleRequest.value(forHTTPHeaderField: "Referer") == nil)
        assert(ExternalSubtitleDownload.subtitleExtension(for: Data("WEBVTT\n\n00:00:01.000 --> 00:00:02.000".utf8)) == "vtt")
        assert(ExternalSubtitleDownload.subtitleExtension(for: Data("1\n00:00:01,000 --> 00:00:02,000\nHi".utf8)) == "srt")
        assert(ExternalSubtitleDownload.subtitleExtension(for: Data("<html>Forbidden</html>".utf8)) == nil)
        assert(ExternalSubtitleDownload.subtitleExtension(for: Data("#EXTM3U\n".utf8)) == nil)
        let subtitleURL = URL(string: "https://sub.example.com/track.vtt")!
        let subtitleFile = FileManager.default.temporaryDirectory
            .appendingPathComponent("playbridge-test-\(UUID().uuidString).vtt")
        try! Data("WEBVTT\n\n00:01.000 --> 00:02.000\nHello".utf8).write(to: subtitleFile)
        let forbidden = HTTPURLResponse(url: subtitleURL, statusCode: 403, httpVersion: nil, headerFields: nil)!
        do {
            _ = try ExternalSubtitleDownload.prepare(file: subtitleFile, response: forbidden)
            assertionFailure("A 403 response must never be attached to the player")
        } catch ExternalSubtitleDownloadError.httpStatus(403) {
            // The original file remains available for the success-path assertion below.
        } catch { assertionFailure("Unexpected error: \(error)") }
        let ok = HTTPURLResponse(url: subtitleURL, statusCode: 200, httpVersion: nil, headerFields: nil)!
        let prepared = try! ExternalSubtitleDownload.prepare(file: subtitleFile, response: ok)
        assert(prepared.pathExtension == "vtt")
        try! FileManager.default.removeItem(at: prepared)
        print("PASS: external subtitle download forwards only safe headers and validates text format")

        let vtt = ExternalSubtitleCues(data: Data("""
        WEBVTT

        NOTE Ignore this block
        not a subtitle

        intro
        00:01.000 --> 00:03.000 align:start
        <v Speaker>Hello &amp; welcome</v>

        00:02.000 --> 00:04.000
        Second line
        """.utf8))!
        assert(vtt.text(at: 0.9) == nil)
        assert(vtt.text(at: 1.5) == "Hello & welcome")
        assert(vtt.text(at: 2.5) == "Hello & welcome\nSecond line")
        assert(vtt.text(at: 3.5) == "Second line")
        assert(vtt.text(at: 5) == nil)
        assert(vtt.text(at: .nan) == nil)
        let srt = ExternalSubtitleCues(data: Data("1\n00:00:01,500 --> 00:00:02,500\nHi\n".utf8))!
        assert(srt.text(at: 2) == "Hi")
        assert(ExternalSubtitleCues(data: Data("not captions".utf8)) == nil)
        print("PASS: WebVTT/SRT timing, overlap, seek positions and plain-text rendering")
    }
}
