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

        var timelineGate = PlaybackUIUpdateGate()
        assert(timelineGate.shouldUpdate(at: 0))
        assert(!timelineGate.shouldUpdate(at: 0.01))
        assert(!timelineGate.shouldUpdate(at: 0.09))
        assert(timelineGate.shouldUpdate(at: 0.11))
        assert(!timelineGate.shouldUpdate(at: 0.12))
        assert(!timelineGate.shouldUpdate(at: .nan))
        assert(!timelineGate.shouldUpdate(at: .infinity))
        timelineGate.reset()
        assert(timelineGate.shouldUpdate(at: 0.13))
        assert(timelineGate.shouldUpdate(at: 0)) // a clock reset cannot freeze the HUD
        print("PASS: playback timeline UI updates are bounded and reset for a new item")

        assert(PlaybackPauseCommand.targetPaused(for: "play", isPlaying: true) == false)
        assert(PlaybackPauseCommand.targetPaused(for: "play", isPlaying: false) == false)
        assert(PlaybackPauseCommand.targetPaused(for: "pause", isPlaying: true) == true)
        assert(PlaybackPauseCommand.targetPaused(for: "pause", isPlaying: false) == true)
        assert(PlaybackPauseCommand.targetPaused(for: "toggle", isPlaying: true) == true)
        assert(PlaybackPauseCommand.targetPaused(for: "play_pause", isPlaying: false) == false)
        assert(PlaybackPauseCommand.targetPaused(for: "stop", isPlaying: true) == nil)
        print("PASS: idempotent MPV play/pause and explicit toggle behavior")

        assert(PlaybackEngine.allCases.map(\.name) == ["AVPlayer", "MPV"])
        assert(PlaybackEngine.allCases.map(\.menuID) == [0, 2])
        assert(PlaybackEngine.capabilityPlayers == ["avplayer", "mpv"])
        assert(PlaybackEngine.menuOrder(current: .avplayer) == [.avplayer, .mpv])
        assert(PlaybackEngine.menuOrder(current: .mpv) == [.mpv, .avplayer])
        assert(PlaybackEngine(command: "native") == .avplayer)
        assert(PlaybackEngine(command: "exo") == .avplayer)
        assert(PlaybackEngine(command: "vlc") == .mpv)
        assert(PlaybackEngine(command: "VLC") == .mpv)
        assert(PlaybackEngine(command: "mpv") == .mpv)
        assert(PlaybackEngine(command: "unsupported") == nil)
        let preferenceSuite = "playbridge-engine-migration-\(UUID().uuidString)"
        let engineDefaults = UserDefaults(suiteName: preferenceSuite)!
        defer { engineDefaults.removePersistentDomain(forName: preferenceSuite) }
        for legacy in ["vlc", "VLC"] {
            engineDefaults.set(legacy, forKey: "preferredPlayer")
            PlaybackEngine.migrateLegacyPreference(in: engineDefaults)
            assert(engineDefaults.string(forKey: "preferredPlayer") == "mpv")
            PlaybackEngine.migrateLegacyPreference(in: engineDefaults)
            assert(engineDefaults.string(forKey: "preferredPlayer") == "mpv")
        }
        for unchanged in ["avplayer", "mpv", "unsupported"] {
            engineDefaults.set(unchanged, forKey: "preferredPlayer")
            PlaybackEngine.migrateLegacyPreference(in: engineDefaults)
            assert(engineDefaults.string(forKey: "preferredPlayer") == unchanged)
        }
        engineDefaults.removeObject(forKey: "preferredPlayer")
        PlaybackEngine.migrateLegacyPreference(in: engineDefaults)
        assert(engineDefaults.string(forKey: "preferredPlayer") == nil)
        print("PASS: two engines/capabilities/menu targets and idempotent legacy VLC-to-MPV preference/command migration")

        var reusedAudioOptions = ["aid": "3", "mute": "yes", "volume": "75"]
        for (name, value) in MPVAudioPolicy.loadOptions(isPreBuffering: false) { reusedAudioOptions[name] = value }
        assert(reusedAudioOptions["aid"] == "auto" && reusedAudioOptions["mute"] == "no")
        assert(reusedAudioOptions["volume"] == "75")
        for (name, value) in MPVAudioPolicy.loadOptions(isPreBuffering: true) { reusedAudioOptions[name] = value }
        assert(reusedAudioOptions["aid"] == "auto" && reusedAudioOptions["mute"] == "yes")
        assert(MPVAudioPolicy.muteValue(isPreBuffering: false) == "no")
        assert(MPVAudioPolicy.muteValue(isPreBuffering: true) == "yes")
        assert(MPVAudioPolicy.muteValue(isPreBuffering: false, appMuted: true) == "yes")
        assert(MPVAudioPolicy.muteValue(isPreBuffering: true, appMuted: true) == "yes")
        let audioTestPath = "https://example.test/video?parts=1,2"
        assert(MPVAudioPolicy.loadArguments(path: audioTestPath, isPreBuffering: false) ==
               ["loadfile", audioTestPath, "replace", "-1", "aid=auto,mute=no"])
        assert(MPVAudioPolicy.loadArguments(path: "/tmp/movie.mp4", isPreBuffering: true) ==
               ["loadfile", "/tmp/movie.mp4", "replace", "-1", "aid=auto,mute=yes"])
        assert(MPVAudioPolicy.loadArguments(path: "/tmp/movie.mp4", isPreBuffering: false, appMuted: true) ==
               ["loadfile", "/tmp/movie.mp4", "replace", "-1", "aid=auto,mute=yes"])
        print("PASS: MPV per-file audio resets stale track IDs/preplay mute without touching outgoing tracks or volume")

        let playbackHeaders = MPVHTTPHeaders.fields(from: [
            "Range": "bytes=0-", "If-Range": "captured-etag",
            "User-Agent": "Browser UA", "Referer": "https://video.example.com/",
            "Authorization": "Bearer test-token", "Cookie": "session=test",
            "Accept": "video/mp4,video/*", "X-Test": "a\\b,c"
        ])
        assert(playbackHeaders == "Accept: video/mp4\\,video/*,Authorization: Bearer test-token,Cookie: session=test,Referer: https://video.example.com/,X-Test: a\\\\b\\,c")
        for name in ["range", "RANGE", "rAnGe", "if-range", "IF-RANGE", "uSeR-aGeNt"] {
            assert(MPVHTTPHeaders.fields(from: [name: "captured"]) == "")
        }
        assert(MPVHTTPHeaders.fields(from: nil) == "")
        assert(MPVHTTPHeaders.fields(from: [:]) == "")
        assert(MPVHTTPHeaders.fields(from: ["User-Agent": "Browser UA", "Range": "bytes=10-20"]) == "")
        assert(MPVHTTPHeaders.fields(from: ["Referer": "https://next.example.com/"]) == "Referer: https://next.example.com/")
        print("PASS: MPV owns byte ranges; playback headers preserve credentials/escaping and clear between items")

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

        runRemoteNavigationTests()
        runTransportChromeTests()
        runTouchScrubTests()
        runPlaybackFailureSessionTests()
        runPlayerControlsAuditTests()
        runPlayerSettingsTests()
        runPlayerTuningTests()
        runPlayerSettingsMenuTests()
        runAppVolumeTests()
        runTimelineReportTests()
        runChapterNavigationTests()
        runChapterRemoteTests()
        runSkipSegmentTests()
        runSkipSegmentFetchTests()
    }
}

private func pumpMain() {
    let until = Date().addingTimeInterval(0.05)
    while Date() < until {
        RunLoop.current.run(mode: .default, before: until)
    }
}

private func runPlayerControlsAuditTests() {
    // HUD visibility and pause state do not delegate Select; only a focused
    // Skip/action button or a list does. Play/Pause always controls playback.
    for owned in [false, true] {
        assert(PlayerRemoteNavigation.physicalPlaybackEffect(for: .playPause,
            scrubbing: false, selectActivatesFocusedControl: owned) == .togglePlayback)
        assert(PlayerRemoteNavigation.physicalPlaybackEffect(for: .playPause,
            scrubbing: true, selectActivatesFocusedControl: owned) == .commitScrub)
        assert(PlayerRemoteNavigation.physicalPlaybackEffect(for: .select,
            scrubbing: false, selectActivatesFocusedControl: owned) == (owned ? .ignored : .togglePlayback))
        assert(PlayerRemoteNavigation.physicalPlaybackEffect(for: .select,
            scrubbing: true, selectActivatesFocusedControl: owned) == (owned ? .ignored : .commitScrub))
    }
    print("PASS: Select toggles on the video/seek bar, focused buttons retain Select, and hold-scrub commits")

    // Both physical MPV Back and phone Back now enter this same policy. The
    // playlist belongs to the player screen and must precede the engine HUD.
    var chrome = PlayerChromeState(sessionMatches: true, scrubbing: true, playlist: true, controlsVisible: true, infoPanel: true)
    for expected: PlayerRemoteEffect in [.dismissInfoPanel, .dismissScrub, .dismissPlaylist, .dismissControls] {
        let effect = PlayerRemoteNavigation.effect(for: "back", chrome: chrome)
        assert(effect == expected)
        PlayerRemoteNavigation.closing(effect, in: &chrome)
    }
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: chrome) == .exitPlayback)
    var pausedHUD = PlayerChromeState(sessionMatches: true, controlsVisible: true)
    let firstBack = PlayerRemoteNavigation.effect(for: "back", chrome: pausedHUD)
    assert(firstBack == .dismissControls)
    PlayerRemoteNavigation.closing(firstBack, in: &pausedHUD)
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: pausedHUD) == .exitPlayback)
    print("PASS: shared physical/phone Back closes menus, scrub, playlist and HUD before exiting")
}

private func runRemoteNavigationTests() {
    let overlays: [(String, WritableKeyPath<PlayerChromeState, Bool>, PlayerRemoteEffect)] = [
        ("still watching", \.stillWatching, .dismissStillWatching),
        ("info panel", \.infoPanel, .dismissInfoPanel),
        ("engine menu", \.engineMenu, .dismissEngineMenu),
        ("settings sheet", \.settingsSheet, .dismissSettingsSheet),
        ("scrub", \.scrubbing, .dismissScrub),
        ("playlist", \.playlist, .dismissPlaylist),
        ("controls", \.controlsVisible, .dismissControls),
    ]

    for (name, path, expected) in overlays {
        var only = PlayerChromeState(sessionMatches: true)
        only[keyPath: path] = true
        let effect = PlayerRemoteNavigation.effect(for: "back", chrome: only)
        assert(effect == expected, "Back should close \(name)")
        var closed = only
        PlayerRemoteNavigation.closing(effect, in: &closed)
        assert(closed[keyPath: path] == false, "Back should clear \(name)")
        assert(closed.sessionMatches, "Closing \(name) should stay in the player")
        for (otherName, otherPath, _) in overlays where otherPath != path {
            assert(closed[keyPath: otherPath] == false, "Closing \(name) should leave \(otherName) untouched")
        }
        assert(PlayerRemoteNavigation.effect(for: "home", chrome: only) == .exitPlayback,
               "Home should leave from \(name)")

        var stacked = PlayerChromeState(
            sessionMatches: true,
            stillWatching: true,
            engineMenu: true,
            settingsSheet: true,
            scrubbing: true,
            playlist: true,
            controlsVisible: true,
            infoPanel: true
        )
        stacked[keyPath: path] = false
        // The highest remaining overlay is the one Back closes.
        if let top = overlays.first(where: { stacked[keyPath: $0.1] }) {
            assert(PlayerRemoteNavigation.effect(for: "back", chrome: stacked) == top.2,
                   "Back with \(name) already closed should close \(top.0)")
        }
    }

    let everything = PlayerChromeState(
        sessionMatches: true,
        stillWatching: true,
        engineMenu: true,
        settingsSheet: true,
        scrubbing: true,
        playlist: true,
        controlsVisible: true,
        infoPanel: true
    )
    var walk = everything
    for (_, path, expected) in overlays {
        let effect = PlayerRemoteNavigation.effect(for: "back", chrome: walk)
        assert(effect == expected, "Stacked Back should close one level at a time")
        let beforeClose = walk
        PlayerRemoteNavigation.closing(effect, in: &walk)
        assert(walk[keyPath: path] == false)
        for (_, otherPath, _) in overlays where otherPath != path {
            assert(walk[keyPath: otherPath] == beforeClose[keyPath: otherPath],
                   "Back should leave the other overlays open")
        }
    }
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: walk) == .exitPlayback,
           "Back with nothing open should exit")
    assert(PlayerRemoteNavigation.effect(for: "home", chrome: everything) == .exitPlayback)
    assert(PlayerRemoteNavigation.effect(for: "home", chrome: PlayerChromeState(sessionMatches: true)) == .exitPlayback)

    var stale = everything
    stale.sessionMatches = false
    let staleBefore = stale
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: stale) == .ignored)
    assert(PlayerRemoteNavigation.effect(for: "home", chrome: stale) == .ignored)
    assert(stale == staleBefore, "A stale key leaves every overlay where it is")
    for key in ["volume_up", "Back", "Home", ""] {
        assert(PlayerRemoteNavigation.effect(for: key, chrome: everything) == .ignored,
               "\(key) stays outside Back/Home")
    }
    print("PASS: Back closes one overlay, Home exits, stale and other keys are ignored")
    runRemoteDirectionTests()

    let gate = PlayerRemoteExitGate()
    gate.note(engine: .avplayer, generation: 1)
    var exits = 0
    gate.scheduleExit(engine: .avplayer, generation: 1) { exits += 1 }
    gate.note(engine: .mpv, generation: 2)
    pumpMain()
    assert(exits == 0 && !gate.didExit, "A key queued for the previous item must not exit")
    gate.scheduleExit(engine: .mpv, generation: 2) { exits += 1 }
    pumpMain()
    assert(exits == 1 && gate.didExit)
    gate.scheduleExit(engine: .mpv, generation: 2) { exits += 1 }
    assert(gate.beginExit() == false)
    pumpMain()
    assert(exits == 1, "A second Home after exit must not run the owner path again")
    print("PASS: stale generation and a repeated exit are ignored")

    var events: [[String: Any]] = []
    let webhook = PlaybackProgressWebhook(send: { _, _, data in
        events.append(try! JSONSerialization.jsonObject(with: data) as! [String: Any])
    })
    webhook.configure(url: "https://example.com/progress", token: "test-token", playbackID: "cast-remote")
    let identity: [String: Any] = ["type": "movie", "contentId": "movie", "videoId": "movie-1"]
    webhook.sample(itemID: "movie-1", content: identity, state: "playing", position: 120_000, duration: 600_000)

    let suite = "PlayBridgeTVRemoteExit.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let provider = TestHistoryKeyProvider(key: SymmetricKey(size: .bits256))
    let store = HistoryStore(defaults: defaults, keyProvider: provider)
    let url = URL(string: "https://example.com/remote-exit.mp4")!
    store.addToHistory(url: url, title: "Remote", headers: nil)
    store.updateProgress(url: url, positionMs: 120_000, durationMs: 600_000)
    let beforeFlush = HistoryStore(defaults: defaults, keyProvider: provider)
    assert(beforeFlush.history.first?.resumePositionMs == nil,
           "Progress stays unsaved until the exit flush")

    var progressFinishes = 0
    var historyFlushes = 0
    var idleBroadcasts = 0
    let owner = PlayerRemoteExitGate()
    owner.note(engine: .avplayer, generation: 4)
    func ownerExit() {
        guard owner.beginExit() else { return }
        PlayerSessionExit.perform(
            finishProgress: {
                progressFinishes += 1
                webhook.finish(clear: true)
            },
            flushHistory: {
                historyFlushes += 1
                store.flushProgress()
            },
            leaveToIdle: {
                // The screen dismiss finishes progress again and publishes idle once.
                webhook.finish(clear: true)
                idleBroadcasts += 1
            }
        )
    }
    ownerExit()
    ownerExit()
    assert(progressFinishes == 1 && historyFlushes == 1 && idleBroadcasts == 1)
    assert(events.compactMap { $0["event"] as? String } == ["started", "stopped"])
    let afterFlush = HistoryStore(defaults: defaults, keyProvider: provider)
    assert(afterFlush.history.first?.resumePositionMs == 120_000)
    let saved = defaults.data(forKey: "pb_playback_history_encrypted_v1")
    ownerExit()
    assert(defaults.data(forKey: "pb_playback_history_encrypted_v1") == saved)
    assert(progressFinishes == 1 && historyFlushes == 1 && idleBroadcasts == 1)
    print("PASS: exit saves progress and history once and broadcasts idle once")
}

private func runRemoteDirectionTests() {
    func effect(_ key: String, _ chrome: PlayerChromeState) -> PlayerRemoteEffect {
        PlayerRemoteNavigation.effect(for: key, chrome: chrome)
    }

    func assertTransport(_ chrome: PlayerChromeState, _ name: String) {
        assert(effect("dpad_left", chrome) == .seekBackward, "\(name) Left seeks")
        assert(effect("dpad_right", chrome) == .seekForward, "\(name) Right seeks")
        assert(effect("dpad_up", chrome) == .adjustAppVolume(steps: 1), "\(name) Up steps app volume")
        assert(effect("dpad_down", chrome) == .adjustAppVolume(steps: -1), "\(name) Down steps app volume")
        assert(effect("dpad_center", chrome) == .togglePlayback, "\(name) Center toggles playback")
        assert(effect("volume_up", chrome) == .adjustAppVolume(steps: 1), "\(name) volume up steps app volume")
        assert(effect("volume_down", chrome) == .adjustAppVolume(steps: -1), "\(name) volume down steps app volume")
        assert(effect("mute", chrome) == .toggleAppMute, "\(name) mute toggles app mute")
    }

    let menu = PlayerChromeState(sessionMatches: true, infoPanel: true)
    assert(effect("dpad_up", menu) == .moveHighlight(delta: -1))
    assert(effect("dpad_down", menu) == .moveHighlight(delta: 1))
    assert(effect("dpad_center", menu) == .activateHighlight)
    assert(effect("dpad_left", menu) == .seekBackward)
    assert(effect("dpad_right", menu) == .seekForward)
    var rows = PlayerHighlightList(count: 4, index: 1)
    if case .moveHighlight(let delta) = effect("dpad_down", menu) { rows = rows.moving(delta) }
    assert(rows.index == 2, "Down moves one row")
    if case .moveHighlight(let delta) = effect("dpad_up", menu) { rows = rows.moving(delta) }
    assert(rows.index == 1, "Up moves one row")
    assert(PlayerHighlightList(count: 4, index: 0).moving(-1).index == 0, "Up clamps on the first row")
    assert(PlayerHighlightList(count: 4, index: 3).moving(1).index == 3, "Down clamps on the last row")
    assert(PlayerHighlightList(count: 0, index: 0).moving(1).index == 0)
    assert(effect("volume_up", menu) == .adjustAppVolume(steps: 1), "Volume up steps app volume while a menu is open")
    assert(effect("volume_down", menu) == .adjustAppVolume(steps: -1))
    assert(effect("mute", menu) == .toggleAppMute)
    for key in ["Back", "Home", ""] {
        assert(effect(key, menu) == .ignored, "\(key) stays ignored while a menu is open")
    }
    assert(effect("dpad_up", PlayerChromeState(sessionMatches: true, engineMenu: true)) == .moveHighlight(delta: -1))
    assert(effect("dpad_center", PlayerChromeState(sessionMatches: true, engineMenu: true)) == .activateHighlight)

    var menuAndPlaylist = PlayerChromeState(sessionMatches: true, playlist: true, infoPanel: true)
    assert(PlayerRemoteNavigation.listOwner(in: menuAndPlaylist) == .infoPanel)
    assert(effect("dpad_down", menuAndPlaylist) == .moveHighlight(delta: 1))
    assert(effect("dpad_center", menuAndPlaylist) == .activateHighlight)
    var playlistRows = PlayerHighlightList(count: 5, index: 2)
    let playlistBefore = playlistRows
    if PlayerRemoteNavigation.listOwner(in: menuAndPlaylist) == .playlist,
       case .moveHighlight(let delta) = effect("dpad_down", menuAndPlaylist) {
        playlistRows = playlistRows.moving(delta)
    }
    assert(playlistRows == playlistBefore, "An open menu keeps the playlist highlight where it is")
    menuAndPlaylist.infoPanel = false
    assert(PlayerRemoteNavigation.listOwner(in: menuAndPlaylist) == .playlist)
    assert(effect("dpad_up", menuAndPlaylist) == .moveHighlight(delta: -1))
    assert(effect("dpad_down", menuAndPlaylist) == .moveHighlight(delta: 1))
    assert(effect("dpad_center", menuAndPlaylist) == .activateHighlight)
    assert(effect("dpad_left", menuAndPlaylist) == .seekBackward)
    assert(effect("dpad_right", menuAndPlaylist) == .seekForward)
    assert(effect("volume_up", menuAndPlaylist) == .adjustAppVolume(steps: 1),
           "Volume up steps app volume while the playlist is open")
    assert(effect("dpad_up", menuAndPlaylist) == .moveHighlight(delta: -1))
    if case .moveHighlight(let delta) = effect("dpad_down", menuAndPlaylist) {
        playlistRows = playlistRows.moving(delta)
    }
    assert(playlistRows.index == 3)
    assert(PlayerHighlightList(count: 5, index: 0).moving(-1).index == 0)
    assert(PlayerHighlightList(count: 5, index: 4).moving(1).index == 4)

    assertTransport(PlayerChromeState(sessionMatches: true, controlsVisible: true), "visible chrome")
    assertTransport(PlayerChromeState(sessionMatches: true), "hidden chrome")
    assertTransport(PlayerChromeState(sessionMatches: true, controlsVisible: true), "paused row")
    let playing = PlayerChromeState(sessionMatches: true, controlsVisible: true)
    assert(effect("back", playing) == .dismissControls)
    assert(effect("home", playing) == .exitPlayback)
    let hidden = PlayerChromeState(sessionMatches: true)
    assert(effect("back", hidden) == .exitPlayback)
    assert(effect("home", hidden) == .exitPlayback)

    let prompt = PlayerChromeState(sessionMatches: true, stillWatching: true, controlsVisible: true)
    for key in ["dpad_up", "dpad_down", "dpad_center", "dpad_left", "dpad_right"] {
        assert(effect(key, prompt) == .resumeStillWatching, "\(key) resumes the still-watching prompt")
    }
    assert(effect("back", prompt) == .dismissStillWatching)
    assert(effect("home", prompt) == .exitPlayback)
    var promptRows = PlayerHighlightList(count: 1, index: 0)
    let promptBefore = promptRows
    if case .moveHighlight(let delta) = effect("dpad_down", prompt) { promptRows = promptRows.moving(delta) }
    assert(promptRows == promptBefore, "Still-watching Up/Down resume and leave the row alone")

    let scrub = PlayerChromeState(sessionMatches: true, scrubbing: true, controlsVisible: true)
    assert(effect("dpad_left", scrub) == .scrubBackward)
    assert(effect("dpad_right", scrub) == .scrubForward)
    assert(effect("dpad_center", scrub) == .commitScrub)
    assert(effect("dpad_up", scrub) == .adjustAppVolume(steps: 1))
    assert(effect("dpad_down", scrub) == .adjustAppVolume(steps: -1))
    assert(effect("volume_down", scrub) == .adjustAppVolume(steps: -1), "Scrub leaves Up and Down alone")
    assert(effect("back", scrub) == .dismissScrub)

    let sheet = PlayerChromeState(sessionMatches: true, settingsSheet: true, controlsVisible: true)
    for key in ["dpad_up", "dpad_down", "dpad_center", "dpad_left", "dpad_right"] {
        assert(effect(key, sheet) == .ignored, "\(key) stays out of a presented sheet")
    }
    assert(effect("volume_up", sheet) == .adjustAppVolume(steps: 1))
    assert(effect("back", sheet) == .dismissSettingsSheet)
    assert(effect("home", sheet) == .exitPlayback)

    var staleList = PlayerHighlightList(count: 4, index: 1)
    let staleMenu = PlayerChromeState(sessionMatches: false, playlist: true, controlsVisible: true, infoPanel: true)
    for key in ["dpad_up", "dpad_down", "dpad_center", "dpad_left", "dpad_right", "back", "home",
                "volume_up", "volume_down", "mute"] {
        let staleEffect = effect(key, staleMenu)
        assert(staleEffect == .ignored, "A stale \(key) is ignored")
        if case .moveHighlight(let delta) = staleEffect { staleList = staleList.moving(delta) }
    }
    assert(staleList.index == 1 && staleList.count == 4, "A stale key leaves the highlight where it is")

    assert(PlayerRemoteNavigation.menuRowIDs(trackIDs: [3, 5], includeOff: true) == [-1, 3, 5])
    assert(PlayerRemoteNavigation.menuRowIDs(trackIDs: [3, 5], includeOff: false) == [3, 5])
    let promptVolume = PlayerChromeState(sessionMatches: true, stillWatching: true)
    assert(effect("volume_up", promptVolume) == .ignored)
    assert(effect("volume_down", promptVolume) == .ignored)
    assert(effect("mute", promptVolume) == .ignored)
    print("PASS: Up/Down step app volume on playback, menus keep the highlight, and a stale session leaves the highlight")
}

private func jsonObject(at url: URL) -> [String: Any] {
    let data = try! Data(contentsOf: url)
    return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
}

private func canonicalJSON(_ object: [String: Any]) -> Data {
    try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
}

private func runPlayerSettingsTests() {
    let fixtureRoot = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    let future = jsonObject(at: fixtureRoot.appendingPathComponent("future-apple-complete-settings.json"))
    let legacy = jsonObject(at: fixtureRoot.appendingPathComponent("legacy-complete-settings.json"))
    let loopOnly = jsonObject(at: fixtureRoot.appendingPathComponent("invalid-loop-only-settings.json"))

    let avplayer = PlayerPlaybackReport.settings(engine: "avplayer", loop: false)
    assert(PlayerPlaybackReport.isValidSnapshot(avplayer))
    assert(PlayerPlaybackReport.isValidSnapshot(future))
    assert(PlayerPlaybackReport.isValidSnapshot(legacy))
    assert(!PlayerPlaybackReport.isValidSnapshot(loopOnly), "A loop-only frame is not a snapshot")
    assert(!PlayerPlaybackReport.isValidSnapshot([
        "type": "status", "state": "playing", "position": 0, "duration": 0, "loop": true,
    ]), "Loop does not belong on status")
    assert(canonicalJSON(avplayer) == canonicalJSON(future))
    assert(PlayerPlaybackReport.requiredFields.allSatisfy { avplayer[$0] != nil })
    for key in ["speedAvailable", "scalingAvailable", "audioBoostAvailable", "qualityAvailable", "subtitleOffsetAvailable"] {
        assert(avplayer[key] as? Bool == false, "\(key) stays false")
    }
    assert(avplayer["engineAvailable"] as? Bool == true)
    assert(avplayer["loopAvailable"] as? Bool == true)
    assert(avplayer["speed"] as? Int == 1)
    assert(avplayer["scaling"] as? String == "Fit")
    assert(avplayer["audioBoost"] as? Bool == false)
    assert(avplayer["subtitleOffsetMs"] as? Int == 0)
    assert(avplayer["state"] == nil)

    let looping = PlayerPlaybackReport.settings(engine: "mpv", loop: true)
    assert(PlayerPlaybackReport.isValidSnapshot(looping))
    assert(looping["engine"] as? String == "mpv")
    assert(looping["loop"] as? Bool == true)
    assert(PlayerPlaybackReport.requiredFields.allSatisfy { looping[$0] != nil })

    let playerContext = PlayerPlaybackReport.context(active: "player", engine: "mpv")
    assert(playerContext["type"] as? String == "context")
    assert(playerContext["active"] as? String == "player")
    assert(playerContext["engine"] as? String == "mpv")
    assert(playerContext["loop"] == nil)
    assert(PlayerPlaybackReport.context(active: "player", engine: nil)["engine"] == nil)
    assert(PlayerPlaybackReport.context(active: "player", engine: "")["engine"] == nil)
    let idle = PlayerPlaybackReport.context(active: "idle", engine: "mpv")
    assert(idle["active"] as? String == "idle")
    assert(idle["engine"] == nil)
    assert(Set(idle.keys) == ["type", "active"])

    let reporter = PlayerSettingsReporter(engine: "avplayer", generation: 4)
    let avplayerIdentity = PlayerReportIdentity(engine: "avplayer", generation: 4)
    let staleEngine = PlayerReportIdentity(engine: "mpv", generation: 4)
    let staleItem = PlayerReportIdentity(engine: "avplayer", generation: 3)
    assert(reporter.resync(identity: avplayerIdentity)!["loop"] as? Bool == false)
    assert(reporter.resync(identity: staleEngine) == nil)
    assert(reporter.resync(identity: staleItem) == nil)
    assert(reporter.setLoop(true, identity: staleEngine) == nil)
    assert(reporter.loop == false, "A stale engine cannot change loop")
    assert(reporter.endOfFile(identity: staleEngine).0 == .ignored)
    assert(reporter.endOfFile(identity: staleItem).1 == nil)

    let enabled = reporter.setLoop(true, identity: avplayerIdentity)!
    assert(enabled["type"] as? String == "player_settings")
    assert(enabled["loop"] as? Bool == true)
    assert(enabled["engine"] as? String == "avplayer")
    assert(PlayerPlaybackReport.isValidSnapshot(enabled))
    assert(reporter.endOfFile(identity: avplayerIdentity).0 == .repeatCurrentItem)

    let switched = reporter.switchEngine(from: avplayerIdentity, to: "mpv")!
    assert(switched["engine"] as? String == "mpv")
    assert(switched["loop"] as? Bool == true, "Engine switch keeps repeat-one")
    assert(PlayerPlaybackReport.isValidSnapshot(switched))
    assert(reporter.switchEngine(from: avplayerIdentity, to: "mpv") == nil)
    assert(reporter.setLoop(false, identity: avplayerIdentity) == nil)
    assert(reporter.loop == true)

    let mpvIdentity = PlayerReportIdentity(engine: "mpv", generation: 4)
    let resync = reporter.resync(identity: mpvIdentity)!
    assert(resync["engine"] as? String == "mpv")
    assert(resync["loop"] as? Bool == true)
    assert(reporter.resync(identity: PlayerReportIdentity(engine: "mpv", generation: 5)) == nil)

    reporter.note(engine: "mpv", generation: 9)
    assert(reporter.loop == true, "The next item keeps repeat-one")
    assert(reporter.resync(identity: mpvIdentity) == nil, "The previous item cannot report")
    let nextItem = PlayerReportIdentity(engine: "mpv", generation: 9)
    assert(reporter.resync(identity: nextItem)!["loop"] as? Bool == true)
    let repeated = reporter.endOfFile(identity: nextItem)
    assert(repeated.0 == .repeatCurrentItem)
    assert(repeated.1?["loop"] as? Bool == true)
    assert(reporter.setLoop(false, identity: nextItem) != nil)

    func outcome(loopOn: Bool, hasNextItem: Bool, identity: PlayerReportIdentity) -> String {
        let decision = loopOn
            ? PlayerEndDecision.repeatCurrentItem
            : PlayerEndDecision.advanceOrLeave
        let actual = reporter.endOfFile(identity: identity).0
        guard actual == decision || actual == .ignored else {
            return "unexpected"
        }
        switch actual {
        case .repeatCurrentItem: return "repeat"
        case .advanceOrLeave: return hasNextItem ? "advance" : "leave"
        case .ignored: return "ignored"
        }
    }
    assert(outcome(loopOn: false, hasNextItem: true, identity: nextItem) == "advance")
    assert(outcome(loopOn: false, hasNextItem: false, identity: nextItem) == "leave")
    assert(reporter.setLoop(true, identity: nextItem) != nil)
    assert(outcome(loopOn: true, hasNextItem: true, identity: nextItem) == "repeat")
    assert(outcome(loopOn: true, hasNextItem: false, identity: nextItem) == "repeat")
    assert(outcome(loopOn: true, hasNextItem: true, identity: mpvIdentity) == "ignored")

    print("PASS: player settings snapshot, loop, engine switch, resync, and stale engine")

    // ReceiverCapabilities descriptor tests
    let descriptor = TvCapabilities.currentDescriptor
    assert(descriptor["version"] as? Int == 1, "Descriptor version must be 1")

    // 1. Host
    let host = descriptor["host"] as? [String: Any]
    assert(host?["osFamily"] as? String == "tvos", "Host osFamily must be tvos")
    assert(host?["deviceClass"] as? String == "tv", "Host deviceClass must be tv")

    // 2. App volume and CompanionHints
    guard let volume = descriptor["volume"] as? [String: Any] else {
        fatalError("descriptor.volume missing")
    }
    assert(volume["scope"] as? String == "app")
    assert(volume["operations"] as? [String] == [
        "relative_step", "absolute", "mute_toggle", "level_report", "mute_report",
    ])
    assert(volume["step"] as? Double == 0.05)
    assert(volume["fallbackScope"] == nil, "App volume has no fallback scope")
    assert(Set(volume.keys) == ["scope", "operations", "step"])
    assert(descriptor["companionHints"] == nil, "Companion hints must be omitted for Apple TV")

    // 3. Surfaces & Engines
    guard let surfaces = descriptor["surfaces"] as? [String: Any] else {
        fatalError("descriptor.surfaces missing")
    }
    assert(Set(surfaces.keys) == ["player"], "Only player surface must be declared")
    guard let playerSurface = surfaces["player"] as? [String: Any],
          let engines = playerSurface["engines"] as? [String: Any] else {
        fatalError("player.engines missing")
    }
    assert(Set(engines.keys) == ["avplayer", "mpv"], "Must advertise both avplayer and mpv engines")

    let expectedTransport = ["play", "pause", "toggle", "stop", "seek_absolute", "seek_relative"]
    let expectedKeys = ["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home"]
    let expectedSettings = ["avplayer": ["loop", "engine", "speed", "scaling", "subtitle_offset"],
                            "mpv": ["loop", "engine", "speed", "scaling", "subtitle_offset"]]
    let expectedTracks = ["audio_select", "subtitle_select", "subtitle_url_add", "subtitle_resource_add"]
    let expectedQueue = ["replace", "append", "jump_index", "jump_item", "remove", "move", "clear"]
    let expectedReporting = ["status", "context", "tracks", "playlist_status", "player_settings", "timeline", "buffered"]

    for engineName in ["avplayer", "mpv"] {
        guard let engineCaps = engines[engineName] as? [String: Any] else {
            assertionFailure("Missing capabilities for engine \(engineName)")
            continue
        }
        assert(engineCaps["transport"] as? [String] == expectedTransport, "Transport operations match")
        assert(engineCaps["keys"] as? [String] == expectedKeys, "Key operations match")
        assert(engineCaps["settings"] as? [String] == expectedSettings[engineName], "Settings operations match")
        assert(engineCaps["tracks"] as? [String] == expectedTracks, "Track operations match")
        assert(engineCaps["queue"] as? [String] == expectedQueue, "Queue operations match")
        assert(engineCaps["reporting"] as? [String] == expectedReporting, "Reporting operations match")

        // Verified non-presence of unhandled operation categories
        assert(engineCaps["pointer"] == nil, "Apple TV player must not declare pointer")
        assert(engineCaps["text"] == nil, "Apple TV player must not declare text")
        assert(engineCaps["imageTransforms"] == nil, "Apple TV player must not declare imageTransforms")
        assert(engineCaps["browser"] == nil, "Apple TV player must not declare browser")
    }

    // 4. Auth response and credential bundle carry identical descriptor
    let certFp = "sha256/test_fingerprint_hash"
    let token = "test_token_sample"
    let authResponse = TvCapabilities.buildAuthResponsePayload(certFingerprint: certFp)
    let credentialBundle = TvCapabilities.buildCredentialBundlePayload(token: token, certFingerprint: certFp)

    let authCaps = authResponse["capabilities"] as? [String: Any]
    let bundleCaps = credentialBundle["capabilities"] as? [String: Any]
    assert(authCaps != nil, "Auth response contains capabilities")
    assert(bundleCaps != nil, "Credential bundle contains capabilities")

    let authCapsData = try! JSONSerialization.data(withJSONObject: authCaps!, options: [.sortedKeys])
    let bundleCapsData = try! JSONSerialization.data(withJSONObject: bundleCaps!, options: [.sortedKeys])
    assert(authCapsData == bundleCapsData, "Auth response and credential bundle must carry identical descriptor")
    let authVolume = authCaps!["volume"] as? [String: Any]
    let bundleVolume = bundleCaps!["volume"] as? [String: Any]
    assert(authVolume != nil && bundleVolume != nil, "Auth and bundle both carry app volume")
    let authVolumeData = try! JSONSerialization.data(withJSONObject: authVolume!, options: [.sortedKeys])
    let bundleVolumeData = try! JSONSerialization.data(withJSONObject: bundleVolume!, options: [.sortedKeys])
    assert(authVolumeData == bundleVolumeData, "Auth and bundle app volume blocks match")
    assert(authVolume!["scope"] as? String == "app")
    assert(authVolume!["fallbackScope"] == nil)

    // 5. Legacy fields unchanged
    assert(authResponse["type"] as? String == "auth_response")
    assert(authResponse["success"] as? Bool == true)
    assert(authResponse["certFingerprint"] as? String == certFp)
    assert(authResponse["players"] as? [String] == ["avplayer", "mpv"])
    assert(authResponse["features"] as? [String] == TvCapabilities.defaultFeatures)
    assert(authResponse["browsers"] == nil, "Browsers must not be added to Apple TV auth")
    assert(authResponse["mediaKinds"] == nil, "mediaKinds must not be added to Apple TV auth")
    assert(authResponse["screenMirrorWebRtc"] == nil, "screenMirrorWebRtc must not be added to Apple TV auth")

    assert(credentialBundle["token"] as? String == token)
    assert(credentialBundle["certFingerprint"] as? String == certFp)
    assert(credentialBundle["players"] as? [String] == ["avplayer", "mpv"])
    assert(credentialBundle["features"] as? [String] == TvCapabilities.defaultFeatures)
    assert(credentialBundle["browsers"] == nil)
    assert(credentialBundle["mediaKinds"] == nil)
    assert(credentialBundle["screenMirrorWebRtc"] == nil)

    // 6. Byte identity of existing fields when capabilities is stripped
    let legacyAuth = TvCapabilities.buildAuthResponsePayload(certFingerprint: certFp, capabilities: nil)
    var strippedAuth = authResponse
    strippedAuth.removeValue(forKey: "capabilities")
    let legacyAuthData = try! JSONSerialization.data(withJSONObject: legacyAuth, options: [.sortedKeys])
    let strippedAuthData = try! JSONSerialization.data(withJSONObject: strippedAuth, options: [.sortedKeys])
    assert(legacyAuthData == strippedAuthData, "Auth response existing fields are identical when capabilities stripped")

    let legacyBundle = TvCapabilities.buildCredentialBundlePayload(token: token, certFingerprint: certFp, capabilities: nil)
    var strippedBundle = credentialBundle
    strippedBundle.removeValue(forKey: "capabilities")
    let legacyBundleData = try! JSONSerialization.data(withJSONObject: legacyBundle, options: [.sortedKeys])
    let strippedBundleData = try! JSONSerialization.data(withJSONObject: strippedBundle, options: [.sortedKeys])
    assert(legacyBundleData == strippedBundleData, "Credential bundle existing fields are identical when capabilities stripped")

    // Also test without certFingerprint (optional field)
    let authNoFp = TvCapabilities.buildAuthResponsePayload(certFingerprint: nil)
    assert(authNoFp["certFingerprint"] == nil)
    let bundleNoFp = TvCapabilities.buildCredentialBundlePayload(token: token, certFingerprint: nil)
    assert(bundleNoFp["certFingerprint"] == nil)

    print("PASS: receiver capabilities descriptor shape, both engines, identical auth/bundle descriptor, and legacy field preservation")
}

private func runAppVolumeTests() {
    assert(PlayerAppVolume.step == 0.05)
    assert(PlayerAppVolume.scope == "app")
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: 0.35) == 0.35)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: 0) == 0)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: 1) == 1)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: 1.0) == 1)
    assert(PlayerAppVolume.absoluteLevel(in: ["scope": "app", "level": NSNumber(value: 0.35)]) == 0.35)
    assert(PlayerAppVolume.absoluteLevel(scope: "system", level: 0.35) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "hdmi-cec", level: 0.2) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: 35) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: -0.01) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: 1.01) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: Double.nan) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: Double.infinity) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: true) == nil)
    assert(PlayerAppVolume.absoluteLevel(scope: "app", level: NSNumber(value: true)) == nil)
    let trueLevel = try! JSONSerialization.jsonObject(with: Data(#"{"scope":"app","level":true}"#.utf8)) as! [String: Any]
    assert(PlayerAppVolume.absoluteLevel(in: trueLevel) == nil, "A JSON boolean is not a level")
    let intLevel = try! JSONSerialization.jsonObject(with: Data(#"{"scope":"app","level":1}"#.utf8)) as! [String: Any]
    assert(PlayerAppVolume.absoluteLevel(in: intLevel) == 1)
    assert(PlayerAppVolume.absoluteLevel(scope: nil, level: 0.5) == nil)
    assert(PlayerAppVolume.absoluteLevel(in: ["level": 0.5]) == nil)

    assert(PlayerAppVolume.mpvVolumeString(level: 1) == "100.000")
    assert(PlayerAppVolume.mpvVolumeString(level: 0) == "0.000")
    assert(PlayerAppVolume.mpvVolumeString(level: 0.35) == "35.000")
    assert(PlayerAppVolume.avPlayerVolume(0) == 0)
    assert(PlayerAppVolume.avPlayerVolume(1) == 1)
    assert(abs(PlayerAppVolume.avPlayerVolume(0.35) - 0.042875) < 0.000001)
    assert(PlayerAppVolume.avPlayerVolume(0.5) == 0.125)
    assert(PlayerAppVolume.avPlayerVolume(-1) == 0)
    assert(PlayerAppVolume.avPlayerVolume(2) == 1)
    assert(PlayerAppVolume.indicatorText(level: 0.35, muted: false) == "App volume 35%")
    assert(PlayerAppVolume.indicatorText(level: 1, muted: false) == "App volume 100%")
    assert(PlayerAppVolume.indicatorText(level: 0, muted: false) == "App volume 0%")
    assert(PlayerAppVolume.indicatorText(level: 0.35, muted: true) == "App volume muted")

    let volume = PlayerAppVolumeController(engine: "avplayer", generation: 4)
    let avplayer = PlayerReportIdentity(engine: "avplayer", generation: 4)
    let staleEngine = PlayerReportIdentity(engine: "mpv", generation: 4)
    let staleItem = PlayerReportIdentity(engine: "avplayer", generation: 3)
    assert(volume.level == 1)
    assert(volume.muted == false)
    assert(volume.step(-1, identity: staleEngine) == nil)
    assert(volume.step(-1, identity: staleItem) == nil)
    assert(volume.setAbsolute(0.2, identity: staleEngine) == nil)
    assert(volume.toggleMute(identity: staleItem) == nil)
    assert(volume.resync(identity: staleEngine) == nil)
    assert(volume.level == 1 && volume.muted == false, "A stale engine cannot change app volume")

    var written: [String] = []
    for _ in 0..<3 {
        let frame = volume.step(-1, identity: avplayer)!
        assert(frame["type"] as? String == "volume_state")
        assert(frame["scope"] as? String == "app")
        assert(frame["muted"] as? Bool == false)
        assert(PlayerAppVolume.isValidVolumeState(frame))
        written.append(PlayerAppVolume.mpvVolumeString(level: volume.level))
    }
    assert(written == ["95.000", "90.000", "85.000"], "Rapid steps publish absolute mpv volumes in order")
    assert(volume.level == 0.85)
    let up = volume.step(1, identity: avplayer)!
    assert(up["level"] as? Double == 0.9)
    assert(up["muted"] as? Bool == false)

    let muted = volume.toggleMute(identity: avplayer)!
    assert(muted["muted"] as? Bool == true)
    assert(muted["level"] as? Double == 0.9, "Mute keeps the stored level")
    assert(PlayerAppVolume.indicatorText(level: volume.level, muted: volume.muted) == "App volume muted")
    let whileMuted = volume.step(-1, identity: avplayer)!
    assert(whileMuted["level"] as? Double == 0.85)
    assert(whileMuted["muted"] as? Bool == true, "A level change keeps mute")
    let absolute = volume.setAbsolute(0.35, identity: avplayer)!
    assert(absolute["level"] as? Double == 0.35)
    assert(absolute["muted"] as? Bool == true, "An absolute level keeps mute")
    assert(volume.setAbsolute(0.35, identity: avplayer) == nil, "The same level is not a change")
    assert(volume.setAbsolute(1.5, identity: avplayer) == nil)
    assert(volume.setAbsolute(Double.nan, identity: avplayer) == nil)
    assert(volume.level == 0.35 && volume.muted)
    let unmuted = volume.toggleMute(identity: avplayer)!
    assert(unmuted["muted"] as? Bool == false)
    assert(unmuted["level"] as? Double == 0.35, "Unmute restores the stored level")
    assert(PlayerAppVolume.indicatorText(level: volume.level, muted: volume.muted) == "App volume 35%")

    volume.note(engine: "mpv", generation: 4)
    assert(volume.level == 0.35 && volume.muted == false, "An engine switch keeps app volume")
    assert(volume.step(1, identity: avplayer) == nil)
    assert(volume.level == 0.35)
    let mpv = PlayerReportIdentity(engine: "mpv", generation: 4)
    let switched = volume.resync(identity: mpv)!
    assert(switched["scope"] as? String == "app")
    assert(switched["level"] as? Double == 0.35)
    assert(switched["muted"] as? Bool == false)
    assert(PlayerAppVolume.isValidVolumeState(switched))
    assert(!PlayerAppVolume.isValidVolumeState([
        "type": "volume_state", "scope": "app", "level": 0.35, "muted": false, "playbackId": "x",
    ]), "volume_state does not carry extra fields")
    assert(!PlayerAppVolume.isValidVolumeState(["type": "volume_state", "scope": "system", "level": 0.35]))
    assert(!PlayerAppVolume.isValidVolumeState(["type": "volume_state", "scope": "app"]))

    volume.note(engine: "mpv", generation: 9)
    assert(volume.level == 0.35 && volume.muted == false, "The next item keeps app volume")
    assert(volume.resync(identity: mpv) == nil, "The previous item cannot report volume")
    assert(volume.setAbsolute(0.5, identity: mpv) == nil)
    let next = PlayerReportIdentity(engine: "mpv", generation: 9)
    let nextFrame = volume.resync(identity: next)!
    assert(nextFrame["level"] as? Double == 0.35)
    assert(nextFrame["engine"] == nil)

    let topped = PlayerAppVolumeController(engine: "avplayer", generation: 1, level: 0.98)
    let topID = PlayerReportIdentity(engine: "avplayer", generation: 1)
    let raised = topped.step(1, identity: topID)!
    assert(raised["level"] as? Double == 1)
    assert(topped.step(1, identity: topID) == nil)
    assert(topped.muted == false)
    let floored = PlayerAppVolumeController(engine: "avplayer", generation: 1, level: 0.02)
    let floorID = PlayerReportIdentity(engine: "avplayer", generation: 1)
    let lowered = floored.step(-1, identity: floorID)!
    assert(lowered["level"] as? Double == 0)
    assert(lowered["muted"] as? Bool == false, "Level 0 is not mute")
    assert(floored.step(-1, identity: floorID) == nil)
    assert(floored.toggleMute(identity: floorID)!["level"] as? Double == 0)
    assert(floored.muted)
    assert(floored.toggleMute(identity: floorID)!["level"] as? Double == 0)

    let fresh = PlayerAppVolumeController(engine: "mpv", generation: 0)
    assert(fresh.level == 1 && fresh.muted == false, "A new session starts unattenuated and unmuted")

    print("PASS: app volume step, absolute, mute, clamp, engine switch, and stale volume_state")
}

private func runPlayerTuningTests() {
    assert(PlayerTuning.parse("speed:1.25") == .speed(1.25))
    assert(PlayerTuning.parse("speed:1.0") == .speed(1))
    assert(PlayerTuning.parse("speed:1") == .speed(1))
    assert(PlayerTuning.parse("sub_offset:-100") == .subtitleOffsetDelta(-100))
    assert(PlayerTuning.parse("sub_offset:+100") == .subtitleOffsetDelta(100))
    assert(PlayerTuning.parse("scaling:Zoom") == .scaling(.zoom))
    for invalid in ["speed:nan", "speed:inf", "speed:-inf", "speed:1e999", "speed:abc", "speed:",
                    "sub_offset:1.5", "sub_offset:9223372036854775808", "sub_offset:",
                    "scaling:zoom", "scaling:16:9", "scaling:", "audio_boost", "video_quality:auto"] {
        assert(PlayerTuning.parse(invalid) == nil, "Unsupported or malformed tuning must be ignored")
    }
    var tuning = PlayerTuning()
    assert(!tuning.apply(.speed(.nan)) && !tuning.apply(.speed(.infinity)))
    assert(!tuning.apply(.speed(1)))
    assert(tuning.apply(PlayerTuning.parse("speed:9")!) && tuning.speed == 2)
    assert(tuning.apply(PlayerTuning.parse("speed:0.1")!) && tuning.speed == 0.25)
    assert(!tuning.apply(.speed(-1)) && tuning.speed == 0.25)
    assert(tuning.apply(.subtitleOffsetDelta(100)))
    assert(tuning.apply(.subtitleOffsetDelta(500)) && tuning.subtitleOffsetMs == 600)
    assert(tuning.apply(.subtitleOffsetDelta(-100)) && tuning.subtitleOffsetMs == 500)
    assert(tuning.apply(.subtitleOffsetDelta(-tuning.subtitleOffsetMs)) && tuning.subtitleOffsetMs == 0)
    assert(!tuning.apply(.subtitleOffsetDelta(0)))
    assert(tuning.apply(.subtitleOffsetDelta(100)))
    assert(tuning.apply(PlayerTuning.parse("sub_offset:9223372036854775807")!) && tuning.subtitleOffsetMs == 120_000)
    assert(!tuning.apply(.subtitleOffsetDelta(Int.max)))
    assert(tuning.apply(.subtitleOffsetDelta(-240_000)) && tuning.subtitleOffsetMs == -120_000)
    assert(!tuning.apply(.subtitleOffsetDelta(Int.min)))
    assert(tuning.apply(.subtitleOffsetDelta(-tuning.subtitleOffsetMs)) && tuning.subtitleOffsetMs == 0)
    assert(tuning.apply(.scaling(.fill)) && !tuning.apply(.scaling(.fill)))
    print("PASS: tuning command parsing, speed bounds, accumulated signed subtitle deltas, overflow saturation and Reset")

    assert(PlayerTuning.mpvNumber(1.25) == "1.25")
    assert(PlayerTuning.mpvSubDelay(ms: 250) == "0.25")
    assert(PlayerTuning.mpvSubDelay(ms: -500) == "-0.5")
    for (mode, aspect, panscan) in [(PlayerScaling.fit, "yes", "0"), (.zoom, "yes", "1.0"), (.fill, "no", "0")] {
        let properties = Dictionary(uniqueKeysWithValues: PlayerTuning.mpvScalingProperties(mode))
        assert(properties == ["video-unscaled": "no", "keepaspect": aspect, "panscan": panscan])
    }
    assert(PlayerTuning.overlayCueTime(position: 10, offsetMs: 500) == 9.5)
    assert(PlayerTuning.overlayCueTime(position: 10, offsetMs: -500) == 10.5)
    assert(PlayerTuning.overlayCueTime(position: .nan, offsetMs: 500).isNaN)
    let cues = ExternalSubtitleCues(data: Data("1\n00:00:01,000 --> 00:00:02,000\nHello\n".utf8))!
    assert(cues.text(at: PlayerTuning.overlayCueTime(position: 1.1, offsetMs: 500)) == nil)
    assert(cues.text(at: PlayerTuning.overlayCueTime(position: 2.1, offsetMs: 500)) == "Hello")
    assert(cues.text(at: PlayerTuning.overlayCueTime(position: 0.6, offsetMs: -500)) == "Hello")
    print("PASS: mpv number/scaling mappings and positive-later external subtitle cue timing")

    let fixtures = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    let reporter = PlayerSettingsReporter(engine: "mpv", generation: 4)
    let mpv = PlayerReportIdentity(engine: "mpv", generation: 4)
    func checked(_ frame: [String: Any]?) -> [String: Any] {
        guard let frame else { fatalError("Expected a changed settings snapshot") }
        assert(PlayerPlaybackReport.isValidSnapshot(frame))
        return frame
    }
    _ = checked(reporter.apply(.speed(1.5), identity: mpv))
    _ = checked(reporter.apply(.scaling(.zoom), identity: mpv))
    let mpvFrame = checked(reporter.apply(.subtitleOffsetDelta(250), identity: mpv))
    assert(canonicalJSON(mpvFrame) == canonicalJSON(jsonObject(at: fixtures.appendingPathComponent("apple-tv-player-settings-mpv.json"))))
    assert(reporter.apply(.speed(1.5), identity: mpv) == nil)
    let retained = reporter.tuning
    let stale = PlayerReportIdentity(engine: "mpv", generation: 3)
    assert(reporter.apply(.subtitleOffsetDelta(100), identity: stale) == nil && reporter.tuning == retained)
    assert(reporter.setSidecarOverlay(true, identity: stale) == nil && !reporter.sidecarOverlayActive)
    _ = checked(reporter.switchEngine(from: mpv, to: "avplayer"))
    let avplayer = PlayerReportIdentity(engine: "avplayer", generation: 4)
    assert(reporter.tuning == retained && !reporter.sidecarOverlayActive)
    assert(reporter.apply(.subtitleOffsetDelta(100), identity: avplayer) == nil && reporter.tuning == retained)
    assert(reporter.apply(.speed(2), identity: mpv) == nil)
    _ = checked(reporter.apply(.speed(1.25), identity: avplayer))
    let avFrame = checked(reporter.apply(.scaling(.fill), identity: avplayer))
    assert(canonicalJSON(avFrame) == canonicalJSON(jsonObject(at: fixtures.appendingPathComponent("apple-tv-player-settings-avplayer.json"))))
    let active = checked(reporter.setSidecarOverlay(true, identity: avplayer))
    assert(active["subtitleOffsetAvailable"] as? Bool == true && active["subtitleOffsetMs"] as? Int == 250)
    assert(reporter.setSidecarOverlay(true, identity: avplayer) == nil)
    reporter.note(engine: "avplayer", generation: 4) // body renders must not withdraw overlay availability
    assert(reporter.sidecarOverlayActive)
    _ = checked(reporter.apply(.subtitleOffsetDelta(-100), identity: avplayer))
    let inactive = checked(reporter.setSidecarOverlay(false, identity: avplayer))
    assert(inactive["subtitleOffsetAvailable"] as? Bool == false && inactive["subtitleOffsetMs"] as? Int == 0)
    assert(reporter.tuning.subtitleOffsetMs == 150)
    _ = checked(reporter.setSidecarOverlay(true, identity: avplayer))
    let nextTuning = reporter.tuning
    reporter.note(engine: "avplayer", generation: 5)
    assert(!reporter.sidecarOverlayActive && reporter.tuning == nextTuning)
    assert(reporter.setSidecarOverlay(true, identity: avplayer) == nil)
    let nextItem = PlayerReportIdentity(engine: "avplayer", generation: 5)
    let nextFrame = checked(reporter.resync(identity: nextItem))
    assert(nextFrame["speed"] as? Double == 1.25 && nextFrame["scaling"] as? String == "Fill")
    _ = checked(reporter.setSidecarOverlay(true, identity: nextItem))
    _ = checked(reporter.switchEngine(from: nextItem, to: "mpv"))
    assert(!reporter.sidecarOverlayActive && reporter.tuning == nextTuning)
    let newSession = PlayerSettingsReporter(engine: "mpv", generation: 0)
    assert(newSession.tuning == PlayerTuning())
    assert(PlayerSettingsAvailability.forEngine("unknown", sidecarOverlayActive: true) == .none)
    let unknown = PlayerSettingsReporter(engine: "unknown", generation: 0)
    assert(unknown.apply(.speed(1.5), identity: PlayerReportIdentity(engine: "unknown", generation: 0)) == nil)
    print("PASS: tuning snapshots match protocol fixtures, engine/overlay availability, session carry/reset, resync and stale-result fencing")
}

private func runTimelineReportTests() {
    // mpv chapter-list: seconds to ms, placeholder titles dropped, malformed entries skipped.
    let parsed = TimelineReport.parseMpvChapterList(
        #"[{"title":"Opening","time":0},{"title":"(unnamed)","time":312.5},{"time":"12"},{"title":"x"},{"time":true},{"time":600}]"#)
    assert(parsed == [TimelineChapter(startMs: 0, title: "Opening"), TimelineChapter(startMs: 312_500),
                      TimelineChapter(startMs: 600_000)])
    assert(TimelineReport.parseMpvChapterList("not json").isEmpty)

    // The frame sorts, trims and bounds chapters, and never invents segments.
    let long = String(repeating: "x", count: TimelineReport.maxTitleLength + 20)
    let frame = TimelineReport.frame(chapters: [TimelineChapter(startMs: 600_000, title: "  The Heist  "),
        TimelineChapter(startMs: 0, title: long), TimelineChapter(startMs: -5), TimelineChapter(startMs: 1_200_000, title: " ")])
    assert(frame["type"] as? String == "timeline")
    let chapters = frame["chapters"] as! [[String: Any]]
    assert(chapters.map { $0["startMs"] as! Int } == [0, 600_000, 1_200_000])
    assert((chapters[0]["title"] as! String).count == TimelineReport.maxTitleLength)
    assert(chapters[1]["title"] as? String == "The Heist" && chapters[2]["title"] == nil)
    assert((frame["segments"] as! [Any]).isEmpty)
    let many = TimelineReport.frame(chapters: (0..<300).map { TimelineChapter(startMs: $0 * 1000) })
    assert((many["chapters"] as! [Any]).count == TimelineReport.maxChapters)
    assert(JSONSerialization.isValidJSONObject(frame))

    // Buffered stays between position and duration and is absent when unknown or live.
    assert(TimelineReport.buffered(bufferedSeconds: 40, positionSeconds: 10, durationSeconds: 100) == 40_000)
    assert(TimelineReport.buffered(bufferedSeconds: 2, positionSeconds: 10, durationSeconds: 100) == 10_000)
    assert(TimelineReport.buffered(bufferedSeconds: 150, positionSeconds: 10, durationSeconds: 100) == 100_000)
    assert(TimelineReport.buffered(bufferedSeconds: 40, positionSeconds: 10, durationSeconds: 0) == nil)
    assert(TimelineReport.buffered(bufferedSeconds: 0, positionSeconds: 10, durationSeconds: 100) == nil)

    print("PASS: timeline chapters from mpv, bounded frame without segments, and buffered clamping")
}


private func runSkipSegmentTests() {
    func data(_ json: String) -> Data { Data(json.utf8) }
    let episode = SkipSegmentMetadata(imdbID: " tt0903747 ", tmdbID: "1396", season: 1, episode: 2)
    let movie = SkipSegmentMetadata(imdbID: "tt0137523", tmdbID: "550")
    func query(_ url: URL?) -> [String: String] {
        Dictionary(uniqueKeysWithValues: URLComponents(url: url!, resolvingAgainstBaseURL: false)!
            .queryItems!.map { ($0.name, $0.value!) })
    }
    assert(SkipSegmentFetcher.introDBURL(movie) == nil)
    assert(query(SkipSegmentFetcher.introDBURL(episode)) == ["imdb_id": "tt0903747", "season": "1", "episode": "2"])
    assert(query(SkipSegmentFetcher.theIntroDBURL(movie)) == ["tmdb_id": "550"])
    var coordinates = episode
    coordinates.tmdbID = nil
    assert(query(SkipSegmentFetcher.theIntroDBURL(coordinates)) == ["imdb_id": "tt0903747", "season": "1", "episode": "2"])
    coordinates.episode = nil
    assert(SkipSegmentFetcher.theIntroDBURL(coordinates) == nil)
    coordinates.season = nil
    coordinates.episode = 1
    assert(SkipSegmentFetcher.theIntroDBURL(coordinates) == nil)
    assert(SkipSegmentFetcher.theIntroDBURL(SkipSegmentMetadata()) == nil)
    assert(SkipSegmentFetcher.theIntroDBURL(movie, baseURL: "not a URL") == nil)
    assert(SkipSegmentFetcher.theIntroDBURL(movie, baseURL: "file:///tmp") == nil)
    assert(SkipSegmentFetcher.theIntroDBURL(movie, baseURL: " ")!.absoluteString == "https://api.theintrodb.org/v4/media?tmdb_id=550")
    for suffix in ["", "/", "/v1", "/v2/", "/v3", "/v3///", "/v4/"] {
        let url = SkipSegmentFetcher.theIntroDBURL(movie, baseURL: " https://example.test/proxy\(suffix) ")!
        assert(url.path == "/proxy/v4/media")
    }
    let custom = SkipSegmentFetcher.theIntroDBURL(movie,
        baseURL: "https://example.test/proxy%2Froute/v3/?key=a%26b&tvdb_id=123&season=8&episode=9#fragment")!
    assert(URLComponents(url: custom, resolvingAgainstBaseURL: false)!.percentEncodedPath == "/proxy%2Froute/v4/media")
    assert(query(custom) == ["key": "a&b", "tmdb_id": "550"] && custom.fragment == nil)
    let injected = SkipSegmentMetadata(imdbID: "tt1&mapping=dvd", season: 0, episode: 0)
    assert(query(SkipSegmentFetcher.theIntroDBURL(injected)) == ["imdb_id": "tt1&mapping=dvd", "season": "0", "episode": "0"])
    assert(query(SkipSegmentFetcher.introDBURL(injected))["imdb_id"] == "tt1&mapping=dvd")
    print("PASS: skip provider movie/episode URLs, ID preference, query encoding and partial-coordinate guards")

    let intro = SkipSegmentFetcher.parseIntroDB(data(#"{"intro":{"start_ms":1000,"end_ms":23000},"recap":{"start_sec":0.5,"end_sec":2.75},"outro":{"start_ms":-2,"end_ms":300}}"#))
    assert(intro == [SkipSegment(type: "intro", startMs: 1000, endMs: 23000),
                     SkipSegment(type: "recap", startMs: 500, endMs: 2750)])
    assert(SkipSegmentFetcher.parseIntroDB(data(#"{"intro":{"start_ms":0,"end_ms":null},"recap":{"start_ms":2,"end_ms":2},"outro":{"start_sec":true,"end_sec":3}}"#)).isEmpty)
    let secondary = SkipSegmentFetcher.parseTheIntroDB(data(#"{"tvdb_id":81189,"type":"tv","intro":[{"start_ms":null,"end_ms":23000},{"start_ms":30000,"end_ms":90000}],"recap":[{"start_ms":null,"end_ms":12000}],"credits":[{"start_ms":5801777,"end_ms":6371111},{"start_ms":6408000,"end_ms":null}],"preview":[{"start_ms":1680000,"end_ms":1740000}]}"#))
    assert(secondary == [SkipSegment(type: "intro", startMs: 0, endMs: 23000),
                         SkipSegment(type: "intro", startMs: 30000, endMs: 90000),
                         SkipSegment(type: "recap", startMs: 0, endMs: 12000),
                         SkipSegment(type: "outro", startMs: 5801777, endMs: 6371111),
                         SkipSegment(type: "outro", startMs: 6408000, endMs: nil),
                         SkipSegment(type: "preview", startMs: 1680000, endMs: 1740000)])
    assert(SkipSegmentFetcher.parseTheIntroDB(data(#"{"intro":[{"start_ms":null,"end_ms":0},{"start_ms":0},{"end_ms":2},{"start_ms":-1,"end_ms":2},{"start_ms":2,"end_ms":1},{"start_ms":true,"end_ms":5},{"start_ms":0,"end_ms":"bad"},{"start_ms":1e30,"end_ms":null}],"outro":{},"credits":[{"start_ms":null,"end_ms":5},{"start_ms":90000,"end_ms":null}]}"#)) == [SkipSegment(type: "outro", startMs: 90000, endMs: nil)])
    // Android gives an existing outro array precedence over the credits alias, even if empty.
    assert(SkipSegmentFetcher.parseTheIntroDB(data(#"{"outro":[],"credits":[{"start_ms":9,"end_ms":null}]}"#)).isEmpty)
    for malformed in ["not json", "[]", "null", #"{"episodes":[]}"#] {
        assert(SkipSegmentFetcher.parseIntroDB(data(malformed)).isEmpty)
        assert(SkipSegmentFetcher.parseTheIntroDB(data(malformed)).isEmpty)
    }
    let merged = SkipSegmentFetcher.merge(intro, secondary)
    assert(merged.filter { $0.type == "intro" } == [intro[0]])
    assert(merged.filter { $0.type == "recap" } == [intro[1]])
    assert(merged.map(\.startMs) == merged.map(\.startMs).sorted())
    assert(merged.contains { $0.type == "outro" && $0.endMs == nil })
    print("PASS: IntroDB milliseconds/seconds, TheIntroDB arrays/nulls/credits, malformed ranges and provider precedence")

    let invalid = [SkipSegment(type: "unknown", startMs: 0, endMs: 100),
                   SkipSegment(type: "intro", startMs: -1, endMs: 100),
                   SkipSegment(type: "intro", startMs: 20, endMs: 20),
                   SkipSegment(type: "intro", startMs: 20, endMs: 10)]
    let many = (0..<30).reversed().map { SkipSegment(type: "intro", startMs: $0 * 1000, endMs: $0 * 1000 + 500) }
    let bounded = TimelineReport.frame(chapters: [], segments: invalid + many)["segments"] as! [[String: Any]]
    assert(bounded.count == TimelineReport.maxSegments)
    assert(bounded.map { $0["startMs"] as! Int } == (0..<16).map { $0 * 1000 })
    let open = SkipSegment(type: "outro", startMs: 90_000, endMs: nil)
    let frame = TimelineReport.frame(chapters: [], segments: [open])
    assert((frame["segments"] as! [[String: Any]])[0]["endMs"] is NSNull)
    assert(JSONSerialization.isValidJSONObject(frame))
    assert(open.buttonTitle == "Skip Credits" && intro[0].buttonTitle == "Skip Intro")
    assert(!open.contains(seconds: 89) && open.contains(seconds: 90) && open.contains(seconds: 100))
    assert(!open.contains(seconds: .nan) && !open.contains(seconds: .infinity))
    assert(open.targetSeconds(duration: 100) == 100)
    assert(open.targetSeconds(duration: 0) == nil && open.targetSeconds(duration: .nan) == nil)
    assert(intro[0].contains(seconds: 1) && !intro[0].contains(seconds: 23))
    assert(intro[0].targetSeconds(duration: 20) == 20 && intro[0].targetSeconds(duration: 0) == 23)
    // Open-ended or within a second of the end advances to the next item (Android TV parity).
    assert(open.endsPlayback(duration: 100) && open.endsPlayback(duration: 0))
    let nearEnd = SkipSegment(type: "outro", startMs: 90_000, endMs: 99_500)
    assert(nearEnd.endsPlayback(duration: 100) && !nearEnd.endsPlayback(duration: 120))
    assert(!intro[0].endsPlayback(duration: 1_000) && !nearEnd.endsPlayback(duration: 0))
    print("PASS: timeline segment types, sorting, max 16, null ends, containment, duration-clamped skip targets and end-of-item skips")

    var state = SkipSegmentResultState()
    let firstA = state.reset()
    assert(state.accept(intro, token: firstA) && state.segments == intro.sorted { $0.startMs < $1.startMs })
    let itemB = state.reset()
    assert(state.segments.isEmpty)
    assert(!state.accept(secondary, token: firstA) && state.segments.isEmpty)
    let nextA = state.reset()
    assert(!state.accept(secondary, token: firstA) && !state.accept(secondary, token: itemB))
    assert(state.accept([open], token: nextA) && state.segments == [open])
    _ = state.reset() // teardown/cancel also revokes queued completions
    assert(!state.accept(intro, token: nextA) && state.segments.isEmpty)
    print("PASS: skip fetch stale-result fencing across item changes, A/B/A reuse and teardown")
}

/// No real provider requests: exercise HTTP/transport errors and provider selection
/// through URLSession, rather than testing only the parsers in isolation.
private final class SkipSegmentTestProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        assert(!Thread.isMainThread)
        let url = request.url!
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems ?? []
        let id = query.first { ["imdb_id", "tmdb_id"].contains($0.name) }!.value!
        let ua = request.value(forHTTPHeaderField: "User-Agent") ?? ""
        assert(ua.hasPrefix("PlayBridgeTV/") && ua.hasSuffix(" (+https://github.com/playbridgeapp/playbridge)"))
        if id == "transport-fail" {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let primary = url.host == "api.introdb.app"
        let body = id == "malformed" ? "not JSON" : primary
            ? #"{"intro":{"start_ms":1000,"end_ms":23000}}"#
            : #"{"intro":[{"start_ms":0,"end_ms":5000}],"credits":[{"start_ms":90000,"end_ms":null}]}"#
        let response = HTTPURLResponse(url: url, statusCode: id == "http-fail" ? 503 : 200,
                                       httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private func runSkipSegmentFetchTests() {
    let done = DispatchSemaphore(value: 0)
    Task.detached {
        func fetch(_ metadata: SkipSegmentMetadata, _ provider: SkipSegmentProvider) async -> [SkipSegment] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [SkipSegmentTestProtocol.self]
            return await SkipSegmentFetcher.fetch(metadata, provider: provider, configuration: config)
        }
        let metadata = SkipSegmentMetadata(imdbID: "valid", tmdbID: "valid", season: 1, episode: 2)
        let primary = SkipSegment(type: "intro", startMs: 1000, endMs: 23000)
        let secondary = SkipSegment(type: "intro", startMs: 0, endMs: 5000)
        let credits = SkipSegment(type: "outro", startMs: 90000, endMs: nil)
        let both = await fetch(metadata, .both)
        assert(both == [primary, credits])
        let introDBOnly = await fetch(metadata, .introdb)
        assert(introDBOnly == [primary])
        let theIntroDBOnly = await fetch(metadata, .theintrodb)
        assert(theIntroDBOnly == [secondary, credits])
        let disabled = await fetch(metadata, .disabled)
        assert(disabled.isEmpty)
        for failure in ["http-fail", "transport-fail", "malformed"] {
            let failing = SkipSegmentMetadata(imdbID: failure, tmdbID: failure, season: 1, episode: 2)
            let empty = await fetch(failing, .both)
            assert(empty.isEmpty)
            let fallback = await fetch(SkipSegmentMetadata(imdbID: failure, tmdbID: "valid", season: 1, episode: 2), .both)
            assert(fallback == [secondary, credits], "One provider failing must not suppress the other")
        }
        done.signal()
    }
    assert(done.wait(timeout: .now() + 30) == .success)
    print("PASS: skip fetching off main, provider modes, User-Agent, HTTP/transport/JSON failures and fallback")
}

private func runChapterNavigationTests() {
    typealias Nav = ChapterNavigation
    let chapters = Nav.sorted([
        TimelineChapter(startMs: 600_000, title: "The Heist"),
        TimelineChapter(startMs: 0, title: "Opening"),
        TimelineChapter(startMs: -1, title: "Bad"),
        TimelineChapter(startMs: 1_200_000),
    ])
    assert(chapters.map(\.startMs) == [0, 600_000, 1_200_000], "Sorted like the timeline frame")
    func jump(_ positionMs: Int, _ forward: Bool) -> Int? {
        Nav.jumpTarget(chapters: chapters, positionMs: positionMs, forward: forward)?.number
    }

    // Next: the first start beyond position + 1 s lead.
    assert(jump(0, true) == 2)
    assert(jump(599_500, true) == 3, "A jump that has not landed yet does not pick the same chapter")
    assert(jump(598_000, true) == 2)
    assert(jump(1_200_000, true) == nil, "Past the last chapter there is no next")
    // Previous: restart after 5 s into a chapter, otherwise go back one, clamped to the first.
    assert(jump(610_000, false) == 2, "More than 5 s in restarts the chapter")
    assert(jump(605_000, false) == 1, "Exactly 5 s in goes back one")
    assert(jump(602_000, false) == 1)
    assert(jump(1_000, false) == 1, "The first chapter clamps")
    let late = [TimelineChapter(startMs: 30_000, title: "A"), TimelineChapter(startMs: 60_000)]
    assert(Nav.jumpTarget(chapters: late, positionMs: 10_000, forward: false)?.number == 1,
           "Before the first chapter, previous goes to chapter 1")
    let single = [TimelineChapter(startMs: 0, title: "Only")]
    assert(Nav.jumpTarget(chapters: single, positionMs: 0, forward: true) == nil)
    assert(Nav.jumpTarget(chapters: single, positionMs: 50_000, forward: false) == nil)
    assert(Nav.jumpTarget(chapters: [], positionMs: 0, forward: true) == nil)

    // Repeated presses chain from the pending target, not a stale reported position.
    var pending = 0
    for expected in [2, 3] {
        let target = Nav.jumpTarget(chapters: chapters, positionMs: pending, forward: true)!
        assert(target.number == expected)
        pending = target.chapter.startMs
    }
    assert(Nav.jumpTarget(chapters: chapters, positionMs: pending, forward: false)?.number == 2,
           "Previous right after landing goes back one chapter")

    // Labels and captions.
    let heist = Nav.jumpTarget(chapters: chapters, positionMs: 0, forward: true)!
    assert(Nav.label(heist) == "Chapter 2 · The Heist")
    // Muxer placeholder titles are not repeated after the number.
    assert(Nav.label(ChapterJumpTarget(number: 2, chapter: TimelineChapter(startMs: 0, title: "Chapter 2"))) == "Chapter 2")
    assert(Nav.label(ChapterJumpTarget(number: 2, chapter: TimelineChapter(startMs: 0, title: "chapter 02"))) == "Chapter 2")
    assert(Nav.label(ChapterJumpTarget(number: 2, chapter: TimelineChapter(startMs: 0, title: "Chapter 3"))) == "Chapter 2 · Chapter 3")
    assert(Nav.toast(heist) == "Chapter 2 · The Heist")
    assert(Nav.toast(Nav.jumpTarget(chapters: chapters, positionMs: 1_300_000, forward: true)) == "Last chapter")
    assert(Nav.label(ChapterJumpTarget(number: 3, chapter: TimelineChapter(startMs: 1, title: "  ")))
           == "Chapter 3", "A blank title reads as the number")
    assert(Nav.captionAt(chapters: chapters, positionMs: 1_250_000) == "Chapter 3")
    assert(Nav.captionAt(chapters: chapters, positionMs: 700_000) == "Chapter 2 · The Heist")
    assert(Nav.captionAt(chapters: late, positionMs: 1_000) == nil, "Nothing before the first chapter")
    assert(Nav.captionAt(chapters: single, positionMs: 9_000) == "Only", "A lone chapter shows only its title")
    assert(Nav.captionAt(chapters: [TimelineChapter(startMs: 0)], positionMs: 9_000) == nil)

    let segments = [SkipSegment(type: "intro", startMs: 10_000, endMs: 40_000),
                    SkipSegment(type: "outro", startMs: 1_700_000, endMs: nil)]
    func caption(_ ms: Int) -> String? {
        Nav.timelineCaption(chapters: chapters, segments: segments, positionMs: ms, durationMs: 1_800_000)
    }
    assert(caption(20_000) == "Intro", "A segment wins over the chapter")
    assert(caption(40_000) == "Chapter 1 · Opening", "A segment end is exclusive")
    assert(caption(1_750_000) == "Credits", "Outro reads Credits and open-ended runs to the end")
    assert(Nav.segmentLabel("recap") == "Recap" && Nav.segmentLabel("preview") == "Preview")
    assert(Nav.timelineCaption(chapters: [], segments: [], positionMs: 0, durationMs: 0) == nil)

    // Geometry: gaps skip the chapter at 0 and any at or past the end.
    let fractions = Nav.chapterFractions(
        chapters: chapters + [TimelineChapter(startMs: 1_800_000)], durationMs: 1_800_000)
    assert(fractions.count == 2 && abs(fractions[0] - 1.0 / 3) < 1e-9 && abs(fractions[1] - 2.0 / 3) < 1e-9)
    assert(Nav.chapterFractions(chapters: chapters, durationMs: 0).isEmpty, "No duration, no gaps")
    assert(Nav.chapterFractions(chapters: [TimelineChapter(startMs: 500)], durationMs: 1000).isEmpty,
           "One chapter has nothing to separate")
    let ranges = Nav.segmentRanges(segments: segments + [SkipSegment(type: "intro", startMs: 5, endMs: 5),
                                                         SkipSegment(type: "bogus", startMs: 0, endMs: 9)],
                                   durationMs: 2_000_000)
    assert(ranges == [0.005...0.02, 0.85...1.0], "Open-ended clamps to the end; invalid segments draw nothing")
    assert(Nav.segmentRanges(segments: [SkipSegment(type: "outro", startMs: 900, endMs: 5000)], durationMs: 1000)
           == [0.9...1.0], "Bands clamp to the duration")
    assert(Nav.segmentRanges(segments: segments, durationMs: 0).isEmpty)

    // AVPlayer Chapters panel entries.
    let markers = Nav.navigationMarkers(chapters: [TimelineChapter(startMs: 600_000), TimelineChapter(startMs: 0, title: " Opening ")],
                                        durationMs: 900_000)
    assert(markers == [ChapterNavigationMarker(startMs: 0, endMs: 600_000, title: "Opening"),
                       ChapterNavigationMarker(startMs: 600_000, endMs: 900_000, title: "Chapter 2")])
    let unknownEnd = Nav.navigationMarkers(chapters: chapters, durationMs: nil)
    assert(unknownEnd.last == ChapterNavigationMarker(startMs: 1_200_000, endMs: nil, title: "Chapter 3"))
    assert(Nav.navigationMarkers(chapters: single, durationMs: 900_000).isEmpty, "One chapter adds no Chapters tab")
    print("PASS: chapter jumps (lead, restart, clamp, chain), labels, captions, gaps, bands and AVPlayer markers")
}

private func runChapterRemoteTests() {
    for scrubbing in [false, true] {
        for visible in [false, true] {
            let chrome = PlayerChromeState(sessionMatches: true, scrubbing: scrubbing, controlsVisible: visible)
            assert(PlayerRemoteNavigation.effect(for: "dpad_up", chrome: chrome) == .adjustAppVolume(steps: 1))
            assert(PlayerRemoteNavigation.effect(for: "dpad_down", chrome: chrome) == .adjustAppVolume(steps: -1))
            var panel = chrome
            panel.infoPanel = true
            assert(PlayerRemoteNavigation.effect(for: "dpad_up", chrome: panel) == .moveHighlight(delta: -1))
            assert(PlayerRemoteNavigation.effect(for: "dpad_down", chrome: panel) == .moveHighlight(delta: 1))
            assert(PlayerRemoteNavigation.effect(for: "dpad_center", chrome: panel) == .activateHighlight)
            assert(PlayerRemoteNavigation.effect(for: "dpad_left", chrome: panel) == .seekBackward)
            assert(PlayerRemoteNavigation.effect(for: "dpad_right", chrome: panel) == .seekForward)
            var playlist = chrome
            playlist.playlist = true
            assert(PlayerRemoteNavigation.effect(for: "dpad_up", chrome: playlist) == .moveHighlight(delta: -1))
            assert(PlayerRemoteNavigation.effect(for: "dpad_down", chrome: playlist) == .moveHighlight(delta: 1))
        }
    }
    print("PASS: network Up/Down adjust volume outside lists, including hold-scrub, and panel rows own highlight/activation")
}

private func runTransportChromeTests() {
    assert(TransportBarMetrics.horizontalInset == 80 && TransportBarMetrics.bottomInset == 60)
    assert(TransportBarMetrics.frameHeight == 14 && TransportBarMetrics.lineHeight == 6)
    assert(TransportBarMetrics.thumbDiameter == 16)
    assert(TransportTimeLabels.elapsed(0) == "0:00")
    assert(TransportTimeLabels.elapsed(65.9) == "1:05")
    assert(TransportTimeLabels.elapsed(3723) == "1:02:03")
    assert(TransportTimeLabels.remaining(12, duration: 3723) == "−1:01:51")
    assert(TransportTimeLabels.remaining(3723, duration: 3723) == "−0:00")
    assert(TransportTimeLabels.remaining(4000, duration: 3723) == "−0:00")
    for invalid in [-1.0, .nan, .infinity, -.infinity] {
        assert(TransportTimeLabels.elapsed(invalid) == "0:00")
        assert(TransportTimeLabels.remaining(invalid, duration: 3723) == "−1:02:03")
        assert(TransportTimeLabels.remaining(12, duration: invalid).isEmpty)
    }
    assert(TransportTimeLabels.elapsed(.greatestFiniteMagnitude) == "0:00")
    assert(TransportTimeLabels.remaining(12, duration: 0).isEmpty)
    // AVPlayer clock and the label riding under the playhead.
    assert(TransportTimeLabels.clock(0) == "00:00" && TransportTimeLabels.clock(111) == "01:51")
    assert(TransportTimeLabels.clock(3723) == "1:02:03")
    assert(TransportTimeLabels.clockRemaining(111, duration: 301) == "-03:10")
    assert(TransportTimeLabels.clockRemaining(400, duration: 301) == "-00:00")
    assert(TransportTimeLabels.clockRemaining(12, duration: .nan).isEmpty)
    let start = TransportTimeLabels.playheadLabelLayout(fraction: 0, width: 1000, elapsedWidth: 80, remainingWidth: 90)
    assert(start.x == 0 && start.showsRemaining)
    let middle = TransportTimeLabels.playheadLabelLayout(fraction: 0.5, width: 1000, elapsedWidth: 80, remainingWidth: 90)
    assert(middle.x == 460 && middle.showsRemaining)
    let end = TransportTimeLabels.playheadLabelLayout(fraction: 1, width: 1000, elapsedWidth: 80, remainingWidth: 90)
    assert(end.x == 920 && !end.showsRemaining)
    assert(TransportTimeLabels.playheadLabelLayout(fraction: .nan, width: 1000, elapsedWidth: 80, remainingWidth: 90).x == 0)
    assert(TransportTimeLabels.playheadLabelLayout(fraction: 0.5, width: 0, elapsedWidth: 80, remainingWidth: 90).x == 0)
    assert(TransportTimeLabels.metadataSubtitle(title: "Show", year: "2024", season: 2, episode: 4,
                                               episodeTitle: "  The Heist  ") == "S2 E4 · The Heist")
    assert(TransportTimeLabels.metadataSubtitle(season: 2, episode: 4) == "S2 E4")
    assert(TransportTimeLabels.metadataSubtitle(season: 0, episode: 0, episodeTitle: "Special") == "S0 E0 · Special")
    assert(TransportTimeLabels.metadataSubtitle(season: 2, episode: 4, episodeTitle: " \n ") == "S2 E4")
    assert(TransportTimeLabels.metadataSubtitle(year: " 2024 ") == "2024")
    assert(TransportTimeLabels.metadataSubtitle(year: "2024", season: 2) == "2024")
    assert(TransportTimeLabels.metadataSubtitle(title: "Only the main title", episodeTitle: "No coordinates").isEmpty)
    assert(TransportTimeLabels.metadataSubtitle().isEmpty)
    print("PASS: transport metrics, elapsed/remaining time bounds, metadata presence and season-zero labels")

    assert(TransportScrub.clickSkipSeconds == 10 && TransportScrub.remoteSkipSeconds == 15)
    assert(TransportScrub.holdThreshold == 0.5)
    func proposal(_ translation: Double, velocity: Double = 0, duration: Double = 1000) -> Double {
        TransportScrub.proposal(origin: 500, translationX: translation, velocityX: velocity, duration: duration)
    }
    // 1000 s item: a full 1920 pt swipe spans the 600 s cap (0.3125 s per point).
    assert(proposal(0) == 500)
    assert(proposal(192) == 560 && proposal(-192) == 440)
    assert(proposal(192, velocity: 299) == 560 && proposal(192, velocity: -299) == 560)
    assert(proposal(192, velocity: 300) == 560)
    assert(proposal(192, velocity: 1150) == 590)
    assert(proposal(192, velocity: 2000) == 620 && proposal(192, velocity: -2000) == 620)
    assert(proposal(192, velocity: 10000) == 620, "Gain caps at 2x")
    assert(proposal(1920) == 1000 && proposal(-1920) == 0, "Full swipe covers the capped span")
    assert(proposal(.greatestFiniteMagnitude) == 1000 && proposal(-.greatestFiniteMagnitude) == 0)
    for invalid in [Double.nan, .infinity, -.infinity] {
        assert(proposal(invalid) == 500)
        assert(proposal(192, velocity: invalid) == 560)
        assert(proposal(192, duration: invalid) == 500)
    }
    assert(proposal(192, duration: 0) == 500 && proposal(192, duration: -1) == 500)
    // A 42-minute episode: a 300 pt swipe moves ~94 s, not to either end.
    assert(abs(TransportScrub.proposal(origin: 1000, translationX: 300, velocityX: 0, duration: 2520) - 1093.75) < 0.001)
    // Short items: the whole item per full swipe (60 s minimum span).
    assert(TransportScrub.proposal(origin: 5, translationX: 1920, velocityX: 0, duration: 10) == 10)
    assert(TransportScrub.proposal(origin: .nan, translationX: 0, velocityX: 0, duration: 10) == 0)
    print("PASS: transport click/remote/hold constants, scrub direction, velocity gain, clamps and invalid inputs")

    // AVPlayer's tabs; audio and subtitles are transport buttons.
    assert(InfoPanelTab.allCases == [.info, .chapters, .settings])
    assert(InfoPanelModel.visibleTabs(chapterCount: 0, settingsAvailable: false) == [.info])
    assert(InfoPanelModel.visibleTabs(chapterCount: 1, settingsAvailable: false) == [.info])
    assert(InfoPanelModel.visibleTabs(chapterCount: 2, settingsAvailable: false) == [.info, .chapters])
    assert(InfoPanelModel.visibleTabs(chapterCount: 0, settingsAvailable: true) == [.info, .settings])
    assert(InfoPanelModel.visibleTabs(chapterCount: 2, settingsAvailable: true) == [.info, .chapters, .settings])
    // Only tabs with content: a file without metadata keeps Chapters and Settings.
    assert(InfoPanelModel.visibleTabs(chapterCount: 3, settingsAvailable: true, hasInfo: false) == [.chapters, .settings])
    assert(InfoPanelModel.visibleTabs(chapterCount: 0, settingsAvailable: false, hasInfo: false) == [.info])
    assert(InfoPanelModel.validTab(.info, tabs: [.chapters, .settings]) == .chapters)
    assert(InfoPanelModel.validTab(.settings, tabs: [.chapters, .settings]) == .settings)
    assert(TransportMetadata().isEmpty && !TransportMetadata(year: "1999").isEmpty
        && !TransportMetadata(genres: ["Drama"]).isEmpty)
    print("PASS: info-panel tab ordering, chapter threshold, optional settings and unconditional audio/subtitles")

    let chapterRows = InfoPanelModel.chapterRows(chapters: [
        TimelineChapter(startMs: 60000, title: "Chapter 02"),
        TimelineChapter(startMs: 0, title: nil),
        TimelineChapter(startMs: 120000, title: "The Heist")], durationMs: 180000, positionMs: 70000)
    assert(chapterRows.map(\.title) == ["Chapter 1", "Chapter 02", "The Heist"])
    assert(chapterRows.map(\.id) == [0, 60000, 120000])
    assert(chapterRows.map(\.trailingText) == ["0:00", "1:00", "2:00"])
    let longChapterRows = InfoPanelModel.chapterRows(chapters: [
        TimelineChapter(startMs: 0), TimelineChapter(startMs: 3_723_500)],
        durationMs: 5_000_000, positionMs: 3_800_000)
    assert(longChapterRows.map(\.trailingText) == ["0:00", "1:02:03"])
    assert(longChapterRows.last?.selected == true)
    assert(InfoPanelRow(id: 1, title: "Audio").trailingText == nil)
    assert(InfoPanelModel.initialHighlight(rows: chapterRows) == 1)
    assert(InfoPanelModel.initialHighlight(rows: []) == 0)
    assert(InfoPanelModel.chapterRows(chapters: [], durationMs: 0, positionMs: 0).isEmpty)
    let tabs = InfoPanelModel.visibleTabs(chapterCount: 2, settingsAvailable: false)
    assert(InfoPanelModel.movingTab(.info, delta: -1, tabs: tabs) == .info)
    assert(InfoPanelModel.movingTab(.info, delta: 1, tabs: tabs) == .chapters)
    assert(InfoPanelModel.movingTab(.chapters, delta: 1, tabs: tabs) == .chapters)
    assert(InfoPanelModel.movingTab(.settings, delta: 1, tabs: []) == .info)
    assert(TransportBarMetrics.skipVisibleInset == 240 && TransportBarMetrics.skipHiddenInset == 80)
    assert(TransportVisibility.autoHideSeconds == 5 && TransportVisibility.feedbackSeconds == 0.8)
    assert(TransportVisibility.canAutoHide(userPaused: false, scrubbing: false, panel: false,
        engineMenu: false, playlist: false, prebuffering: false))
    for gate in 0..<6 {
        assert(!TransportVisibility.canAutoHide(userPaused: gate == 0, scrubbing: gate == 1,
            panel: gate == 2, engineMenu: gate == 3, playlist: gate == 4, prebuffering: gate == 5))
    }
    let firstRow = InfoPanelFocus()
    let tabsFocus = firstRow.moving(-1, rowCount: 3, physical: true)
    assert(tabsFocus == InfoPanelFocus(row: 0, tabBar: true))
    assert(tabsFocus.moving(1, rowCount: 3, physical: true) == firstRow)
    assert(firstRow.moving(1, rowCount: 3, physical: true).row == 1)
    assert(firstRow.moving(-1, rowCount: 3, physical: false) == firstRow)
    assert(InfoPanelFocus(row: 2).moving(1, rowCount: 3, physical: false).row == 2)
    assert(firstRow.moving(1, rowCount: 0, physical: true).tabBar)
    // Chapter cards: Up from any card reaches the tabs, Down stays, Left/Right move cards.
    let thirdCard = InfoPanelFocus(row: 2)
    assert(thirdCard.moving(-1, rowCount: 4, physical: true, horizontalRows: true) == InfoPanelFocus(row: 2, tabBar: true))
    assert(thirdCard.moving(1, rowCount: 4, physical: true, horizontalRows: true) == thirdCard)
    assert(InfoPanelFocus(row: 2, tabBar: true).moving(1, rowCount: 4, physical: true, horizontalRows: true) == thirdCard)
    assert(thirdCard.moving(1, rowCount: 4, physical: false, horizontalRows: true).row == 3)
    assert(InfoPanelModel.horizontalMovesRows(tab: .chapters, tabBarFocused: false))
    assert(!InfoPanelModel.horizontalMovesRows(tab: .chapters, tabBarFocused: true))
    assert(!InfoPanelModel.horizontalMovesRows(tab: .settings, tabBarFocused: false))
    let moved = Date(timeIntervalSince1970: 1000)
    assert(InfoPanelModel.isMoveEcho(focusMovedAt: moved, now: moved.addingTimeInterval(0.05)))
    assert(!InfoPanelModel.isMoveEcho(focusMovedAt: moved, now: moved.addingTimeInterval(0.5)))
    assert(!InfoPanelModel.isMoveEcho(focusMovedAt: .distantPast, now: moved))
    // Duplicate chapter starts collapse; card frames sit a little into each chapter.
    let duplicated = ChapterNavigation.sorted([TimelineChapter(startMs: 240_000, title: "Credits"),
        TimelineChapter(startMs: 0, title: "Opening"), TimelineChapter(startMs: 240_000, title: "Credits")])
    assert(duplicated.map(\.startMs) == [0, 240_000])
    let frames = ChapterNavigation.thumbnailTimes(chapters: [TimelineChapter(startMs: 0),
        TimelineChapter(startMs: 20_000), TimelineChapter(startMs: 200_000)], durationMs: 300_000)
    assert(frames.map(\.startMs) == [0, 20_000, 200_000])
    assert(frames.map(\.seconds) == [5, 30, 210])
    assert(ChapterNavigation.thumbnailTimes(chapters: [TimelineChapter(startMs: 0)], durationMs: 300_000).isEmpty)
    let many = (0..<60).map { TimelineChapter(startMs: $0 * 10_000) }
    assert(ChapterNavigation.thumbnailTimes(chapters: many, durationMs: 700_000).count == ChapterNavigation.maxThumbnails)
    print("PASS: panel chapter labels/targets/checks, selected-row focus, tab clamps, Skip clearance and auto-hide gates")

    var stack = PlayerChromeState(sessionMatches: true, engineMenu: true, scrubbing: true,
                                  playlist: true, controlsVisible: true, infoPanel: true)
    for expected: PlayerRemoteEffect in [.dismissInfoPanel, .dismissEngineMenu, .dismissScrub,
                                        .dismissPlaylist, .dismissControls, .exitPlayback] {
        let effect = PlayerRemoteNavigation.effect(for: "back", chrome: stack)
        assert(effect == expected)
        PlayerRemoteNavigation.closing(effect, in: &stack)
        if effect == .dismissInfoPanel {
            assert(!stack.infoPanel && stack.engineMenu && stack.scrubbing && stack.playlist && stack.controlsVisible)
        }
    }
    var panel = PlayerChromeState(sessionMatches: true, engineMenu: true, playlist: true,
                                 controlsVisible: true, infoPanel: true)
    assert(PlayerRemoteNavigation.listOwner(in: panel) == .infoPanel)
    assert(PlayerRemoteNavigation.effect(for: "home", chrome: panel) == .exitPlayback)
    panel.stillWatching = true
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: panel) == .dismissStillWatching)
    assert(PlayerRemoteNavigation.listOwner(in: panel) == .stillWatching)
    panel.sessionMatches = false
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: panel) == .ignored)
    assert(PlayerRemoteNavigation.effect(for: "home", chrome: panel) == .ignored)
    var transport = PlayerChromeState(sessionMatches: true, controlsVisible: true)
    let firstBack = PlayerRemoteNavigation.effect(for: "back", chrome: transport)
    assert(firstBack == .dismissControls)
    PlayerRemoteNavigation.closing(firstBack, in: &transport)
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: transport) == .exitPlayback)
    let snapshot = PlayerEngineChromeSnapshot(controlsVisible: true, infoPanel: true)
    let copied = snapshot.chrome(sessionMatches: true, stillWatching: false, playlist: false)
    assert(copied.infoPanel && copied.controlsVisible)
    // Panel ownership replaces the old subtitle/audio menus.
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: PlayerChromeState(
        sessionMatches: true, engineMenu: true, infoPanel: true)) == .dismissInfoPanel)
    print("PASS: info-panel Back/list priority, shared snapshot flags, paused-HUD exit levels, Home and stale keys")

    func route(_ up: Bool, _ chrome: TransportChrome) -> TransportKeyRoute {
        TransportKeyRoute.physicalVertical(up: up, chrome: chrome)
    }
    var chrome = TransportChrome()
    assert(route(false, chrome) == .openPanel && route(true, chrome) == .showTransport)
    // Pause, hold-scrub and Skip focus do not steal Down from Info.
    for visible in [false, true] {
        for paused in [false, true] {
            for scrubbing in [false, true] {
                for skipFocused in [false, true] {
                    chrome.navigation.controlsVisible = visible
                    chrome.navigation.scrubbing = scrubbing
                    chrome.userPaused = paused
                    chrome.skipPillFocused = skipFocused
                    assert(route(false, chrome) == .openPanel)
                    assert(route(true, chrome) == (visible ? (skipFocused ? .moveFocus : .focusActions) : .showTransport))
                }
            }
        }
    }
    chrome.navigation.infoPanel = true
    assert(route(false, chrome) == .moveInPanel && route(true, chrome) == .moveInPanel)
    chrome.panelTabBarFocused = true
    assert(route(true, chrome) == .closePanel && route(false, chrome) == .moveInPanel)
    chrome.navigation.infoPanel = false
    for path in [\PlayerChromeState.engineMenu, \.settingsSheet, \.playlist] {
        var menu = TransportChrome()
        menu.navigation[keyPath: path] = true
        // An open list owns Up and Down; Down only opens Info outside lists.
        assert(route(false, menu) == .moveFocus)
        assert(route(true, menu) == .moveFocus)
    }

    for paused in [false, true] {
        var actions = TransportChrome(userPaused: paused)
        actions.navigation.sessionMatches = true
        actions.navigation.controlsVisible = true
        assert(route(true, actions) == .focusActions)
        actions.transportActionFocused = true
        assert(route(true, actions) == .focusActions, "Up stays in the action row")
        assert(route(false, actions) == .focusScrubber)
        assert(PlayerRemoteNavigation.effect(for: "back", chrome: actions.navigation) == .dismissControls)
        actions.navigation.infoPanel = true
        assert(route(false, actions) == .moveInPanel, "A panel keeps priority over stale action focus")
        actions.navigation.infoPanel = false
        actions.navigation.engineMenu = true
        assert(route(true, actions) == .moveFocus, "An engine menu owns its directions")
    }
    assert(TransportAction.available(hasPlaylist: false) == [.loop, .speed, .switchEngine, .audio])
    assert(TransportAction.available(hasPlaylist: true) == [.loop, .speed, .switchEngine, .audio, .playlist])
    assert(TransportAction.available(hasPlaylist: true, hasSubtitles: true)
        == [.loop, .speed, .switchEngine, .subtitles, .audio, .playlist])
    assert(TransportAction.switchEngine.moving(1, hasPlaylist: false, hasSubtitles: true) == .subtitles)
    assert(TransportAction.subtitles.moving(1, hasPlaylist: false, hasSubtitles: true) == .audio)
    // One audio track: no Audio button; Right from the last button stays put.
    assert(TransportAction.available(hasPlaylist: false, hasSubtitles: true, hasAudioChoice: false)
        == [.loop, .speed, .switchEngine, .subtitles])
    assert(TransportAction.available(hasPlaylist: true, hasAudioChoice: false) == [.loop, .speed, .switchEngine, .playlist])
    assert(TransportAction.subtitles.moving(1, hasPlaylist: false, hasSubtitles: true, hasAudioChoice: false) == .subtitles)
    assert(TransportAction.switchEngine.moving(1, hasPlaylist: true, hasAudioChoice: false) == .playlist)
    assert(TransportAction.subtitles.menu == .subtitles)
    let subtitleRows = TransportMenuModel.subtitleRows(tracks: [(id: 3, name: "English"), (id: 4, name: "SDH")], current: 4)
    assert(subtitleRows.map(\.id) == [-1, 3, 4] && subtitleRows.map(\.title).first == "Off")
    assert(TransportMenuModel.initialHighlight(rows: subtitleRows) == 2)
    assert(TransportMenuModel.subtitleRows(tracks: [], current: -1).first?.selected == true)
    // The episode line is dropped when the title already carries it.
    assert(TransportTimeLabels.distinctSubtitle("S1 E5 · Little Pot Rock", title: "Below · S1E5 · Little Pot Rock").isEmpty)
    assert(TransportTimeLabels.distinctSubtitle("S1 E5 · Little Pot Rock", title: "Below") == "S1 E5 · Little Pot Rock")
    assert(TransportTimeLabels.distinctSubtitle("2024", title: "Movie") == "2024")
    assert(TransportTimeLabels.distinctSubtitle("", title: "Movie").isEmpty)
    // 4K artwork: TMDB sizes are upgraded, then the URL as sent is the fallback.
    let backdrop = ArtworkURL.candidates("https://image.tmdb.org/t/p/w780/abc.jpg", kind: .backdrop)
    assert(backdrop.map(\.absoluteString) == ["https://image.tmdb.org/t/p/original/abc.jpg",
                                               "https://image.tmdb.org/t/p/w780/abc.jpg"])
    let posterArt = ArtworkURL.candidates("https://image.tmdb.org/t/p/w342/p.jpg", kind: .poster)
    assert(posterArt.map(\.absoluteString).first == "https://image.tmdb.org/t/p/w780/p.jpg" && posterArt.count == 2)
    assert(ArtworkURL.candidates("https://image.tmdb.org/t/p/w1280/p.jpg", kind: .poster).count == 1, "Never shrinks")
    // Experimental Matroska remux routing and the loopback HLS paths.
    assert(RemuxRoute.isCandidate(URL(string: "https://cdn.example/x/Movie.2024.MKV?token=1")!))
    assert(RemuxRoute.isCandidate(URL(fileURLWithPath: "/tmp/a.mk3d")))
    assert(!RemuxRoute.isCandidate(URL(string: "https://cdn.example/x/movie.mp4")!))
    assert(!RemuxRoute.isCandidate(URL(string: "https://cdn.example/stream?file=a.mkv")!))
    assert(!RemuxRoute.isCandidate(URL(string: "ftp://host/a.mkv")!))
    assert(RemuxRoute.parse(path: "/abc/master.m3u8")! == ("abc", .master))
    assert(RemuxRoute.parse(path: "/abc/index.m3u8?x=1")! == ("abc", .media))
    assert(RemuxRoute.parse(path: "/abc/init.mp4")! == ("abc", .initSegment))
    assert(RemuxRoute.parse(path: "/abc/seg12.m4s")! == ("abc", .segment(12)))
    for bad in ["/abc/seg-1.m4s", "/abc/segx.m4s", "/abc", "/abc/def/master.m3u8", "/", "//master.m3u8", "/abc/other.ts"] {
        assert(RemuxRoute.parse(path: bad) == nil, bad)
    }
    assert(RemuxRoute.Resource.segment(0).contentType == "video/iso.segment")
    assert(ArtworkURL.candidates("https://image.tmdb.org/t/p/w300/b.jpg", kind: .card).first?.absoluteString
        == "https://image.tmdb.org/t/p/w780/b.jpg")
    assert(ArtworkURL.candidates("https://image.tmdb.org/t/p/w780/b.jpg", kind: .card).count == 1)
    // Playlist card text: episode lines, else the item title, else a numbered fallback.
    let episodeCard = PlaylistCardText.lines(title: "Below", season: 1, episode: 6, episodeTitle: "The Tide", index: 1)
    assert(episodeCard.eyebrow == "S1 · E6" && episodeCard.title == "The Tide")
    assert(PlaylistCardText.lines(title: "Below", season: 1, episode: 6, episodeTitle: " ", index: 1).title == "Below")
    assert(PlaylistCardText.lines(title: nil, season: 2, episode: 3, episodeTitle: nil, index: 0).title == "Episode 3")
    let movieCard = PlaylistCardText.lines(title: "Heat", season: nil, episode: nil, episodeTitle: nil, index: 0)
    assert(movieCard.eyebrow.isEmpty && movieCard.title == "Heat")
    assert(PlaylistCardText.lines(title: "", season: nil, episode: 4, episodeTitle: "x", index: 2).title == "Item 3")
    assert(ArtworkURL.candidates("https://image.tmdb.org/t/p/original/b.jpg", kind: .backdrop).count == 1)
    assert(ArtworkURL.candidates("https://images.metahub.space/background/medium/tt1/img", kind: .backdrop).count == 1)
    assert(ArtworkURL.candidates("https://image.tmdb.org/other/w780/b.jpg", kind: .backdrop).count == 1)
    assert(ArtworkURL.candidates("", kind: .poster).isEmpty && ArtworkURL.candidates("file:///x.jpg", kind: .poster).isEmpty)
    assert(!TransportMetadata(posterURL: "https://image.tmdb.org/t/p/w342/p.jpg").isEmpty, "A poster alone fills Info")
    assert(TransportAction.loop.moving(-1, hasPlaylist: true) == .loop)
    assert(TransportAction.loop.moving(1, hasPlaylist: false) == .speed)
    assert(TransportAction.speed.moving(1, hasPlaylist: false) == .switchEngine)
    assert(TransportAction.audio.moving(1, hasPlaylist: false) == .audio)
    assert(TransportAction.audio.moving(1, hasPlaylist: true) == .playlist)
    assert(TransportAction.playlist.moving(-1, hasPlaylist: true) == .audio)
    assert(TransportAction.playlist.moving(1, hasPlaylist: true) == .playlist)
    assert(TransportAction.speed.menu == .speed && TransportAction.audio.menu == .audio
        && TransportAction.switchEngine.menu == .engine)
    assert(TransportAction.loop.menu == nil && TransportAction.playlist.menu == nil)
    let speedRows = TransportMenuModel.speedRows(current: 1.25)
    assert(speedRows.count == PlayerSettingsItem.speeds.count)
    assert(TransportMenuModel.initialHighlight(rows: speedRows) == PlayerSettingsItem.speeds.firstIndex(of: 1.25))
    assert(TransportMenuModel.speed(forRow: speedRows[0].id) == PlayerSettingsItem.speeds[0])
    assert(TransportMenuModel.speed(forRow: -1) == nil && TransportMenuModel.speed(forRow: 99) == nil)
    let audioRows = TransportMenuModel.audioRows(tracks: [(id: 1, name: "English"), (id: 2, name: "Hindi")], current: 2)
    assert(audioRows.map(\.id) == [1, 2] && TransportMenuModel.initialHighlight(rows: audioRows) == 1)
    assert(TransportMenuModel.initialHighlight(rows: TransportMenuModel.audioRows(tracks: [], current: -1)) == 0)
    // The mpv Settings tab leaves speed to the transport's Speed button.
    let mpvTab = PlayerSettingsAvailability.forEngine("mpv", sidecarOverlayActive: false).withoutSpeed
    assert(!mpvTab.speed && mpvTab.scaling && mpvTab.subtitleOffset && mpvTab.any)
    assert(PlayerSettingsItem.rows(tuning: PlayerTuning(), availability: mpvTab).first?.section == "Scaling")
    let engineRows = TransportMenuModel.engineRows(current: .mpv)
    assert(engineRows.filter(\.selected).map(\.id) == [PlaybackEngine.mpv.menuID])
    assert(PlayerRemoteNavigation.physicalPlaybackEffect(for: .select, scrubbing: false,
        selectActivatesFocusedControl: true) == .ignored, "Action Select never toggles playback")
    print("PASS: playing/paused transport action focus, Down to scrubber, Back hides, optional Playlist and clamped Left/Right")

    let ready = TransportChrome(userPaused: true, duration: 100)
    assert(TransportKeyRoute.canTouchScrub(chrome: ready))
    for duration in [0.0, -1, .nan, .infinity] {
        var invalid = ready
        invalid.duration = duration
        assert(!TransportKeyRoute.canTouchScrub(chrome: invalid))
    }
    for path in [\TransportChrome.prebuffering, \.hasFailure, \.skipPillFocused,
                 \.navigation.stillWatching, \.navigation.infoPanel, \.navigation.engineMenu,
                 \.navigation.playlist,
                 \.navigation.settingsSheet, \.navigation.scrubbing] {
        var blocked = ready
        blocked[keyPath: path] = true
        assert(!TransportKeyRoute.canTouchScrub(chrome: blocked))
    }
    var playing = ready
    playing.userPaused = false
    assert(!TransportKeyRoute.canTouchScrub(chrome: playing))
    print("PASS: Up reaches actions, Down opens Info from the scrubber, panel/list priority and touch-scrub gates")

    let placement = TransportSubtitlePlacement.position
    assert(placement(0, 1080) == 100)
    assert(placement(360, 1080) < 70)
    assert(placement(880, 1080) < placement(360, 1080))
    assert(placement(360, 1080) == placement(720 + 24, 2160))
    assert(placement(2000, 1080) == TransportSubtitlePlacement.minimumPosition)
    for invalid in [0.0, -1, .nan, .infinity] {
        assert(placement(360, invalid) == 100)
        assert(placement(invalid, 1080) == 100)
    }
    assert(placement(0, 1080) == TransportSubtitlePlacement.normalPosition, "Hiding chrome restores subtitles")
    print("PASS: subtitle placement clears transport/panel, scales by viewport, clamps and restores when hidden")

    for cache in [false, true] {
        for enginePaused in [false, true] {
            let paused = PlaybackSurfaceState.resolve(cacheStalled: cache, userPaused: true,
                                                      prebuffering: false, enginePaused: enginePaused)
            assert(paused == .paused && !paused.showsSpinner)
            let preplay = PlaybackSurfaceState.resolve(cacheStalled: cache, userPaused: false,
                                                       prebuffering: true, enginePaused: enginePaused)
            assert(preplay == .paused && !preplay.showsSpinner)
            let active = PlaybackSurfaceState.resolve(cacheStalled: cache, userPaused: false,
                                                      prebuffering: false, enginePaused: enginePaused)
            assert(active == (cache ? .buffering : enginePaused ? .paused : .playing))
            assert(active.showsSpinner == cache)
        }
    }
    assert(PlaybackSurfaceState.buffering.rawValue == "buffering")
    print("PASS: playback surface reduction, user-pause/pre-play precedence and spinner visibility")

    let open = "The video couldn't be opened."
    let generic = "This video couldn't be played."
    assert(PlaybackFailureMessage.sentence(forMpvCode: -13) == open)
    for code: Int32 in -11 ... -5 { assert(PlaybackFailureMessage.sentence(forMpvCode: code) == open) }
    for code: Int32 in [0, -1, -4, -12, -14, -16, Int32.min, Int32.max] {
        assert(PlaybackFailureMessage.sentence(forMpvCode: code) == generic)
    }
    for sentence in [open, generic] { assert(!sentence.contains("://") && !sentence.contains("//")) }
    print("PASS: fixed playback failure sentences for mpv loading/init codes and unknown errors")

    assert(PlaybackFailureModel.actions(hasNextItem: false) == [.tryAgain, .close])
    assert(PlaybackFailureModel.actions(hasNextItem: true) == [.tryAgain, .next, .close])
    assert(PlaybackFailureAction.tryAgain.rawValue == "Try Again")
    assert(PlaybackFailureAction.next.rawValue == "Next" && PlaybackFailureAction.close.rawValue == "Close")
    for hasNext in [false, true] {
        assert(PlaybackFailureModel.actions(hasNextItem: hasNext).first == PlaybackFailureModel.defaultAction)
    }
    assert(PlaybackFailureModel.autoCloseSeconds == 60)
    for elapsed in [-1.0, 0, 59.999, .nan, .infinity, -.infinity] {
        assert(!PlaybackFailureModel.shouldAutoClose(elapsedSeconds: elapsed))
    }
    assert(PlaybackFailureModel.shouldAutoClose(elapsedSeconds: 60))
    assert(PlaybackFailureModel.shouldAutoClose(elapsedSeconds: 61))
    let initial = PlaybackFailureModel.phoneReport(for: .presented(mpvCode: -13))
    assert(initial == .errorStatus(reason: open), "Presentation immediately reports the error, never idle")
    assert(PlaybackFailureModel.phoneReport(for: .tryAgain) == nil)
    assert(PlaybackFailureModel.phoneReport(for: .next) == nil)
    assert(PlaybackFailureModel.phoneReport(for: .presented(mpvCode: -999)) == .errorStatus(reason: generic))
    for closing: PlaybackFailureModel.Event in [.close, .phoneStop, .autoClose] {
        assert(PlaybackFailureModel.phoneReport(for: closing) == .idleContext)
    }
    let retryAndNext: [PlaybackFailureModel.Event] = [
        .presented(mpvCode: -13), .tryAgain, .presented(mpvCode: -999), .next,
    ]
    assert(retryAndNext.compactMap { PlaybackFailureModel.phoneReport(for: $0) }
           == [.errorStatus(reason: open), .errorStatus(reason: generic)])
    print("PASS: error actions/default focus, optional Next, 60-second auto-close and error-now/idle-on-close reporting")
}

private func runPlaybackFailureSessionTests() {
    var session = PlaybackFailureSession()
    let first = session.beginAttempt()
    assert(session.matches(first) && session.sentence == nil)
    assert(!session.close(attempt: first), "Normal playback is not an error-screen close")
    assert(session.present(code: -13, attempt: first))
    assert(session.sentence == PlaybackFailureMessage.sentence(forMpvCode: -13))
    assert(!session.present(code: -999, attempt: first), "Duplicate end/init errors cannot restart the 60-second timer")
    let retry = session.beginAttempt()
    assert(session.sentence == nil && !session.matches(first))
    assert(!session.present(code: -13, attempt: first), "A late result from the failed attempt must not replace its retry")
    assert(!session.close(attempt: first), "An old timeout cannot close the retry")
    assert(session.present(code: -999, attempt: retry))
    assert(session.sentence == PlaybackFailureMessage.sentence(forMpvCode: -999))
    assert(session.close(attempt: retry))
    assert(!session.matches(retry) && !session.close(attempt: retry))
    assert(!session.present(code: -13, attempt: retry), "Close is terminal for that attempt")
    let nextItem = session.beginAttempt()
    assert(session.sentence == nil && session.matches(nextItem))
    assert(!session.close(attempt: retry))
    assert(session.present(code: -13, attempt: nextItem))
    _ = session.beginAttempt() // item replacement or teardown
    assert(!session.matches(nextItem) && !session.present(code: -13, attempt: nextItem))
    print("PASS: error attempt fencing, duplicate failures, retry/item replacement, close and stale timeouts")
}

private func runPlayerSettingsMenuTests() {
    let mpv = PlayerSettingsAvailability.forEngine("mpv", sidecarOverlayActive: false)
    let avNoSidecar = PlayerSettingsAvailability.forEngine("avplayer", sidecarOverlayActive: false)
    let avSidecar = PlayerSettingsAvailability.forEngine("avplayer", sidecarOverlayActive: true)
    assert(mpv.any && avNoSidecar.any && !PlayerSettingsAvailability.none.any)
    assert(InfoPanelModel.visibleTabs(chapterCount: 0, settingsAvailable: mpv.any).last == .settings)

    // Menu / row ids → PlayerTuning.Command (the design's examples).
    assert(PlayerSettingsItem.command(id: "speed:1.25", currentOffsetMs: 0) == .speed(1.25))
    assert(PlayerSettingsItem.command(id: "scaling:Zoom", currentOffsetMs: 0) == .scaling(.zoom))
    assert(PlayerSettingsItem.command(id: "sub_offset:+500", currentOffsetMs: 250) == .subtitleOffsetDelta(500))
    assert(PlayerSettingsItem.command(id: "sub_offset:-100", currentOffsetMs: 250) == .subtitleOffsetDelta(-100))
    assert(PlayerSettingsItem.command(id: "sub_offset:reset", currentOffsetMs: 250) == .subtitleOffsetDelta(-250))
    assert(PlayerSettingsItem.command(id: "sub_offset:reset", currentOffsetMs: -1_500) == .subtitleOffsetDelta(1_500))
    for invalid in ["", "speed:3", "speed:1.3", "scaling:zoom", "sub_offset:+250", "sub_offset:", "reset"] {
        assert(PlayerSettingsItem.command(id: invalid, currentOffsetMs: 0) == nil, "Unknown menu id must be ignored")
    }
    // Every id round-trips, and every command is one the engine accepts.
    for item in PlayerSettingsItem.items(availability: mpv) {
        assert(PlayerSettingsItem(id: item.id) == item)
        assert(mpv.allows(item.command(currentOffsetMs: 250)))
    }
    assert(PlayerSettingsItem.speeds == [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2])
    assert(PlayerSettingsItem.items(in: .speed).allSatisfy {
        guard case .speed(let value) = $0 else { return false }
        return PlayerTuning.speedRange.contains(value)
    })

    // Reset through the real tuning model returns the offset to zero.
    var tuning = PlayerTuning()
    assert(tuning.apply(PlayerSettingsItem.subtitleStep(500).command(currentOffsetMs: tuning.subtitleOffsetMs)))
    assert(tuning.apply(PlayerSettingsItem.subtitleStep(-100).command(currentOffsetMs: tuning.subtitleOffsetMs)))
    assert(tuning.subtitleOffsetMs == 400)
    assert(tuning.apply(PlayerSettingsItem.subtitleReset.command(currentOffsetMs: tuning.subtitleOffsetMs)))
    assert(tuning.subtitleOffsetMs == 0)
    assert(!tuning.apply(PlayerSettingsItem.subtitleReset.command(currentOffsetMs: 0)), "Reset at zero is a no-op")

    // Sections: Subtitle Sync last, and only while offset is available.
    assert(PlayerSettingsItem.sections(availability: mpv) == [.speed, .scaling, .subtitleSync])
    assert(PlayerSettingsItem.sections(availability: avNoSidecar) == [.speed, .scaling])
    assert(PlayerSettingsItem.sections(availability: avSidecar) == [.speed, .scaling, .subtitleSync])

    // mpv Settings-tab rows: index ids, headers, checks, current offset on Reset.
    tuning = PlayerTuning(speed: 1.25, subtitleOffsetMs: 250, scaling: .zoom)
    let rows = PlayerSettingsItem.rows(tuning: tuning, availability: mpv)
    assert(rows.count == 8 + 3 + 5)
    assert(rows.map(\.id) == Array(0..<rows.count))
    assert(rows.compactMap(\.section) == ["Speed", "Scaling", "Subtitle Sync"])
    assert(rows[0].section == "Speed" && rows[8].section == "Scaling" && rows[11].section == "Subtitle Sync")
    assert(rows.filter(\.selected).map(\.title) == ["1.25×", "Zoom"])
    assert(InfoPanelModel.initialHighlight(rows: rows) == 4, "Opening Settings focuses the current speed")
    assert(rows.map(\.title) == ["0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×",
                                 "Fit", "Zoom", "Fill", "−500 ms", "−100 ms", "+100 ms", "+500 ms", "Reset"])
    assert(rows[15].trailingText == "+250 ms" && rows[14].trailingText == nil)
    func rowCommand(_ title: String) -> PlayerTuning.Command? {
        guard let row = rows.first(where: { $0.title == title }) else { return nil }
        return PlayerSettingsItem.command(rowID: row.id, tuning: tuning, availability: mpv)
    }
    assert(rowCommand("1.25×") == .speed(1.25))
    assert(rowCommand("Zoom") == .scaling(.zoom))
    assert(rowCommand("+500 ms") == .subtitleOffsetDelta(500))
    assert(rowCommand("Reset") == .subtitleOffsetDelta(-250))
    assert(PlayerSettingsItem.command(rowID: -1, tuning: tuning, availability: mpv) == nil)
    assert(PlayerSettingsItem.command(rowID: rows.count, tuning: tuning, availability: mpv) == nil)
    assert(PlayerSettingsItem.command(rowID: 11, tuning: tuning, availability: avNoSidecar) == nil,
           "No Subtitle Sync rows without an offset owner")
    assert(PlayerSettingsItem.rows(tuning: tuning, availability: .none).isEmpty)

    // Labels the menus show beside their titles.
    assert(PlayerSettingsItem.currentLabel(.speed, tuning: tuning) == "1.25×")
    assert(PlayerSettingsItem.currentLabel(.scaling, tuning: tuning) == "Zoom")
    assert(PlayerSettingsItem.currentLabel(.subtitleSync, tuning: tuning) == "+250 ms")
    assert(PlayerSettingsItem.offsetLabel(0) == "0 ms" && PlayerSettingsItem.offsetLabel(-1_500) == "−1500 ms")
    assert(PlayerSettingsItem.speedLabel(1) == "1×" && PlayerSettingsItem.speedLabel(0.3) == "0.3×")
    print("PASS: Settings tab rows and AVPlayer tuning menus map to PlayerTuning commands, including Reset")
}

private func runTouchScrubTests() {
    let ready = TransportChrome(userPaused: true, duration: 1000)
    func accepts(_ x: Double, _ y: Double, _ chrome: TransportChrome = ready, action: Bool = false) -> Bool {
        TouchScrubSession.canBegin(chrome: chrome, actionFocused: action, translationX: x, translationY: y)
    }
    assert(accepts(12, 0) && accepts(-12, 5))
    assert(!accepts(11, 0) && !accepts(12, 12) && !accepts(12, 30))
    assert(!accepts(.nan, 0) && !accepts(20, .infinity))
    assert(!accepts(20, 0, action: true))
    for path in [\TransportChrome.prebuffering, \.hasFailure, \.skipPillFocused,
                 \.navigation.stillWatching, \.navigation.infoPanel, \.navigation.engineMenu,
                 \.navigation.settingsSheet, \.navigation.playlist, \.navigation.scrubbing] {
        var chrome = ready
        chrome[keyPath: path] = true
        assert(!accepts(20, 0, chrome))
    }
    var playing = ready
    playing.userPaused = false
    assert(!accepts(20, 0, playing))
    for duration in [0.0, -1, .nan, .infinity] {
        var invalid = ready
        invalid.duration = duration
        assert(!accepts(20, 0, invalid))
    }
    print("PASS: paused pan gates, horizontal slop, vertical swipe ownership, button/list/Skip focus and invalid durations")

    // 1000 s item: 320 pt of touchpad travel is 100 s (600 s per 1920 pt).
    var scrub = TouchScrubSession()
    let token = scrub.begin(position: 100, duration: 1000)!
    assert(scrub.begin(position: 100, duration: 1000) == nil, "Cannot begin another live pan")
    assert(scrub.update(token: token, translationX: 320, velocityX: 0))
    assert(scrub.proposal?.position == 200 && scrub.proposal?.origin == 100)
    scrub.click(by: TransportScrub.clickSkipSeconds)
    assert(scrub.proposal?.position == 210)
    assert(scrub.update(token: token, translationX: 480, velocityX: 0))
    assert(scrub.proposal?.position == 260, "Later pan samples preserve click adjustments")
    assert(scrub.update(token: token, translationX: 320, velocityX: 2000))
    assert(scrub.proposal?.position == 310, "Fast drag uses the twofold gain")
    scrub.click(by: 5000)
    assert(scrub.proposal?.position == 1000)
    scrub.click(by: -5000)
    assert(scrub.proposal?.position == 0)
    let duringDrag = scrub.commit()!
    assert(duringDrag == TouchScrubSession.Commit(position: 0, requiresSeek: true))
    assert(scrub.proposal == nil && scrub.commit() == nil)
    print("PASS: pan preview, gain, click accumulation, clamps and mid-pan commit without per-sample seeks")

    let releaseToken = scrub.begin(position: 100, duration: 1000)!
    assert(scrub.update(token: releaseToken, translationX: 320, velocityX: 0))
    assert(scrub.release(token: releaseToken) == 200)
    assert(scrub.proposal?.isPanning == false)
    assert(!scrub.update(token: releaseToken, translationX: 640, velocityX: 0), "Released drag ignores late movement")
    assert(scrub.commit() == TouchScrubSession.Commit(position: 200, requiresSeek: false),
           "Select/Play after release does not seek twice")
    let second = scrub.begin(position: 100, duration: 1000)!
    assert(scrub.update(token: second, translationX: 320, velocityX: 0))
    assert(scrub.release(token: second) == 200)
    let third = scrub.begin(position: 200, duration: 1000)!
    assert(scrub.proposal?.origin == 100, "Consecutive drags retain the original pause point")
    assert(scrub.update(token: third, translationX: 320, velocityX: 0))
    assert(scrub.release(token: third) == 300)
    assert(scrub.cancel() == 100 && scrub.proposal == nil)
    assert(scrub.cancel() == nil)
    print("PASS: release stays pending for confirmation, seeks once, consecutive drags and Back restore the paused origin")

    for interruption in ["phone relative seek", "phone absolute seek", "new item", "error", "teardown"] {
        let old = scrub.begin(position: 100, duration: 1000)!
        assert(scrub.update(token: old, translationX: 50, velocityX: 0))
        scrub.discard()
        scrub.click(by: TransportScrub.clickSkipSeconds)
        assert(scrub.proposal == nil, "A pending click cannot revive a discarded gesture")
        assert(!scrub.update(token: old, translationX: 200, velocityX: 2000), interruption)
        assert(scrub.release(token: old) == nil && scrub.cancel() == nil, interruption)
        let fresh = scrub.begin(position: 115, duration: 1000)!
        assert(fresh != old)
        assert(!scrub.update(token: old, translationX: 200, velocityX: 2000))
        assert(scrub.release(token: old) == nil)
        assert(scrub.proposal?.position == 115, "A late callback cannot override the phone's winning seek")
        scrub.discard()
    }
    var invalid = TouchScrubSession()
    assert(invalid.begin(position: .nan, duration: 1000) == nil)
    assert(invalid.begin(position: 100, duration: 0) == nil)
    print("PASS: phone seeks, item/error/teardown discard touch transactions and fence old pan callbacks")
}
