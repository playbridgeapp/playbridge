import 'dart:convert';

/// Intro / recap / outro / preview range, matching Android TV `SkipSegment`.
class SkipSegment {
  const SkipSegment({
    required this.type,
    required this.startMs,
    required this.endMs,
  });

  final String type;
  final int startMs;
  final int endMs;

  @override
  bool operator ==(Object other) =>
      other is SkipSegment &&
      other.type == type &&
      other.startMs == startMs &&
      other.endMs == endMs;

  @override
  int get hashCode => Object.hash(type, startMs, endMs);
}

/// Sentinel end for TheIntroDB `end_ms: null` (credits to EOF).
const int skipSegmentOpenEndedMs = 9223372036854775807 ~/ 2;

const int skipEndSeekPaddingMs = 250;

/// Fetch when an IMDb or TMDB id is present. Episodes need both season and
/// episode; movies have neither. A half-specified episode is skipped.
bool skipFetchAllowed({
  String? imdbId,
  String? tmdbId,
  int? season,
  int? episode,
}) {
  final hasId = (imdbId != null && imdbId.isNotEmpty) ||
      (tmdbId != null && tmdbId.isNotEmpty);
  final validShape = (season != null && episode != null) ||
      (season == null && episode == null);
  return hasId && validShape;
}

class SkipAutoPrefs {
  const SkipAutoPrefs({
    this.intro = false,
    this.recap = false,
    this.outro = false,
  });

  final bool intro;
  final bool recap;
  final bool outro;

  bool enabledFor(String type) => switch (type) {
        'intro' => intro,
        'recap' => recap,
        'outro' => outro,
        _ => false,
      };
}

SkipSegment? activeSkipSegment({
  required List<SkipSegment> segments,
  required int positionMs,
  SkipSegment? lastSkipped,
}) {
  for (final segment in segments) {
    if (positionMs >= segment.startMs &&
        positionMs <= segment.endMs &&
        segment != lastSkipped) {
      return segment;
    }
  }
  return null;
}

bool skipSegmentEndsPlayback(SkipSegment segment, int durationMs) =>
    segment.endMs == skipSegmentOpenEndedMs ||
    (durationMs > 0 && segment.endMs >= durationMs - 1000);

int skipTargetMs(SkipSegment segment, int durationMs) {
  if (durationMs > 0 && skipSegmentEndsPlayback(segment, durationMs)) {
    final target = durationMs - skipEndSeekPaddingMs;
    return target < 0 ? 0 : target;
  }
  if (segment.endMs == skipSegmentOpenEndedMs) {
    return segment.startMs < 0 ? 0 : segment.startMs;
  }
  return segment.endMs + 1000;
}

String skipButtonLabel(String type) {
  if (type.isEmpty) return 'Skip';
  return 'Skip ${type[0].toUpperCase()}${type.substring(1)}';
}

/// IntroDB `{ "intro": {start_ms, end_ms}, … }`.
List<SkipSegment> parseIntroDbSegments(Map<String, dynamic> json) {
  final segments = <SkipSegment>[];
  for (final type in const ['intro', 'recap', 'outro']) {
    final raw = json[type];
    if (raw is! Map) continue;
    final startMs = _msFrom(raw, 'start_ms', 'start_sec');
    final endMs = _msFrom(raw, 'end_ms', 'end_sec');
    if (startMs != null && endMs != null) {
      segments.add(SkipSegment(type: type, startMs: startMs, endMs: endMs));
    }
  }
  return segments;
}

/// TheIntroDB v3 `{ "intro": [{start_ms, end_ms}], "credits": [...], … }`.
List<SkipSegment> parseTheIntroDbSegments(Map<String, dynamic> json) {
  const typeMap = <String, String>{
    'intro': 'intro',
    'recap': 'recap',
    'outro': 'outro',
    'credits': 'outro',
    'preview': 'preview',
  };
  final seen = <String>{};
  final segments = <SkipSegment>[];
  for (final entry in typeMap.entries) {
    if (seen.contains(entry.value)) continue;
    final raw = json[entry.key];
    if (raw is! List) continue;
    seen.add(entry.value);
    for (final item in raw) {
      if (item is! Map) continue;
      final startMs = _asInt(item['start_ms']);
      if (startMs == null || startMs < 0) continue;
      final endRaw = item['end_ms'];
      final int endMs;
      if (endRaw == null) {
        endMs = skipSegmentOpenEndedMs;
      } else {
        final parsed = _asInt(endRaw);
        if (parsed == null || parsed <= startMs) continue;
        endMs = parsed;
      }
      segments.add(
        SkipSegment(type: entry.value, startMs: startMs, endMs: endMs),
      );
    }
  }
  return segments;
}

List<SkipSegment> mergeSkipProviders({
  required List<SkipSegment> introDb,
  required List<SkipSegment> theIntroDb,
}) {
  final covered = introDb.map((s) => s.type).toSet();
  return [
    ...introDb,
    ...theIntroDb.where((s) => !covered.contains(s.type)),
  ]..sort((a, b) => a.startMs.compareTo(b.startMs));
}

Map<String, dynamic>? decodeJsonObject(String body) {
  final decoded = jsonDecode(body);
  if (decoded is Map<String, dynamic>) return decoded;
  if (decoded is Map) return Map<String, dynamic>.from(decoded);
  return null;
}

int? _msFrom(Map raw, String msKey, String secKey) {
  final ms = _asInt(raw[msKey]);
  if (ms != null && ms != -1) return ms;
  final sec = raw[secKey];
  if (sec is num && sec != -1) return (sec * 1000).round();
  return null;
}

int? _asInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.round();
  return null;
}
