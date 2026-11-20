/// Reset per-item audio choices on a reused core; restore named track preferences after discovery.
enum MPVAudioPolicy {
    /// Pre-buffer mute and the user's app mute both silence this file. App mute
    /// stays on after pre-buffering ends. App volume is the separate mpv
    /// `volume` property (0–100) and is not rewritten here.
    static func muteValue(isPreBuffering: Bool, appMuted: Bool = false) -> String {
        (isPreBuffering || appMuted) ? "yes" : "no"
    }

    static func loadArguments(
        path: String,
        isPreBuffering: Bool,
        appMuted: Bool = false,
        sid: String? = nil
    ) -> [String] {
        let options = loadOptions(isPreBuffering: isPreBuffering, appMuted: appMuted, sid: sid)
            .map { "\($0.name)=\($0.value)" }.joined(separator: ",")
        // mpv >= 0.38 requires an index before per-file options. These options
        // take effect on the NEW file, avoiding a transient track change in the old one.
        return ["loadfile", path, "replace", "-1", options]
    }

    static func loadOptions(
        isPreBuffering: Bool,
        appMuted: Bool = false,
        sid: String? = nil
    ) -> [(name: String, value: String)] {
        // Numeric aid/sid values belong to one file: carrying an absent ID into the next
        // file can disable tracks. Never carry preplay mute into normal playback.
        var options: [(name: String, value: String)] = [
            ("aid", "auto"),
            ("mute", muteValue(isPreBuffering: isPreBuffering, appMuted: appMuted)),
        ]
        if let sid {
            options.append(("sid", sid))
        }
        return options
    }

    /// Computes per-item `alang`, `slang`, and per-file `sid` for mpv from saved session
    /// choices and the current item's cast preferences.
    static func languageOptions(
        savedAudioLanguage: String?,
        castAudioLanguage: String?,
        savedSubtitleLanguage: String?,
        castSubtitleLanguage: String?,
        subtitlesOff: Bool,
        deviceLanguages: [String] = []
    ) -> (alang: String, slang: String, sid: String) {
        let hasAudioPref = TrackLanguagePolicy.candidateTag(savedAudioLanguage) != nil
            || TrackLanguagePolicy.normalizedCode(castAudioLanguage) != nil
        let alang = hasAudioPref
            ? TrackLanguagePolicy.mpvLanguageList(
                savedLanguage: savedAudioLanguage,
                castLanguage: castAudioLanguage,
                deviceLanguages: deviceLanguages
            )
            : ""
        if subtitlesOff {
            return (alang: alang, slang: "", sid: "no")
        }
        let slang = TrackLanguagePolicy.mpvLanguageList(
            savedLanguage: savedSubtitleLanguage,
            castLanguage: castSubtitleLanguage
        )
        return (alang: alang, slang: slang, sid: "auto")
    }
}
