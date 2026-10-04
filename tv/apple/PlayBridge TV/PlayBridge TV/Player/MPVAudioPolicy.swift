/// Reset per-item audio choices on a reused core; restore named track preferences after discovery.
enum MPVAudioPolicy {
    static func muteValue(isPreBuffering: Bool) -> String {
        isPreBuffering ? "yes" : "no"
    }

    static func loadArguments(path: String, isPreBuffering: Bool) -> [String] {
        let options = loadOptions(isPreBuffering: isPreBuffering)
            .map { "\($0.name)=\($0.value)" }.joined(separator: ",")
        // mpv >= 0.38 requires an index before per-file options. These options
        // take effect on the NEW file, avoiding a transient track change in the old one.
        return ["loadfile", path, "replace", "-1", options]
    }

    static func loadOptions(isPreBuffering: Bool) -> [(name: String, value: String)] {
        // Numeric aid values belong to one file: carrying an absent ID into the next
        // file can disable audio. Never carry preplay mute into normal playback.
        [("aid", "auto"), ("mute", muteValue(isPreBuffering: isPreBuffering))]
    }
}
