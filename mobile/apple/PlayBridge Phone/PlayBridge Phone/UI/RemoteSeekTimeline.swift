import Foundation

/// Seek-bar geometry shared with the Android remote (`RemoteSeekStyle`): one soft wave
/// on the played part that eases into a round thumb, flat when paused, still with a
/// shimmer while buffering. Sizes are points.
enum RemoteSeekStyle {
    static let wavelength = 44.0
    static let amplitude = 3.0
    static let stroke = 3.5
    static let track = 3.0
    static let segment = 7.0
    static let chapterGap = 3.0
    static let thumbRadius = 6.5
    static let thumbHeldRadius = 10.0
    /// The wave drifts one wavelength in this time while playing.
    static let drift: TimeInterval = 4.0
    static let grow: TimeInterval = 0.8
    static let flatten: TimeInterval = 0.55
    /// The wave fades to flat over this many wavelengths before the thumb.
    static let taperWavelengths = 1.5
}

enum RemoteSeekTimeline {
    /// Height multiplier (0...1) at `x` for a wave drawn on 0...`thumbX`: half a wavelength
    /// of ease-in from the start and `taperWavelengths` of ease-out into the thumb.
    static func waveEnvelope(x: Double, thumbX: Double, wavelength: Double) -> Double {
        guard x > 0, x < thumbX, wavelength > 0 else { return 0 }
        func smooth(_ t: Double) -> Double { let c = min(1, max(0, t)); return c * c * (3 - 2 * c) }
        let start = smooth(x / (wavelength * 0.5))
        let end = smooth((thumbX - x) / (wavelength * RemoteSeekStyle.taperWavelengths))
        return start * end
    }

    static func segmentLabel(_ type: String) -> String {
        switch type {
        case "intro": return "Intro"
        case "recap": return "Recap"
        case "outro": return "Credits"
        case "preview": return "Preview"
        default: return type.prefix(1).uppercased() + type.dropFirst()
        }
    }

    /// The segment containing `positionMs`; open-ended segments run to `durationMs`.
    static func segment(at positionMs: Int64, in segments: [RemoteSegment], durationMs: Int64) -> RemoteSegment? {
        segments.first { positionMs >= $0.startMs && positionMs < ($0.endMs ?? durationMs) }
    }

    /// 1-based number and chapter that `positionMs` falls in, or nil before the first or with none.
    static func chapter(at positionMs: Int64, in chapters: [RemoteChapter]) -> (number: Int, chapter: RemoteChapter)? {
        guard let index = chapters.lastIndex(where: { $0.startMs <= positionMs }) else { return nil }
        return (index + 1, chapters[index])
    }

    /// Scrub caption: the segment wins, then the chapter.
    static func caption(chapters: [RemoteChapter], segments: [RemoteSegment], positionMs: Int64, durationMs: Int64) -> String? {
        if let segment = segment(at: positionMs, in: segments, durationMs: durationMs) { return segmentLabel(segment.type) }
        guard let (number, chapter) = chapter(at: positionMs, in: chapters) else { return nil }
        if chapters.count < 2 { return chapter.title }
        return chapter.title.map { "Chapter \(number) · \($0)" } ?? "Chapter \(number)"
    }

    /// Scrub magnet: a target within `toleranceMs` of a chapter start or a segment end is
    /// pulled onto it. Returns `targetMs` unchanged when nothing is near.
    static func snap(_ targetMs: Int64, durationMs: Int64, chapters: [RemoteChapter], segments: [RemoteSegment], toleranceMs: Int64) -> Int64 {
        guard toleranceMs > 0 else { return targetMs }
        let anchors = chapters.compactMap { $0.startMs > 0 ? $0.startMs : nil }
            + segments.compactMap { segment in segment.endMs.flatMap { $0 < durationMs ? $0 : nil } }
        guard let nearest = anchors.min(by: { abs($0 - targetMs) < abs($1 - targetMs) }) else { return targetMs }
        return abs(nearest - targetMs) <= toleranceMs ? nearest : targetMs
    }

    /// About 1.2% of the media, so the magnet scales with length but stays small.
    static func snapToleranceMs(durationMs: Int64) -> Int64 {
        min(45_000, max(1_500, Int64(Double(durationMs) * 0.012)))
    }
}
