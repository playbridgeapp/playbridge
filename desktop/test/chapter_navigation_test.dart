import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/chapter_navigation.dart';
import 'package:playbridge_desktop/player_engine.dart';

void main() {
  const chapters = [
    ChapterInfo(startMs: 0, title: 'Opening'),
    ChapterInfo(startMs: 600000, title: 'The Heist'),
    ChapterInfo(startMs: 1800000),
  ];

  int? jump(int positionMs, {required bool forward}) =>
      chapterJumpTarget(chapters, positionMs, forward: forward)
          ?.chapter
          .startMs;

  test('next goes to the following chapter and stops after the last', () {
    expect(jump(10000, forward: true), 600000);
    // Just after a jump the position can lag slightly behind the target.
    expect(jump(599500, forward: true), 1800000);
    expect(jump(1900000, forward: true), isNull);
  });

  test('previous restarts the chapter unless near its start', () {
    expect(jump(700000, forward: false), 600000);
    expect(jump(603000, forward: false), 0);
    expect(jump(2000, forward: false), 0);
  });

  test('a single chapter or none has nothing to jump to', () {
    expect(
        chapterJumpTarget(const [ChapterInfo(startMs: 0)], 5000, forward: true),
        isNull);
    expect(chapterJumpTarget(const [], 5000, forward: false), isNull);
  });

  test('label numbers the chapter and adds its title', () {
    final heist = chapterJumpTarget(chapters, 10000, forward: true)!;
    expect(chapterLabel(heist), 'Chapter 2 · The Heist');
    final untitled = chapterJumpTarget(chapters, 700000, forward: true)!;
    expect(chapterLabel(untitled), 'Chapter 3');
  });

  test('fractions skip the opening chapter and need a duration', () {
    expect(chapterFractions(chapters, 3600000), [600000 / 3600000, 0.5]);
    expect(chapterFractions(chapters, 0), isEmpty);
    expect(chapterFractions(const [ChapterInfo(startMs: 0)], 1000), isEmpty);
  });

  test('hover caption names the chapter, or the only title', () {
    expect(chapterCaptionAt(chapters, 700000), 'Chapter 2 · The Heist');
    expect(chapterCaptionAt(chapters, 1900000), 'Chapter 3');
    expect(
        chapterCaptionAt(const [ChapterInfo(startMs: 0, title: 'Movie')], 10),
        'Movie');
    expect(chapterCaptionAt(const [], 10), isNull);
  });
}
