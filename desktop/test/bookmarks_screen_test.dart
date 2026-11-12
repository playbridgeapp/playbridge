import 'dart:ui' show PointerDeviceKind;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/bookmarks_screen.dart';
import 'package:playbridge_desktop/history_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Engine extends PlayerEngine {
  @override
  String get state => 'idle';
  @override
  int get positionMs => 0;
  @override
  int get durationMs => 0;
  @override
  dynamic get tracks => null;
  @override
  dynamic get track => null;
  @override
  Future<void> setAudioTrack(dynamic t) async {}
  @override
  Future<void> setSubtitleTrack(dynamic t) async {}
  @override
  double get playbackRate => 1;
  @override
  Future<void> setPlaybackRate(double rate) async {}
  @override
  Future<void> open(QueueItem item) async {}
  @override
  Future<void> openPlaylist(List<QueueItem> items, int startIndex,
      {bool play = true}) async {}
  @override
  Future<void> resume() async {}
  @override
  Future<void> pause() async {}
  @override
  Future<void> seek(Duration position) async {}
  @override
  Future<void> stop() async {}
  @override
  Future<void> dispose() async {
    super.dispose();
  }
}

void main() {
  late HistoryStore store;
  late PlayerController player;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    store = await HistoryStore.load(recentsEnabled: true);
    player = PlayerController(engineForTest: _Engine());
  });

  tearDown(() => player.dispose());

  Future<void> pump(
    WidgetTester tester, {
    bool enableHistory = true,
    VoidCallback? onNav,
    void Function(PlayHistoryItem)? onTarget,
  }) async {
    tester.view.physicalSize = const Size(1280, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(
        body: BookmarksScreen(
          store: store,
          player: player,
          enableHistory: enableHistory,
          onNavigateToNowPlaying: onNav ?? () {},
          onPlayOnTarget: onTarget,
          playOnTargetLabel: 'Resume on Living Room TV',
        ),
      ),
    ));
    await tester.pump();
  }

  Future<void> add(String n, {int? ms}) => store.addOrBump(
      url: 'https://x/$n.mp4', title: 'Title $n', displayDurationMs: ms);

  testWidgets('empty states give one line of guidance per tab', (tester) async {
    await pump(tester);
    expect(find.text('No bookmarks yet'), findsOneWidget);
    expect(find.text('Bookmark a video from Recent to keep it here.'),
        findsOneWidget);
    await tester.tap(find.text('Recent'));
    await tester.pump();
    expect(find.text('Nothing played yet'), findsOneWidget);
    expect(find.text('Videos you play will show up here.'), findsOneWidget);
  });

  testWidgets('recent tab hidden when history is off', (tester) async {
    await pump(tester, enableHistory: false);
    expect(find.text('Recent'), findsNothing);
    expect(find.text('Videos you bookmark will show up here.'), findsOneWidget);
  });

  testWidgets('cards show real meta only; no old headings', (tester) async {
    await add('a', ms: 8 * 60000);
    await add('b');
    await pump(tester);
    await tester.tap(find.text('Recent'));
    await tester.pump();
    expect(find.text('Title a'), findsOneWidget);
    expect(find.text('8 min · Just now'), findsOneWidget);
    expect(find.text('Just now'), findsOneWidget);
    expect(find.text('History'), findsNothing);
    expect(find.text('Favorites'), findsNothing);
  });

  testWidgets('bookmark toggle moves item to Bookmarks tab; remove works',
      (tester) async {
    await add('a');
    await pump(tester);
    await tester.tap(find.text('Recent'));
    await tester.pump();
    await tester.tap(find.byTooltip('Add bookmark'), warnIfMissed: false);
    // Actions are hidden until hover; hover the card first.
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    await mouse.addPointer(location: tester.getCenter(find.text('Title a')));
    await tester.pump();
    await tester.tap(find.byTooltip('Add bookmark'));
    await tester.pump();
    expect(store.favorites.single.title, 'Title a');
    // Bookmarked: Recent hides remove.
    expect(find.byTooltip('Remove from recent'), findsNothing);
    await tester.tap(find.text('Bookmarks').last);
    await tester.pump();
    expect(find.text('Title a'), findsOneWidget);
    await mouse.moveTo(tester.getCenter(find.text('Title a')));
    await tester.pump();
    // One remove control per card, not two that do the same thing.
    expect(find.byTooltip('Remove bookmark'), findsOneWidget);
    await tester.tap(find.byTooltip('Remove bookmark'));
    await tester.pump();
    expect(store.favorites, isEmpty);
    expect(store.items, hasLength(1));
    // Now remove from Recent.
    await tester.tap(find.text('Recent'));
    await tester.pump();
    await mouse.moveTo(tester.getCenter(find.text('Title a')));
    await tester.pump();
    await tester.tap(find.byTooltip('Remove from recent'));
    await tester.pump();
    expect(store.items, isEmpty);
  });

  testWidgets('clear recent keeps bookmarks', (tester) async {
    await add('a');
    await add('b');
    await store.toggleFavorite('https://x/a.mp4');
    await pump(tester);
    await tester.tap(find.text('Recent'));
    await tester.pump();
    await tester.tap(find.text('Clear recent'));
    await tester.pump();
    expect(store.items.map((i) => i.title), ['Title a']);
  });

  testWidgets('Play here plays locally and navigates; target button optional',
      (tester) async {
    await add('a');
    await store.toggleFavorite('https://x/a.mp4');
    var navigated = 0;
    PlayHistoryItem? target;
    await pump(tester, onNav: () => navigated++);
    expect(find.byTooltip('Resume on Living Room TV'), findsNothing);
    await tester.tap(find.byTooltip('Play here'), warnIfMissed: false);
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    await mouse.addPointer(location: tester.getCenter(find.text('Title a')));
    await tester.pump();
    await tester.tap(find.byTooltip('Play here'));
    await tester.pump();
    expect(navigated, 1);
    // Let the player's async play request finish before teardown.
    await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 300)));
    expect(player.queue.single.url, 'https://x/a.mp4');
    await tester.pumpWidget(const SizedBox());
    await player.stop();
    await tester.pump(const Duration(seconds: 2));

    await pump(tester, onTarget: (i) => target = i);
    await mouse.moveTo(tester.getCenter(find.text('Title a')));
    await tester.pump();
    await tester.tap(find.byTooltip('Resume on Living Room TV'));
    expect(target?.title, 'Title a');
  });

  testWidgets('keyboard focus reveals the actions', (tester) async {
    await add('a');
    await store.toggleFavorite('https://x/a.mp4');
    await pump(tester);
    double opacity() =>
        tester.widget<AnimatedOpacity>(find.byType(AnimatedOpacity)).opacity;
    expect(opacity(), 0);
    for (var i = 0; i < 4 && opacity() == 0; i++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
    }
    expect(opacity(), 1);
  });

  test('formatting helpers', () {
    expect(formatLength(30000), '30 sec');
    expect(formatLength(8 * 60000), '8 min');
    expect(formatLength(90 * 60000), '1 h 30 min');
    final now = DateTime(2026, 1, 10, 12);
    expect(
        timeAgo(now.subtract(const Duration(hours: 3)), now: now), '3 h ago');
  });
}
