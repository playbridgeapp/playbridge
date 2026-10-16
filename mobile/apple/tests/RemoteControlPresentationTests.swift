import Foundation

enum RemoteControlPresentationTests {
    static func run() {
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

        var gesture = RemoteNavigationGesture()
        precondition(gesture.update(x: 0, y: 0) == nil)
        precondition(gesture.end(x: 2, y: 1) == "dpad_center")
        precondition(gesture.update(x: 34, y: 5) == "dpad_right")
        precondition(gesture.update(x: 35, y: 7) == nil)
        precondition(gesture.update(x: 34, y: -30) == "dpad_up")
        precondition(gesture.end(x: 0, y: 0) == nil, "A swipe returning to its origin is not a tap")
        precondition(gesture.update(x: 20, y: 0) == nil)
        precondition(gesture.end(x: 0, y: 0) == nil)
        print("PASS: remote track labels, queue text/identity/progress, honest control badges, volume reports and navigation gestures")
    }
}
