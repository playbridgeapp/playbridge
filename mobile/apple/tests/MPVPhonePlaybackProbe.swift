import SwiftUI
import AVFoundation

// Simulator harness stubs only the route registration service. PlaybackSession,
// MPVPhonePlayback and its touch/rendering view are the production sources.
final class PhoneProxyRegistration { let url = URL(string: "http://127.0.0.1/unused")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?) async throws -> PhoneProxyRegistration { fatalError("Unexpected proxy startup") }
}

@main struct MPVPhonePlaybackProbe: App {
    @StateObject private var session: PlaybackSession
    @State private var result = "Loading MKV"
    private let media: RoutedStream

    init() {
        let url = URL(string: ProcessInfo.processInfo.environment["MPV_FIXTURE"]!)!
        let headers = ProcessInfo.processInfo.environment["MPV_NETWORK_PROBE"] == "1" ? [:] : ["Cookie": "fixture=present, second=2", "User-Agent": "PlayBridgeFixture"]
        media = RoutedStream(url: url, headers: headers, sourceURL: url.absoluteString)
        let media = media
        _session = StateObject(wrappedValue: PlaybackSession(media: media, route: .direct, contentType: "video/x-matroska") { media })
    }

    var body: some Scene {
        WindowGroup {
            VStack {
                Text(result).foregroundStyle(.white).accessibilityIdentifier("probe-result")
                MPVPhonePlayerView(session: session)
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
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
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
            result = "PASS MKV: resume, play, pause, seek, tracks, lifecycle, replacement, diagnostics"
            write(result)
        } catch {
            result = "FAIL: \(error)"
            write(result)
        }
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
