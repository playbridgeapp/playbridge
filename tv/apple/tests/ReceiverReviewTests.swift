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
        runPlaybackFailureSessionTests()
        runPlayerControlsAuditTests()
        runPlayerSettingsTests()
        runPlayerTuningTests()
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
    // The Siri Play/Pause key never becomes a focused-button activation, even
    // when paused or inside a menu. A live scrub is committed before toggling.
    for paused in [false, true] {
        for menu in [false, true] {
            assert(PlayerRemoteNavigation.physicalPlaybackEffect(
                for: .playPause, userPaused: paused, scrubbing: false, menuOpen: menu) == .togglePlayback)
            assert(PlayerRemoteNavigation.physicalPlaybackEffect(
                for: .playPause, userPaused: paused, scrubbing: true, menuOpen: menu) == .commitScrub)
        }
    }
    assert(PlayerRemoteNavigation.physicalPlaybackEffect(
        for: .select, userPaused: true, scrubbing: false, menuOpen: false) == .ignored)
    assert(PlayerRemoteNavigation.physicalPlaybackEffect(
        for: .select, userPaused: false, scrubbing: false, menuOpen: false) == .togglePlayback)
    assert(PlayerRemoteNavigation.physicalPlaybackEffect(
        for: .select, userPaused: true, scrubbing: true, menuOpen: false) == .commitScrub)
    assert(PlayerRemoteNavigation.physicalPlaybackEffect(
        for: .select, userPaused: false, scrubbing: false, menuOpen: true) == .ignored)
    print("PASS: physical Play/Pause toggles while paused/in menus, commits scrub first, and Select keeps focus activation")

    // Both physical MPV Back and phone Back now enter this same policy. The
    // playlist belongs to the player screen and must precede the engine HUD.
    var chrome = PlayerChromeState(sessionMatches: true, subtitleMenu: true,
                                   scrubbing: true, playlist: true, controlsVisible: true)
    for expected: PlayerRemoteEffect in [.dismissSubtitleMenu, .dismissScrub, .dismissPlaylist, .dismissControls] {
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
        ("subtitle menu", \.subtitleMenu, .dismissSubtitleMenu),
        ("audio menu", \.audioMenu, .dismissAudioMenu),
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
            subtitleMenu: true,
            audioMenu: true,
            engineMenu: true,
            settingsSheet: true,
            scrubbing: true,
            playlist: true,
            controlsVisible: true
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
        subtitleMenu: true,
        audioMenu: true,
        engineMenu: true,
        settingsSheet: true,
        scrubbing: true,
        playlist: true,
        controlsVisible: true
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

    let menu = PlayerChromeState(sessionMatches: true, subtitleMenu: true)
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
    assert(effect("dpad_up", PlayerChromeState(sessionMatches: true, audioMenu: true)) == .moveHighlight(delta: -1))
    assert(effect("dpad_center", PlayerChromeState(sessionMatches: true, engineMenu: true)) == .activateHighlight)

    var menuAndPlaylist = PlayerChromeState(sessionMatches: true, subtitleMenu: true, playlist: true)
    assert(PlayerRemoteNavigation.listOwner(in: menuAndPlaylist) == .subtitleMenu)
    assert(effect("dpad_down", menuAndPlaylist) == .moveHighlight(delta: 1))
    assert(effect("dpad_center", menuAndPlaylist) == .activateHighlight)
    var playlistRows = PlayerHighlightList(count: 5, index: 2)
    let playlistBefore = playlistRows
    if PlayerRemoteNavigation.listOwner(in: menuAndPlaylist) == .playlist,
       case .moveHighlight(let delta) = effect("dpad_down", menuAndPlaylist) {
        playlistRows = playlistRows.moving(delta)
    }
    assert(playlistRows == playlistBefore, "An open menu keeps the playlist highlight where it is")
    menuAndPlaylist.subtitleMenu = false
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
    assert(effect("dpad_up", scrub) == .ignored)
    assert(effect("dpad_down", scrub) == .ignored)
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
    let staleMenu = PlayerChromeState(sessionMatches: false, subtitleMenu: true, playlist: true, controlsVisible: true)
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
    func effect(_ key: String, _ chrome: PlayerChromeState) -> PlayerRemoteEffect {
        PlayerRemoteNavigation.effect(for: key, chrome: chrome)
    }
    for count in [0, 1, 3] {
        let jumps = count >= 2
        let strip = PlayerChromeState(sessionMatches: true, controlsVisible: true,
                                      chapterCount: count, seekStripVisible: true)
        assert(effect("dpad_up", strip) == (jumps ? .chapterNext : .adjustAppVolume(steps: 1)))
        assert(effect("dpad_down", strip) == (jumps ? .chapterPrevious : .adjustAppVolume(steps: -1)))
        assert(effect("dpad_left", strip) == .seekBackward && effect("dpad_right", strip) == .seekForward)
        assert(effect("dpad_center", strip) == .togglePlayback)
        assert(effect("volume_up", strip) == .adjustAppVolume(steps: 1), "Phone volume keys stay volume")

        let shown = PlayerChromeState(sessionMatches: true, controlsVisible: true, chapterCount: count)
        assert(effect("dpad_up", shown) == .adjustAppVolume(steps: 1), "Controls without a seek keep volume")
        assert(effect("dpad_down", PlayerChromeState(sessionMatches: true, chapterCount: count))
               == .adjustAppVolume(steps: -1), "Hidden controls keep volume")

        let scrub = PlayerChromeState(sessionMatches: true, scrubbing: true, controlsVisible: true, chapterCount: count)
        assert(effect("dpad_up", scrub) == (jumps ? .chapterNext : .ignored), "Scrub Up moves the ghost")
        assert(effect("dpad_down", scrub) == (jumps ? .chapterPrevious : .ignored))
        assert(effect("dpad_center", scrub) == .commitScrub)

        var menu = strip
        menu.subtitleMenu = true
        assert(effect("dpad_up", menu) == .moveHighlight(delta: -1), "A menu keeps Up/Down")
        var playlist = strip
        playlist.playlist = true
        assert(effect("dpad_down", playlist) == .moveHighlight(delta: 1), "The playlist keeps Up/Down")
        var prompt = strip
        prompt.stillWatching = true
        assert(effect("dpad_up", prompt) == .resumeStillWatching)
        var sheet = strip
        sheet.settingsSheet = true
        assert(effect("dpad_up", sheet) == .ignored)
        var stale = strip
        stale.sessionMatches = false
        assert(effect("dpad_up", stale) == .ignored)
    }
    let snapshot = PlayerEngineChromeSnapshot(controlsVisible: true, chapterCount: 4, seekStripVisible: true)
    let chrome = snapshot.chrome(sessionMatches: true, stillWatching: false, playlist: false)
    assert(chrome.chapterCount == 4 && chrome.seekStripVisible, "The snapshot carries the chapter flags")
    print("PASS: Up/Down jump chapters only in the seek strip or scrub with two or more chapters")
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
    assert(proposal(0) == 500)
    assert(proposal(10) == 520 && proposal(-10) == 480)
    assert(proposal(10, velocity: 299) == 520 && proposal(10, velocity: -299) == 520)
    assert(proposal(10, velocity: 300) == 520)
    assert(proposal(10, velocity: 1150) == 550)
    assert(proposal(10, velocity: 2000) == 580 && proposal(10, velocity: -2000) == 580)
    assert(proposal(10, velocity: 10000) == 580, "Gain caps at 4x")
    assert(proposal(-1000) == 0 && proposal(1000) == 1000)
    assert(proposal(.greatestFiniteMagnitude) == 1000 && proposal(-.greatestFiniteMagnitude) == 0)
    for invalid in [Double.nan, .infinity, -.infinity] {
        assert(proposal(invalid) == 500)
        assert(proposal(10, velocity: invalid) == 520)
        assert(proposal(10, duration: invalid) == 500)
    }
    assert(proposal(10, duration: 0) == 500 && proposal(10, duration: -1) == 500)
    assert(TransportScrub.proposal(origin: 5, translationX: 50, velocityX: 0, duration: 10) == 10)
    assert(TransportScrub.proposal(origin: .nan, translationX: 0, velocityX: 0, duration: 10) == 0)
    print("PASS: transport click/remote/hold constants, scrub direction, velocity gain, clamps and invalid inputs")

    assert(InfoPanelModel.visibleTabs(chapterCount: 0, settingsAvailable: false) == [.info, .audio, .subtitles])
    assert(InfoPanelModel.visibleTabs(chapterCount: 1, settingsAvailable: false) == [.info, .audio, .subtitles])
    assert(InfoPanelModel.visibleTabs(chapterCount: 2, settingsAvailable: false) == [.info, .chapters, .audio, .subtitles])
    assert(InfoPanelModel.visibleTabs(chapterCount: 0, settingsAvailable: true) == [.info, .audio, .subtitles, .settings])
    assert(InfoPanelModel.visibleTabs(chapterCount: 2, settingsAvailable: true) == [.info, .chapters, .audio, .subtitles, .settings])
    assert(InfoPanelModel.visibleTabs(chapterCount: -1, settingsAvailable: false).contains(.audio))
    print("PASS: info-panel tab ordering, chapter threshold, optional settings and unconditional audio/subtitles")

    var stack = PlayerChromeState(sessionMatches: true, engineMenu: true, scrubbing: true,
                                  playlist: true, controlsVisible: true, chapterCount: 3,
                                  seekStripVisible: true, infoPanel: true)
    for expected: PlayerRemoteEffect in [.dismissInfoPanel, .dismissEngineMenu, .dismissScrub,
                                        .dismissPlaylist, .dismissControls, .exitPlayback] {
        let effect = PlayerRemoteNavigation.effect(for: "back", chrome: stack)
        assert(effect == expected)
        let before = stack
        PlayerRemoteNavigation.closing(effect, in: &stack)
        if effect == .dismissInfoPanel {
            assert(!stack.infoPanel && stack.engineMenu && stack.scrubbing && stack.playlist && stack.controlsVisible)
        }
        assert(stack.chapterCount == before.chapterCount && stack.seekStripVisible == before.seekStripVisible)
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
    let snapshot = PlayerEngineChromeSnapshot(controlsVisible: true, chapterCount: 4,
                                              seekStripVisible: true, infoPanel: true)
    let copied = snapshot.chrome(sessionMatches: true, stillWatching: false, playlist: false)
    assert(copied.infoPanel && copied.chapterCount == 4 && copied.seekStripVisible)
    // Existing subtitle/audio menus remain until the later view replacement.
    assert(PlayerRemoteNavigation.effect(for: "back", chrome: PlayerChromeState(
        sessionMatches: true, subtitleMenu: true, audioMenu: true, infoPanel: true)) == .dismissInfoPanel)
    print("PASS: info-panel Back/list priority, shared snapshot flags, paused-HUD exit levels, Home and stale keys")

    func route(_ up: Bool, _ chrome: TransportChrome) -> TransportKeyRoute {
        TransportKeyRoute.physicalVertical(up: up, chrome: chrome)
    }
    var chrome = TransportChrome()
    assert(route(false, chrome) == .openPanel && route(true, chrome) == .showTransport)
    // The old chapter flags still exist in the shared state until PR 3. None
    // of them, nor pause/hold-scrub/Skip focus, may steal Down from Info here.
    for visible in [false, true] {
        for paused in [false, true] {
            for scrubbing in [false, true] {
                for skipFocused in [false, true] {
                    chrome.navigation.controlsVisible = visible
                    chrome.navigation.scrubbing = scrubbing
                    chrome.navigation.chapterCount = 3
                    chrome.navigation.seekStripVisible = true
                    chrome.userPaused = paused
                    chrome.skipPillFocused = skipFocused
                    assert(route(false, chrome) == .openPanel)
                    assert(route(true, chrome) == (visible ? .moveFocus : .showTransport))
                }
            }
        }
    }
    chrome.navigation.infoPanel = true
    assert(route(false, chrome) == .moveInPanel && route(true, chrome) == .moveInPanel)
    chrome.panelTabBarFocused = true
    assert(route(true, chrome) == .closePanel && route(false, chrome) == .moveInPanel)
    chrome.navigation.infoPanel = false
    for path in [\PlayerChromeState.subtitleMenu, \.audioMenu, \.engineMenu, \.settingsSheet, \.playlist] {
        var menu = TransportChrome()
        menu.navigation[keyPath: path] = true
        // An open list owns Up and Down; Down only opens Info outside lists.
        assert(route(false, menu) == .moveFocus)
        assert(route(true, menu) == .moveFocus)
    }

    let ready = TransportChrome(userPaused: true, duration: 100)
    assert(TransportKeyRoute.canTouchScrub(chrome: ready))
    for duration in [0.0, -1, .nan, .infinity] {
        var invalid = ready
        invalid.duration = duration
        assert(!TransportKeyRoute.canTouchScrub(chrome: invalid))
    }
    for path in [\TransportChrome.prebuffering, \.hasFailure, \.skipPillFocused,
                 \.navigation.stillWatching, \.navigation.infoPanel, \.navigation.engineMenu,
                 \.navigation.playlist, \.navigation.subtitleMenu, \.navigation.audioMenu,
                 \.navigation.settingsSheet, \.navigation.scrubbing] {
        var blocked = ready
        blocked[keyPath: path] = true
        assert(!TransportKeyRoute.canTouchScrub(chrome: blocked))
    }
    var playing = ready
    playing.userPaused = false
    assert(!TransportKeyRoute.canTouchScrub(chrome: playing))
    print("PASS: Down always opens Info, Up keeps focus/reveal, panel tab movement and touch-scrub gates")

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
