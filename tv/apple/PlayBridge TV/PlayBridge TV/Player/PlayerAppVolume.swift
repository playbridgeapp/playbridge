import CoreFoundation
import Foundation

/// PlayBridge attenuation for the item that is playing. 0 is silence and 1 is
/// unattenuated. This is not the TV's system volume and not HDMI-CEC.
enum PlayerAppVolume {
    static let scope = "app"
    static let step = 0.05
    static let defaultLevel = 1.0
    static let storedLevelKey = "playerAppVolumeLevel"

    /// The last app level, so the next cast starts where the user left it.
    /// Mute is not kept: a new cast never starts silent.
    static func storedLevel(in defaults: UserDefaults = .standard) -> Double {
        finiteUnit(defaults.object(forKey: storedLevelKey)) ?? defaultLevel
    }

    static func storeLevel(_ level: Double, in defaults: UserDefaults = .standard) {
        defaults.set(clamp(level), forKey: storedLevelKey)
    }

    /// Keep wire values on the 0.001 grid so repeated ±0.05 steps stay exact.
    static func clamp(_ level: Double) -> Double {
        let clamped = min(1, max(0, level))
        return (clamped * 1000).rounded() / 1000
    }

    /// A finite JSON number in [0, 1]. Values outside that range, and every
    /// scope other than `app`, are ignored.
    static func absoluteLevel(scope: String?, level: Any?) -> Double? {
        guard scope == Self.scope else { return nil }
        return finiteUnit(level)
    }

    static func absoluteLevel(in payload: [String: Any]) -> Double? {
        absoluteLevel(scope: payload["scope"] as? String, level: payload["level"])
    }

    /// mpv's `volume` property is 0–100. The stored level stays 0–1.
    static func mpvVolumeUnits(_ level: Double) -> Double {
        clamp(level) * 100
    }

    static func mpvVolumeString(level: Double) -> String {
        String(format: "%.3f", mpvVolumeUnits(level))
    }

    /// AVPlayer.volume is linear amplitude gain. Cube the normalized level to
    /// match mpv's software-volume curve; mute remains a separate flag.
    static func avPlayerVolume(_ level: Double) -> Float {
        Float(pow(clamp(level), 3))
    }

    static func volumeState(level: Double, muted: Bool) -> [String: Any] {
        [
            "type": "volume_state",
            "scope": scope,
            "level": clamp(level),
            "muted": muted,
        ]
    }

    static func isValidVolumeState(_ object: [String: Any]) -> Bool {
        guard Set(object.keys).isSubset(of: ["type", "scope", "level", "muted"]) else { return false }
        guard object["type"] as? String == "volume_state" else { return false }
        guard object["scope"] as? String == scope else { return false }
        let hasLevel = object["level"] != nil
        let hasMuted = object["muted"] != nil
        guard hasLevel || hasMuted else { return false }
        if hasLevel, finiteUnit(object["level"]) == nil { return false }
        if hasMuted, jsonBool(object["muted"]) == nil { return false }
        return true
    }

    /// On-screen label. The words "App volume" stay visible while the level or mute changes.
    static func indicatorText(level: Double, muted: Bool) -> String {
        if muted { return "App volume muted" }
        let percent = Int((clamp(level) * 100).rounded())
        return "App volume \(percent)%"
    }

    static func finiteUnit(_ value: Any?) -> Double? {
        let number: Double?
        switch value {
        case let value as NSNumber:
            // A JSON boolean bridges to NSNumber before Double. Mute is never inferred from it.
            if isJSONBool(value) { return nil }
            number = value.doubleValue
        case let value as Int:
            number = Double(value)
        case let value as Double:
            number = value
        default:
            number = nil
        }
        guard let number, number.isFinite, number >= 0, number <= 1 else { return nil }
        return clamp(number)
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

/// One app-volume level and mute flag for the playback session. An engine
/// switch or a later playlist item keeps both. A callback whose engine or item
/// generation does not match is dropped, and the level is unchanged.
final class PlayerAppVolumeController {
    private(set) var level: Double
    private(set) var muted: Bool
    private(set) var engine: String
    private(set) var generation: Int

    init(engine: String, generation: Int, level: Double = PlayerAppVolume.defaultLevel, muted: Bool = false) {
        self.engine = engine
        self.generation = generation
        self.level = PlayerAppVolume.clamp(level)
        self.muted = muted
    }

    /// The item or engine now on screen. The stored level and mute stay.
    func note(engine: String, generation: Int) {
        self.engine = engine
        self.generation = generation
    }

    func frame() -> [String: Any] {
        PlayerAppVolume.volumeState(level: level, muted: muted)
    }

    /// One relative step is ±0.05. Mute is left as it is. A step that is
    /// already at 0 or 1 does not emit a new report.
    func step(_ steps: Int, identity: PlayerReportIdentity) -> [String: Any]? {
        guard matches(identity), steps != 0 else { return nil }
        let next = PlayerAppVolume.clamp(level + Double(steps) * PlayerAppVolume.step)
        guard next != level else { return nil }
        level = next
        return frame()
    }

    /// Absolute app level. The mute flag is left as it is.
    func setAbsolute(_ level: Double, identity: PlayerReportIdentity) -> [String: Any]? {
        guard matches(identity), let accepted = PlayerAppVolume.finiteUnit(level) else { return nil }
        guard accepted != self.level else { return nil }
        self.level = accepted
        return frame()
    }

    /// Mute toggles without changing the stored level. Unmute uses that level.
    func toggleMute(identity: PlayerReportIdentity) -> [String: Any]? {
        guard matches(identity) else { return nil }
        muted.toggle()
        return frame()
    }

    func resync(identity: PlayerReportIdentity) -> [String: Any]? {
        guard matches(identity) else { return nil }
        return frame()
    }

    private func matches(_ identity: PlayerReportIdentity) -> Bool {
        identity.engine == engine && identity.generation == generation
    }
}
