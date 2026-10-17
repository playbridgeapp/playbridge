import Foundation

enum RemoteControlPresentationTests {
    static func run() {
        headerAndInputSelection()
        queueSelection()
        let off = MediaTrack(id: "none", name: "0", selected: false)
        let numeric = MediaTrack(id: "1962663743", name: "1962663743", selected: true)
        let hex = MediaTrack(id: "2", name: "a0be3d142c", selected: false)
        let language = MediaTrack(id: "3", name: "1962663743 · eng", selected: false)
        let named = MediaTrack(id: "4", name: "English (SDH)", selected: false)
        let tracks = [off, numeric, hex, language, named]
        precondition(RemoteControlPresentation.trackLabel(off, in: tracks, subtitle: true) == "Off")
        precondition(RemoteControlPresentation.trackLabel(numeric, in: tracks, subtitle: true) == "Track 1")
        precondition(RemoteControlPresentation.trackLabel(hex, in: tracks, subtitle: true) == "Track 2")
        precondition(RemoteControlPresentation.trackLabel(language, in: tracks, subtitle: true) == "English")
        precondition(RemoteControlPresentation.trackLabel(named, in: tracks, subtitle: true) == "English (SDH)")
        precondition(RemoteControlPresentation.trackLabel(numeric, in: [numeric]) == "Track 1")
        for (name, expected) in [("1962663743 nld", "Dutch"), ("1962663743 vie", "Vietnamese"), ("1962663743 pt-br", "Portuguese (BR)")] {
            let track = MediaTrack(id: "language-test", name: name, selected: false)
            precondition(RemoteControlPresentation.trackLabel(track, in: [track], subtitle: true) == expected)
        }
        let und = MediaTrack(id: "5", name: "und", selected: false)
        precondition(RemoteControlPresentation.trackLabel(und, in: [und]) == "Track 1")

        let current = PlaylistEpisode(index: 0, title: "Carrie · S1E4 · DRAMATIC ARTS", itemId: "episode-4", season: 1, episode: 4)
        let next = PlaylistEpisode(index: 1, title: "Carrie.S01E05.PHYSICAL_EDUCATION.mkv", itemId: "episode-5", season: 1, episode: 5)
        precondition(RemoteControlPresentation.episodeBadge(current) == "S1·E4")
        precondition(RemoteControlPresentation.episodeTitle(current) == "DRAMATIC ARTS")
        precondition(RemoteControlPresentation.episodeTitle(next) == "PHYSICAL EDUCATION")
        let release = PlaylistEpisode(index: 0, title: "Carrie.S01E04.Dramatic_Arts.1080p.WEB-DL.x264.mkv", season: 1, episode: 4)
        precondition(RemoteControlPresentation.episodeTitle(release) == "Dramatic Arts")
        precondition(RemoteControlPresentation.episodeTitle(PlaylistEpisode(index: 0, title: "Carrie S01E04", season: 1, episode: 4)) == "Carrie")
        let mismatch = PlaylistEpisode(index: 0, title: "A show S2E4 — Keep me", season: 1, episode: 4)
        precondition(RemoteControlPresentation.episodeTitle(mismatch) == mismatch.title)
        precondition(RemoteControlPresentation.episodeTitle(PlaylistEpisode(index: 2, title: "")) == "Episode 3")
        precondition(RemoteControlPresentation.episodeBadge(PlaylistEpisode(index: 2, title: "Movie")) == "3")
        var queue = PlaylistUiState(currentIndex: 0, totalCount: 2, items: [current, next], playbackId: "play-4", currentItemId: "episode-4")
        var playback = TvPlaybackStatus(state: "playing", positionMs: 25, durationMs: 100, playbackId: "play-4", currentItemId: "episode-4")
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: false) == 0.25)
        precondition(RemoteControlPresentation.progress(next, playlist: queue, playback: playback, live: false) == nil)
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: true) == nil)
        playback.playbackId = "previous"
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: false) == nil)
        playback.playbackId = "play-4"; playback.currentItemId = "previous"
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: false) == nil)
        playback.currentItemId = nil
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: false) == nil)
        queue.currentItemId = "episode-5" // Stable identity wins over a stale index.
        precondition(!RemoteControlPresentation.isCurrent(current, playlist: queue))
        queue.playbackId = nil; queue.currentItemId = nil; playback.playbackId = nil
        playback.positionMs = 200
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: false) == 1)
        playback.durationMs = 0
        precondition(RemoteControlPresentation.progress(current, playlist: queue, playback: playback, live: false) == nil)

        var policy = ReceiverRemotePolicy(isLegacy: false, state: ReceiverRemoteState(connected: true, context: "player"))
        precondition(RemoteControlPresentation.controlLevel(policy: policy, connected: true, externalProtocol: nil) == "Limited control")
        policy.transport = ["toggle"]
        precondition(RemoteControlPresentation.controlLevel(policy: policy, connected: true, externalProtocol: nil) == "Media control")
        policy.keys = ["dpad_center", "home"]
        precondition(RemoteControlPresentation.controlLevel(policy: policy, connected: true, externalProtocol: nil) == "App control", "App keys cannot prove Android OS Full control")
        precondition(RemoteControlPresentation.controlLevel(policy: policy, connected: false, externalProtocol: nil) == nil)
        precondition(RemoteControlPresentation.controlLevel(policy: policy, connected: true, externalProtocol: "roku") == "Device control")
        precondition(RemoteControlPresentation.controlLevel(policy: policy, connected: true, externalProtocol: "google_cast") == "Media control")
        policy.volume = ReceiverVolume(scope: "system", operations: ["relative_step", "level_report", "mute_report"], step: nil, fallbackScope: "app")
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "system", level: 0.19, muted: false) == "TV volume 19/100 · Unmuted")
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "app", level: 0.4, muted: true) == "App volume 40/100 · Muted")
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "other", level: 0.4, muted: true) == nil)
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "system", level: .nan, muted: nil) == nil)
        policy.volume = ReceiverVolume(scope: "app", operations: ["relative_step"], step: nil, fallbackScope: nil)
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "app", level: 0.19, muted: false) == nil)
        policy.volume = ReceiverVolume(scope: "app", operations: ["level_report"], step: nil, fallbackScope: nil)
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "app", level: 0.19, muted: false) == "App volume 19/100")
        precondition(RemoteControlPresentation.volumeStatus(policy: policy, scope: "system", level: 0.19, muted: false) == nil)

        print("PASS: remote track labels, queue text/identity/progress, honest control badges and volume reports")
    }

    private static func headerAndInputSelection() {
        let name = "Living Room"
        for (context, kind, expected) in [
            ("player", "video", "Watching on Living Room"),
            ("player", "audio", "Listening on Living Room"),
            ("player", "image", "Viewing on Living Room"),
            ("player", "future", "Watching on Living Room"),
            ("browser", "audio", "Browsing on Living Room"),
            ("browser_external", "image", "Browsing on Living Room"),
            ("idle", "audio", "Connected to Living Room"),
            ("future", "image", "Connected to Living Room"),
        ] {
            precondition(RemoteControlPresentation.connectionTitle(connected: true, external: false,
                context: context, mediaKind: kind, name: name) == expected)
            precondition(RemoteControlPresentation.connectionTitle(connected: false, external: false,
                context: context, mediaKind: kind, name: name) == "Not connected")
            precondition(RemoteControlPresentation.connectionTitle(connected: true, external: true,
                context: context, mediaKind: kind, name: name) == "Casting to Living Room")
        }

        var native = ReceiverRemotePolicy(isLegacy: false, state: ReceiverRemoteState(connected: true, context: "player"))
        func layout(_ context: String = "player", image: Bool = false) -> RemoteInputLayout {
            RemoteInputLayout(policy: native, context: context, image: image, external: false, externalProtocol: nil)
        }
        var preferences = RemotePadPreferences()
        precondition(layout().surface(in: "player", preferences: preferences) == .none)
        native.keys = ["home"] // Navigation only appears for a D-pad key, as before.
        precondition(!layout().hasNavigation)
        native.keys.insert("dpad_center")
        precondition(layout().surface(in: "player", preferences: preferences) == .swipe)
        precondition(!layout().hasKeyboard && !layout().canSwitchPadPointer)
        preferences.toggleButtons(in: "player")
        precondition(layout().surface(in: "player", preferences: preferences) == .buttons)
        precondition(layout("browser").surface(in: "browser", preferences: preferences) == .swipe)
        native.pointer = ["move", "click"]
        native.text = ["delete", "enter"]
        precondition(!layout("browser").hasKeyboard, "Keyboard entry requires replace, not delete/enter alone")
        native.text.insert("replace")
        precondition(layout("browser").hasKeyboard && layout("browser").canSwitchPadPointer)
        precondition(layout("browser").surface(in: "browser", preferences: preferences) == .pointer)
        precondition(layout().surface(in: "player", preferences: preferences) == .pointer, "A native pointer capability is presented in any context")
        preferences.setPointer(false, in: "browser")
        preferences.toggleButtons(in: "browser")
        precondition(layout("browser").surface(in: "browser", preferences: preferences) == .buttons)
        preferences.setPointer(false, in: "player")
        precondition(layout().surface(in: "player", preferences: preferences) == .buttons)
        preferences.toggleButtons(in: "browser")
        precondition(layout().surface(in: "player", preferences: preferences) == .buttons, "Pad choices are separate per context")
        native.keys = [] // Saved preference must not keep a revoked control alive.
        precondition(layout().surface(in: "player", preferences: preferences) == .pointer)
        native.pointer = []
        native.text = []
        precondition(layout().surface(in: "player", preferences: preferences) == .none && !layout().hasKeyboard)
        native.keys = ["dpad_center"]
        precondition(layout().surface(in: "player", preferences: preferences) == .buttons, "Restoration retains the user's choice")
        preferences = RemotePadPreferences() // Destination changes reset choices.
        precondition(layout().surface(in: "player", preferences: preferences) == .swipe)

        let legacy = ReceiverRemotePolicy(isLegacy: true, state: ReceiverRemoteState())
        for context in ["player", "browser", "idle"] {
            for protocolID in ["roku", "google_cast", "dlna", "airplay"] {
                let external = RemoteInputLayout(policy: legacy, context: context, image: false, external: true, externalProtocol: protocolID)
                precondition(!external.hasPointer && !external.hasKeyboard)
                precondition(external.surface(in: context, preferences: preferences) == (protocolID == "roku" ? .swipe : .none))
            }
        }
        let browser = RemoteInputLayout(policy: legacy, context: "browser", image: false, external: false, externalProtocol: nil)
        precondition(browser.hasKeyboard && browser.surface(in: "browser", preferences: preferences) == .pointer)
        let video = RemoteInputLayout(policy: legacy, context: "player", image: false, external: false, externalProtocol: nil)
        precondition(!video.hasKeyboard && video.surface(in: "player", preferences: preferences) == .swipe)
        let image = RemoteInputLayout(policy: legacy, context: "player", image: true, external: false, externalProtocol: nil)
        precondition(image.surface(in: "player", preferences: preferences) == .pointer)
        print("PASS: header context/media/external titles; policy-gated pad/keyboard selection, context preferences, revocation/restoration and external receiver eligibility")
    }


    private static func queueSelection() {
        let current = PlaylistEpisode(index: 0, title: "Current", itemId: "item-a")
        let next = PlaylistEpisode(index: 1, title: "Next", itemId: "item-b")
        var playlist = PlaylistUiState(currentIndex: 0, totalCount: 2, items: [current, next], currentItemId: "item-a")
        var sends: [String] = []
        func select(_ item: PlaylistEpisode, _ queue: PlaylistUiState?) -> Bool {
            RemoteControlPresentation.selectQueueItem(item, playlist: queue) { sends.append($0.id) }
        }
        // Repeated taps, from either the card or sheet, cannot restart current playback.
        precondition(!select(current, playlist) && !select(current, playlist) && sends.isEmpty)
        precondition(select(next, playlist) && sends == ["item-b"])
        playlist.currentItemId = "item-b" // Latest identity wins even if the index is stale.
        precondition(!select(next, playlist) && sends == ["item-b"])
        precondition(select(current, playlist) && sends == ["item-b", "item-a"])
        precondition(!select(next, nil) && sends.count == 2)
        playlist.currentItemId = nil // Legacy queues still identify the current row by index.
        precondition(!select(current, playlist) && sends.count == 2)
        precondition(select(next, playlist) && sends.count == 3)
        print("PASS: current queue item is a no-op; other selections dispatch once; live stable identity and legacy index gates")
    }

}
