import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/skip_segment.dart';

void main() {
  test('fetch is allowed only with ids and a complete season/episode shape',
      () {
    expect(
      skipFetchAllowed(imdbId: 'tt1', season: 1, episode: 2),
      isTrue,
    );
    expect(skipFetchAllowed(tmdbId: '99'), isTrue);
    expect(skipFetchAllowed(imdbId: 'tt1', season: 1), isFalse);
    expect(skipFetchAllowed(), isFalse);
  });

  test('parses IntroDB objects and TheIntroDB arrays', () {
    final intro = parseIntroDbSegments({
      'intro': {'start_ms': 1000, 'end_ms': 5000},
      'recap': {'start_sec': 10, 'end_sec': 20},
    });
    expect(intro, [
      const SkipSegment(type: 'intro', startMs: 1000, endMs: 5000),
      const SkipSegment(type: 'recap', startMs: 10000, endMs: 20000),
    ]);

    final the = parseTheIntroDbSegments({
      'intro': [
        {'start_ms': 0, 'end_ms': 8000},
      ],
      'credits': [
        {'start_ms': 90000, 'end_ms': null},
      ],
    });
    expect(the.first.type, 'intro');
    expect(the.last.type, 'outro');
    expect(the.last.endMs, skipSegmentOpenEndedMs);
  });

  test('both-provider merge lets IntroDB win per type', () {
    final merged = mergeSkipProviders(
      introDb: const [
        SkipSegment(type: 'intro', startMs: 1, endMs: 2),
      ],
      theIntroDb: const [
        SkipSegment(type: 'intro', startMs: 9, endMs: 10),
        SkipSegment(type: 'recap', startMs: 3, endMs: 4),
      ],
    );
    expect(merged.map((s) => s.type).toList(), ['intro', 'recap']);
    expect(merged.first.startMs, 1);
  });

  test('active segment, auto-skip hide, and skip target', () {
    const intro = SkipSegment(type: 'intro', startMs: 1000, endMs: 5000);
    expect(
      activeSkipSegment(segments: [intro], positionMs: 2000),
      intro,
    );
    expect(
      activeSkipSegment(
        segments: [intro],
        positionMs: 2000,
        lastSkipped: intro,
      ),
      isNull,
    );
    expect(
      const SkipAutoPrefs(intro: true).enabledFor('intro'),
      isTrue,
    );
    expect(skipButtonLabel('intro'), 'Skip Intro');
    expect(skipTargetMs(intro, 60000), 6000);
    expect(
      skipSegmentEndsPlayback(
        const SkipSegment(
          type: 'outro',
          startMs: 50000,
          endMs: skipSegmentOpenEndedMs,
        ),
        60000,
      ),
      isTrue,
    );
  });
}
