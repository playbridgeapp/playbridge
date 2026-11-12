import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/auto_launch.dart';
import 'package:playbridge_desktop/keyboard_shortcuts_sheet.dart';
import 'package:playbridge_desktop/logging/log_store.dart';
import 'package:playbridge_desktop/logs_screen.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/settings_screen.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:playbridge_desktop/update/update_checker.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'desktop_linked_playback_test.dart' show Engine;

Future<
    ({
      PairingStore store,
      PlayerController player,
      ReceiverServer server,
      UpdateChecker checker,
    })> _deps() async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  final store = PairingStore.forTest(prefs);
  final player = PlayerController(engineForTest: Engine());
  final server = ReceiverServer(player: player, store: store);
  return (
    store: store,
    player: player,
    server: server,
    checker: UpdateChecker(endpointOverride: 'http://127.0.0.1:1/none'),
  );
}

void main() {
  Future<PairingStore> pumpSettings(
    WidgetTester tester, {
    VoidCallback? onQuit,
    ValueNotifier<bool>? showStats,
  }) async {
    tester.view.physicalSize = const Size(1400, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final d = await tester.runAsync(_deps) as dynamic;
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(
        body: SettingsScreen(
          server: d.server,
          store: d.store,
          player: d.player,
          showStats: showStats ?? ValueNotifier(false),
          updateChecker: d.checker,
          onNavigateToCast: () {},
          onQuit: onQuit,
          autoLaunchForTest: AutoLaunch(
            executablePath: '/tmp/none',
            home: Directory.systemTemp.createTempSync('al').path,
          ),
        ),
      ),
    ));
    await tester.pump();
    return d.store as PairingStore;
  }

  Future<void> openTab(WidgetTester tester, String label) async {
    await tester.tap(find.text(label));
    await tester.pump();
  }

  testWidgets('General tab holds name, pairing, history and window settings',
      (tester) async {
    final store = await pumpSettings(tester);
    for (final t in [
      'This computer’s name',
      'When I close the window',
      'Keep my casts out of the TV’s history',
    ]) {
      expect(find.text(t), findsOneWidget, reason: t);
    }
    expect(find.text('Keep playing'), findsOneWidget);
    expect(find.text('Quit PlayBridge'), findsNothing);

    // No Recent list exists until a phone can supply one, so its switch is
    // not offered.
    expect(find.text('Save cast history'), findsNothing);
    expect(store.enableHistory, isTrue);
  });

  testWidgets('value row opens a picker and saves the choice', (tester) async {
    final store = await pumpSettings(tester);
    await tester.tap(find.text('When I close the window'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Pause'));
    await tester.pumpAndSettle();
    expect(store.pauseOnWindowHide, isTrue);
    expect(find.text('Pause'), findsOneWidget);
  });

  testWidgets('Playback tab: still watching and skip settings', (tester) async {
    final stats = ValueNotifier(false);
    final store = await pumpSettings(tester, showStats: stats);
    await openTab(tester, 'Playback');
    for (final t in [
      'Go full screen when a video starts',
      'Show playback stats',
      'Ask “Still watching?”',
      'Ask after',
      'Wait for an answer',
      'Skip without asking',
      'Intros',
      'Recaps',
      'Credits',
    ]) {
      expect(find.text(t), findsOneWidget, reason: t);
    }
    expect(find.text('1.5 hours'), findsOneWidget);
    // Disabled while the reminder is off.
    await tester.tap(find.text('Ask after'));
    await tester.pumpAndSettle();
    expect(find.text('Cancel'), findsNothing);

    await tester.tap(find.text('Ask “Still watching?”'));
    await tester.pump();
    expect(store.stillWatchingEnabled, isTrue);
    await tester.tap(find.text('Ask after'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2 hours'));
    await tester.pumpAndSettle();
    expect(store.stillWatchingThresholdMinutes, 120);

    await tester.tap(find.text('Recaps'));
    await tester.pump();
    expect(store.autoSkipRecap, isTrue);
    expect(store.autoSkipIntro, isFalse);

    await tester.tap(find.text('Show playback stats'));
    await tester.pump();
    expect(stats.value, isTrue);
  });

  testWidgets('Advanced tab: proxy, quality, provider, logging',
      (tester) async {
    final store = await pumpSettings(tester);
    await openTab(tester, 'Advanced');
    for (final t in [
      'Load streams through this computer',
      'Pick the stream quality before playing',
      'Where skip times come from',
      'Connected phones',
      'Keep a log',
      'View logs',
    ]) {
      expect(find.text(t), findsOneWidget, reason: t);
    }
    expect(find.text('Disconnect every phone'), findsNothing);
    await tester.tap(find.text('Load streams through this computer'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Always'));
    await tester.pumpAndSettle();
    expect(store.streamProxyMode, StreamProxyMode.always);

    await tester.tap(find.text('Where skip times come from'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('TheIntroDB'));
    await tester.pumpAndSettle();
    expect(store.skipSegmentsProvider, 'theintrodb');
  });

  testWidgets('About tab: version, update check, shortcuts and quit',
      (tester) async {
    var quit = 0;
    await pumpSettings(tester, onQuit: () => quit++);
    await openTab(tester, 'About');
    expect(find.textContaining('PlayBridge Desktop 0.'), findsOneWidget);
    expect(find.text('Check for updates'), findsOneWidget);
    expect(find.text('Open-source licences'), findsNothing);

    await tester.tap(find.text('Keyboard shortcuts'));
    await tester.pumpAndSettle();
    expect(find.text('Keyboard and mouse'), findsOneWidget);
    await tester.tap(find.byTooltip('Close'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quit PlayBridge'));
    await tester.pumpAndSettle();
    expect(find.text('Quit PlayBridge?'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(quit, 0);
    await tester.tap(find.text('Quit PlayBridge'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Quit'));
    await tester.pumpAndSettle();
    expect(quit, 1);
  });

  testWidgets('shortcuts sheet lists only registered shortcuts',
      (tester) async {
    tester.view.physicalSize = const Size(1400, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: const Scaffold(body: KeyboardShortcutsDialog()),
    ));
    for (final t in [
      'Space',
      'F',
      'B',
      'I',
      'Z',
      'G H',
      '[ ]',
      'PgUp PgDn',
      '?'
    ]) {
      expect(find.text(t), findsOneWidget, reason: t);
    }
    expect(find.text('Bookmark this video'), findsOneWidget);

    // Every key in the sheet must be bound in main.dart.
    final main = File('lib/main.dart').readAsStringSync();
    for (final k in [
      'space',
      'keyF',
      'keyB',
      'keyI',
      'keyZ',
      'keyG',
      'keyH',
      'bracketLeft',
      'bracketRight',
      'pageUp',
      'pageDown',
      'arrowUp',
      'arrowDown',
      'arrowLeft',
      'arrowRight',
      'slash',
    ]) {
      expect(main, contains('LogicalKeyboardKey.$k'), reason: k);
    }
  });

  group('LogsScreen', () {
    testWidgets('shows the start prompt while logging is off', (tester) async {
      tester.view.physicalSize = const Size(1400, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        theme: buildPbTheme(),
        home: const LogsScreen(),
      ));
      expect(LogStore.instance.enabled, isFalse);
      expect(find.text('Logs'), findsOneWidget);
      expect(find.text('Start logging'), findsOneWidget);
      expect(find.text('Copy all'), findsNothing);
    });
    testWidgets('filters by level and text, and offers every action',
        (tester) async {
      tester.view.physicalSize = const Size(1400, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await LogStore.instance.setEnabled(true);
      addTearDown(() => LogStore.instance.setEnabled(false));
      LogEntry e(LogLevel l, String tag, String m) => LogEntry(
          time: DateTime(2026, 1, 1, 9, 14, 2), level: l, tag: tag, message: m);
      LogStore.instance.entries.value = [
        e(LogLevel.info, 'server', 'Phone connected'),
        e(LogLevel.warn, 'player', 'Buffer ran low'),
        e(LogLevel.error, 'sender', 'TV did not answer'),
      ];
      await tester.pumpWidget(MaterialApp(
        theme: buildPbTheme(),
        home: const LogsScreen(),
      ));
      await tester.pump();
      expect(find.text('Recording'), findsOneWidget);
      for (final t in ['Copy all', 'Clear', 'Stop and delete']) {
        expect(find.text(t), findsOneWidget, reason: t);
      }
      expect(find.textContaining('Phone connected'), findsOneWidget);

      await tester.tap(find.text('Errors'));
      await tester.pump();
      expect(find.textContaining('Phone connected'), findsNothing);
      expect(find.textContaining('TV did not answer'), findsOneWidget);

      await tester.tap(find.text('All'));
      await tester.enterText(find.byType(TextField), 'buffer');
      await tester.pump();
      expect(find.textContaining('Buffer ran low'), findsOneWidget);
      expect(find.textContaining('TV did not answer'), findsNothing);

      await tester.tap(find.textContaining('Buffer ran low'));
      await tester.pumpAndSettle();
      expect(find.text('Copy message'), findsOneWidget);
      expect(find.text('player'), findsOneWidget);
    });
  });
}
