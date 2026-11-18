import SwiftUI

/// The player's ⋮ More sheet, grouped like Android's: Playback, Picture, Player.
struct PhonePlayerMoreSheet: View {
    @ObservedObject var session: PlaybackSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Playback") {
                    Picker("Speed", selection: preference(\.speed)) {
                        ForEach(PhonePlayerPreferences.speeds, id: \.self) { Text(PhonePlayerChrome.speedLabel($0)).tag($0) }
                    }
                    .accessibilityIdentifier("phone-player-speed-picker")
                }
                Section {
                    Picker("When you leave", selection: Binding(
                        get: { session.keepPlayingInBackground ? PhoneLeaveChoice.keepPlaying : .pause },
                        set: { session.setKeepPlayingInBackground($0 == .keepPlaying) })) {
                        ForEach(PhoneLeaveChoice.allCases) { Text($0.label).tag($0) }
                    }
                    .accessibilityIdentifier("phone-player-leave")
                } footer: {
                    Text("For this video. Keep playing goes on when you go back, go home or lock the phone; Settings › Phone player sets the default.")
                }
                Section("Picture") {
                    Picker("Video sizing", selection: preference(\.sizing)) {
                        Text("Fit — show the entire picture").tag(PhoneVideoSizing.fit)
                        Text("Fill — crop to fill the screen").tag(PhoneVideoSizing.fill)
                    }
                    .accessibilityIdentifier("phone-player-sizing")
                }
                Section {
                    Picker("Player engine", selection: Binding(get: { session.engineKind }, set: { session.switchEngine(to: $0) })) {
                        Text("mpv").tag(PhonePlaybackEngineKind.mpv)
                        Text("AVPlayer").tag(PhonePlaybackEngineKind.avPlayer)
                    }
                    .disabled(session.retrying)
                    .accessibilityIdentifier("phone-player-engine")
                } header: {
                    Text("Player")
                } footer: {
                    Text("Switching reopens the video at the current position. Try AVPlayer when a stream won’t seek or shows no picture in mpv. AVPlayer can’t play MKV files or styled (ASS) subtitles. The choice lasts until you close the player; the next video you open starts with mpv.")
                }
            }
            .navigationTitle("More")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func preference<Value>(_ key: WritableKeyPath<PhonePlayerPreferences, Value>) -> Binding<Value> {
        Binding(get: { session.preferences[keyPath: key] }, set: { value in session.updatePreferences { $0[keyPath: key] = value } })
    }
}

/// Audio and subtitles in one sheet: tracks, preferred languages, subtitle timing and style.
struct PhonePlayerTracksSheet: View {
    @ObservedObject var session: PlaybackSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Audio") {
                    if !session.mpvState.audioTracks.isEmpty {
                        Picker("Current track", selection: Binding(get: { session.mpvState.selectedAudio ?? session.mpvState.audioTracks.first?.id ?? -1 }, set: { session.selectAudio($0) })) {
                            ForEach(session.mpvState.audioTracks) { Text($0.label).tag($0.id) }
                        }
                    }
                    Picker("Preferred language", selection: Binding(get: { session.preferences.audioLanguage ?? "auto" }, set: { value in
                        session.setPreferredAudioLanguage(value == "auto" ? nil : value)
                    })) {
                        Text("Source default").tag("auto")
                        languageChoices
                    }
                }
                Section("Subtitles") {
                    if !session.mpvState.subtitleTracks.isEmpty || !session.websiteSubtitleTracks.isEmpty {
                        Picker("Current track", selection: Binding(get: { subtitleSelection }, set: { value in
                            if value.hasPrefix("embedded:"), let id = Int(value.dropFirst(9)) { session.selectEmbeddedSubtitle(id) }
                            else if value.hasPrefix("website:"), let index = Int(value.dropFirst(8)) { session.selectWebsiteSubtitle(index) }
                            else { session.selectEmbeddedSubtitle(nil) }
                        })) {
                            Text("Off").tag("off")
                            ForEach(session.mpvState.subtitleTracks) { Text($0.label).tag("embedded:\($0.id)") }
                            ForEach(session.websiteSubtitleTracks.indices, id: \.self) { index in
                                Text(session.websiteSubtitleTracks[index].label).tag("website:\(index)")
                            }
                        }
                    }
                    Picker("Preferred language", selection: Binding(get: {
                        session.preferences.subtitlesEnabled ? (session.preferences.subtitleLanguage ?? "auto") : "off"
                    }, set: { value in
                        session.setPreferredSubtitleLanguage(value == "off" || value == "auto" ? nil : value, enabled: value != "off")
                    })) {
                        Text("Off").tag("off")
                        Text("First available").tag("auto")
                        languageChoices
                    }
                    HStack {
                        Text("Timing offset")
                        Spacer()
                        Text(String(format: "%+.2f s", session.subtitleDelay)).monospacedDigit()
                    }
                    Slider(value: Binding(get: { session.subtitleDelay }, set: { session.setSubtitleDelay($0) }), in: -10...10, step: 0.25)
                        .accessibilityLabel("Subtitle timing offset")
                        .accessibilityValue(String(format: "%+.2f seconds", session.subtitleDelay))
                    Button("Reset timing") { session.setSubtitleDelay(0) }
                    Text("Positive delays subtitles; negative advances them. Timing resets for a new episode.").font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Text("Subtitle style").font(Theme.font(size: 17, weight: .semibold))
                } footer: {
                    Text("Also in Settings → Audio & subtitles. Image-based subtitles can't be restyled.")
                }
                PhoneSubtitleStyleSections()
            }
            .navigationTitle("Audio & subtitles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private var subtitleSelection: String {
        if let index = session.websiteSubtitleIndex { return "website:\(index)" }
        if let id = session.mpvState.selectedSubtitle { return "embedded:\(id)" }
        return "off"
    }
    private var languages: [String] {
        let tags = session.mpvState.audioTracks.map(\.language) + session.mpvState.subtitleTracks.map(\.language)
            + session.websiteSubtitleLanguages + [session.preferences.audioLanguage, session.preferences.subtitleLanguage]
        return Array(Set(tags.compactMap(PhonePlayerPreferences.languageCode) + PhoneLanguageChoices.common))
            .sorted { languageName($0) < languageName($1) }
    }
    private func languageName(_ code: String) -> String { PhoneLanguageChoices.name(code) }
    private var languageChoices: some View {
        ForEach(languages, id: \.self) { code in Text(languageName(code)).tag(code) }
    }
}
