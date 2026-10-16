import Foundation

/// Presentation only: identifiers remain unchanged when commands are dispatched.
enum RemoteControlPresentation {
    static func isOff(_ track: MediaTrack) -> Bool {
        ["none", "off", "no", "-1"].contains(track.id.lowercased())
    }

    static func trackLabel(_ track: MediaTrack, in tracks: [MediaTrack], subtitle: Bool = false) -> String {
        if subtitle && isOff(track) { return "Off" }
        let label = track.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let tokens = label.components(separatedBy: CharacterSet(charactersIn: " ·•|/,")).filter { !$0.isEmpty }
        let languages = tokens.compactMap(language)
        let remainder = tokens.filter { language($0) == nil }
        if !languages.isEmpty && remainder.allSatisfy(opaque) {
            var seen = Set<String>()
            return languages.filter { seen.insert($0).inserted }.joined(separator: " • ")
        }
        let first = tokens.first?.lowercased() ?? ""
        if !label.isEmpty && !opaque(first) && !["und", "mis", "zxx", "unknown", "undefined", "undetermined"].contains(first) {
            return label
        }
        let choices = subtitle ? tracks.filter { !isOff($0) } : tracks
        return "Track \((choices.firstIndex(where: { $0.id == track.id }) ?? 0) + 1)"
    }

    private static func opaque(_ value: String) -> Bool {
        let compact = value.replacingOccurrences(of: "-", with: "")
        return compact.isEmpty || compact.allSatisfy(\.isNumber) || compact.count >= 8 && compact.allSatisfy { $0.isHexDigit }
    }

    // Same language aliases as Android's track presentation; wire names have no separate language field.
    private static let languages: [String: String] = {
        let groups: [(String, [String])] = [
            ("English", ["english", "en", "eng"]),
            ("Spanish", ["spanish", "es", "spa", "esp"]),
            ("French", ["french", "fr", "fre", "fra"]),
            ("German", ["german", "de", "ger", "deu"]),
            ("Italian", ["italian", "it", "ita"]),
            ("Japanese", ["japanese", "ja", "jpn"]),
            ("Korean", ["korean", "ko", "kor"]),
            ("Chinese", ["chinese", "zh", "chi", "zho"]),
            ("Russian", ["russian", "ru", "rus"]),
            ("Portuguese", ["portuguese", "pt", "por"]),
            ("Portuguese (BR)", ["portuguese (br)", "pob", "pt-br", "ptbr"]),
            ("Arabic", ["arabic", "ar", "ara"]),
            ("Hindi", ["hindi", "hi", "hin"]),
            ("Dutch", ["dutch", "nl", "dut", "nld"]),
            ("Swedish", ["swedish", "sv", "swe"]),
            ("Turkish", ["turkish", "tr", "tur"]),
            ("Polish", ["polish", "pl", "pol"]),
            ("Romanian", ["romanian", "ro", "ron", "rum"]),
            ("Greek", ["greek", "el", "ell", "gre"]),
            ("Czech", ["czech", "cs", "cze", "ces"]),
            ("Danish", ["danish", "da", "dan"]),
            ("Hungarian", ["hungarian", "hu", "hun"]),
            ("Bulgarian", ["bulgarian", "bg", "bul"]),
            ("Slovenian", ["slovenian", "sl", "slv"]),
            ("Indonesian", ["indonesian", "id", "ind"]),
            ("Hebrew", ["hebrew", "he", "heb"]),
            ("Finnish", ["finnish", "fi", "fin"]),
            ("Serbian", ["serbian", "sr", "srp"]),
            ("Croatian", ["croatian", "hr", "hrv"]),
            ("Norwegian", ["norwegian", "no", "nor"]),
            ("Ukrainian", ["ukrainian", "uk", "ukr"]),
            ("Thai", ["thai", "th", "tha"]),
            ("Vietnamese", ["vietnamese", "vi", "vie"]),
            ("Persian", ["persian", "farsi", "fa", "per", "fas"]),
        ]
        return groups.reduce(into: [:]) { result, group in
            for token in group.1 { result[token] = group.0 }
        }
    }()

    private static func language(_ value: String) -> String? { languages[value.lowercased()] }

    static func episodeBadge(_ item: PlaylistEpisode) -> String {
        if let season = item.season, let episode = item.episode { return "S\(season)·E\(episode)" }
        if let episode = item.episode { return "E\(episode)" }
        return "\(item.index + 1)"
    }

    static func episodeTitle(_ item: PlaylistEpisode) -> String {
        let title = cleanTitle(item.title)
        if let season = item.season, let episode = item.episode,
           let regex = try? NSRegularExpression(pattern: #"(?i)(?<![A-Za-z0-9])S(\d+)[ ._·-]*E(\d+)(?![A-Za-z0-9])"#) {
            let ns = title as NSString
            for match in regex.matches(in: title, range: NSRange(location: 0, length: ns.length)) {
                guard Int(ns.substring(with: match.range(at: 1))) == season,
                      Int(ns.substring(with: match.range(at: 2))) == episode else { continue }
                let suffix = ns.substring(from: NSMaxRange(match.range)).trimmingCharacters(in: CharacterSet(charactersIn: " ._·-–—:|)]"))
                if !suffix.isEmpty { return suffix }
                let prefix = ns.substring(to: match.range.location).trimmingCharacters(in: CharacterSet(charactersIn: " ._·-–—:|(["))
                return prefix.isEmpty ? "Episode" : prefix
            }
        }
        return title.isEmpty ? "Episode \(item.episode ?? item.index + 1)" : title
    }

    private static func cleanTitle(_ raw: String) -> String {
        let extensionPattern = #"(?i)\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mp3|flac|ogg|wav|jpg|jpeg|png|gif|webp)$"#
        let releasePattern = #"(?i)(?:^|[ ._\-\[\]()])(?:480p|576p|720p|1080[pi]|1440p|2160p|4320p|[xh][ ._-]?26[45]|hevc|avc|web[ ._-]?dl|webrip|bluray|blu[ ._-]?ray|brrip|bdrip|hdtv|dvdrip|remux|aac[ ._-]?[257](?:[ ._]1)?|dts[ ._-]?hd)(?=$|[ ._\-\[\]()])"#
        let filename = raw.range(of: extensionPattern, options: .regularExpression) != nil || raw.contains("_") ||
            raw.filter { $0 == "." }.count >= 2 && raw.range(of: releasePattern, options: .regularExpression) != nil
        guard filename else { return raw.trimmingCharacters(in: .whitespacesAndNewlines) }
        var title = raw.replacingOccurrences(of: extensionPattern, with: "", options: .regularExpression)
        for _ in 0..<3 { title = title.replacingOccurrences(of: releasePattern, with: " ", options: .regularExpression) }
        title = title.replacingOccurrences(of: #"[._]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\[\s*]|\(\s*\)"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: " -[]()"))
        return title.isEmpty ? raw : title
    }

    static func isCurrent(_ item: PlaylistEpisode, playlist: PlaylistUiState) -> Bool {
        if let currentID = playlist.currentItemId { return item.itemId == currentID }
        return item.index == playlist.currentIndex
    }

    static func progress(_ item: PlaylistEpisode, playlist: PlaylistUiState, playback: TvPlaybackStatus?, live: Bool) -> Double? {
        guard isCurrent(item, playlist: playlist), !live, let playback, playback.durationMs > 0,
              ["playing", "paused", "buffering"].contains(playback.state) else { return nil }
        // If either source uses stable identity, both must agree before sharing progress.
        if playlist.playbackId != nil || playback.playbackId != nil {
            guard let id = playlist.playbackId, id == playback.playbackId else { return nil }
        }
        if playlist.currentItemId != nil || playback.currentItemId != nil {
            guard let id = item.itemId, id == playlist.currentItemId, id == playback.currentItemId else { return nil }
        }
        return min(1, max(0, Double(playback.positionMs) / Double(playback.durationMs)))
    }

    static func controlLevel(policy: ReceiverRemotePolicy, connected: Bool, externalProtocol: String?) -> String? {
        guard connected else { return nil }
        // iOS has no Android TV FullControlChannel; app D-pad permissions do not prove OS control.
        if let externalProtocol { return externalProtocol == "roku" ? "Device control" : "Media control" }
        if policy.isLegacy { return "App control" }
        if !policy.keys.isEmpty || policy.hasPointer || !policy.text.isEmpty { return "App control" }
        if !policy.transport.isEmpty || !policy.tracks.isEmpty || !policy.settings.isEmpty || !policy.queue.isEmpty || policy.volume != nil { return "Media control" }
        return "Limited control"
    }

    static func volumeStatus(policy: ReceiverRemotePolicy, scope: String?, level: Double?, muted: Bool?) -> String? {
        guard !policy.isLegacy, let scope,
              scope == policy.volume?.scope || policy.volume?.scope == "system" && scope == "app" && policy.volume?.fallbackScope == "app" && policy.hasVolume("relative_step") else { return nil }
        var result = scope == "app" ? "App volume" : "TV volume"
        var reported = false
        if policy.hasVolume("level_report"), let level, level.isFinite, (0...1).contains(level) {
            result += " \(Int((level * 100).rounded()))/100"
            reported = true
        }
        if policy.hasVolume("mute_report"), let muted {
            result += " · " + (muted ? "Muted" : "Unmuted")
            reported = true
        }
        return reported ? result : nil
    }
}

/// D-pad gestures, deliberately separate from browser/image pointer events.
struct RemoteNavigationGesture {
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var moved = false
    mutating func update(x: Double, y: Double) -> String? {
        if hypot(x, y) >= 12 { moved = true }
        let dx = x - anchorX, dy = y - anchorY
        guard max(abs(dx), abs(dy)) >= 32 else { return nil }
        anchorX = x; anchorY = y
        return abs(dx) > abs(dy) ? (dx > 0 ? "dpad_right" : "dpad_left") : (dy > 0 ? "dpad_down" : "dpad_up")
    }
    mutating func end(x: Double, y: Double) -> String? {
        defer { self = Self() }
        return !moved && hypot(x, y) < 12 ? "dpad_center" : nil
    }
}
