import Foundation

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration { fatalError("Unexpected proxy startup") }
}

@main struct PhonePlayerFeaturesTests {
    @MainActor static func main() async throws {
        let suite = "PhonePlayerFeaturesTests." + UUID().uuidString
        let store = UserDefaults(suiteName: suite)!
        defer { store.removePersistentDomain(forName: suite) }
        precondition(PhonePlayerPreferences.languageCode("ENG") == "en")
        precondition(PhonePlayerPreferences.languageCode("pt_BR") == "pt-br")
        precondition(PhonePlayerPreferences.languageCode("swe") == "sv")
        precondition(PhonePlayerPreferences.preferredTrack([.init(id: 1, label: "", language: "fr"), .init(id: 2, label: "", language: "fr-CA")], language: "fr-ca")?.id == 2)
        precondition(PhonePlayerPreferences.matches("fra", preference: "fr-CA"))
        precondition(PhonePlayerPreferences.languageCode("und") == nil)
        precondition(PhonePlayerPreferences.languageCode("https://private.test/token") == nil)
        var invalid = PhonePlayerPreferences(); invalid.speed = .infinity; invalid.subtitleScale = .nan
        precondition(invalid.sanitized().speed == 1 && invalid.sanitized().subtitleScale == 1)
        store.set(Data("corrupt".utf8), forKey: PhonePlayerPreferences.key)
        precondition(PhonePlayerPreferences.load(from: store) == PhonePlayerPreferences())
        store.removeObject(forKey: PhonePlayerPreferences.key)

        let media = RoutedStream(url: URL(string: "https://fixture.test/movie.mkv?token=private")!, headers: [:])
        var engines: [TestAlternativeEngine] = []
        let factory: () -> PhoneAlternativePlaybackEngine? = { let engine = TestAlternativeEngine(); engines.append(engine); return engine }
        let session = PlaybackSession(media: media, route: .direct, preferencesStore: store, alternativeFactory: factory) { media }
        let first = engines.last!
        precondition(first.configurations.first == PhonePlayerOptions())
        var state = PhonePlaybackState(position: 20, duration: 100, paused: false)
        state.audioTracks = [.init(id: 1, label: "English audio", language: "eng"), .init(id: 2, label: "French audio", language: "fra")]
        state.subtitleTracks = [.init(id: 3, label: "English captions", language: "en"), .init(id: 4, label: "French captions", language: "fr")]
        first.onState?(state)
        session.skip(by: 10); session.skip(by: 10)
        precondition(first.seeks.suffix(2) == [30, 40], "Rapid double-taps must accumulate without waiting for a callback")
        session.seek(to: 500); precondition(first.seeks.last == 100)
        session.seek(to: -3); precondition(first.seeks.last == 0)
        session.seek(to: .nan); precondition(first.seeks.last == 0)
        session.selectAudio(2); session.selectEmbeddedSubtitle(4)
        precondition(session.preferences.audioLanguage == "fr" && session.preferences.subtitleLanguage == "fr")
        precondition(first.selectedAudio == 2 && first.selectedSubtitle == 4)
        session.updatePreferences { $0.speed = 1.5; $0.sizing = .fill; $0.subtitleScale = 1.25; $0.subtitleColor = .yellow; $0.subtitleBackground = false }
        session.setSubtitleDelay(2)
        precondition(first.configurations.last!.preferences == session.preferences && first.configurations.last!.subtitleDelay == 2)
        first.onState?(state)
        precondition(first.selectedAudio == 2 && first.selectedSubtitle == 4, "A style update must not override a manual track choice")
        session.setPreferredAudioLanguage(nil)
        precondition(first.selectedAudio == nil, "Source default must restore mpv's automatic audio selection")
        session.setPreferredAudioLanguage("fr")
        session.setPreferredSubtitleLanguage("en", enabled: true)
        precondition(first.selectedAudio == 2 && first.selectedSubtitle == 3)
        session.setPreferredSubtitleLanguage("de", enabled: true)
        precondition(first.selectedSubtitle == nil, "A missing preferred language must not leave the old language enabled")
        session.setPreferredSubtitleLanguage("en", enabled: true)
        session.setSubtitleDelay(999); precondition(session.subtitleDelay == 10)
        session.setSubtitleDelay(2)
        let saved = store.data(forKey: PhonePlayerPreferences.key)!
        precondition(!String(data: saved, encoding: .utf8)!.contains("private") && !String(data: saved, encoding: .utf8)!.contains("audio" + " title"))
        precondition(!String(data: saved, encoding: .utf8)!.contains("subtitleDelay"), "Timing is media-specific and must not be saved")
        session.retry()
        for _ in 0..<100 { if !session.retrying { break }; try await Task.sleep(nanoseconds: 1_000_000) }
        precondition(engines.count == 2 && engines.last!.configurations.last!.subtitleDelay == 2)
        await session.replaceWebsiteMedia(media, title: "Next episode", resumeMs: 0)
        let next = engines.last!
        precondition(next.configurations.last!.preferences.speed == 1.5 && next.configurations.last!.subtitleDelay == 0)
        next.onState?(state)
        precondition(next.selectedAudio == 2 && next.selectedSubtitle == 3, "Languages must survive decoder replacement")
        session.websiteSubtitleTracks = [("https://fixture.test/fr.vtt", "French")]
        session.websiteSubtitleLanguages = ["fra"]
        var sidecarSelections: [Int?] = []
        session.onWebsiteSubtitleSelection = { sidecarSelections.append($0) }
        state.subtitleTracks = []
        next.onState?(state)
        session.setPreferredSubtitleLanguage("fr", enabled: true)
        precondition(sidecarSelections.last! == 0 && next.selectedSubtitle == nil)
        session.selectEmbeddedSubtitle(nil)
        precondition(!session.preferences.subtitlesEnabled && sidecarSelections.last! == nil)
        state.subtitleTracks = [.init(id: 3, label: "English", language: "eng")]
        next.onState?(state)
        precondition(next.selectedSubtitle == nil, "Off must remain off when new tracks arrive")
        session.selectEmbeddedSubtitle(3)
        session.setPreferredSubtitleLanguage(nil, enabled: true)
        precondition(next.selectedSubtitle == 3)
        let restored = PlaybackSession(media: media, route: .direct, preferencesStore: store, alternativeFactory: factory) { media }
        precondition(restored.preferences == session.preferences && restored.subtitleDelay == 0)
        restored.close(); session.close()
        let count = next.configurations.count
        session.updatePreferences { $0.speed = 2 }; session.setSubtitleDelay(4); session.selectAudio(1)
        precondition(next.configurations.count == count, "Closed sessions must ignore new settings")
        print("PASS presentation settings, safe persistence, clamped/accumulated seeking, language restore, sidecar fallback, retry and close fencing")

        let controls = PhonePlayerControls(hideDelayNanoseconds: 20_000_000)
        controls.update(playing: true, buffering: false, voiceOver: false)
        try await settle(); precondition(!controls.visible)
        controls.tap(); precondition(controls.visible)
        controls.setScrubbing(true); try await settle(); precondition(controls.visible)
        controls.setScrubbing(false); try await settle(); precondition(!controls.visible)
        controls.reveal(); controls.setSheetPresented(true); try await settle(); precondition(controls.visible)
        controls.setSheetPresented(false); try await settle(); precondition(!controls.visible)
        controls.update(playing: false, buffering: false, voiceOver: false)
        try await settle(); precondition(controls.visible)
        controls.update(playing: true, buffering: true, voiceOver: false)
        try await settle(); precondition(controls.visible)
        controls.update(playing: true, buffering: false, voiceOver: true)
        try await settle(); controls.tap(); precondition(controls.visible)
        controls.update(playing: true, buffering: false, voiceOver: false)
        controls.lock(); precondition(controls.locked && !controls.visible && controls.unlockVisible)
        try await settle(); precondition(controls.locked && !controls.unlockVisible)
        controls.tap(); precondition(controls.unlockVisible && controls.locked && !controls.visible)
        controls.unlock(); precondition(!controls.locked && controls.visible)
        controls.setSuspended(true); try await settle(); precondition(controls.visible)
        controls.setSuspended(false); try await settle(); precondition(!controls.visible)
        controls.reveal(); controls.stop(); try await settle(); precondition(controls.visible)
        print("PASS shared auto-hide timer, pause/buffering, scrubbing/sheets, VoiceOver, touch lock/unlock and lifecycle cancellation")
    }
    // Well above the 20 ms hide delay so slow CI runners settle too.
    static func settle() async throws { try await Task.sleep(nanoseconds: 400_000_000) }
}
