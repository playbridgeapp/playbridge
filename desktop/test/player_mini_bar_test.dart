import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/media_kind.dart';
import 'package:flutter/gestures.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/player_mini_bar.dart';

void main() {
  Future<void> pump(
    WidgetTester tester, {
    required int audio,
    required int subs,
    MediaKind kind = MediaKind.video,
    bool hasDuration = true,
    bool playing = true,
    int positionMs = 1000,
    int durationMs = 90000,
    List<ChapterInfo> chapters = const [],
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PlayerMiniBar(
            mediaKind: kind,
            realAudioCount: audio,
            realSubtitleCount: subs,
            playing: playing,
            hasDuration: hasDuration,
            positionMs: positionMs,
            durationMs: hasDuration ? durationMs : 0,
            isFullScreen: false,
            onPlayPause: () {},
            onStop: () {},
            onToggleFullScreen: () {},
            onSeekChanged: (_) {},
            onSeekEnd: (_) {},
            audioMenu: const Icon(Icons.audiotrack, key: Key('audio')),
            subtitleMenu: const Icon(Icons.subtitles, key: Key('subs')),
            chapters: chapters,
          ),
        ),
      ),
    );
  }

  testWidgets('audio needs 2+ tracks and subtitles need 1+', (tester) async {
    await pump(tester, audio: 0, subs: 0);
    expect(find.byKey(const Key('audio')), findsNothing);
    expect(find.byKey(const Key('subs')), findsNothing);

    await pump(tester, audio: 1, subs: 0);
    expect(find.byKey(const Key('audio')), findsNothing);
    expect(find.byKey(const Key('subs')), findsNothing);

    await pump(tester, audio: 2, subs: 0);
    expect(find.byKey(const Key('audio')), findsOneWidget);
    expect(find.byKey(const Key('subs')), findsNothing);

    await pump(tester, audio: 3, subs: 1);
    expect(find.byKey(const Key('audio')), findsOneWidget);
    expect(find.byKey(const Key('subs')), findsOneWidget);

    await pump(tester, audio: 0, subs: 2);
    expect(find.byKey(const Key('audio')), findsNothing);
    expect(find.byKey(const Key('subs')), findsOneWidget);
  });

  testWidgets('images hide track menus; audio keeps them', (tester) async {
    await pump(tester, audio: 2, subs: 1, kind: MediaKind.image);
    expect(find.byKey(const Key('audio')), findsNothing);
    expect(find.byKey(const Key('subs')), findsNothing);

    await pump(tester, audio: 2, subs: 1, kind: MediaKind.audio);
    expect(find.byKey(const Key('audio')), findsOneWidget);
    expect(find.byKey(const Key('subs')), findsOneWidget);
  });

  testWidgets('seek shows elapsed and remaining, and hides for live',
      (tester) async {
    await pump(tester, audio: 0, subs: 0);
    expect(find.byType(Slider), findsOneWidget);
    expect(find.text('00:01'), findsOneWidget);
    expect(find.text('-01:29'), findsOneWidget);
    expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNotNull);

    await pump(tester, audio: 0, subs: 0, hasDuration: false);
    expect(find.byType(Slider), findsNothing);
    expect(find.text('00:01'), findsNothing);
    expect(find.text('-01:29'), findsNothing);
    expect(find.text('LIVE'), findsNothing);
  });

  testWidgets(
      'image slideshow keeps a disabled seek row; play follows duration',
      (tester) async {
    await pump(
      tester,
      audio: 0,
      subs: 0,
      kind: MediaKind.image,
      hasDuration: true,
      playing: false,
    );
    expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNull);
    expect(
      tester
          .widget<IconButton>(find.widgetWithIcon(IconButton, Icons.play_arrow))
          .onPressed,
      isNotNull,
    );

    await pump(
      tester,
      audio: 0,
      subs: 0,
      kind: MediaKind.image,
      hasDuration: false,
      playing: false,
    );
    expect(find.byType(Slider), findsNothing);
    expect(
      tester
          .widget<IconButton>(find.widgetWithIcon(IconButton, Icons.play_arrow))
          .onPressed,
      isNull,
    );
    expect(find.byTooltip('Stop'), findsOneWidget);
    expect(find.byTooltip('Fullscreen'), findsOneWidget);
  });

  testWidgets('hovering the seek bar shows the time and chapter',
      (tester) async {
    await pump(
      tester,
      audio: 0,
      subs: 0,
      chapters: const [
        ChapterInfo(startMs: 0, title: 'Opening'),
        ChapterInfo(startMs: 30000, title: 'The Heist'),
      ],
    );
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    addTearDown(mouse.removePointer);
    await mouse.addPointer(location: Offset.zero);
    await mouse.moveTo(tester.getCenter(find.byType(Slider)));
    await tester.pump();
    expect(find.text('00:45'), findsOneWidget);
    expect(find.text('Chapter 2 · The Heist'), findsOneWidget);

    await mouse.moveTo(Offset.zero);
    await tester.pump();
    expect(find.text('Chapter 2 · The Heist'), findsNothing);
  });
}
