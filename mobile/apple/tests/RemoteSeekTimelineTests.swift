import Foundation

enum RemoteSeekTimelineTests {
    @MainActor static func run() async throws {
        let chapters = [RemoteChapter(startMs: 0, title: "Opening"), RemoteChapter(startMs: 600_000, title: "The Heist"),
                        RemoteChapter(startMs: 1_800_000)]
        let segments = [RemoteSegment(type: "intro", startMs: 30_000, endMs: 95_000),
                        RemoteSegment(type: "outro", startMs: 5_400_000, endMs: nil)]

        // Envelope is flat at both ends and full in the middle.
        precondition(RemoteSeekTimeline.waveEnvelope(x: 0, thumbX: 400, wavelength: 40) == 0)
        precondition(RemoteSeekTimeline.waveEnvelope(x: 400, thumbX: 400, wavelength: 40) == 0)
        precondition(abs(RemoteSeekTimeline.waveEnvelope(x: 200, thumbX: 400, wavelength: 40) - 1) < 0.0001)
        precondition(RemoteSeekTimeline.waveEnvelope(x: 380, thumbX: 400, wavelength: 40) < 0.5)

        // Caption prefers the segment, then the numbered chapter.
        func caption(_ ms: Int64) -> String? {
            RemoteSeekTimeline.caption(chapters: chapters, segments: segments, positionMs: ms, durationMs: 6_000_000)
        }
        precondition(caption(60_000) == "Intro")
        precondition(caption(700_000) == "Chapter 2 · The Heist")
        precondition(caption(2_000_000) == "Chapter 3")
        precondition(caption(5_900_000) == "Credits")
        precondition(RemoteSeekTimeline.caption(chapters: [], segments: [], positionMs: 10, durationMs: 6_000_000) == nil)
        precondition(RemoteSeekTimeline.caption(chapters: [RemoteChapter(startMs: 0, title: "Movie")], segments: [],
                                                positionMs: 10, durationMs: 100_000) == "Movie")

        // Scrub snaps to a nearby chapter or segment end only.
        func snap(_ ms: Int64) -> Int64 {
            RemoteSeekTimeline.snap(ms, durationMs: 6_000_000, chapters: chapters, segments: segments, toleranceMs: 5_000)
        }
        precondition(snap(97_000) == 95_000)
        precondition(snap(596_000) == 600_000)
        precondition(snap(300_000) == 300_000)
        precondition(snap(1_000) == 1_000, "Chapter 0 and open-ended segment ends are not magnets")
        precondition(RemoteSeekTimeline.snapToleranceMs(durationMs: 60_000) == 1_500)
        precondition(RemoteSeekTimeline.snapToleranceMs(durationMs: 6_000_000) == 45_000)

        // The timeline frame parses bounded, sorted and with open-ended segments.
        let coordinator = ConnectionCoordinator()
        coordinator.handle(#"""
        {"type":"timeline","playbackId":"p1","currentItemId":"i1",
         "chapters":[{"startMs":312000},{"startMs":0,"title":"  Opening  "},{"startMs":-5},{"title":"x"},{"startMs":true}],
         "segments":[{"type":"outro","startMs":5400000,"endMs":null},{"type":"intro","startMs":30000,"endMs":95000},
                     {"type":"ad","startMs":1,"endMs":2},{"type":"recap","startMs":50,"endMs":40}]}
        """#)
        coordinator.handle(#"{"type":"status","state":"playing","position":1000,"duration":6000000,"buffered":42000}"#)
        try await Task.sleep(nanoseconds: 50_000_000)
        let timeline = try unwrap(coordinator.timeline)
        precondition(timeline.playbackId == "p1" && timeline.currentItemId == "i1")
        precondition(timeline.chapters == [RemoteChapter(startMs: 0, title: "Opening"), RemoteChapter(startMs: 312_000)])
        precondition(timeline.segments == [RemoteSegment(type: "intro", startMs: 30_000, endMs: 95_000),
                                           RemoteSegment(type: "outro", startMs: 5_400_000, endMs: nil)])
        precondition(coordinator.playback?.bufferedMs == 42_000)
        // A timeline for another queue item is never drawn.
        precondition(timeline.marks(forQueueItem: "i1") != nil && timeline.marks(forQueueItem: nil) != nil)
        precondition(timeline.marks(forQueueItem: "i2") == nil)

        coordinator.handle(#"{"type":"timeline"}"#)
        coordinator.handle(#"{"type":"status","state":"playing","position":1000,"duration":6000000}"#)
        try await Task.sleep(nanoseconds: 50_000_000)
        precondition(coordinator.timeline == TvTimeline() && coordinator.playback?.bufferedMs == nil)
        coordinator.handle(#"{"type":"context","active":"idle"}"#)
        try await Task.sleep(nanoseconds: 50_000_000)
        precondition(coordinator.timeline == nil, "Going idle forgets the timeline")
        print("PASS: seek timeline wave envelope, captions, snapping, timeline parse/bounds/item match, buffered status and idle reset")
    }

    private struct Missing: Error {}
    private static func unwrap<T>(_ value: T?) throws -> T {
        guard let value else { throw Missing() }
        return value
    }
}
