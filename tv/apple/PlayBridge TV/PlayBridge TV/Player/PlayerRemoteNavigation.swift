import Foundation

/// Chrome a network remote key can see. Both engines report the flags they
/// own; playlist and still-watching live on the player screen.
struct PlayerChromeState: Equatable {
    var sessionMatches = false
    var stillWatching = false
    var subtitleMenu = false
    var audioMenu = false
    var engineMenu = false
    var settingsSheet = false
    var scrubbing = false
    var playlist = false
    var controlsVisible = false
}

/// Where a network Up or Down moves a highlighted row. The engine owns the
/// track menus. The player screen owns the playlist.
enum PlayerRemoteListOwner: Equatable {
    case stillWatching
    case subtitleMenu
    case audioMenu
    case engineMenu
    case playlist
    case none
}

/// A highlighted row. Movement clamps at both ends.
struct PlayerHighlightList: Equatable {
    var count: Int
    var index: Int

    func moving(_ delta: Int) -> PlayerHighlightList {
        guard count > 0 else { return self }
        let next = min(max(0, index + delta), count - 1)
        return PlayerHighlightList(count: count, index: next)
    }
}

enum PlayerRemoteEffect: Equatable {
    case ignored
    case dismissStillWatching
    case dismissSubtitleMenu
    case dismissAudioMenu
    case dismissEngineMenu
    case dismissSettingsSheet
    case dismissScrub
    case dismissPlaylist
    case dismissControls
    case exitPlayback
    case resumeStillWatching
    case moveHighlight(delta: Int)
    case activateHighlight
    case togglePlayback
    case seekBackward
    case seekForward
    case scrubBackward
    case scrubForward
    case commitScrub
    /// One relative app-volume step. Positive raises playback attenuation.
    case adjustAppVolume(steps: Int)
    case toggleAppMute
}

/// Live flags read from the engine that is on screen.
struct PlayerEngineChromeSnapshot: Equatable {
    var subtitleMenu = false
    var audioMenu = false
    var engineMenu = false
    var settingsSheet = false
    var scrubbing = false
    var controlsVisible = false

    func chrome(sessionMatches: Bool, stillWatching: Bool, playlist: Bool) -> PlayerChromeState {
        PlayerChromeState(
            sessionMatches: sessionMatches,
            stillWatching: stillWatching,
            subtitleMenu: subtitleMenu,
            audioMenu: audioMenu,
            engineMenu: engineMenu,
            settingsSheet: settingsSheet,
            scrubbing: scrubbing,
            playlist: playlist,
            controlsVisible: controlsVisible
        )
    }
}

/// Hooks the on-screen engine registers so the player screen can read and change
/// its chrome without posting a synthetic Siri Remote press.
final class PlayerEngineChrome {
    var snapshot: () -> PlayerEngineChromeSnapshot = { PlayerEngineChromeSnapshot() }
    var apply: (PlayerRemoteEffect) -> Bool = { _ in false }
}

enum PlayerPlaybackPress {
    case playPause, select
}

/// One trip through the player owner: finish progress, flush history, then the
/// screen dismiss that clears the play request and broadcasts idle.
enum PlayerSessionExit {
    static func perform(
        finishProgress: () -> Void,
        flushHistory: () -> Void,
        leaveToIdle: () -> Void
    ) {
        finishProgress()
        flushHistory()
        leaveToIdle()
    }
}

/// Defers the idle exit until the current remote-key delivery has finished, and
/// drops it when the generation or engine changed, or when an exit already ran.
final class PlayerRemoteExitGate {
    private(set) var liveEngine = PlaybackEngine.avplayer
    private(set) var liveGeneration = 0
    private(set) var didExit = false
    private var exitScheduled = false

    func note(engine: PlaybackEngine, generation: Int) {
        liveEngine = engine
        liveGeneration = generation
    }

    /// First caller wins. Later calls from a repeated key or the physical Menu
    /// leave the owner path idle.
    func beginExit() -> Bool {
        guard !didExit else { return false }
        didExit = true
        return true
    }

    func scheduleExit(
        engine: PlaybackEngine,
        generation: Int,
        perform: @escaping () -> Void
    ) {
        guard !exitScheduled, !didExit else { return }
        guard liveEngine == engine, liveGeneration == generation else { return }
        exitScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.liveEngine == engine, self.liveGeneration == generation else {
                self.exitScheduled = false
                return
            }
            guard self.beginExit() else { return }
            perform()
        }
    }
}

enum PlayerRemoteNavigation {
    /// Play/Pause always toggles (or commits a scrub), including inside a track
    /// menu. Select keeps activating the focused button while paused or in a menu.
    static func physicalPlaybackEffect(
        for press: PlayerPlaybackPress,
        userPaused: Bool,
        scrubbing: Bool,
        menuOpen: Bool
    ) -> PlayerRemoteEffect {
        if press == .select && menuOpen { return .ignored }
        if scrubbing { return .commitScrub }
        if press == .playPause || !userPaused { return .togglePlayback }
        return .ignored
    }

    private struct Level {
        let effect: PlayerRemoteEffect
        let isOpen: (PlayerChromeState) -> Bool
        let close: (inout PlayerChromeState) -> Void
    }

    private enum Direction {
        case up, down, center, left, right

        init?(key: String) {
            switch key {
            case "dpad_up": self = .up
            case "dpad_down": self = .down
            case "dpad_center": self = .center
            case "dpad_left": self = .left
            case "dpad_right": self = .right
            default: return nil
            }
        }
    }

    /// Highest overlay first. Back closes one level. An empty stack exits.
    private static let backLevels: [Level] = [
        Level(effect: .dismissStillWatching, isOpen: { $0.stillWatching }, close: { $0.stillWatching = false }),
        Level(effect: .dismissSubtitleMenu, isOpen: { $0.subtitleMenu }, close: { $0.subtitleMenu = false }),
        Level(effect: .dismissAudioMenu, isOpen: { $0.audioMenu }, close: { $0.audioMenu = false }),
        Level(effect: .dismissEngineMenu, isOpen: { $0.engineMenu }, close: { $0.engineMenu = false }),
        Level(effect: .dismissSettingsSheet, isOpen: { $0.settingsSheet }, close: { $0.settingsSheet = false }),
        Level(effect: .dismissScrub, isOpen: { $0.scrubbing }, close: { $0.scrubbing = false }),
        Level(effect: .dismissPlaylist, isOpen: { $0.playlist }, close: { $0.playlist = false }),
        Level(effect: .dismissControls, isOpen: { $0.controlsVisible }, close: { $0.controlsVisible = false }),
    ]

    static func effect(for key: String, chrome: PlayerChromeState) -> PlayerRemoteEffect {
        guard chrome.sessionMatches else { return .ignored }
        if key == "home" { return .exitPlayback }
        if key == "back" {
            for level in backLevels where level.isOpen(chrome) {
                return level.effect
            }
            return .exitPlayback
        }
        if let volume = appVolumeEffect(for: key, chrome: chrome) {
            return volume
        }
        guard let direction = Direction(key: key) else { return .ignored }
        return directional(direction, chrome: chrome)
    }

    /// Phone volume keys attenuate playback. They still do so while a menu,
    /// the playlist, a scrub, or the AVPlayer sheet is open. A still-watching
    /// prompt does not change app volume.
    private static func appVolumeEffect(
        for key: String,
        chrome: PlayerChromeState
    ) -> PlayerRemoteEffect? {
        let effect: PlayerRemoteEffect
        switch key {
        case "volume_up": effect = .adjustAppVolume(steps: 1)
        case "volume_down": effect = .adjustAppVolume(steps: -1)
        case "mute": effect = .toggleAppMute
        default: return nil
        }
        if chrome.stillWatching { return .ignored }
        return effect
    }

    static func closing(_ effect: PlayerRemoteEffect, in chrome: inout PlayerChromeState) {
        guard let level = backLevels.first(where: { $0.effect == effect }) else { return }
        level.close(&chrome)
    }

    /// Menu wins over the playlist when both are open, matching Back order.
    static func listOwner(in chrome: PlayerChromeState) -> PlayerRemoteListOwner {
        if chrome.stillWatching { return .stillWatching }
        if chrome.subtitleMenu { return .subtitleMenu }
        if chrome.audioMenu { return .audioMenu }
        if chrome.engineMenu { return .engineMenu }
        if chrome.playlist { return .playlist }
        return .none
    }

    /// Track-menu row ids. Off is `-1` and leads the list when the menu offers it.
    static func menuRowIDs(trackIDs: [Int], includeOff: Bool) -> [Int] {
        (includeOff ? [-1] : []) + trackIDs
    }

    private static func directional(
        _ direction: Direction,
        chrome: PlayerChromeState
    ) -> PlayerRemoteEffect {
        if chrome.stillWatching { return .resumeStillWatching }
        if chrome.settingsSheet { return .ignored }
        if chrome.subtitleMenu || chrome.audioMenu || chrome.engineMenu {
            return verticalList(direction)
        }
        if chrome.scrubbing {
            switch direction {
            case .left: return .scrubBackward
            case .right: return .scrubForward
            case .center: return .commitScrub
            case .up, .down: return .ignored
            }
        }
        if chrome.playlist { return verticalList(direction) }
        // Playing, paused, and pre-play, with the transport showing or hidden.
        // Up and Down step app volume. Menus, the playlist, scrub, and a
        // presented sheet never reach this branch.
        switch direction {
        case .up: return .adjustAppVolume(steps: 1)
        case .down: return .adjustAppVolume(steps: -1)
        case .center: return .togglePlayback
        case .left: return .seekBackward
        case .right: return .seekForward
        }
    }

    /// Vertical lists keep Left and Right as a 15-second seek.
    private static func verticalList(_ direction: Direction) -> PlayerRemoteEffect {
        switch direction {
        case .up: return .moveHighlight(delta: -1)
        case .down: return .moveHighlight(delta: 1)
        case .center: return .activateHighlight
        case .left: return .seekBackward
        case .right: return .seekForward
        }
    }
}
