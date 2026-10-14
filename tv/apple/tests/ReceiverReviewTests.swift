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
        let audioTestPath = "https://example.test/video?parts=1,2"
        assert(MPVAudioPolicy.loadArguments(path: audioTestPath, isPreBuffering: false) ==
               ["loadfile", audioTestPath, "replace", "-1", "aid=auto,mute=no"])
        assert(MPVAudioPolicy.loadArguments(path: "/tmp/movie.mp4", isPreBuffering: true) ==
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
        runPlayerSettingsTests()
    }
}

private func pumpMain() {
    let until = Date().addingTimeInterval(0.05)
    while Date() < until {
        RunLoop.current.run(mode: .default, before: until)
    }
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
        assert(effect("dpad_up", chrome) == .ignored, "\(name) Up stays free")
        assert(effect("dpad_down", chrome) == .ignored, "\(name) Down stays free")
        assert(effect("dpad_center", chrome) == .togglePlayback, "\(name) Center toggles playback")
        assert(effect("volume_up", chrome) == .ignored, "\(name) volume stays ignored")
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
    for key in ["volume_up", "Back", "Home", ""] {
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
    assert(effect("back", scrub) == .dismissScrub)

    let sheet = PlayerChromeState(sessionMatches: true, settingsSheet: true, controlsVisible: true)
    for key in ["dpad_up", "dpad_down", "dpad_center", "dpad_left", "dpad_right"] {
        assert(effect(key, sheet) == .ignored, "\(key) stays out of a presented sheet")
    }
    assert(effect("back", sheet) == .dismissSettingsSheet)
    assert(effect("home", sheet) == .exitPlayback)

    var staleList = PlayerHighlightList(count: 4, index: 1)
    let staleMenu = PlayerChromeState(sessionMatches: false, subtitleMenu: true, playlist: true, controlsVisible: true)
    for key in ["dpad_up", "dpad_down", "dpad_center", "dpad_left", "dpad_right", "back", "home"] {
        let staleEffect = effect(key, staleMenu)
        assert(staleEffect == .ignored, "A stale \(key) is ignored")
        if case .moveHighlight(let delta) = staleEffect { staleList = staleList.moving(delta) }
    }
    assert(staleList.index == 1 && staleList.count == 4, "A stale key leaves the highlight where it is")

    assert(PlayerRemoteNavigation.menuRowIDs(trackIDs: [3, 5], includeOff: true) == [-1, 3, 5])
    assert(PlayerRemoteNavigation.menuRowIDs(trackIDs: [3, 5], includeOff: false) == [3, 5])
    print("PASS: Up/Down/center follow the open surface, and a stale session leaves the highlight")
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

    // 2. Volume and CompanionHints
    assert(descriptor["volume"] == nil, "Volume block must be omitted until Step 4c app volume")
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
    let expectedSettings = ["loop", "engine"]
    let expectedTracks = ["audio_select", "subtitle_select", "subtitle_url_add", "subtitle_resource_add"]
    let expectedQueue = ["replace", "append", "jump_index", "jump_item", "remove", "move", "clear"]
    let expectedReporting = ["status", "context", "tracks", "playlist_status", "player_settings"]

    for engineName in ["avplayer", "mpv"] {
        guard let engineCaps = engines[engineName] as? [String: Any] else {
            assertionFailure("Missing capabilities for engine \(engineName)")
            continue
        }
        assert(engineCaps["transport"] as? [String] == expectedTransport, "Transport operations match")
        assert(engineCaps["keys"] as? [String] == expectedKeys, "Key operations match")
        assert(engineCaps["settings"] as? [String] == expectedSettings, "Settings operations match")
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
