import Foundation

/// Presentation only: identifiers remain unchanged when commands are dispatched.
enum RemoteControlPresentation {
    static func connectionTitle(connected: Bool, external: Bool, context: String, mediaKind: String, name: String) -> String {
        guard connected else { return "Not connected" }
        if external { return "Casting to \(name)" }
        switch context {
        case "player":
            switch mediaKind {
            case "audio": return "Listening on \(name)"
            case "image": return "Viewing on \(name)"
            default: return "Watching on \(name)"
            }
        case "browser", "browser_external": return "Browsing on \(name)"
        default: return "Connected to \(name)"
        }
    }

    static func isOff(_ track: MediaTrack) -> Bool {
        ["none", "off", "no", "-1"].contains(track.id.lowercased())
    }

    static func trackLabel(_ track: MediaTrack, in tracks: [MediaTrack], subtitle: Bool = false) -> String {
        if subtitle { return subtitleLabels(tracks)[track.id] ?? (isOff(track) ? "Off" : "Added") }
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

    static func language(_ value: String) -> String? {
        let key = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return languages[key] ?? localeLanguageNames[key]
    }

    // Full names only: arbitrary short words must not become language codes.
    private static let localeLanguageNames: [String: String] = {
        let locale = Locale(identifier: "en")
        return Locale.LanguageCode.isoLanguageCodes.reduce(into: [:]) { result, code in
            if let name = locale.localizedString(forLanguageCode: code.identifier), name.count >= 4 {
                result[name.lowercased()] = name
            }
        }
    }()

    static func readableSubtitleName(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let tokens = trimmed.components(separatedBy: CharacterSet(charactersIn: " ·•|/,")).filter { !$0.isEmpty }
        var seen = Set<String>()
        let names = tokens.compactMap(language).filter { seen.insert($0).inserted }
        let rest = tokens.filter { language($0) == nil }
        if !rest.isEmpty && rest.allSatisfy(opaque) { return names.first }
        if rest.isEmpty && !names.isEmpty { return names.joined(separator: " • ") }
        let segments = trimmed.components(separatedBy: CharacterSet(charactersIn: "·•|"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        if segments.count > 1 && segments.contains(where: { $0.count <= 3 && language($0) != nil }) {
            return segments.map { $0.count <= 3 ? language($0) ?? $0 : $0 }.joined(separator: " · ")
        }
        return trimmed
    }

    static func subtitleOrigin(_ track: MediaTrack) -> SubtitleOrigin {
        let external = track.type == "external_sub" || track.id.hasPrefix("external_") || track.id.contains("://")
        guard external else { return .builtIn }
        let host = URL(string: track.id)?.host?.lowercased() ?? ""
        return track.name.contains("OpenSubtitles #") || host == "strem.io" || host.hasSuffix(".strem.io") ? .addOn : .added
    }

    static func subtitleLinkLanguage(_ link: String) -> String? {
        guard let url = URL(string: link) else { return nil }
        let ambiguous: Set<String> = ["id", "it", "no", "he", "hi", "do", "is", "to"]
        return url.path.components(separatedBy: CharacterSet(charactersIn: "/._-"))
            .filter { !ambiguous.contains($0.lowercased()) }.compactMap(language).first
    }

    static func addedSubtitleLinkLabel(_ link: String) -> String? {
        guard let url = URL(string: link), url.scheme != nil else { return nil }
        if let fragment = url.fragment?.removingPercentEncoding, let name = readableSubtitleName(fragment) { return name }
        let file = (url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent) as NSString
        let stem = file.deletingPathExtension
        if stem.count >= 3 && stem.contains(where: \.isLetter) && !opaque(stem) { return stem }
        let host = url.host?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        let shortHost: String? = host.flatMap { host in
            guard host != "localhost", !host.hasSuffix(".localhost"), !host.contains(":"),
                  !host.allSatisfy({ $0.isNumber || $0 == "." }) else { return nil }
            let labels = host.split(separator: ".")
            let keep = labels.count >= 3 && labels.last!.count == 2 && labels[labels.count - 2].count <= 3 ? 3 : 2
            return labels.suffix(keep).joined(separator: ".")
        }
        let label = [subtitleLinkLanguage(link), shortHost].compactMap { $0 }.joined(separator: " · ")
        return label.isEmpty ? nil : label
    }

    static func subtitleLabels(_ tracks: [MediaTrack]) -> [String: String] {
        let real = tracks.filter { !isOff($0) }
        let bases = real.enumerated().map { index, track -> (String, String) in
            let fallback = subtitleOrigin(track) == .builtIn ? "Subtitle \(index + 1)" : addedSubtitleLinkLabel(track.id) ?? "Added"
            return (track.id, readableSubtitleName(track.name) ?? fallback)
        }
        let counts = Dictionary(grouping: bases, by: { $0.1 }).mapValues(\.count)
        var seen: [String: Int] = [:], labels: [String: String] = [:]
        for (id, base) in bases {
            seen[base, default: 0] += 1
            labels[id] = counts[base, default: 0] > 1 ? "\(base) \(seen[base]!)" : base
        }
        for off in tracks where isOff(off) { labels[off.id] = "Off" }
        return labels
    }

    static func subtitleRows(_ tracks: [MediaTrack]) -> [SubtitleRow] {
        let labels = subtitleLabels(tracks)
        return tracks.filter { !isOff($0) }.map { track in
            let tokens = track.name.components(separatedBy: CharacterSet(charactersIn: " ·•|/,()[]"))
            let origin = subtitleOrigin(track)
            return SubtitleRow(track: track, label: labels[track.id] ?? "Added", origin: origin,
                language: tokens.compactMap(language).first ?? (origin == .builtIn ? nil : subtitleLinkLanguage(track.id)))
        }
    }

    static func originFilters(_ rows: [SubtitleRow]) -> [(SubtitleOrigin, Int)] {
        SubtitleOrigin.allCases.compactMap { origin in
            let count = rows.filter { $0.origin == origin }.count
            return count > 0 ? (origin, count) : nil
        }
    }

    static func languageFilters(_ rows: [SubtitleRow], preferred: [String?]) -> [(String, Int)] {
        let counts = Dictionary(grouping: rows.compactMap(\.language), by: { $0 }).mapValues(\.count)
        var seen = Set<String>()
        let first = preferred.compactMap { $0 }.filter { counts[$0] != nil && seen.insert($0).inserted }
        let rest = counts.keys.filter { !seen.contains($0) }.sorted {
            counts[$0] == counts[$1] ? $0 < $1 : counts[$0]! > counts[$1]!
        }
        return (first + rest).map { ($0, counts[$0]!) }
    }

    static func filterSubtitles(_ rows: [SubtitleRow], origin: SubtitleOrigin?, language: String?, query: String) -> [SubtitleRow] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return rows.filter { row in
            (origin == nil || row.origin == origin) && (language == nil || row.language == language) &&
                (needle.isEmpty || row.label.localizedCaseInsensitiveContains(needle) || row.track.name.localizedCaseInsensitiveContains(needle))
        }
    }

    static func offsetText(_ milliseconds: Int64) -> String {
        guard milliseconds != 0 else { return "Synced" }
        let seconds = String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), abs(Double(milliseconds)) / 1000)
            .replacingOccurrences(of: #"0+$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\.$"#, with: "", options: .regularExpression)
        return (milliseconds > 0 ? "+" : "−") + seconds + " s"
    }

    static func offsetStepLabel(_ milliseconds: Int64) -> String {
        milliseconds == 0 ? "0" : String(offsetText(milliseconds).dropLast(2))
    }

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

    /// Both queue surfaces share this gate; selecting the playing item must never reload it.
    @discardableResult
    static func selectQueueItem(_ item: PlaylistEpisode, playlist: PlaylistUiState?, jump: (PlaylistEpisode) -> Void) -> Bool {
        guard let playlist, !isCurrent(item, playlist: playlist) else { return false }
        jump(item)
        return true
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

    /// The line under the now-playing title: the episode when known and not already in the title,
    /// "Image" for images, otherwise nothing (Android's RemoteTitlePolicy.subtitle).
    static func nowPlayingDetail(title: String?, kind: String, playlist: PlaylistUiState?) -> String? {
        if kind == "image" { return "Image" }
        guard let playlist else { return nil }
        let current = playlist.items.first { playlist.currentItemId != nil ? $0.itemId == playlist.currentItemId : $0.index == playlist.currentIndex }
        guard let current, let episode = current.episode else { return nil }
        let badge = current.season.map { "S\($0)E\(episode)" } ?? "Episode \(episode)"
        let compact = (title ?? "").uppercased().replacingOccurrences(of: " ", with: "")
        if let season = current.season, compact.contains(String(format: "S%02dE%02d", season, episode)) || compact.contains("S\(season)E\(episode)") { return nil }
        return badge
    }

    static func controlLevel(policy: ReceiverRemotePolicy, connected: Bool, externalProtocol: String?) -> String? {
        guard connected else { return nil }
        // iOS has no Android TV FullControlChannel; app D-pad permissions do not prove OS control.
        // Like Android, the ordinary cases carry no badge; only a receiver that offers almost
        // nothing is called out. Full control / Pair again come from the combined policy.
        if externalProtocol != nil || policy.isLegacy { return nil }
        if !policy.keys.isEmpty || policy.hasPointer || !policy.text.isEmpty { return nil }
        if !policy.transport.isEmpty || !policy.tracks.isEmpty || !policy.settings.isEmpty || !policy.queue.isEmpty || policy.volume != nil { return nil }
        return "Limited control"
    }

    static func volumeReportMatches(policy: ReceiverRemotePolicy, scope: String?) -> Bool {
        guard !policy.isLegacy, let scope else { return false }
        return scope == policy.volume?.scope || policy.volume?.scope == "system" && scope == "app" &&
            policy.volume?.fallbackScope == "app" && policy.hasVolume("relative_step")
    }

    static func volumeStatus(policy: ReceiverRemotePolicy, scope: String?, level: Double?, muted: Bool?) -> String? {
        guard volumeReportMatches(policy: policy, scope: scope) else { return nil }
        var result = scope == "app" ? "App volume" : "Volume"
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

/// Android PadGesturePolicy/Session, expressed in UIKit points (Android dp).
/// Pointer samples and the sleeping hold timer share a monotonic millisecond clock.
struct RemoteNavigationGesture {
    enum Direction: CaseIterable {
        case up, down, left, right
        var key: String {
            switch self {
            case .up: return "dpad_up"
            case .down: return "dpad_down"
            case .left: return "dpad_left"
            case .right: return "dpad_right"
            }
        }
        static func from(dx: Double, dy: Double) -> Self {
            if abs(dx) >= abs(dy) { return dx < 0 ? .left : .right }
            return dy < 0 ? .up : .down
        }
    }
    struct Step: Equatable {
        enum Kind { case tap, swipe, drag, hold }
        let key: String
        let kind: Kind
        let direction: Direction?
    }
    static let touchSlop = 20.0, dragStep = 56.0, holdSlop = 12.0
    static let tapTimeout: Int64 = 300, holdDelay: Int64 = 500, holdCadence: Int64 = 150
    static let maxHoldSteps = 100, maxStepsPerMove = 3
    private struct Stepping {
        var anchorX: Double, anchorY: Double, holdX: Double, holdY: Double
        var direction: Direction
        var nextHoldAt: Int64?
        var steps = 1
    }
    private enum Phase {
        case idle, tracking(Double, Double, Int64), stepping(Stepping)
    }
    private var phase: Phase = .idle
    private var now: Int64 = 0
    var holdDeadline: Int64? {
        guard case .stepping(let current) = phase, current.steps < Self.maxHoldSteps else { return nil }
        return current.nextHoldAt
    }
    mutating func down(x: Double, y: Double, at time: Int64) {
        observe(time)
        phase = .tracking(x, y, now)
    }
    mutating func move(x: Double, y: Double, at time: Int64) -> [Step] {
        observe(time)
        switch phase {
        case .idle: return []
        case .tracking(let x0, let y0, _):
            let dx = x - x0, dy = y - y0
            guard hypot(dx, dy) >= Self.touchSlop else { return [] }
            let direction = Direction.from(dx: dx, dy: dy)
            phase = .stepping(stepping(anchorX: x, anchorY: y, holdX: x, holdY: y, direction: direction))
            return [Step(key: direction.key, kind: .swipe, direction: direction)]
        case .stepping(var next):
            if hypot(x - next.holdX, y - next.holdY) > Self.holdSlop { next.nextHoldAt = nil }
            var steps: [Step] = []
            while steps.count < Self.maxStepsPerMove {
                let dx = x - next.anchorX, dy = y - next.anchorY
                let distance = max(abs(dx), abs(dy))
                if distance < Self.dragStep { break }
                let direction = Direction.from(dx: dx, dy: dy), fraction = Self.dragStep / distance
                next = stepping(anchorX: next.anchorX + dx * fraction, anchorY: next.anchorY + dy * fraction,
                                holdX: x, holdY: y, direction: direction)
                steps.append(Step(key: direction.key, kind: .drag, direction: direction))
            }
            if steps.count == Self.maxStepsPerMove { next.anchorX = x; next.anchorY = y }
            phase = .stepping(next)
            return steps
        }
    }
    mutating func up(x: Double, y: Double, at time: Int64) -> [Step] {
        observe(time)
        defer { cancel() }
        if case .tracking(let x0, let y0, let t0) = phase,
           hypot(x - x0, y - y0) < Self.touchSlop, now - t0 <= Self.tapTimeout {
            return [Step(key: "dpad_center", kind: .tap, direction: nil)]
        }
        return [] // Release travel never adds arrows.
    }
    mutating func tick(at time: Int64) -> [Step] {
        observe(time)
        guard case .stepping(var current) = phase, let deadline = current.nextHoldAt,
              now >= deadline, current.steps < Self.maxHoldSteps else { return [] }
        current.steps += 1
        current.nextHoldAt = now + Self.holdCadence // Never catch up after suspension.
        phase = .stepping(current)
        return [Step(key: current.direction.key, kind: .hold, direction: current.direction)]
    }
    mutating func cancel() { phase = .idle }
    private mutating func observe(_ time: Int64) { now = max(now, time) }
    private func stepping(anchorX: Double, anchorY: Double, holdX: Double, holdY: Double, direction: Direction) -> Stepping {
        Stepping(anchorX: anchorX, anchorY: anchorY, holdX: holdX, holdY: holdY,
                 direction: direction, nextHoldAt: now + Self.holdDelay)
    }
}


enum SubtitleOrigin: String, CaseIterable {
    case builtIn, addOn, added
    var title: String {
        switch self { case .builtIn: return "Built-in"; case .addOn: return "Add-ons"; case .added: return "Added" }
    }
    var tag: String {
        switch self { case .builtIn: return "built-in"; case .addOn: return "add-on"; case .added: return "added" }
    }
}
struct SubtitleRow: Identifiable {
    let track: MediaTrack
    let label: String
    let origin: SubtitleOrigin
    let language: String?
    var id: String { track.id }
}

struct RemoteVolumePill: Equatable {
    let percent: Int?
    let muted: Bool
    let exact: Bool
    init(absolute: Bool, wholeTV: Bool, appFallback: Bool, percent: Int?, muted: Bool?) {
        self.percent = percent.map { min(100, max(0, $0)) }
        self.muted = muted == true
        exact = absolute && !wholeTV && !appFallback
    }
    var text: String { muted ? "Muted" : percent.map(String.init) ?? (exact ? "—" : "") }
    var icon: String { muted ? "speaker.slash.fill" : "speaker.wave.2.fill" }
}

/// Monotonic milliseconds, latest value wins, including a final offer on release.
struct LatestVolumeThrottle {
    private var pending: Int?
    private var lastSent: Int64?
    var hasPending: Bool { pending != nil }
    mutating func offer(_ value: Int) { pending = min(100, max(0, value)) }
    mutating func poll(at now: Int64) -> Int? {
        guard let pending, lastSent.map({ now - $0 >= 125 }) ?? true else { return nil }
        self.pending = nil; lastSent = now
        return pending
    }
    func wait(at now: Int64) -> Int64 { lastSent.map { max(0, 125 - (now - $0)) } ?? 0 }
    mutating func clear() { pending = nil; lastSent = nil }
}

/// Incoming reports never move a dragging thumb. Pin release for a bounded, fixed 1.5 s.
struct RemoteVolumeDraft {
    private(set) var value: Double = 50
    private(set) var touched = false
    private(set) var dragging = false
    private(set) var pinDeadline: Int64?
    mutating func move(_ value: Double) {
        self.value = min(100, max(0, value)); touched = true; dragging = true; pinDeadline = nil
    }
    mutating func release(at now: Int64) { dragging = false; pinDeadline = now + 1500 }
    mutating func reconcile(_ percent: Int?, at now: Int64) {
        guard !dragging else { return }
        if let deadline = pinDeadline, now < deadline, percent != Int(value.rounded()) { return }
        pinDeadline = nil
        touched = percent != nil
        value = Double(percent ?? 50)
    }
    mutating func unpin() { pinDeadline = nil; dragging = false }
}
