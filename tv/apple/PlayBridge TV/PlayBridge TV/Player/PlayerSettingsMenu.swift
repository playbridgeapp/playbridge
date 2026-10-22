import Foundation

/// One on-TV tuning choice. mpv shows these as Settings-tab rows and AVPlayer as
/// transport-bar menu actions. Both resolve to the same `PlayerTuning.Command`,
/// which only `PlayerView.applyTuning` applies, so the phone's `player_settings`
/// (and its chips) follow every on-TV change.
enum PlayerSettingsItem: Equatable {
    case speed(Double)
    case scaling(PlayerScaling)
    case subtitleStep(Int)
    case subtitleReset

    /// Subtitle Sync is last, so it can appear or disappear (AVPlayer sidecar
    /// overlay) without moving the rows or menus before it.
    enum Section: String, CaseIterable {
        case speed = "Speed", scaling = "Scaling", subtitleSync = "Subtitle Sync"

        var symbol: String {
            switch self {
            case .speed: return "speedometer"
            case .scaling: return "aspectratio"
            case .subtitleSync: return "timer"
            }
        }
    }

    /// The phone chips and the Android TV list.
    static let speeds: [Double] = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
    static let scalings: [PlayerScaling] = [.fit, .zoom, .fill]
    /// Positive delays the subtitle (`PlayerTuning`, positive = later).
    static let subtitleStepsMs = [-500, -100, 100, 500]

    static func items(in section: Section) -> [Self] {
        switch section {
        case .speed: return speeds.map { .speed($0) }
        case .scaling: return scalings.map { .scaling($0) }
        case .subtitleSync: return subtitleStepsMs.map { .subtitleStep($0) } + [.subtitleReset]
        }
    }

    static func sections(availability: PlayerSettingsAvailability) -> [Section] {
        Section.allCases.filter { section in
            switch section {
            case .speed: return availability.speed
            case .scaling: return availability.scaling
            case .subtitleSync: return availability.subtitleOffset
            }
        }
    }

    static func items(availability: PlayerSettingsAvailability) -> [Self] {
        sections(availability: availability).flatMap { items(in: $0) }
    }

    var section: Section {
        switch self {
        case .speed: return .speed
        case .scaling: return .scaling
        case .subtitleStep, .subtitleReset: return .subtitleSync
        }
    }

    /// Stable string id (AVPlayer `UIAction` identifiers and tests).
    var id: String {
        switch self {
        case .speed(let value): return "speed:" + PlayerTuning.mpvNumber(value)
        case .scaling(let mode): return "scaling:" + mode.rawValue
        case .subtitleStep(let delta): return "sub_offset:" + (delta > 0 ? "+" : "") + String(delta)
        case .subtitleReset: return "sub_offset:reset"
        }
    }

    init?(id: String) {
        guard let match = Self.allItems.first(where: { $0.id == id }) else { return nil }
        self = match
    }

    private static var allItems: [Self] { Section.allCases.flatMap { items(in: $0) } }

    /// Reset is the delta that brings the current offset back to zero.
    func command(currentOffsetMs: Int) -> PlayerTuning.Command {
        switch self {
        case .speed(let value): return .speed(value)
        case .scaling(let mode): return .scaling(mode)
        case .subtitleStep(let delta): return .subtitleOffsetDelta(delta)
        case .subtitleReset:
            return .subtitleOffsetDelta(currentOffsetMs == .min ? .max : -currentOffsetMs)
        }
    }

    func isSelected(_ tuning: PlayerTuning) -> Bool {
        switch self {
        case .speed(let value): return tuning.speed == value
        case .scaling(let mode): return tuning.scaling == mode
        case .subtitleStep, .subtitleReset: return false
        }
    }

    var title: String {
        switch self {
        case .speed(let value): return Self.speedLabel(value)
        case .scaling(let mode): return mode.rawValue
        case .subtitleStep(let delta): return Self.offsetLabel(delta)
        case .subtitleReset: return "Reset"
        }
    }

    static func speedLabel(_ value: Double) -> String {
        String(format: "%g", value) + "×"
    }

    /// Signed milliseconds with a typographic minus, e.g. `+250 ms`, `−100 ms`, `0 ms`.
    static func offsetLabel(_ ms: Int) -> String {
        if ms == 0 { return "0 ms" }
        return (ms > 0 ? "+" : "−") + String(ms.magnitude) + " ms"
    }

    /// The current value a section shows next to its title.
    static func currentLabel(_ section: Section, tuning: PlayerTuning) -> String {
        switch section {
        case .speed: return speedLabel(tuning.speed)
        case .scaling: return tuning.scaling.rawValue
        case .subtitleSync: return offsetLabel(tuning.subtitleOffsetMs)
        }
    }

    // MARK: Info panel rows (mpv)

    /// Row ids are indices into `items(availability:)`. The first row of each
    /// section carries its header; Reset shows the current offset.
    static func rows(tuning: PlayerTuning, availability: PlayerSettingsAvailability) -> [InfoPanelRow] {
        var previous: Section?
        return items(availability: availability).enumerated().map { index, item in
            defer { previous = item.section }
            return InfoPanelRow(id: index, title: item.title, selected: item.isSelected(tuning),
                trailingText: item == .subtitleReset ? offsetLabel(tuning.subtitleOffsetMs) : nil,
                section: previous == item.section ? nil : item.section.rawValue)
        }
    }

    static func command(rowID: Int, tuning: PlayerTuning,
                        availability: PlayerSettingsAvailability) -> PlayerTuning.Command? {
        let items = items(availability: availability)
        guard items.indices.contains(rowID) else { return nil }
        return items[rowID].command(currentOffsetMs: tuning.subtitleOffsetMs)
    }

    // MARK: Transport-bar menus (AVPlayer)

    static func command(id: String, currentOffsetMs: Int) -> PlayerTuning.Command? {
        PlayerSettingsItem(id: id)?.command(currentOffsetMs: currentOffsetMs)
    }
}

extension PlayerSettingsAvailability {
    var any: Bool { speed || scaling || subtitleOffset }
}
