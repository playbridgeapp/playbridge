import 'package:flutter/material.dart';

import 'player_engine.dart';

/// mpv's `chapter-seek-threshold`: further than this into a chapter, "previous"
/// restarts it instead of going back one.
const int chapterRestartThresholdMs = 5000;

/// Position lag allowed right after a jump, so "next" does not pick the
/// chapter the player is still seeking to.
const int _chapterLeadMs = 1000;

/// A chapter jump target: its 1-based number and the chapter itself.
typedef ChapterTarget = ({int number, ChapterInfo chapter});

/// Where PageUp (`forward: false`) or PageDown (`forward: true`) goes from
/// [positionMs]. Null with fewer than two chapters or past the last one.
ChapterTarget? chapterJumpTarget(
  List<ChapterInfo> chapters,
  int positionMs, {
  required bool forward,
}) {
  if (chapters.length < 2) return null;
  if (forward) {
    final next =
        chapters.indexWhere((c) => c.startMs > positionMs + _chapterLeadMs);
    return next < 0 ? null : (number: next + 1, chapter: chapters[next]);
  }
  final current = chapters.lastIndexWhere((c) => c.startMs <= positionMs);
  if (current < 0) return (number: 1, chapter: chapters.first);
  final intoChapter = positionMs - chapters[current].startMs;
  final index = intoChapter > chapterRestartThresholdMs
      ? current
      : (current - 1).clamp(0, chapters.length - 1);
  return (number: index + 1, chapter: chapters[index]);
}

/// OSD text for a chapter jump: "Chapter 3 · The Heist" or "Chapter 3".
String chapterLabel(ChapterTarget target) {
  final title = target.chapter.title;
  return title == null
      ? 'Chapter ${target.number}'
      : 'Chapter ${target.number} · $title';
}

/// Hover caption for [positionMs]: "Chapter 2 · The Heist", "Chapter 3", or
/// just the title of a file's only chapter. Null when there is nothing to say.
String? chapterCaptionAt(List<ChapterInfo> chapters, int positionMs) {
  final index = chapters.lastIndexWhere((c) => c.startMs <= positionMs);
  if (index < 0) return null;
  if (chapters.length < 2) return chapters[index].title;
  return chapterLabel((number: index + 1, chapter: chapters[index]));
}

/// Chapter starts as 0..1 track fractions, skipping the first chapter at 0.
/// Empty with fewer than two chapters or no duration.
List<double> chapterFractions(List<ChapterInfo> chapters, int durationMs) {
  if (chapters.length < 2 || durationMs <= 0) return const [];
  return [
    for (final c in chapters)
      if (c.startMs > 0 && c.startMs < durationMs) c.startMs / durationMs,
  ];
}

/// Slider track with a small gap at each chapter start, matching the phone
/// remote's seek bar. The gaps are painted in [gapColor] over the track.
class ChapterSliderTrackShape extends RoundedRectSliderTrackShape {
  const ChapterSliderTrackShape({
    required this.fractions,
    this.gapWidth = 2,
    this.gapColor = const Color(0xCC000000),
  });

  final List<double> fractions;
  final double gapWidth;
  final Color gapColor;

  @override
  void paint(
    PaintingContext context,
    Offset offset, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required TextDirection textDirection,
    required Offset thumbCenter,
    Offset? secondaryOffset,
    bool isDiscrete = false,
    bool isEnabled = false,
    double additionalActiveTrackHeight = 2,
  }) {
    super.paint(
      context,
      offset,
      parentBox: parentBox,
      sliderTheme: sliderTheme,
      enableAnimation: enableAnimation,
      textDirection: textDirection,
      thumbCenter: thumbCenter,
      secondaryOffset: secondaryOffset,
      isDiscrete: isDiscrete,
      isEnabled: isEnabled,
      additionalActiveTrackHeight: additionalActiveTrackHeight,
    );
    if (fractions.isEmpty) return;
    final track = getPreferredRect(
      parentBox: parentBox,
      offset: offset,
      sliderTheme: sliderTheme,
      isEnabled: isEnabled,
      isDiscrete: isDiscrete,
    );
    // The active part is drawn taller; cover both.
    final height = track.height + additionalActiveTrackHeight + 1;
    final paint = Paint()..color = gapColor;
    for (final f in fractions) {
      final x = textDirection == TextDirection.rtl
          ? track.right - track.width * f
          : track.left + track.width * f;
      context.canvas.drawRect(
        Rect.fromCenter(
          center: Offset(x, track.center.dy),
          width: gapWidth,
          height: height,
        ),
        paint,
      );
    }
  }
}
