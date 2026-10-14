import CoreFoundation
import Foundation

/// Identity of the engine that is trying to report. A late callback from the
/// previous engine or playlist item carries the identity it started with.
struct PlayerReportIdentity: Equatable {
    var engine: String
    var generation: Int
}

enum PlayerEndDecision: Equatable {
    /// Repeat the item that just ended, including when a playlist has a later item.
    case repeatCurrentItem
    /// Play the next playlist item, or leave playback when this item is the last.
    case advanceOrLeave
    /// The callback belongs to an engine or item that is no longer on screen.
    case ignored
}

/// Complete `player_settings` snapshot and the optional `context.engine` field.
enum PlayerPlaybackReport {
    static let requiredFields = ["type", "speed", "scaling", "audioBoost", "subtitleOffsetMs", "engine"]

    private static let allowedFields: Set<String> = [
        "type", "speed", "scaling", "audioBoost", "subtitleOffsetMs", "engine",
        "qualityMaxHeight", "currentVideoHeight", "isLive", "isSeekable",
        "speedAvailable", "scalingAvailable", "audioBoostAvailable", "qualityAvailable",
        "subtitleOffsetAvailable", "engineAvailable", "loopAvailable", "loop",
    ]

    /// Neutral values for settings this receiver does not implement, plus the
    /// real engine id and repeat-one flag. Availability flags are honest.
    static func settings(engine: String, loop: Bool) -> [String: Any] {
        [
            "type": "player_settings",
            "speed": 1,
            "scaling": "Fit",
            "audioBoost": false,
            "subtitleOffsetMs": 0,
            "engine": engine,
            "loop": loop,
            "speedAvailable": false,
            "scalingAvailable": false,
            "audioBoostAvailable": false,
            "qualityAvailable": false,
            "subtitleOffsetAvailable": false,
            "engineAvailable": true,
            "loopAvailable": true,
        ]
    }

    /// `engine` is sent only while the player surface is active and an engine is known.
    static func context(active: String, engine: String?) -> [String: Any] {
        var frame: [String: Any] = ["type": "context", "active": active]
        if active == "player", let engine, !engine.isEmpty {
            frame["engine"] = engine
        }
        return frame
    }

    static func isValidSnapshot(_ object: [String: Any]) -> Bool {
        guard Set(object.keys).isSubset(of: allowedFields) else { return false }
        guard object["type"] as? String == "player_settings" else { return false }
        guard requiredFields.allSatisfy({ object[$0] != nil }) else { return false }
        guard let speed = jsonNumber(object["speed"]), speed > 0 else { return false }
        guard let scaling = object["scaling"] as? String, !scaling.isEmpty else { return false }
        guard jsonBool(object["audioBoost"]) != nil else { return false }
        guard jsonInteger(object["subtitleOffsetMs"]) != nil else { return false }
        guard let engine = object["engine"] as? String, !engine.isEmpty else { return false }
        if object["loop"] != nil, jsonBool(object["loop"]) == nil { return false }
        for key in allowedFields where key.hasSuffix("Available") || key == "isLive" || key == "isSeekable" {
            if object[key] != nil, jsonBool(object[key]) == nil { return false }
        }
        return true
    }

    private static func jsonNumber(_ value: Any?) -> Double? {
        switch value {
        case let number as Double: return number
        case let number as Int: return Double(number)
        case let number as NSNumber where !isJSONBool(number): return number.doubleValue
        default: return nil
        }
    }

    private static func jsonInteger(_ value: Any?) -> Int? {
        switch value {
        case let number as Int: return number
        case let number as NSNumber where !isJSONBool(number):
            let value = number.doubleValue
            guard value.rounded() == value else { return nil }
            return number.intValue
        default: return nil
        }
    }

    private static func jsonBool(_ value: Any?) -> Bool? {
        switch value {
        case let flag as Bool: return flag
        case let number as NSNumber where isJSONBool(number): return number.boolValue
        default: return nil
        }
    }

    private static func isJSONBool(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }
}

/// Live engine, item generation, and repeat-one flag. Reports from any other
/// identity are dropped, so a torn-down player cannot publish itself as current.
final class PlayerSettingsReporter {
    private(set) var engine: String
    private(set) var generation: Int
    private(set) var loop: Bool

    init(engine: String, generation: Int, loop: Bool = false) {
        self.engine = engine
        self.generation = generation
        self.loop = loop
    }

    func note(engine: String, generation: Int) {
        self.engine = engine
        self.generation = generation
    }

    func settingsFrame() -> [String: Any] {
        PlayerPlaybackReport.settings(engine: engine, loop: loop)
    }

    /// Phone, transport-bar, or on-screen loop command for the live item.
    func setLoop(_ enabled: Bool, identity: PlayerReportIdentity) -> [String: Any]? {
        guard matches(identity) else { return nil }
        loop = enabled
        return settingsFrame()
    }

    /// Engine switch keeps repeat-one and reports the engine now on screen.
    func switchEngine(from source: PlayerReportIdentity, to target: String) -> [String: Any]? {
        guard matches(source), target != source.engine, !target.isEmpty else { return nil }
        engine = target
        return settingsFrame()
    }

    func resync(identity: PlayerReportIdentity) -> [String: Any]? {
        guard matches(identity) else { return nil }
        return settingsFrame()
    }

    /// Repeat-one wins over the next playlist item. A stale engine neither
    /// restarts nor advances.
    func endOfFile(identity: PlayerReportIdentity) -> (PlayerEndDecision, [String: Any]?) {
        guard matches(identity) else { return (.ignored, nil) }
        if loop {
            return (.repeatCurrentItem, settingsFrame())
        }
        return (.advanceOrLeave, nil)
    }

    private func matches(_ identity: PlayerReportIdentity) -> Bool {
        identity.engine == engine && identity.generation == generation
    }
}
