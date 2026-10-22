import Foundation

/// A chapter jump target: its 1-based number and the chapter itself.
struct ChapterJumpTarget: Equatable {
    var number: Int
    var chapter: TimelineChapter
}

/// One entry of the AVPlayer Chapters panel. `endMs` is nil when the media
/// duration is unknown and this is the last chapter.
struct ChapterNavigationMarker: Equatable {
    var startMs: Int
    var endMs: Int?
    var title: String
}

/// Chapter rules shared by the mpv HUD and the AVPlayer marker list. A port of
/// `desktop/lib/chapter_navigation.dart` and the phone remote's timeline caption,
/// so all receivers jump and label alike. Chapters must be `sorted(_:)` first.
enum ChapterNavigation {
    /// mpv's `chapter-seek-threshold`: further than this into a chapter, "previous"
    /// restarts it instead of going back one.
    static let restartThresholdMs = 5000
    /// Position lag allowed right after a jump, so "next" does not pick the
    /// chapter the player is still seeking to.
    static let leadMs = 1000
    /// How long the jump toast replaces the playhead caption.
    static let toastSeconds = 2.0
    /// Toast when "next" has nowhere to go.
    static let lastChapterLabel = "Last chapter"

    /// Same filter and order as the `timeline` frame.
    static func sorted(_ chapters: [TimelineChapter]) -> [TimelineChapter] {
        Array(chapters.filter { $0.startMs >= 0 }.sorted { $0.startMs < $1.startMs }
            .prefix(TimelineReport.maxChapters))
    }

    /// Chapter navigation is available with two or more markers.
    static func canJump(_ chapters: [TimelineChapter]) -> Bool { chapters.count >= 2 }

    /// Where "next" (`forward`) or "previous" goes from `positionMs`. Nil with
    /// fewer than two chapters or past the last one.
    static func jumpTarget(chapters: [TimelineChapter], positionMs: Int, forward: Bool) -> ChapterJumpTarget? {
        guard canJump(chapters) else { return nil }
        if forward {
            guard let next = chapters.firstIndex(where: { $0.startMs > positionMs + leadMs }) else { return nil }
            return ChapterJumpTarget(number: next + 1, chapter: chapters[next])
        }
        guard let current = chapters.lastIndex(where: { $0.startMs <= positionMs }) else {
            return ChapterJumpTarget(number: 1, chapter: chapters[0])
        }
        let intoChapter = positionMs - chapters[current].startMs
        let index = intoChapter > restartThresholdMs ? current : max(current - 1, 0)
        return ChapterJumpTarget(number: index + 1, chapter: chapters[index])
    }

    /// "Chapter 3 · The Heist" or "Chapter 3".
    static func label(_ target: ChapterJumpTarget) -> String {
        guard let title = TimelineReport.title(target.chapter.title),
              !isGenericTitle(title, number: target.number) else { return "Chapter \(target.number)" }
        return "Chapter \(target.number) · \(title)"
    }

    /// Muxers often name chapters "Chapter 2" / "Chapter 02"; repeating that after
    /// the number reads "Chapter 2 · Chapter 2".
    static func isGenericTitle(_ title: String, number: Int) -> Bool {
        let parts = title.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber })
        guard parts.count == 2, parts[0] == "chapter", let n = Int(parts[1]) else { return false }
        return n == number
    }

    /// Toast for a jump; nil target means "next" ran past the last chapter.
    static func toast(_ target: ChapterJumpTarget?) -> String {
        target.map(label) ?? lastChapterLabel
    }

    /// "Chapter 2 · The Heist", "Chapter 3", or just the title of a file's only
    /// chapter. Nil before the first chapter or with none.
    static func captionAt(chapters: [TimelineChapter], positionMs: Int) -> String? {
        guard let index = chapters.lastIndex(where: { $0.startMs <= positionMs }) else { return nil }
        if chapters.count < 2 { return TimelineReport.title(chapters[index].title) }
        return label(ChapterJumpTarget(number: index + 1, chapter: chapters[index]))
    }

    /// The phone remote's segment names: outro shows as "Credits".
    static func segmentLabel(_ type: String) -> String {
        switch type {
        case "intro": return "Intro"
        case "recap": return "Recap"
        case "outro": return "Credits"
        case "preview": return "Preview"
        default: return type.prefix(1).uppercased() + type.dropFirst()
        }
    }

    /// The segment holding `positionMs`; an open-ended segment runs to `durationMs`.
    static func segmentAt(segments: [SkipSegment], positionMs: Int, durationMs: Int) -> SkipSegment? {
        segments.first { segment in
            segment.isValid && positionMs >= segment.startMs && positionMs < (segment.endMs ?? max(durationMs, 0))
        }
    }

    /// Caption under the seek bar: a skip segment wins, then the chapter.
    static func timelineCaption(chapters: [TimelineChapter], segments: [SkipSegment],
                                positionMs: Int, durationMs: Int) -> String? {
        if let segment = segmentAt(segments: segments, positionMs: positionMs, durationMs: durationMs) {
            return segmentLabel(segment.type)
        }
        return captionAt(chapters: chapters, positionMs: positionMs)
    }

    /// Chapter starts as 0..1 track fractions, skipping the first chapter at 0
    /// and any at or past the end. Empty with fewer than two chapters or no duration.
    static func chapterFractions(chapters: [TimelineChapter], durationMs: Int) -> [Double] {
        guard canJump(chapters), durationMs > 0 else { return [] }
        return chapters.compactMap { chapter in
            chapter.startMs > 0 && chapter.startMs < durationMs
                ? Double(chapter.startMs) / Double(durationMs) : nil
        }
    }

    /// Skip-segment bands as 0..1 track fractions. Open-ended segments run to the
    /// end; empty ranges and an unknown duration draw nothing.
    static func segmentRanges(segments: [SkipSegment], durationMs: Int) -> [ClosedRange<Double>] {
        guard durationMs > 0 else { return [] }
        let duration = Double(durationMs)
        return segments.filter(\.isValid).compactMap { segment in
            let start = min(max(Double(segment.startMs) / duration, 0), 1)
            let end = min(max(Double(segment.endMs ?? durationMs) / duration, 0), 1)
            return end > start ? start...end : nil
        }
    }

    /// AVPlayer Chapters panel entries: each runs to the next chapter start, the
    /// last to the duration (nil when unknown). Untitled chapters read "Chapter N".
    /// Empty with fewer than two chapters.
    static func navigationMarkers(chapters: [TimelineChapter], durationMs: Int?) -> [ChapterNavigationMarker] {
        let sorted = sorted(chapters)
        guard canJump(sorted) else { return [] }
        return sorted.enumerated().map { index, chapter in
            let next = index + 1 < sorted.count ? sorted[index + 1].startMs : durationMs.flatMap { $0 > chapter.startMs ? $0 : nil }
            return ChapterNavigationMarker(
                startMs: chapter.startMs,
                endMs: next,
                title: TimelineReport.title(chapter.title) ?? "Chapter \(index + 1)")
        }
    }
}
