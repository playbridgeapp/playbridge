import Foundation
import CoreGraphics

final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, allowedPrivateOrigins: [String] = [], forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration { fatalError("Unexpected proxy startup") }
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
        var invalid = PhonePlayerPreferences(); invalid.speed = .infinity
        precondition(invalid.sanitized().speed == 1)
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
        session.updatePreferences { $0.speed = 1.5; $0.sizing = .fill }
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

        let hidden = PhonePlayerControls(hideDelayNanoseconds: 20_000_000)
        hidden.update(playing: true, buffering: false, voiceOver: false)
        try await settle(); precondition(!hidden.visible)
        let generation = hidden.hideGeneration
        hidden.touchChrome(from: .surfaceGesture)
        hidden.setScrubbing(true, source: .surfaceGesture)
        hidden.setScrubbing(false, source: .surfaceGesture)
        precondition(!hidden.visible && hidden.hideGeneration == generation, "Gesture seeks must not reveal or extend the bars")
        hidden.update(playing: false, buffering: false, voiceOver: false)
        precondition(hidden.visible)
        let held = hidden.hideGeneration
        hidden.touchChrome(from: .surfaceGesture)
        precondition(hidden.visible && hidden.hideGeneration == held, "A gesture must not force-hide bars that are already up")
        hidden.touchChrome(from: .transport); precondition(hidden.visible)
        hidden.setScrubbing(true); precondition(hidden.visible, "The slider scrub still reveals")

        var clock = Date(timeIntervalSince1970: 1_000)
        let seeking = PhonePlayerControls(hideDelayNanoseconds: 20_000_000, now: { clock })
        seeking.update(playing: true, buffering: false, voiceOver: false)
        try await settle(); precondition(!seeking.visible)
        seeking.touchChrome(from: .surfaceGesture)
        seeking.update(playing: true, buffering: true, voiceOver: false)
        precondition(!seeking.visible, "Buffering caused by a double-tap seek must not reveal the bars")
        seeking.update(playing: true, buffering: false, voiceOver: false)
        seeking.setScrubbing(true, source: .surfaceGesture); seeking.setScrubbing(false, source: .surfaceGesture)
        seeking.update(playing: true, buffering: true, voiceOver: false)
        precondition(!seeking.visible, "Buffering caused by a swipe seek must not reveal the bars")
        seeking.update(playing: true, buffering: false, voiceOver: false)
        clock = clock.addingTimeInterval(PhonePlayerControls.gestureSeekGrace + 1)
        seeking.update(playing: true, buffering: true, voiceOver: false)
        precondition(seeking.visible, "A later stall still reveals the bars")
        seeking.update(playing: true, buffering: false, voiceOver: false)
        try await settle(); precondition(!seeking.visible)
        seeking.touchChrome(from: .surfaceGesture)
        seeking.update(playing: false, buffering: false, voiceOver: false)
        precondition(seeking.visible, "Pausing still reveals the bars after a gesture seek")
        print("PASS gesture seeks leave chrome alone, including their buffering; transport and slider scrubs still reveal")

        precondition(PhonePlayerScrub.fullWidthSeconds == 100)
        let size = CGSize(width: 400, height: 800)
        let center = CGPoint(x: 200, y: 400)
        let playing = PhonePlayerScrub.Gate(duration: 2700, locked: false, voiceOver: false)
        precondition(PhonePlayerScrub.decide(start: center, translation: CGSize(width: 8, height: 0), viewSize: size, position: 754, gate: playing) == .pending)
        precondition(PhonePlayerScrub.decide(start: center, translation: CGSize(width: 10, height: 20), viewSize: size, position: 754, gate: playing) == .ignore)
        precondition(PhonePlayerScrub.decide(start: CGPoint(x: PhonePlayerScrub.edgeMargin - 1, y: 400), translation: CGSize(width: 80, height: 0), viewSize: size, position: 754, gate: playing) == .ignore)
        precondition(PhonePlayerScrub.decide(start: CGPoint(x: size.width - PhonePlayerScrub.edgeMargin + 1, y: 400), translation: CGSize(width: -80, height: 0), viewSize: size, position: 754, gate: playing) == .ignore)
        precondition(PhonePlayerScrub.decide(start: CGPoint(x: 200, y: size.height - PhonePlayerScrub.homeIndicatorMargin + 1), translation: CGSize(width: 80, height: 0), viewSize: size, position: 754, gate: playing) == .ignore)
        precondition(PhonePlayerScrub.decide(start: center, translation: CGSize(width: 80, height: 0), viewSize: size, position: 754, gate: PhonePlayerScrub.Gate(duration: 0, locked: false, voiceOver: false)) == .ignore)
        precondition(PhonePlayerScrub.decide(start: center, translation: CGSize(width: 80, height: 0), viewSize: size, position: 754, gate: PhonePlayerScrub.Gate(duration: 2700, locked: true, voiceOver: false)) == .ignore)
        precondition(PhonePlayerScrub.decide(start: center, translation: CGSize(width: 80, height: 0), viewSize: size, position: 754, gate: PhonePlayerScrub.Gate(duration: 2700, locked: false, voiceOver: true)) == .ignore)
        guard case .scrub(let sample) = PhonePlayerScrub.decide(start: center, translation: CGSize(width: 60, height: 4), viewSize: size, position: 754, gate: playing) else {
            preconditionFailure("A horizontal drag must scrub")
        }
        precondition(sample.offsetLabel == "+0:15" && sample.timeLabel == "12:49 / 45:00" && abs(sample.target - 769) < 0.001)
        let full = PhonePlayerScrub.preview(translationX: 400, viewWidth: 400, position: 10, duration: 45)!
        precondition(full.target == 45 && full.offsetLabel == "+0:35" && full.timeLabel == "0:45 / 0:45")
        let capped = PhonePlayerScrub.preview(translationX: -400, viewWidth: 400, position: 30, duration: 3600)!
        precondition(capped.target == 0 && capped.offsetLabel == "-0:30" && capped.timeLabel == "0:00 / 1:00:00")
        let forward = PhonePlayerScrub.preview(translationX: 200, viewWidth: 400, position: 754, duration: 2700)!
        precondition(forward.offsetLabel == "+0:50" && forward.timeLabel == "13:24 / 45:00")
        precondition(PhonePlayerScrub.formatSigned(15) == "+0:15" && PhonePlayerScrub.formatSigned(-90) == "-1:30" && PhonePlayerScrub.formatSigned(3661) == "+1:01:01")
        precondition(PhonePlayerScrub.commit(sample, ended: false) == nil && PhonePlayerScrub.commit(sample, ended: true) == sample)
        precondition(PhonePlayerScrub.tickStep == 2)
        precondition([0.0, 1.9, 2, 3.9, 4].map(PhonePlayerScrub.tickBucket) == [0, 0, 1, 1, 2])
        precondition([4.0, 2, 1.9, -1.9, -2].map(PhonePlayerScrub.tickBucket) == [2, 1, 0, 0, -1], "Reversing across a boundary must retick")
        precondition(PhonePlayerScrub.hapticTicks(previousBucket: 1, offset: 1.5, target: 40, duration: 100, heldClamp: nil) == 1)
        let pinned = PhonePlayerScrub.preview(translationX: -400, viewWidth: 400, position: 12, duration: 3600)!
        let stillPinned = PhonePlayerScrub.preview(translationX: -800, viewWidth: 400, position: 12, duration: 3600)!
        precondition(pinned.target == 0 && stillPinned.offset == pinned.offset)
        precondition(PhonePlayerScrub.tickBucket(offset: pinned.offset) == PhonePlayerScrub.tickBucket(offset: stillPinned.offset))
        precondition(PhonePlayerScrub.hapticTicks(previousBucket: PhonePlayerScrub.tickBucket(offset: pinned.offset), offset: stillPinned.offset, target: stillPinned.target, duration: 3600, heldClamp: .start) == 0, "A pinned clamp must not keep ticking")
        precondition(PhonePlayerScrub.clampEdgeArrival(target: 0, duration: 100, held: nil) == .start)
        precondition(PhonePlayerScrub.clampEdgeArrival(target: 0, duration: 100, held: .start) == nil)
        precondition(PhonePlayerScrub.clampEdgeArrival(target: 8, duration: 100, held: .start) == nil)
        precondition(PhonePlayerScrub.clampEdgeArrival(target: 0, duration: 100, held: nil) == .start)
        precondition(PhonePlayerScrub.clampEdgeArrival(target: 100, duration: 100, held: nil) == .end)
        precondition(PhonePlayerScrub.clampEdgeArrival(target: 100, duration: 100, held: .end) == nil)
        print("PASS swipe-scrub qualification, clamping, overlay formatting, ticks and cancel")

        // Player chrome text: title meta, seek bar ends and targets, speed chip.
        precondition(PhonePlayerChrome.meta(queueIndex: 2, queueCount: 12, engine: "mpv") == "3 of 12 · mpv")
        precondition(PhonePlayerChrome.meta(queueIndex: 0, queueCount: 1, engine: "AVPlayer") == "AVPlayer", "A single item is not a queue")
        precondition(PhonePlayerChrome.meta(queueIndex: 40, queueCount: 3, engine: "mpv") == "3 of 3 · mpv")
        precondition(PhonePlayerChrome.clock(65) == "1:05" && PhonePlayerChrome.clock(3725) == "1:02:05" && PhonePlayerChrome.clock(.nan) == "0:00")
        precondition(PhonePlayerChrome.endLabel(position: 30, duration: 90, remaining: false) == "1:30")
        precondition(PhonePlayerChrome.endLabel(position: 30, duration: 90, remaining: true) == "-1:00")
        precondition(PhonePlayerChrome.endLabel(position: 500, duration: 90, remaining: true) == "-0:00")
        precondition(PhonePlayerChrome.endLabel(position: 0, duration: 0, remaining: true) == "--:--", "Unknown length has no end time")
        precondition(PhonePlayerChrome.seekTarget(x: 50, width: 200, duration: 100) == 25)
        precondition(PhonePlayerChrome.seekTarget(x: -20, width: 200, duration: 100) == 0)
        precondition(PhonePlayerChrome.seekTarget(x: 260, width: 200, duration: 100) == 100)
        precondition(PhonePlayerChrome.seekTarget(x: 50, width: 0, duration: 100) == 0)
        precondition(PhonePlayerChrome.signedOffset(target: 95, from: 30) == "+1:05")
        precondition(PhonePlayerChrome.signedOffset(target: 0, from: 30) == "-0:30")
        precondition(PhonePlayerChrome.speedLabel(1) == "1×" && PhonePlayerChrome.speedLabel(1.25) == "1.25×" && PhonePlayerChrome.speedLabel(.nan) == "1×")
        print("PASS player chrome labels")

        // Opening: Settings › Phone player › Landscape videos open in.
        let wide = PhoneLandscapeOpening.fullscreen, upright = PhoneLandscapeOpening.portraitFirst
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 0, height: 0, isAudio: false, preference: wide) == .holdLandscape,
                     "Fullscreen turns at once, before the first frame")
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 0, height: 0, isAudio: false, preference: upright) == nil,
                     "Portrait first waits for the picture's shape")
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 1920, height: 1080, isAudio: false, preference: wide) == .holdLandscape)
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 1920, height: 1080, isAudio: false, preference: upright) == .followPhone)
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 1080, height: 1920, isAudio: false, preference: wide) == .holdPortrait,
                     "Vertical videos always open in portrait")
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 1080, height: 1920, isAudio: false, preference: upright) == .holdPortrait)
        precondition(PhonePlayerOpening.decide(websiteOrientation: "portrait", width: 1920, height: 1080, isAudio: false, preference: wide) == .followPhone,
                     "A website's orientation wins")
        precondition(PhonePlayerOpening.decide(websiteOrientation: nil, width: 0, height: 0, isAudio: true, preference: wide) == .followPhone,
                     "Audio never turns the player")
        precondition(PhonePlayerOpening.usesPortraitLayout(width: 1920, height: 1080, screenIsLandscape: false))
        precondition(!PhonePlayerOpening.usesPortraitLayout(width: 1920, height: 1080, screenIsLandscape: true))
        precondition(!PhonePlayerOpening.usesPortraitLayout(width: 1080, height: 1920, screenIsLandscape: false))
        precondition(!PhonePlayerOpening.usesPortraitLayout(width: 0, height: 0, screenIsLandscape: false))
        precondition(PhonePlayerOpening.portraitVideoHeight(width: 1920, height: 1080, screenWidth: 400, screenHeight: 800) == 225)
        precondition(PhonePlayerOpening.portraitVideoHeight(width: 1000, height: 900, screenWidth: 400, screenHeight: 600) == 270, "Capped at 45% of the screen")
        let openingSuite = "PhonePlayerOpening." + UUID().uuidString
        let openingStore = UserDefaults(suiteName: openingSuite)!
        defer { openingStore.removePersistentDomain(forName: openingSuite) }
        precondition(PhoneLandscapeOpening.load(from: openingStore) == .fullscreen)
        openingStore.set("portraitFirst", forKey: PhoneLandscapeOpening.key)
        precondition(PhoneLandscapeOpening.load(from: openingStore) == .portraitFirst)
        openingStore.set("sideways", forKey: PhoneLandscapeOpening.key)
        precondition(PhoneLandscapeOpening.load(from: openingStore) == .fullscreen)
        precondition(PhoneAmbientMode.fittedSize(width: 1920, height: 1080, in: CGSize(width: 874, height: 402)) == CGSize(width: 715, height: 402),
                     "A 16:9 picture on a wide phone leaves bars at the sides for the glow")
        precondition(PhoneAmbientMode.fittedSize(width: 1080, height: 1920, in: CGSize(width: 402, height: 874)) == CGSize(width: 402, height: 715))
        precondition(PhoneAmbientMode.fittedSize(width: 0, height: 0, in: CGSize(width: 874, height: 402)) == nil)
        print("PASS player opening: fullscreen, portrait first, vertical, website, audio portrait layout and ambient fit")

        // Ambient glow: tiny frames, and steps toward a new frame instead of a jump.
        func solid(_ r: UInt8, _ g: UInt8, _ b: UInt8, width: Int = 4, height: Int = 2) -> CGImage {
            var pixels = [UInt8](repeating: 0, count: width * height * 4)
            for i in 0..<(width * height) { pixels[i * 4] = b; pixels[i * 4 + 1] = g; pixels[i * 4 + 2] = r }
            return pixels.withUnsafeBytes { PhoneAmbientFrame.downscale(bgrx: $0.baseAddress!, width: width, height: height, stride: width * 4)! }
        }
        func centre(_ image: CGImage) -> (Int, Int, Int) {
            let context = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
            context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            let bytes = context.data!.assumingMemoryBound(to: UInt8.self)
            return (Int(bytes[0]), Int(bytes[1]), Int(bytes[2]))
        }
        let red = solid(255, 0, 0), blue = solid(0, 0, 255)
        precondition(red.width == PhoneAmbientFrame.width && red.height == 24, "Frames shrink to 48 px wide at their shape")
        let (r, g, b) = centre(red)
        precondition(r > 240 && g < 15 && b < 15, "BGRX frames keep their colours: \(r) \(g) \(b)")
        precondition(PhoneAmbientFrame.blend(nil, toward: red) === red, "The first frame is the glow")
        let step = centre(PhoneAmbientFrame.blend(red, toward: blue)!)
        precondition(step.0 > 140 && step.0 < 190 && step.2 > 65 && step.2 < 115, "One step moves about a third of the way: \(step)")
        precondition(PhoneAmbientFrame.meanLuma(solid(0, 0, 0)) < 0.01 && PhoneAmbientFrame.meanLuma(solid(255, 255, 255)) > 0.99)
        precondition(!PhoneAmbientFrame.accepts(frameLuma: 0.0, glowLuma: 0.4, previousWasDark: false), "A lone black grab is ignored")
        precondition(PhoneAmbientFrame.accepts(frameLuma: 0.0, glowLuma: 0.4, previousWasDark: true), "Two dark grabs in a row are a dark scene")
        precondition(PhoneAmbientFrame.accepts(frameLuma: 0.0, glowLuma: nil, previousWasDark: false))
        precondition(PhoneAmbientFrame.accepts(frameLuma: 0.2, glowLuma: 0.4, previousWasDark: false))
        print("PASS ambient frames: shrink, colours, gradual steps and black-grab filtering")
    }
    // Well above the 20 ms hide delay so slow CI runners settle too.
    static func settle() async throws { try await Task.sleep(nanoseconds: 400_000_000) }
}
