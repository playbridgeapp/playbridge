import Foundation

enum PhoneVideoSizing: String, Codable, CaseIterable { case fit, fill }

/// Only presentation choices and language codes are persisted. Never persist
/// media URLs, track titles, credentials or a media-specific subtitle offset.
/// How subtitles look is the phone's `SubtitleStyleStore` style, shared with Settings.
struct PhonePlayerPreferences: Codable, Equatable {
    static let key = "phone_mpv_preferences_v1"
    static let speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]
    var sizing: PhoneVideoSizing = .fit
    var speed = 1.0
    var audioLanguage: String?
    var subtitlesEnabled = false
    var subtitleLanguage: String?

    func sanitized() -> Self {
        var copy = self
        copy.speed = Self.speeds.contains(speed) ? speed : 1
        copy.audioLanguage = Self.languageCode(audioLanguage)
        copy.subtitleLanguage = Self.languageCode(subtitleLanguage)
        return copy
    }
    static func load(from store: UserDefaults) -> Self {
        guard let data = store.data(forKey: key), let value = try? JSONDecoder().decode(Self.self, from: data) else { return Self() }
        return value.sanitized()
    }
    func save(to store: UserDefaults) {
        if let data = try? JSONEncoder().encode(sanitized()) { store.set(data, forKey: Self.key) }
    }

    /// Normalize common ISO-639-2 tags used in containers to the two-letter
    /// tags used by websites. Unknown/absent language never becomes a title.
    static func languageCode(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: "_", with: "-")
        guard value.count <= 35 else { return nil }
        let parts = value.split(separator: "-", omittingEmptySubsequences: false)
        guard let first = parts.first, (2...3).contains(first.count),
              parts.allSatisfy({ (2...8).contains($0.count) && $0.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) }),
              first.allSatisfy({ $0.isLetter }), first != "und", first != "mul", first != "zxx" else { return nil }
        return Locale.canonicalLanguageIdentifier(from: value).lowercased()
    }
    static func matches(_ candidate: String?, preference: String) -> Bool {
        guard let code = languageCode(candidate), let preferred = languageCode(preference) else { return false }
        return code == preferred || code.split(separator: "-").first == preferred.split(separator: "-").first
    }
    static func preferredTrack(_ tracks: [PhonePlaybackTrack], language: String?) -> PhonePlaybackTrack? {
        guard let language else { return tracks.first }
        return tracks.first { languageCode($0.language) == languageCode(language) }
            ?? tracks.first { matches($0.language, preference: language) }
    }
}

/// Settings › Phone player › When you leave the player (Android #530 without picture-in-picture).
enum PhoneLeaveChoice: String, CaseIterable, Identifiable {
    /// Back closes the player; Home and locking the phone pause.
    case pause
    /// Back leaves the video playing with a bar to return to it; Home and locking keep the
    /// sound going with lock-screen controls.
    case keepPlaying

    static let key = "phone_player_leave"
    var id: String { rawValue }
    var label: String { self == .pause ? "Pause" : "Keep playing" }

    static func load(from store: UserDefaults = .standard) -> Self {
        store.string(forKey: key).flatMap(Self.init(rawValue:)) ?? .pause
    }
}

struct PhonePlayerOptions: Equatable {
    var preferences = PhonePlayerPreferences()
    var subtitleDelay = 0.0
    /// Pinch to fill: crops to the screen for this view only; the saved sizing is unchanged.
    var displayFill = false
    /// Settings › Phone player › When you leave the player › Keep playing: Home and locking the
    /// phone keep the sound going (video decoding stops until the app returns).
    var keepPlayingInBackground = false

    var fills: Bool { preferences.sizing == .fill || displayFill }
}

/// Languages offered for the preferred audio and subtitle language, in the player and in Settings.
enum PhoneLanguageChoices {
    static let common = ["en", "hi", "es", "fr", "de", "pt", "it", "ja", "ko", "zh", "ar", "ru"]

    static func name(_ code: String) -> String { Locale.current.localizedString(forIdentifier: code) ?? code }

    /// "Audio: Source default · Subtitles: English", for the Settings list.
    static func summary(_ preferences: PhonePlayerPreferences) -> String {
        let audio = preferences.audioLanguage.map(name) ?? "Source default"
        let subtitles = preferences.subtitlesEnabled ? (preferences.subtitleLanguage.map(name) ?? "First available") : "Off"
        return "Audio: \(audio) · Subtitles: \(subtitles)"
    }
}
