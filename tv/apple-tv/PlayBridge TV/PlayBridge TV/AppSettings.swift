//
//  AppSettings.swift
//  PlayBridge TV
//
//  Persisted user-facing preferences. Backed by UserDefaults (non-sensitive values only).
//  Use AppSettings.shared — it is an @Observable class so SwiftUI views tracking its
//  properties will rerender automatically.
//

import Foundation
import Observation

@Observable
final class AppSettings {
    static let shared = AppSettings()

    private let defaults = UserDefaults.standard
    private enum Key {
        static let playerModeOverride = "pb_player_mode_override"
    }

    // MARK: - Player Engine

    /// Controls which playback engine handles incoming streams.
    ///
    /// - `"auto"`:         Respect the `playerMode` field in the incoming `PlayPayload` (default).
    /// - `"internal"`:     Always use AVPlayer regardless of what the phone requests.
    /// - `"internal_vlc"`: Always use VLC (TVVLCKit) regardless of what the phone requests.
    var playerModeOverride: String {
        didSet { defaults.set(playerModeOverride, forKey: Key.playerModeOverride) }
    }

    // MARK: - Init

    private init() {
        self.playerModeOverride =
            defaults.string(forKey: Key.playerModeOverride) ?? "auto"
    }
}
