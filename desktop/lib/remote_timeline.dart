import 'player_engine.dart';
import 'skip_segment.dart';

const int timelineMaxChapters = 256;
const int timelineMaxSegments = 16;
const int timelineMaxTitleLength = 200;
const _timelineSegmentTypes = {'intro', 'recap', 'outro', 'preview'};

/// The `timeline` receiver frame: chapter markers and skip segments for the
/// current item. Hand-written JSON (not a generated protobuf model); bounds
/// match `Timeline` in protocol/asyncapi.yaml.
Map<String, Object?> buildTimelineMessage({
  String? playbackId,
  String? currentItemId,
  List<ChapterInfo> chapters = const [],
  List<SkipSegment> segments = const [],
}) {
  final sortedChapters = chapters.where((c) => c.startMs >= 0).toList()
    ..sort((a, b) => a.startMs.compareTo(b.startMs));
  final sortedSegments = segments
      .where((s) =>
          _timelineSegmentTypes.contains(s.type) &&
          s.startMs >= 0 &&
          (s.endMs == skipSegmentOpenEndedMs || s.endMs > s.startMs))
      .toList()
    ..sort((a, b) => a.startMs.compareTo(b.startMs));
  return {
    'type': 'timeline',
    if (playbackId != null) 'playbackId': playbackId,
    if (currentItemId != null) 'currentItemId': currentItemId,
    'chapters': [
      for (final chapter in sortedChapters.take(timelineMaxChapters))
        {
          'startMs': chapter.startMs,
          if (_chapterTitle(chapter.title) case final title?) 'title': title,
        },
    ],
    'segments': [
      for (final segment in sortedSegments.take(timelineMaxSegments))
        {
          'type': segment.type,
          'startMs': segment.startMs,
          'endMs':
              segment.endMs == skipSegmentOpenEndedMs ? null : segment.endMs,
        },
    ],
  };
}

String? _chapterTitle(String? raw) {
  final title = raw?.trim();
  if (title == null || title.isEmpty) return null;
  final runes = title.runes;
  if (runes.length <= timelineMaxTitleLength) return title;
  return String.fromCharCodes(runes.take(timelineMaxTitleLength)).trimRight();
}
