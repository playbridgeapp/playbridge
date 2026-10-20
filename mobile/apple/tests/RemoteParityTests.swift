import Foundation

enum RemoteParityTests {
    static func run() {
        volume()
        subtitles()
        print("PASS: remote parity pill, latest-volume throttle and pin, subtitle labels/filters and timing")
    }

    private static func volume() {
        func pill(_ absolute: Bool = true, _ whole: Bool = false, _ fallback: Bool = false, _ percent: Int? = nil, _ muted: Bool? = nil) -> RemoteVolumePill {
            .init(absolute: absolute, wholeTV: whole, appFallback: fallback, percent: percent, muted: muted)
        }
        precondition(pill().text == "—" && pill().exact)
        precondition(pill(false).text == "" && !pill(false).exact)
        precondition(pill(true, true).text == "" && !pill(true, true).exact)
        precondition(!pill(true, false, true).exact)
        precondition(pill(true, false, false, 145).text == "100")
        precondition(pill(true, false, false, -5).text == "0")
        precondition(pill(false, true, false, 42).text == "42")
        precondition(pill(true, false, false, 42, true).text == "Muted")
        precondition(pill(true, false, false, 42, false).icon != pill(true, false, false, 42, true).icon)
        var policy = ReceiverRemotePolicy(isLegacy: false, state: .init())
        policy.volume = ReceiverVolume(scope: "system", operations: ["absolute", "relative_step", "level_report"], step: nil, fallbackScope: "app")
        precondition(RemoteControlPresentation.volumeReportMatches(policy: policy, scope: "system"))
        precondition(RemoteControlPresentation.volumeReportMatches(policy: policy, scope: "app"))
        precondition(!RemoteControlPresentation.volumeReportMatches(policy: policy, scope: "other"))
        precondition(!RemoteControlPresentation.volumeReportMatches(policy: policy, scope: nil))
        policy.volume = ReceiverVolume(scope: "system", operations: ["absolute", "level_report"], step: nil, fallbackScope: nil)
        precondition(!RemoteControlPresentation.volumeReportMatches(policy: policy, scope: "app"))
        var throttle = LatestVolumeThrottle()
        precondition(throttle.poll(at: 0) == nil)
        throttle.offer(12)
        precondition(throttle.poll(at: 0) == 12)
        throttle.offer(20); throttle.offer(32)
        precondition(throttle.poll(at: 124) == nil && throttle.wait(at: 124) == 1)
        precondition(throttle.poll(at: 125) == 32)
        throttle.offer(45) // final release offer is sent even if no more move events arrive
        precondition(throttle.poll(at: 126) == nil && throttle.hasPending)
        precondition(throttle.poll(at: 250) == 45 && !throttle.hasPending)
        throttle.offer(101); precondition(throttle.poll(at: 375) == 100)
        throttle.offer(0); throttle.clear()
        precondition(!throttle.hasPending && throttle.poll(at: 500) == nil)
        throttle.offer(-1); precondition(throttle.poll(at: 0) == 0)

        var draft = RemoteVolumeDraft()
        draft.reconcile(nil, at: 0)
        precondition(draft.value == 50 && !draft.touched)
        draft.move(65); draft.reconcile(20, at: 100)
        precondition(draft.value == 65 && draft.dragging)
        draft.release(at: 200); draft.reconcile(20, at: 1699)
        precondition(draft.value == 65 && draft.pinDeadline == 1700)
        draft.reconcile(20, at: 1700)
        precondition(draft.value == 20 && draft.pinDeadline == nil)
        draft.move(70); draft.release(at: 2000); draft.reconcile(70, at: 2001)
        precondition(draft.value == 70 && draft.pinDeadline == nil)
        draft.move(90); draft.release(at: 3000); draft.unpin(); draft.reconcile(91, at: 3001)
        precondition(draft.value == 91) // step/report can move the slider when no longer dragging
    }

    private static func subtitles() {
        let track = MediaTrack(id: "a", name: "eng · OpenSubtitles v3", selected: true)
        let duplicate = MediaTrack(id: "b", name: "eng · OpenSubtitles v3", selected: false)
        let file = MediaTrack(id: "https://subs.example.com/Movie.en.srt", name: "1962663743", selected: false)
        let addon = MediaTrack(id: "https://subs5.strem.io/subs/eng/1962663743.srt", name: "a0be3d142c", selected: false)
        let unknown = MediaTrack(id: "external_1", name: "abcd1234", selected: false)
        let off = MediaTrack(id: "none", name: "Off", selected: false)
        let all = [off, track, duplicate, file, addon, unknown]
        let labels = RemoteControlPresentation.subtitleLabels(all)
        precondition(labels["a"] == "English · OpenSubtitles v3 1")
        precondition(labels["b"] == "English · OpenSubtitles v3 2")
        precondition(labels[file.id] == "Movie.en")
        precondition(labels[addon.id] == "English · strem.io")
        precondition(labels[unknown.id] == "Added")
        precondition(labels[off.id] == "Off")
        precondition(RemoteControlPresentation.trackLabel(track, in: all, subtitle: true) == labels["a"])
        precondition(RemoteControlPresentation.readableSubtitleName("1962663743 · eng") == "English")
        precondition(RemoteControlPresentation.readableSubtitleName("eng | spa") == "English • Spanish")
        precondition(RemoteControlPresentation.readableSubtitleName("Tamil") == "Tamil")
        precondition(RemoteControlPresentation.readableSubtitleName("1962663743") == nil)
        precondition(RemoteControlPresentation.addedSubtitleLinkLabel("http://127.0.0.1/123.srt") == nil)
        precondition(RemoteControlPresentation.addedSubtitleLinkLabel("http://localhost/123.srt") == nil)
        precondition(RemoteControlPresentation.addedSubtitleLinkLabel("http://[::1]/123.srt") == nil)
        precondition(RemoteControlPresentation.addedSubtitleLinkLabel("https://subs.example.co.uk/123.srt") == "example.co.uk")
        precondition(RemoteControlPresentation.addedSubtitleLinkLabel("https://subs.example.com/123.srt#English%20SDH") == "English SDH")
        precondition(RemoteControlPresentation.subtitleOrigin(addon) == .addOn)
        precondition(RemoteControlPresentation.subtitleOrigin(file) == .added)
        precondition(RemoteControlPresentation.subtitleOrigin(track) == .builtIn)
        precondition(RemoteControlPresentation.subtitleOrigin(.init(id: "https://evil-strem.io/1.srt", name: "", selected: false)) == .added)
        let rows = RemoteControlPresentation.subtitleRows(all)
        precondition(rows.count == 5 && rows[3].language == "English")
        let origins = RemoteControlPresentation.originFilters(rows)
        precondition(origins.map { $0.0 } == [.builtIn, .addOn, .added] && origins.map { $0.1 } == [2, 1, 2])
        precondition(RemoteControlPresentation.filterSubtitles(rows, origin: .added, language: nil, query: "movie").map(\.id) == [file.id])
        precondition(RemoteControlPresentation.filterSubtitles(rows, origin: nil, language: "English", query: " opensubtitles ").count == 2)
        let mixed = RemoteControlPresentation.subtitleRows([
            .init(id: "1", name: "eng", selected: true), .init(id: "2", name: "eng", selected: false),
            .init(id: "3", name: "spa", selected: false), .init(id: "4", name: "fra", selected: false)])
        let languages = RemoteControlPresentation.languageFilters(mixed, preferred: ["Spanish", "French", "Spanish", "Absent"])
        precondition(languages.map { $0.0 } == ["Spanish", "French", "English"])
        precondition(languages.map { $0.1 } == [1, 1, 2])
        let defaults = RemoteControlPresentation.languageFilters(mixed, preferred: [])
        precondition(defaults.map { $0.0 } == ["English", "French", "Spanish"])
        for (value, expected) in [(Int64(0), "Synced"), (300, "+0.3 s"), (-1250, "−1.25 s"), (1000, "+1 s"), (10, "+0.01 s")] {
            precondition(RemoteControlPresentation.offsetText(value) == expected)
        }
        precondition([-500, -100, 100, 500].map { RemoteControlPresentation.offsetStepLabel(Int64($0)) } == ["−0.5", "−0.1", "+0.1", "+0.5"])
    }
}
