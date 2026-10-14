/// Reset per-item audio choices on a reused core; restore named track preferences after discovery.
enum MPVAudioPolicy {
    /// Pre-buffer mute and the user's app mute both silence this file. App mute
    /// stays on after pre-buffering ends. App volume is the separate mpv
    /// `volume` property (0–100) and is not rewritten here.
    static func muteValue(isPreBuffering: Bool, appMuted: Bool = false) -> String {
        (isPreBuffering || appMuted) ? "yes" : "no"
    }

    static func loadArguments(path: String, isPreBuffering: Bool, appMuted: Bool = false) -> [String] {
        let options = loadOptions(isPreBuffering: isPreBuffering, appMuted: appMuted)
            .map { "\($0.name)=\($0.value)" }.joined(separator: ",")
        // mpv >= 0.38 requires an index before per-file options. These options
        // take effect on the NEW file, avoiding a transient track change in the old one.
        return ["loadfile", path, "replace", "-1", options]
    }

    static func loadOptions(isPreBuffering: Bool, appMuted: Bool = false) -> [(name: String, value: String)] {
        // Numeric aid values belong to one file: carrying an absent ID into the next
        // file can disable audio. Never carry preplay mute into normal playback.
        [("aid", "auto"), ("mute", muteValue(isPreBuffering: isPreBuffering, appMuted: appMuted))]
    }
}
