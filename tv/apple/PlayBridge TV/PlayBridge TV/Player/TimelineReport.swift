import Foundation

/// One chapter marker of the open media.
struct TimelineChapter: Equatable {
    var startMs: Int
    var title: String?
}

/// The `timeline` receiver frame and `status.buffered`, with the same bounds as the
/// Android TV and desktop receivers (`Timeline` in protocol/asyncapi.yaml). Hand-written
/// JSON, not a generated protobuf model.
enum TimelineReport {
    nonisolated static let maxSegments = 16
    static let maxChapters = 256
    static let maxTitleLength = 200
    /// mpv's placeholder for a chapter without a title (Matroska).
    private static let mpvUnnamedChapter = "(unnamed)"

    /// The frame body; the server adds `playbackId` and `currentItemId`.
    static func frame(chapters: [TimelineChapter], segments: [SkipSegment] = []) -> [String: Any] {
        let sorted = chapters.filter { $0.startMs >= 0 }.sorted { $0.startMs < $1.startMs }
        return [
            "type": "timeline",
            "chapters": sorted.prefix(maxChapters).map { chapter -> [String: Any] in
                var entry: [String: Any] = ["startMs": chapter.startMs]
                if let title = title(chapter.title) { entry["title"] = title }
                return entry
            },
            "segments": segments.filter(\.isValid).sorted { $0.startMs < $1.startMs }
                .prefix(maxSegments).map { segment -> [String: Any] in
                    ["type": segment.type, "startMs": segment.startMs,
                     "endMs": segment.endMs.map { $0 as Any } ?? NSNull()]
                },
        ]
    }

    /// Trimmed title capped at `maxTitleLength` characters; blank is dropped.
    static func title(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed.count <= maxTitleLength
            ? trimmed
            : String(trimmed.prefix(maxTitleLength)).trimmingCharacters(in: .whitespaces)
    }

    /// Parses mpv's string form of `chapter-list` (JSON `[{"title","time"}]`, time in
    /// seconds). Malformed entries are skipped.
    static func parseMpvChapterList(_ raw: String) -> [TimelineChapter] {
        guard let data = raw.data(using: .utf8),
              let list = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        return list.compactMap { entry in
            guard let object = entry as? [String: Any],
                  let number = object["time"] as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            let seconds = number.doubleValue
            guard seconds.isFinite, seconds >= 0, seconds < 1e12 else { return nil }
            let title = (object["title"] as? String).flatMap { $0 == mpvUnnamedChapter ? nil : $0 }
            return TimelineChapter(startMs: Int((seconds * 1000).rounded()), title: title)
        }
    }

    /// `status.buffered` in ms, kept within [position, duration]; nil when unknown or live.
    static func buffered(bufferedSeconds: Double, positionSeconds: Double, durationSeconds: Double) -> Int? {
        let duration = PlaybackTime.milliseconds(durationSeconds)
        guard duration > 0, bufferedSeconds.isFinite, bufferedSeconds > 0 else { return nil }
        let floor = min(PlaybackTime.milliseconds(positionSeconds), duration)
        return min(max(PlaybackTime.milliseconds(bufferedSeconds), floor), duration)
    }
}
