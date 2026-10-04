import SwiftUI
import AVFoundation
import Metal

#if !canImport(PlayBridgeCastCore)
// Simulator harness stubs only the route registration service. PlaybackSession,
// MPVPhonePlayback and its touch/rendering view are the production sources.
final class PhoneProxyRegistration { let url = URL(string: "http://127.0.0.1/unused")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?, allowedPrivateOrigins: [String] = [], forLocalPlayback: Bool = false) async throws -> PhoneProxyRegistration { fatalError("Unexpected proxy startup") }
}

#endif

enum LocalFileServer {
    static func lanIPAddress() -> String? { ProcessInfo.processInfo.environment["MPV_LAN_HOST"] }
}
@MainActor final class CastSystemPlayback {
    static let shared = CastSystemPlayback()
    func beginLocalPlayback() throws -> UUID {
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try AVAudioSession.sharedInstance().setActive(true)
        return UUID()
    }
    func endLocalPlayback(_ owner: UUID) {}
}

@main struct MPVPhonePlaybackProbe: App {
    @StateObject private var session: PlaybackSession
    @StateObject private var controls = PhonePlayerControls()
    @State private var result = "Loading MKV"
    @State private var playerReady = false
    @State private var playerVisible = true
    private let media: RoutedStream

    init() {
        let url = URL(string: ProcessInfo.processInfo.environment["MPV_FIXTURE"]!)!
        let headers = ProcessInfo.processInfo.environment["MPV_NETWORK_PROBE"] == "1" ? [:] : ["Cookie": "fixture=present, second=2", "User-Agent": "PlayBridgeFixture"]
        media = RoutedStream(url: url, headers: headers, sourceURL: url.absoluteString)
        let media = media
        let route: StreamRoute = ProcessInfo.processInfo.environment["MPV_PHONE_PROXY_PROBE"] == "1" ? .phone : .direct
        _playerVisible = State(initialValue: ProcessInfo.processInfo.environment["MPV_OPENING_ORIENTATION_PROBE"] != "1")
        _session = StateObject(wrappedValue: PlaybackSession(media: media, route: route, contentType: "video/x-matroska",
            initialOrientation: ProcessInfo.processInfo.environment["MPV_OPENING_ORIENTATION"]) { media })
    }

    var body: some Scene {
        WindowGroup {
            ZStack(alignment: .top) {
                if playerVisible {
                    FullScreenVideoPlayerView(session: session, diagnosticsReport: { session.diagnosticsReport() }, onDismiss: { playerVisible = false }, playerControls: controls)
                        .onAppear { playerReady = true }
                        .onDisappear { playerReady = false }
                }
                Text(result).font(.caption).foregroundStyle(.white).accessibilityIdentifier("probe-result")
            }.background(.black)
                .task { await checkPlayback() }
        }
    }

    @MainActor private func wait(_ label: String, _ condition: () -> Bool) async throws {
        for _ in 0..<160 {
            if condition() { return }
            if let failure = session.failure { throw ProbeError(message: label + ": " + failure.message + "\n" + session.diagnosticsReport()) }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw ProbeError(message: "Timed out: " + label + "\n" + session.diagnosticsReport())
    }

    @MainActor private func checkPlayback() async {
        do {
            if ProcessInfo.processInfo.environment["MPV_OPENING_ORIENTATION_PROBE"] == "1" {
                try await checkOpeningOrientation()
                result = "PASS native opening orientation, decoded video and preceding page restoration"
                write(result); return
            }
            // Fullscreen normally starts playback on appearance. Complete that
            // lifecycle before the probe deliberately prepares a paused item.
            try await wait("Fullscreen presentation") { playerReady }
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
            if ProcessInfo.processInfo.environment["MPV_FEATURES_PROBE"] == "1" {
                try await checkFeatures()
                result = "PASS native auto-hide/lock state, fit/fill, speed, subtitle options and remembered language restoration"
                write(result); return
            }
            if ProcessInfo.processInfo.environment["MPV_PHONE_PROXY_PROBE"] == "1" {
                try await checkPhoneProxy()
                result = "PASS mpv via-phone proxy playback and seeking for MKV, MP4 and HLS"
                write(result); return
            }
            if ProcessInfo.processInfo.environment["MPV_ORIENTATION_PROBE"] == "1" {
                await session.replaceWebsiteMedia(media, title: "Rotation fixture", resumeMs: 1000, autoplay: true)
                try await wait("Rotation playback") { session.durationSeconds > 5 && session.positionSeconds > 1.5 }
                try await checkOrientations()
                result = "PASS fullscreen portrait/landscape-left/landscape-right rendering and playback"
                write(result); return
            }
            if ProcessInfo.processInfo.environment["MPV_NETWORK_PROBE"] == "1" {
                await session.replaceWebsiteMedia(media, title: "HTTPS fixture", resumeMs: 0,
                                                  contentType: "video/x-matroska", autoplay: true)
                if ProcessInfo.processInfo.environment["MPV_TLS_REJECTION_PROBE"] == "1" {
                    try await wait("Untrusted TLS rejection") { session.failure != nil }
                    guard session.alternativeEngine?.networkIssue == .certificate, session.durationSeconds == 0 else {
                        throw ProbeError(message: "Untrusted certificate was not rejected with safe certificate evidence\n" + session.diagnosticsReport())
                    }
                    result = "PASS untrusted TLS certificate rejected"
                    write(result)
                    return
                }
                try await wait("HTTPS playback") { session.durationSeconds > 0 && session.positionSeconds > 0.5 }
                session.seek(to: 10)
                try await wait("HTTPS seek and continued playback") { session.positionSeconds > 10.5 && session.isPlaying }
                session.pause()
                result = "PASS HTTPS playback and seek with certificate verification"
                write(result)
                return
            }
            await session.replaceWebsiteMedia(media, title: "MKV fixture", resumeMs: 2000, autoplay: false)
            try await wait("MKV metadata") { session.durationSeconds > 5 && !session.mpvState.audioTracks.isEmpty }
            try await wait("Paused resume at 2 seconds") { !session.isPlaying && abs(session.positionSeconds - 2) < 0.6 }
            guard session.mpvState.subtitleTracks.count == 1 else { throw ProbeError(message: "Embedded subtitle was not discovered") }
            session.alternativeEngine?.selectSubtitle(session.mpvState.subtitleTracks[0].id)
            session.play()
            try await wait("Playback advancing") { session.positionSeconds > 2.6 && session.isPlaying }
            session.pause()
            try await wait("Pause") { session.mpvState.paused }
            session.seek(to: 5)
            try await wait("Seek") { abs(session.positionSeconds - 5) < 0.6 }
            session.play()
            try await wait("Playback after seek") { session.positionSeconds > 5.6 }
            NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
            try await wait("Background pause") { session.mpvState.paused }
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
            try await wait("Foreground resume") { session.isPlaying && session.positionSeconds > 6.1 }
            session.pause()
            try await wait("User pause") { session.mpvState.paused }
            NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
            try await Task.sleep(nanoseconds: 300_000_000)
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
            try await Task.sleep(nanoseconds: 500_000_000)
            guard !session.isPlaying else { throw ProbeError(message: "Foreground overrode the user's pause choice") }
            let snapshot = session.diagnosticsReport()
            guard snapshot.contains("Engine: mpv"), !snapshot.contains("fixture=present") else { throw ProbeError(message: "Diagnostics missing or unsafe") }
            // Reload on the same session tests layer replacement and event fencing.
            await session.replaceWebsiteMedia(media, title: "Second item", resumeMs: 3000, autoplay: true)
            try await wait("Replacement resume") { session.durationSeconds > 5 && session.positionSeconds > 3.6 }
            session.pause()
            let base = media.url.deletingLastPathComponent()
            for (name, mime) in [("video.mp4", "video/mp4"), ("video.m3u8", "application/vnd.apple.mpegurl"),
                                 ("video.mpd", "application/dash+xml"), ("audio.wav", "audio/wav")] {
                let url = base.appendingPathComponent(name)
                let next = RoutedStream(url: url, headers: media.headers, sourceURL: url.absoluteString)
                await session.replaceWebsiteMedia(next, title: name, resumeMs: 2000, contentType: mime, autoplay: false)
                try await wait(name + " metadata and paused resume") { session.durationSeconds > 5 && !session.isPlaying && abs(session.positionSeconds - 2) < 0.6 }
                session.play()
                try await wait(name + " native playback") { session.positionSeconds > 2.6 && session.isPlaying }
                session.pause()
                try await wait(name + " pause") { session.mpvState.paused }
                session.seek(to: 5)
                try await wait(name + " seek") { abs(session.positionSeconds - 5) < 0.6 }
            }
            var request = URLRequest(url: base.appendingPathComponent("video.mp4"))
            request.allHTTPHeaderFields = media.headers
            let (data, _) = try await URLSession.shared.data(for: request)
            let local = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("local.mp4")
            try data.write(to: local)
            await session.replaceWebsiteMedia(RoutedStream(url: local, headers: [:]), title: "Local file", resumeMs: 2000, contentType: "video/mp4", autoplay: true)
            try await wait("Local MP4 native playback") { session.durationSeconds > 5 && session.positionSeconds > 2.6 && session.isPlaying }
            session.pause()
            result = "PASS mpv-only MKV, MP4, HLS, DASH, audio and local files: resume, playback, pause, seek, tracks, lifecycle and diagnostics"
            write(result)
        } catch {
            result = "FAIL: \(error)"
            write(result)
        }
    }
    @MainActor private func checkFeatures() async throws {
        await session.replaceWebsiteMedia(media, title: "Feature fixture", resumeMs: 2000, autoplay: true)
        try await wait("Native playback") { session.durationSeconds > 5 && session.positionSeconds > 2.5 }
        try await wait("Native control auto-hide") { !controls.visible }
        session.pause()
        try await wait("Pause reveals chrome") { !session.isPlaying && controls.visible }
        controls.lock()
        guard controls.locked && !controls.visible else { throw ProbeError(message: "Lock did not hide controls") }
        controls.tap()
        guard controls.locked && controls.unlockVisible else { throw ProbeError(message: "Locked tap did not reveal unlock") }
        controls.unlock()
        guard !controls.locked && controls.visible else { throw ProbeError(message: "Unlock did not restore controls") }
        guard let audio = session.mpvState.audioTracks.first, let subtitle = session.mpvState.subtitleTracks.first else {
            throw ProbeError(message: "Missing language-tagged native tracks")
        }
        session.selectAudio(audio.id); session.selectEmbeddedSubtitle(subtitle.id)
        session.updatePreferences { $0.speed = 2; $0.subtitleScale = 1.5; $0.subtitleColor = .yellow; $0.subtitleBackground = true }
        session.setSubtitleDelay(3)
        try await wait("Native 2x speed and subtitle selection") { session.mpvState.speed == 2 && session.mpvState.selectedSubtitle == subtitle.id }
        guard session.preferences.audioLanguage == "en" && session.preferences.subtitleLanguage == "en" else {
            throw ProbeError(message: "Container ISO language tags were not normalized")
        }
        session.seek(to: 2)
        try await wait("Paused feature seek") { abs(session.positionSeconds - 2) < 0.2 && !session.isPlaying }
        controls.tap()
        session.updatePreferences { $0.sizing = .fit }
        try await captureFeature("fit")
        session.updatePreferences { $0.sizing = .fill }
        try await captureFeature("fill")
        session.updatePreferences { $0.sizing = .fit; $0.speed = 1 }
        let styleURL = media.url.deletingLastPathComponent().appendingPathComponent("style.mkv")
        let styleMedia = RoutedStream(url: styleURL, headers: media.headers)
        await session.replaceWebsiteMedia(styleMedia, title: "Language restore", resumeMs: 2000, autoplay: false)
        try await wait("Native language restoration") { session.mpvState.selectedSubtitle == subtitle.id && session.mpvState.selectedAudio == audio.id }
        guard session.subtitleDelay == 0 && session.preferences.subtitleScale == 1.5 else {
            throw ProbeError(message: "New episode did not reset timing while retaining style")
        }
        try await captureFeature("subtitle-style")
        session.setSubtitleDelay(4)
        try await captureFeature("subtitle-delayed")
    }
    @MainActor private func captureFeature(_ name: String) async throws {
        try await Task.sleep(nanoseconds: 600_000_000)
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try name.write(to: directory.appendingPathComponent(name + ".geometry.txt"), atomically: true, encoding: .utf8)
        try name.write(to: directory.appendingPathComponent("stage.txt"), atomically: true, encoding: .utf8)
        try await wait(name + " screenshot") { FileManager.default.fileExists(atPath: directory.appendingPathComponent(name + ".ack").path) }
    }

    @MainActor private func checkPhoneProxy() async throws {
        let base = media.url.deletingLastPathComponent()
        let origin = URL(string: "/", relativeTo: base)!.absoluteURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        for (name, mime) in [("video.mkv", "video/x-matroska"), ("video.mp4", "video/mp4"), ("video.m3u8", "application/vnd.apple.mpegurl")] {
            let original = base.appendingPathComponent(name)
            let registration = try await PhoneSenderServices.shared.register(url: original.absoluteString, headers: media.headers,
                contentType: mime, allowedPrivateOrigins: [origin], forLocalPlayback: true)
            guard registration.url.host == "127.0.0.1" else { throw ProbeError(message: "Local proxy must advertise loopback") }
            let routed = RoutedStream(url: registration.url, headers: [:], registration: registration, sourceURL: original.absoluteString)
            await session.replaceWebsiteMedia(routed, title: name, resumeMs: 1000, contentType: mime, autoplay: true)
            try await wait("Via phone " + name) { session.durationSeconds > 5 && session.positionSeconds > 1.6 && session.isPlaying }
            session.seek(to: 5)
            try await wait("Via phone seek " + name) { session.positionSeconds > 5.6 && session.isPlaying }
            session.pause()
        }
    }

    @MainActor private func checkOpeningOrientation() async throws {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { throw ProbeError(message: "Missing window scene") }
        try await wait("Preceding page geometry") { scene.interfaceOrientation != .unknown }
        let previous = scene.interfaceOrientation
        playerVisible = true
        try await wait("Fullscreen presentation") { playerReady }
        let portrait = session.initialOrientation == "portrait"
        try await wait("Requested opening orientation") { portrait ? scene.interfaceOrientation == .portrait : scene.interfaceOrientation.isLandscape }
        try await wait("Decoded opening video") { session.durationSeconds > 0 && session.positionSeconds > 0.5 && session.isPlaying }
        session.pause()
        try await wait("Paused opening video") { !session.isPlaying }
        try await Task.sleep(nanoseconds: 700_000_000)
        guard let window = scene.windows.first(where: \.isKeyWindow), let surface = metalSurface(window),
              surface.convert(surface.bounds, to: window).contains(CGPoint(x: window.bounds.midX, y: window.bounds.midY)) else {
            throw ProbeError(message: "Opening video surface is outside the viewport")
        }
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let name = "native-opening"
        let layer = surface.layer as! CAMetalLayer
        try "orientation=\(scene.interfaceOrientation.rawValue), window=\(window.bounds), view=\(surface.bounds), drawable=\(layer.drawableSize)\n"
            .write(to: directory.appendingPathComponent(name + ".geometry.txt"), atomically: true, encoding: .utf8)
        try name.write(to: directory.appendingPathComponent("stage.txt"), atomically: true, encoding: .utf8)
        try await wait("Opening frame capture") { FileManager.default.fileExists(atPath: directory.appendingPathComponent(name + ".ack").path) }
        playerVisible = false
        try await wait("Preceding page orientation restored") { !playerReady && scene.interfaceOrientation == previous }
    }

    @MainActor private func checkOrientations() async throws {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { throw ProbeError(message: "Missing window scene") }
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        for (name, orientation, mask) in [("portrait", UIInterfaceOrientation.portrait, UIInterfaceOrientationMask.portrait),
                                        ("landscape-left", .landscapeLeft, .landscapeLeft),
                                        ("landscape-right", .landscapeRight, .landscapeRight), ("portrait-return", .portrait, .portrait)] {
            let pausedRotation = name == "landscape-left"
            if pausedRotation { session.pause(); try await wait("User pause before rotation") { !session.isPlaying } }
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
            try await wait(name + " geometry") { scene.interfaceOrientation == orientation }
            try await Task.sleep(nanoseconds: 700_000_000)
            guard let window = scene.windows.first(where: \.isKeyWindow), let surface = metalSurface(window) else {
                throw ProbeError(message: name + ": missing video surface")
            }
            let frame = surface.convert(surface.bounds, to: window)
            let layer = surface.layer as! CAMetalLayer
            let geometry = "\(name): window=\(window.bounds), view=\(surface.bounds), layer=\(layer.bounds), drawable=\(layer.drawableSize), scale=\(layer.contentsScale)\n"
            try geometry.write(to: directory.appendingPathComponent(name + ".geometry.txt"), atomically: true, encoding: .utf8)
            guard frame.width > 100, frame.height > 100, frame.contains(CGPoint(x: window.bounds.midX, y: window.bounds.midY)) else {
                throw ProbeError(message: name + ": video surface is outside the viewport: \(frame)")
            }
            if pausedRotation {
                guard !session.isPlaying else { throw ProbeError(message: "Rotation overrode the user's pause") }
            } else {
                session.seek(to: 2); session.play()
                try await wait(name + " continued playback") { session.positionSeconds > 2.5 && session.isPlaying }
            }
            try name.write(to: directory.appendingPathComponent("stage.txt"), atomically: true, encoding: .utf8)
            try await wait(name + " capture acknowledgement") { FileManager.default.fileExists(atPath: directory.appendingPathComponent(name + ".ack").path) }
            if pausedRotation {
                session.seek(to: 2); session.play()
                try await wait("Resume after paused rotation") { session.positionSeconds > 2.5 && session.isPlaying }
            }
        }
    }

    @MainActor private func metalSurface(_ view: UIView) -> UIView? {
        if view.layer is CAMetalLayer { return view }
        return view.subviews.lazy.compactMap { metalSurface($0) }.first
    }

    private func write(_ text: String) {
        let path = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("result.txt")
        try? text.write(to: path, atomically: true, encoding: .utf8)
    }
}

private struct ProbeError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}
