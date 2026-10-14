import Foundation

/// Chrome a network Back or Home key can see. Both engines report the flags they
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

/// Hooks the on-screen engine registers so the player screen can read and dismiss
/// its chrome without posting a synthetic Siri Remote press.
final class PlayerEngineChrome {
    var snapshot: () -> PlayerEngineChromeSnapshot = { PlayerEngineChromeSnapshot() }
    var applyDismiss: (PlayerRemoteEffect) -> Bool = { _ in false }
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
    private struct Level {
        let effect: PlayerRemoteEffect
        let isOpen: (PlayerChromeState) -> Bool
        let close: (inout PlayerChromeState) -> Void
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
        guard key == "back" else { return .ignored }
        for level in backLevels where level.isOpen(chrome) {
            return level.effect
        }
        return .exitPlayback
    }

    static func closing(_ effect: PlayerRemoteEffect, in chrome: inout PlayerChromeState) {
        guard let level = backLevels.first(where: { $0.effect == effect }) else { return }
        level.close(&chrome)
    }
}
