import SwiftUI

struct PhonePlayerSettingsView: View {
    @ObservedObject var session: PlaybackSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Playback") {
                    Picker("Speed", selection: preference(\.speed)) {
                        ForEach(PhonePlayerPreferences.speeds, id: \.self) { Text(String(format: "%g×", $0)).tag($0) }
                    }
                    Picker("Video sizing", selection: preference(\.sizing)) {
                        Text("Fit — show the entire picture").tag(PhoneVideoSizing.fit)
                        Text("Fill — crop to fill the screen").tag(PhoneVideoSizing.fill)
                    }
                }
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
                    Picker("Remembered language", selection: Binding(get: {
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
                Section("Subtitle appearance") {
                    HStack {
                        Text("Text size")
                        Spacer()
                        Text(String(format: "%.0f%%", session.preferences.subtitleScale * 100)).monospacedDigit()
                    }
                    Slider(value: preference(\.subtitleScale), in: 0.75...2, step: 0.05).accessibilityLabel("Subtitle text size")
                    Picker("Text colour", selection: preference(\.subtitleColor)) {
                        Text("White").tag(PhoneSubtitleColor.white)
                        Text("Yellow").tag(PhoneSubtitleColor.yellow)
                    }
                    Toggle("Dark background", isOn: preference(\.subtitleBackground))
                    Text("Appearance overrides embedded subtitle styling. Image-based subtitles may not support text styling.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Player settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var subtitleSelection: String {
        if let index = session.websiteSubtitleIndex { return "website:\(index)" }
        if let id = session.mpvState.selectedSubtitle { return "embedded:\(id)" }
        return "off"
    }
    private func preference<Value>(_ key: WritableKeyPath<PhonePlayerPreferences, Value>) -> Binding<Value> {
        Binding(get: { session.preferences[keyPath: key] }, set: { value in session.updatePreferences { $0[keyPath: key] = value } })
    }
    private var languages: [String] {
        let tags = session.mpvState.audioTracks.map(\.language) + session.mpvState.subtitleTracks.map(\.language)
            + session.websiteSubtitleLanguages + [session.preferences.audioLanguage, session.preferences.subtitleLanguage]
        return Array(Set(tags.compactMap(PhonePlayerPreferences.languageCode) + ["en", "hi", "es", "fr", "de", "pt", "it", "ja", "ko", "zh", "ar", "ru"]))
            .sorted { languageName($0) < languageName($1) }
    }
    private func languageName(_ code: String) -> String { Locale.current.localizedString(forIdentifier: code) ?? code }
    private var languageChoices: some View {
        ForEach(languages, id: \.self) { code in Text(languageName(code)).tag(code) }
    }
}
