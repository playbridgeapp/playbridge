import Foundation

final class PhoneProxyRegistration { let url = URL(string: "http://127.0.0.1/sub")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, allowedPrivateOrigins: [String] = [], forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration { fatalError("Unexpected proxy startup") }
}

private func subtitle(_ url: String, title: String? = nil, at time: Int64 = 0) -> DetectedVideo {
    DetectedVideo(url: url, contentType: nil, detectedBy: "network", originUrl: nil, headers: [:],
                  kind: .subtitle, timestamp: time, title: title)
}

@main struct SubtitleLanguagesTests {
    @MainActor static func main() async {
        contentChecks()
        languageChecks()
        rankingChecks()
        castLanguageChecks()
        await tickedSubtitleChecks()
        logRedactionChecks()
        print("PASS: subtitle content checks, languages, ranking, cast languages, ticked subtitles and trace redaction")
    }

    static func contentChecks() {
        let vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello there\n"
        precondition(SubtitleContentCheck.looksLikeSubtitles(vtt))
        precondition(SubtitleContentCheck.looksLikeSubtitles("\u{FEFF}1\n00:00:01,000 --> 00:00:02,000\nHi\n"), "SRT with a byte order mark")
        precondition(SubtitleContentCheck.looksLikeSubtitles("[Script Info]\nTitle: x"))
        precondition(SubtitleContentCheck.looksLikeSubtitles("<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\">"))
        precondition(SubtitleContentCheck.looksLikeSubtitles("#EXTM3U\n#EXT-X-TARGETDURATION:6"))
        precondition(!SubtitleContentCheck.looksLikeSubtitles("<!DOCTYPE html><html><body>Not found</body></html>"))
        precondition(!SubtitleContentCheck.looksLikeSubtitles("{\"error\":\"forbidden\"}"))
        precondition(!SubtitleContentCheck.looksLikeSubtitles("<?xml version=\"1.0\"?><Error>AccessDenied</Error>"))

        let sprites = "WEBVTT\n\n00:00.000 --> 00:05.000\nsprite.jpg#xywh=0,0,160,90\n\n00:05.000 --> 00:10.000\nsprite.jpg#xywh=160,0,160,90\n"
        guard case .invalid = SubtitlePreview.check(sprites) else { preconditionFailure("Thumbnail sprites are not subtitles") }
        guard case .invalid = SubtitlePreview.check("<html>Access denied</html>") else { preconditionFailure("Error pages are hidden") }
        guard case .sample(let sample) = SubtitlePreview.check(vtt), sample.preview == "Hello there" else { preconditionFailure("Dialogue is kept") }

        var utf16 = Data([0xFF, 0xFE]); utf16.append("WEBVTT\n".data(using: .utf16LittleEndian)!)
        precondition(SubtitleContentCheck.decodeHead(utf16).hasPrefix("WEBVTT"))
        var big = Data([0xFE, 0xFF]); big.append("WEBVTT".data(using: .utf16BigEndian)!); big.append(0x00)
        precondition(SubtitleContentCheck.decodeHead(big) == "WEBVTT", "A cut-off final byte is dropped")
    }

    static func languageChecks() {
        precondition(SubtitleLanguages.normalize("eng") == "en")
        precondition(SubtitleLanguages.normalize("fre") == "fr")
        precondition(SubtitleLanguages.normalize("fra") == "fr")
        precondition(SubtitleLanguages.normalize("pt-BR") == "pt")
        precondition(SubtitleLanguages.normalize("und") == nil && SubtitleLanguages.normalize("multi") == nil && SubtitleLanguages.normalize(" ") == nil)
        precondition(SubtitleLanguages.inTitle("English") == "en")
        precondition(SubtitleLanguages.inTitle("Spanish (Latin America)") == "es")
        precondition(SubtitleLanguages.inTitle("de") == "de", "Two letters count only when they're the whole title")
        precondition(SubtitleLanguages.inTitle("Track to watch") == nil)
        precondition(SubtitleLanguages.inFileName("https://cdn.test/movie.en.vtt?token=x") == "en")
        precondition(SubtitleLanguages.inFileName("https://cdn.test/show_eng.srt") == "en")
        precondition(SubtitleLanguages.inFileName("https://cdn.test/subs/french-forced.vtt") == "fr")
        precondition(SubtitleLanguages.inFileName("https://cdn.test/segment.vtt") == nil)
        let named = subtitle("https://cdn.test/a.vtt", title: "German")
        precondition(SubtitleLanguages.hint(for: named, detected: "es") == "es", "What the dialogue is in wins over the title")
        precondition(SubtitleLanguages.hint(for: named, detected: nil) == "de")
    }

    static func rankingChecks() {
        let newest = [
            subtitle("https://cdn.test/a.fr.vtt", at: 5),
            subtitle("https://cdn.test/b.en.vtt", title: "English SDH", at: 4),
            subtitle("https://cdn.test/c.de.vtt", at: 3),
            subtitle("https://cdn.test/d.en.vtt", title: "English", at: 2),
        ]
        let language: (DetectedVideo) -> String? = { SubtitleLanguages.hint(for: $0, detected: nil) }
        let ranked = SubtitleLanguages.rank(newest, preferred: "de", device: "en", language: language)
        precondition(ranked.map(\.url) == ["https://cdn.test/c.de.vtt", "https://cdn.test/b.en.vtt",
                                           "https://cdn.test/d.en.vtt", "https://cdn.test/a.fr.vtt"],
                     "Preferred, then the phone's language, newest first within each")
        precondition(SubtitleLanguages.rank(newest, preferred: nil, device: nil, language: language) == newest)
        precondition(SubtitleLanguages.preferredPick(ranked, preferred: "eng", language: language)?.url == "https://cdn.test/d.en.vtt",
                     "The plain subtitle over an SDH one")
        precondition(SubtitleLanguages.preferredPick(ranked, preferred: nil, language: language) == nil, "Nothing is ticked without a preference")
        precondition(SubtitleLanguages.preferredPick(ranked, preferred: "ja", language: language) == nil)
    }

    static func castLanguageChecks() {
        var item: [String: Any] = [:]
        WireProtocol.CastLanguages(audio: "fr", subtitles: "en").apply(to: &item)
        precondition(item["preferredAudioLanguage"] as? String == "fr" && item["preferredSubtitleLanguage"] as? String == "en")
        var none: [String: Any] = [:]
        WireProtocol.CastLanguages.none.apply(to: &none)
        precondition(none.isEmpty)
        let command = WireProtocol.singleVideoCommand(url: "https://cdn.test/v.mp4", languages: .init(audio: "ja", subtitles: nil))
        let json = try! JSONSerialization.jsonObject(with: Data(command.utf8)) as! [String: Any]
        let payload = json["payload"] as? [String: Any] ?? json
        let first = (payload["items"] as? [[String: Any]])?.first
        precondition(first?["preferredAudioLanguage"] as? String == "ja" && first?["preferredSubtitleLanguage"] == nil)
    }

    /// The play-on-phone trace never carries a full address, query, header value or credential.
    static func logRedactionChecks() {
        let secretURL = "https://cdn.test/path/video.m3u8?token=abc123&exp=99"
        precondition(PhonePlaybackLog.describe(secretURL) == "host=cdn.test scheme=https ext=m3u8")
        precondition(PhonePlaybackLog.describe("http://127.0.0.1:8080/r/abc/seg.ts") == "loopback scheme=http ext=ts")
        precondition(PhonePlaybackLog.redact("Opening '\(secretURL)' for reading") == nil, "A line with a token is dropped")
        let opened = PhonePlaybackLog.redact("Opening 'https://cdn.test/private/path/video.m3u8?id=7&sig2=x' for reading")!
        precondition(!opened.contains("/private/") && !opened.contains("id=7") && opened.contains("host=cdn.test"), "Addresses become host and extension")
        precondition(PhonePlaybackLog.redact("http: Cookie: session=abc") == nil)
        precondition(PhonePlaybackLog.redact("Authorization header rejected") == nil)
        precondition(PhonePlaybackLog.redact("segment ?exp=1&token=x failed") == nil)
        precondition(PhonePlaybackLog.headerNames(["Cookie": "secret", "Referer": "https://a.test/x"]) == "cookie,referer")
        precondition(PhonePlaybackLog.redact("   ") == nil)
    }

    @MainActor static func tickedSubtitleChecks() async {
        let suite = "SubtitleLanguagesTests." + UUID().uuidString
        let store = UserDefaults(suiteName: suite)!
        defer { store.removePersistentDomain(forName: suite) }
        var preferences = PhonePlayerPreferences(); preferences.subtitlesEnabled = true; preferences.subtitleLanguage = "fr"
        preferences.save(to: store)
        let media = RoutedStream(url: URL(string: "https://fixture.test/movie.mkv")!, headers: [:])
        let ticked = [PhoneExternalSubtitle(url: URL(string: "http://127.0.0.1:9/a")!, title: "English", language: "en"),
                      PhoneExternalSubtitle(url: URL(string: "http://127.0.0.1:9/b")!, title: "German", language: "de")]
        var engines: [TestAlternativeEngine] = []
        let session = PlaybackSession(media: media, route: .direct, preferencesStore: store, externalSubtitles: ticked,
                                      alternativeFactory: { let engine = TestAlternativeEngine(); engines.append(engine); return engine }) { media }
        let engine = engines.last!
        precondition(engine.externalSubtitles == ticked, "Ticked subtitles are handed to the player before loading")
        var state = PhonePlaybackState(position: 0, duration: 100, paused: false)
        state.subtitleTracks = [.init(id: 1, label: "French", language: "fr")]
        engine.onState?(state)
        precondition(engine.selectedSubtitle == nil, "Waits for the ticked subtitle rather than using the remembered language")
        state.subtitleTracks += [.init(id: 2, label: "English", language: "en", externalURL: "http://127.0.0.1:9/a"),
                                 .init(id: 3, label: "German", language: "de", externalURL: "http://127.0.0.1:9/b")]
        engine.onState?(state)
        precondition(engine.selectedSubtitle == 2, "The first ticked subtitle is on")
        session.selectEmbeddedSubtitle(1)
        engine.onState?(state)
        precondition(engine.selectedSubtitle == 1, "A track the user picks wins")
        let downloaded = PhoneSubtitleDownload.save(Data("1\n00:00:01,000 --> 00:00:02,000\nHi\n".utf8), name: "sub")
        guard case .success(let file) = downloaded, file.pathExtension == "srt" else { preconditionFailure("SRT is saved by its format") }
        precondition(PhoneExternalSubtitle(url: file, title: "x").mpvPath == file.path, "mpv gets a file path, not a URL")
        guard case .failure(.playlist) = PhoneSubtitleDownload.save(Data("#EXTM3U\n".utf8), name: "a.vtt") else { preconditionFailure("HLS playlists are refused") }
        guard case .failure(.notSubtitles) = PhoneSubtitleDownload.save(Data("<html></html>".utf8), name: "a.vtt") else { preconditionFailure() }
        PhoneSubtitleDownload.remove([file])
        precondition(!FileManager.default.fileExists(atPath: file.path))
        session.retry()
        for _ in 0..<100 where engines.count < 2 { await Task.yield() }
        precondition(engines.count == 2 && engines.last!.externalSubtitles == ticked, "A retry adds them again")
    }
}
