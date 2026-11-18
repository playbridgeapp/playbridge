import Foundation

@main struct CastHistoryTests {
    @MainActor static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("history.json")
        let suite = "CastHistoryTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CastHistoryStore(fileURL: file, defaults: defaults)
        let normal = WireProtocol.singleVideoCommand(url: "https://media.example/video.mp4", title: "Fixture",
            headers: ["Referer": "https://page.example/"], playerMode: "native")
        let marked = WireProtocol.applyingHistoryPreference(normal, prevent: true)
        precondition(marked.contains("\"skipHistory\":true"))
        precondition(WireProtocol.applyingHistoryPreference(marked, prevent: false) == marked)
        precondition(WireProtocol.applyingHistoryPreference(normal, prevent: false) == normal)
        store.record(marked, receiver: "TV")
        precondition(store.entries.isEmpty)
        let mixed = """
        {"type":"command","action":"playlist","payload":{"items":[{"url":"https://media.example/public"},{"url":"https://media.example/private","skipHistory":true}]}}
        """
        store.record(mixed, receiver: "TV")
        precondition(store.entries.isEmpty && !FileManager.default.fileExists(atPath: file.path))
        let queue = WireProtocol.queueVideoCommand(url: "https://media.example/queued")
        precondition(CastHistoryStore.items(in: WireProtocol.applyingHistoryPreference(queue, prevent: true)) == nil)
        let control = WireProtocol.controlCommand("pause")
        precondition(WireProtocol.applyingHistoryPreference(control, prevent: true) == control)
        let resourceCommand = WireProtocol.addSubtitleCommand(url: "https://subs.example/file.vtt",
            headers: ["Origin": "https://site.example"], label: "English", requestID: "fixture-request")
        let resourceJSON = try JSONSerialization.jsonObject(with: Data(resourceCommand.utf8)) as! [String: Any]
        let resourcePayload = resourceJSON["payload"] as! [String: Any]
        let resource = resourcePayload["subtitleResource"] as! [String: Any]
        precondition(resourcePayload["command"] as? String == "add_subtitle")
        precondition(resourceJSON["requestId"] as? String == "fixture-request")
        precondition((resource["headers"] as? [String: String])?["Origin"] == "https://site.example")
        let imageCommand = WireProtocol.singleVideoCommand(url: "https://media.example/photo.jpg", mediaKind: "image")
        let imageItems = CastHistoryStore.items(in: imageCommand)!
        precondition(imageItems.first?["mediaKind"] as? String == "image")
        let queuedAudio = WireProtocol.queueVideoCommand(url: "https://media.example/song.mp3", mediaKind: "audio")
        let queuedJSON = try JSONSerialization.jsonObject(with: Data(queuedAudio.utf8)) as! [String: Any]
        let queuedPayload = queuedJSON["payload"] as! [String: Any]
        precondition((queuedPayload["item"] as? [String: Any])?["mediaKind"] as? String == "audio")
        store.record(control, receiver: nil)
        store.record(normal, receiver: "TV")
        store.record(normal, receiver: "TV")
        precondition(store.entries.count == 1 && store.persistenceError == nil)
        let reloaded = CastHistoryStore(fileURL: file, defaults: defaults)
        precondition(reloaded.entries.count == 1 && reloaded.entries[0].command == normal)
        for index in 0..<105 {
            store.record(WireProtocol.singleVideoCommand(url: "https://media.example/\(index)"), receiver: nil)
        }
        precondition(store.entries.count == 50, "The default limit is 50")
        precondition(store.removedBy(limit: 25) == 25)
        store.setLimit(25)
        precondition(store.entries.count == 25 && CastHistoryStore(fileURL: file, defaults: defaults).entries.count == 25)
        store.setLimit(37)
        precondition(CastHistorySettings.load(defaults).limit == 25, "A stray limit snaps to the nearest choice")
        store.delete(store.entries[0].id)
        precondition(CastHistoryStore(fileURL: file, defaults: defaults).entries.count == 24)
        store.clear()
        precondition(CastHistoryStore(fileURL: file, defaults: defaults).entries.isEmpty)
        try settingsChecks(directory)
        linkExpiryChecks()
        presentationChecks()
        trackerChecks()
        try progressChecks(directory)
        await linkCheckChecks(directory)
        print("PASS: history round trip, deduplication, limit, deletion, private playlists, queue flags, controls, settings, bridged apps, link expiry, wording, position tracking and progress")
    }

    static func settingsChecks(_ directory: URL) throws {
        let suite = "CastHistoryTests.settings." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let file = directory.appendingPathComponent("settings.json")
        let video = WireProtocol.singleVideoCommand(url: "https://cdn.example/v.mp4?Expires=1900000000", title: "Show")

        // Someone who already kept more than the new default keeps 100, not 50.
        var old: [CastHistoryStore.Entry] = []
        for index in 0..<70 { old.append(.init(command: WireProtocol.singleVideoCommand(url: "https://m.example/\(index)"), title: "\(index)", host: "m.example", receiver: nil)) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(old).write(to: file)
        let upgraded = CastHistoryStore(fileURL: file, defaults: defaults)
        precondition(upgraded.entries.count == 70 && CastHistorySettings.load(defaults).limit == 100, "Old entries load and aren't halved")
        precondition(upgraded.entries[0].pageURL == nil && upgraded.entries[0].preview == nil, "Entries saved before still read")
        upgraded.clear()

        let page = CastOrigin(pageURL: "https://site.example/watch/1")
        precondition(upgraded.record(video, receiver: "Den", receiverKind: "playbridge", origin: page) != nil)
        let entry = upgraded.entries[0]
        precondition(entry.pageURL == "https://site.example/watch/1" && entry.host == "site.example" && entry.receiverKind == "playbridge")
        precondition(entry.expiresAt == Date(timeIntervalSince1970: 1_900_000_000), "The link's stated expiry is kept")

        precondition(upgraded.record(video, receiver: nil, origin: .init(pageURL: "https://app.example", bridgedApp: true)) == nil,
                     "Bridged apps are left out by default")
        precondition(upgraded.record(video, receiver: nil, origin: .init(pageInitiated: true)) == nil,
                     "A website cast from an unknown page is left out")
        defaults.set(false, forKey: CastHistorySettings.excludeBridgedKey)
        precondition(upgraded.record(video, receiver: nil, origin: .init(pageURL: "https://app.example", bridgedApp: true)) != nil)
        defaults.set(false, forKey: CastHistorySettings.enabledKey)
        precondition(upgraded.record(video, receiver: nil) == nil, "History off records nothing")
        defaults.set(true, forKey: CastHistorySettings.enabledKey)

        let list = WireProtocol.singleVideoCommand(url: "https://cdn.example/a.mp4")
        let playlist = list.replacingOccurrences(of: "\"items\":[", with: "\"items\":[{\"url\":\"https://cdn.example/b.mp4\",\"title\":\"B\"},")
        precondition(CastHistoryStore.items(in: playlist)?.count == 2)
        upgraded.record(playlist, receiver: nil)
        precondition(upgraded.entries[0].playlistCount == 2 && CastHistoryPresentation.badge(upgraded.entries[0]) == "2 items")
        upgraded.clear()
    }

    static func linkExpiryChecks() {
        let saved = Date(timeIntervalSince1970: 1_700_000_000)
        func at(_ url: String) -> TimeInterval? { LinkExpiry.expiresAt(url, savedAt: saved)?.timeIntervalSince1970 }
        precondition(at("https://a.example/v.m3u8?expires=1700003600") == 1_700_003_600)
        precondition(at("https://a.example/v.m3u8?Expires=1700003600000") == 1_700_003_600, "Milliseconds")
        precondition(at("https://a.example/v.m3u8?X-Amz-Date=20231114T221320Z&X-Amz-Expires=3600") == 1_700_003_600)
        precondition(at("https://a.example/v.mp4?sv=2021&se=2023-11-14T23:13:20Z&sig=abc") == 1_700_003_600)
        precondition(at("https://a.example/hdnts=exp=1700003600~acl=/*~hmac=ff/master.m3u8") == 1_700_003_600, "Akamai path token")
        precondition(at("https://a.example/v.m3u8?hdnts=st%3D1%7Eexp%3D1700003600%7Ehmac%3Dff") == 1_700_003_600)
        let jwt = "eyJhbGciOiJIUzI1NiJ9." + Data("{\"exp\":1700003600}".utf8).base64EncodedString()
            .replacingOccurrences(of: "=", with: "") + ".sig"
        precondition(at("https://a.example/v.m3u8?t=\(jwt)") == 1_700_003_600)
        precondition(at("https://a.example/v.m3u8?exp=1700003600&expires=1700007200") == 1_700_003_600, "Earliest wins")
        precondition(at("https://a.example:8443/v.m3u8?e=42&id=1700003600") == nil, "Short counters and other names aren't expiries")
        precondition(at("https://a.example/v.m3u8?expires=99999999999999") == nil, "Implausible dates are ignored")
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        precondition(LinkStatus.of(expiresAt: now.addingTimeInterval(7200), now: now).label(now: now) == "Link expires in 2 h")
        precondition(LinkStatus.of(expiresAt: now.addingTimeInterval(-3 * 86_400), now: now).label(now: now) == "Link expired 3 days ago")
        precondition(LinkStatus.of(expiresAt: now.addingTimeInterval(3 * 86_400), now: now) == .none)
        precondition(LinkStatus.of(expiresAt: nil, now: now) == .none)
    }

    static func presentationChecks() {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "UTC")!
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        func ago(_ seconds: TimeInterval) -> String { CastHistoryPresentation.ago(now.addingTimeInterval(-seconds), now: now, calendar: calendar) }
        precondition(ago(10) == "just now" && ago(20 * 60) == "20 min ago" && ago(2 * 3600) == "2 h ago")
        precondition(ago(30 * 3600) == "Yesterday" && ago(3 * 86_400) == "3 days ago" && ago(9 * 86_400) == "last week")
        precondition(CastHistoryPresentation.dayLabel(now, now: now, calendar: calendar) == "Today")
        precondition(CastHistoryPresentation.receiverLabel(name: "Kitchen", kind: "dlna") == "Kitchen (DLNA)")
        precondition(CastHistoryPresentation.receiverLabel(name: nil, kind: nil) == "TV")
        precondition(CastHistoryPresentation.receiverLabel(name: "AirPlay", kind: "airplay") == "AirPlay")
        var entry = CastHistoryStore.Entry(command: "{}", title: " ", host: "site.example", receiver: "Den")
        entry.date = now.addingTimeInterval(-7200)
        precondition(CastHistoryPresentation.title(entry) == "site.example")
        precondition(CastHistoryPresentation.subtitle(entry) == "site.example · Den")
        precondition(CastHistoryPresentation.status(entry, now: now) == "Cast 2 h ago")
        var older = entry; older.date = now.addingTimeInterval(-30 * 3600)
        let groups = CastHistoryPresentation.groupByDay([entry, entry, older], now: now, calendar: calendar)
        precondition(groups.map(\.label) == ["Today", "Yesterday"] && groups[0].entries.count == 2)
        precondition(CastHistoryPresentation.storageLine(casts: 1, pictures: 3) == "1 cast · 3 pictures")
        var expired = entry; expired.expiresAt = now.addingTimeInterval(-60)
        precondition(CastHistoryFilter.expired.matches(expired, now: now) && !CastHistoryFilter.expired.matches(entry, now: now))
        print("PASS: link expiry and history wording")
    }

    static func trackerChecks() {
        typealias T = CastPositionTracker
        func sample(_ state: PlaybackSample.State, _ position: Int64, _ duration: Int64 = 600_000, title: String? = "Show",
                    live: Bool = false, index: Int? = nil, count: Int? = nil) -> PlaybackSample {
            PlaybackSample(state: state, positionMs: position, durationMs: duration, isLive: live, title: title,
                           playlistIndex: index, playlistCount: count)
        }
        let first = UUID(), second = UUID()
        var tracker = T()
        precondition(tracker.onSample(sample(.playing, 5_000), at: 0) == nil, "Nothing tracked yet")
        _ = tracker.begin(first, at: 1_000, title: "Show", playlistIndex: nil, playlistCount: nil)
        // The previous cast's report keeps arriving until the receiver says something new.
        precondition(tracker.onSample(sample(.playing, 5_000), at: 1_500) == nil && tracker.nowPlaying == nil)
        var write = tracker.onSample(sample(.playing, 6_000, 700_000), at: 2_000)
        precondition(write?.id == first && write?.progress.positionMs == 6_000 && write?.progress.positionReported == true)
        precondition(tracker.nowPlaying?.id == first && tracker.nowPlaying?.paused == false)
        precondition(tracker.onSample(sample(.playing, 9_000, 700_000), at: 5_000) == nil, "Positions save about every 10 s")
        write = tracker.onSample(sample(.playing, 16_000, 700_000), at: 12_500)
        precondition(write?.progress.positionMs == 16_000)
        write = tracker.onSample(sample(.paused, 17_000, 700_000), at: 13_000)
        precondition(write?.progress.positionMs == 17_000 && tracker.nowPlaying?.paused == true, "A pause saves at once")
        write = tracker.onSample(sample(.stopped, 0, 700_000), at: 14_000)
        precondition(write == nil, "A stop keeps the last real position, already saved at the pause")
        precondition(tracker.flush() == nil)
        precondition(tracker.nowPlaying == nil)

        // A quick second cast hands back the first one's unsaved progress.
        precondition(tracker.onSample(sample(.playing, 20_000, 700_000), at: 15_000) != nil, "Playing again saves at once")
        precondition(tracker.onSample(sample(.playing, 22_000, 700_000), at: 15_500) == nil, "Not due yet")
        let pending = tracker.begin(second, at: 16_000, title: "Other", playlistIndex: nil, playlistCount: nil)
        precondition(pending?.id == first && pending?.progress.positionMs == 22_000)
        precondition(tracker.onSample(sample(.playing, 21_000, 700_000), at: 16_500) == nil, "The old title isn't the new cast")
        write = tracker.onSample(sample(.playing, 1_000, 300_000, title: "Other"), at: 17_000)
        precondition(write?.id == second)
        write = tracker.onSample(sample(.playing, 290_000, 300_000, title: "Other"), at: 40_000)
        precondition(write?.progress.finished == true, "Within 5% of the end is finished")

        var failing = T()
        _ = failing.begin(first, at: 0, title: nil, playlistIndex: nil, playlistCount: nil)
        write = failing.onSample(sample(.error, 0, 0, title: nil), at: 100)
        precondition(write?.progress.failed == true && write?.progress.failureReason == T.failReceiverError)
        var early = T()
        _ = early.begin(first, at: 0, title: nil, playlistIndex: nil, playlistCount: nil)
        _ = early.onSample(sample(.buffering, 0, 0, title: nil), at: 100)
        write = early.onSample(sample(.stopped, 0, 0, title: nil), at: 200)
        precondition(write?.progress.failureReason == T.failStoppedEarly, "Stopped before anything played")

        var live = T()
        _ = live.begin(first, at: 0, title: nil, playlistIndex: nil, playlistCount: nil)
        _ = live.onSample(sample(.playing, 1_000, 0, title: nil, live: true), at: 1_000)
        write = live.onSample(sample(.playing, 13_000, 0, title: nil, live: true), at: 13_000)
        precondition(write?.progress.live == true && write?.progress.liveWatchedMs == 12_000 && write?.progress.positionReported == false,
                     "Live streams keep time watched, not a position")

        var list = T()
        _ = list.begin(first, at: 0, title: nil, playlistIndex: 0, playlistCount: 3)
        _ = list.onSample(sample(.playing, 590_000, 600_000, title: nil, index: 0, count: 3), at: 1_000)
        write = list.onSample(sample(.playing, 2_000, 400_000, title: nil, index: 1, count: 3), at: 2_000)
        precondition(write?.progress.playlistIndex == 1 && write?.progress.positionMs == 2_000 && write?.progress.finished == false,
                     "A new item has its own position")
        // Resuming: the receiver reports 0 while it loads, then jumps to the saved place.
        var resumed = T()
        let saved = CastProgress(positionMs: 517_000, durationMs: 912_000, positionReported: true)
        _ = resumed.begin(first, at: 0, title: "Show", playlistIndex: nil, playlistCount: nil, resumeFrom: saved)
        _ = resumed.onSample(sample(.buffering, 0, 0), at: 500)
        write = resumed.onSample(sample(.playing, 0, 912_000), at: 1_000)
        precondition(write == nil || write?.progress.positionMs == 517_000, "Loading from 0 doesn't overwrite the resume point")
        precondition(resumed.flush() == nil || resumed.flush()?.progress.positionMs == 517_000)
        write = resumed.onSample(sample(.playing, 518_000, 912_000), at: 4_000)
        precondition(write?.progress.positionMs == 518_000, "Once it gets there, positions save again")
        var gaveUp = T()
        _ = gaveUp.begin(first, at: 0, title: "Show", playlistIndex: nil, playlistCount: nil, resumeFrom: saved)
        _ = gaveUp.onSample(sample(.playing, 1_000, 912_000), at: 1_000)
        write = gaveUp.onSample(sample(.paused, 31_000, 912_000), at: 31_000)
        precondition(write?.progress.positionMs == 31_000, "A receiver that started over is believed after 30 s")
        precondition(T.reachedEnd(positionMs: 55_000, durationMs: 60_000) && !T.reachedEnd(positionMs: 40_000, durationMs: 60_000))
        precondition(T.reachedEnd(positionMs: 3_591_000, durationMs: 3_600_000), "Under 10 s left on a long video")
    }

    static func progressChecks(_ directory: URL) throws {
        let suite = "CastHistoryTests.progress." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let file = directory.appendingPathComponent("progress.json")
        let store = CastHistoryStore(fileURL: file, defaults: defaults)
        let command = WireProtocol.singleVideoCommand(url: "https://cdn.example/movie.mp4", title: "Movie")
        let id = store.record(command, receiver: "Den")!
        precondition(store.entries[0].status == .unknown && CastHistoryPresentation.status(store.entries[0], now: Date()).hasPrefix("Cast "))
        store.update(id, progress: CastProgress(positionMs: 178_000, durationMs: 734_000, positionReported: true))
        let entry = CastHistoryStore(fileURL: file, defaults: defaults).entries[0]
        precondition(entry.status == .inProgress && entry.canResume, "Progress is saved and reloads")
        precondition(CastHistoryPresentation.status(entry, now: entry.date) == "Stopped at 2:58 · just now")
        precondition(CastHistoryPresentation.stoppedDetail(entry) == "Stopped at 2:58 of 12:14")
        precondition(CastHistoryPresentation.resumeLabel(entry) == "Resume from 2:58")
        precondition(CastHistoryPresentation.badge(entry) == "12:14")
        precondition(CastHistoryFilter.unfinished.matches(entry, now: Date()) && !CastHistoryFilter.failed.matches(entry, now: Date()))
        store.record(command, receiver: "Den")
        precondition(store.entries.count == 1 && store.entries[0].progress?.positionMs == 178_000, "Casting it again keeps where it got to")

        defaults.set(false, forKey: CastHistorySettings.rememberPositionKey)
        store.update(store.entries[0].id, progress: CastProgress(positionMs: 300_000, durationMs: 734_000, positionReported: true))
        precondition(store.entries[0].progress?.positionMs == 178_000, "With positions off nothing new is saved")

        let failed = store.record(WireProtocol.singleVideoCommand(url: "https://cdn.example/b.mp4"), receiver: "Den",
                                  failure: CastPositionTracker.failNotSent)!
        let failedEntry = store.entries.first { $0.id == failed }!
        precondition(failedEntry.status == .failed && CastHistoryFilter.failed.matches(failedEntry, now: Date()))
        precondition(CastHistoryPresentation.status(failedEntry, now: failedEntry.date) == "Couldn't reach the TV · just now")
        var live = failedEntry; live.progress = CastProgress(live: true, liveWatchedMs: 65 * 60_000)
        precondition(CastHistoryPresentation.badge(live) == "LIVE" && CastHistoryPresentation.status(live, now: live.date) == "Watched for 1 h 5 min · just now")
        store.clear()
    }

    @MainActor static func linkCheckChecks(_ directory: URL) async {
        precondition(LinkCheck.result(forStatus: 206) == .works && LinkCheck.result(forStatus: 410) == .expired)
        precondition(LinkCheck.result(forStatus: 403) == .expired && LinkCheck.result(forStatus: 500) == .couldNotCheck)
        precondition(LinkCheck.result(forStatus: 302) == .couldNotCheck && LinkCheck.result(forStatus: nil) == .couldNotCheck)
        for host in ["192.168.1.20", "10.0.0.5", "172.20.1.1", "127.0.0.1", "nas.local", "router", "fd00::1", "localhost"] {
            precondition(LinkCheck.isLocal(host), "\(host) is not checked")
        }
        precondition(!LinkCheck.isLocal("cdn.example.com") && !LinkCheck.isLocal("8.8.8.8") && !LinkCheck.isLocal("172.32.0.1"))
        let local = await LinkCheck.check(url: "http://192.168.1.20/movie.mp4", headers: [:])
        precondition(local == .couldNotCheck, "Home-network links aren't asked")

        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let past = now.addingTimeInterval(-3600)
        precondition(LinkStatus.of(expiresAt: past, now: now, check: .works, checkedAt: now) == .none, "A later check that worked wins")
        precondition(LinkStatus.of(expiresAt: past, now: now, check: .works, checkedAt: past.addingTimeInterval(-60)).isExpired)
        precondition(LinkStatus.of(expiresAt: nil, now: now, check: .expired) == .expired(nil))
        precondition(LinkStatus.of(expiresAt: nil, now: now, check: .couldNotCheck).label(now: now) == "Couldn’t check · may have expired")
        precondition(LinkStatus.of(expiresAt: nil, now: now, checking: true).label(now: now) == "Checking the link…")

        let suite = "CastHistoryTests.links." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CastHistoryStore(fileURL: directory.appendingPathComponent("links.json"), defaults: defaults)
        let id = store.record(WireProtocol.singleVideoCommand(url: "https://cdn.example/v.mp4", headers: ["Cookie": "session=1"]), receiver: nil)!
        var asked: (String, [String: String])?
        await store.checkLink(id, now: now) { url, headers in asked = (url, headers); return .expired }
        precondition(asked?.0 == "https://cdn.example/v.mp4" && asked?.1["Cookie"] == "session=1", "The saved link and headers are what's checked")
        precondition(store.entries[0].linkCheck == .expired && store.entries[0].linkStatus(now: now).isExpired && store.checking.isEmpty)
        precondition(CastHistoryFilter.expired.matches(store.entries[0], now: now))
        store.clear()
        print("PASS: link checks, local-address guard and status priority")
    }
}
