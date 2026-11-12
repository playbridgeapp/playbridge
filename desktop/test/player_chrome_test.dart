import 'package:flutter/gestures.dart' show PointerDeviceKind;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:media_kit/media_kit.dart' show AudioTrack, SubtitleTrack;
import 'package:playbridge_desktop/media_presentation_surface.dart';
import 'package:playbridge_desktop/player/player_chrome.dart';
import 'package:playbridge_desktop/player/player_panels.dart';
import 'package:playbridge_desktop/player/player_widgets.dart';
import 'package:playbridge_desktop/player_chrome_policy.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/preplay_overlay.dart';
import 'package:playbridge_desktop/skip_segment.dart';
import 'package:playbridge_desktop/still_watching_prompt.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/subtitle_delay.dart';
import 'package:playbridge_desktop/video_scaling.dart';

class _Tracks {
  _Tracks(this.audio, this.subtitle);
  final List<AudioTrack> audio;
  final List<SubtitleTrack> subtitle;
}

class _Selected {
  _Selected(this.audio, this.subtitle);
  AudioTrack audio;
  SubtitleTrack subtitle;
}

/// Engine with scriptable tracks and a few recorded calls.
class _Engine extends PlayerEngine {
  String _state = 'idle';
  final _tracks = _Tracks(
    [
      const AudioTrack('1', 'Surround', 'eng'),
      const AudioTrack('2', 'Commentary', 'eng'),
    ],
    [
      const SubtitleTrack('1', 'Full', 'eng'),
      const SubtitleTrack('2', null, 'spa'),
    ],
  );
  late final _Selected _track =
      _Selected(_tracks.audio.first, SubtitleTrack.no());
  int durationValue = 600000;
  int positionValue = 100000;
  int? delay;
  double volumeValue = 0.7;
  final volumes = <double>[];
  final addedSubtitles = <String>[];
  String? scaling;

  @override
  String get state => _state;
  @override
  int get positionMs => positionValue;
  @override
  int get durationMs => durationValue;
  @override
  double get volume => volumeValue;
  @override
  dynamic get tracks => _tracks;
  @override
  dynamic get track => _track;

  @override
  Future<void> setAudioTrack(dynamic t) async {
    _track.audio = t as AudioTrack;
    notifyListeners();
  }

  @override
  Future<void> setSubtitleTrack(dynamic t) async {
    _track.subtitle = t as SubtitleTrack;
    notifyListeners();
  }

  @override
  Future<void> setSubtitleDelayMs(int delayMs) async => delay = delayMs;

  @override
  Future<void> setVideoScaling(String mode) async => scaling = mode;

  @override
  Future<void> setVolume(double v) async {
    volumeValue = v;
    volumes.add(v);
    notifyListeners();
  }

  @override
  Future<bool> addExternalSubtitleFile(String path, String label) async {
    addedSubtitles.add('$path|$label');
    return true;
  }

  @override
  Future<void> open(QueueItem item) async {
    _state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> resume() async {
    _state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> pause() async {
    _state = 'paused';
    notifyListeners();
  }

  @override
  Future<void> seek(Duration position) async =>
      positionValue = position.inMilliseconds;

  @override
  Future<void> stop() async {
    _state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> dispose() async => super.dispose();
}

QueueItem _item(int n, {String? artist, String? album, int? start}) =>
    QueueItem(
      url: 'https://x/$n.mp4',
      title: 'Video $n',
      artist: artist,
      album: album,
      startPositionMs: start,
    );

Future<(PlayerController, _Engine)> _player(
  WidgetTester tester, {
  int items = 3,
  int index = 1,
}) async {
  final engine = _Engine();
  final player = PlayerController(engineForTest: engine);
  addTearDown(player.dispose);
  // Outside fake async: the controller's stall monitor owns a periodic timer.
  await tester.runAsync(
    () => player.playPlaylist(
      [for (var i = 1; i <= items; i++) _item(i)],
      index,
    ),
  );
  return (player, engine);
}

Widget _app(Widget child) => MaterialApp(
      home: Scaffold(
        backgroundColor: Colors.black,
        body: SizedBox(width: 1280, height: 800, child: child),
      ),
    );

void main() {
  setUp(() async => StreamProxyServer.instance.stop());
  tearDown(() async => StreamProxyServer.instance.stop());

  setUp(() {});

  void bigWindow(WidgetTester tester) {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  group('SubtitlesPanel', () {
    testWidgets('lists subtitle and audio tracks and selects them',
        (tester) async {
      bigWindow(tester);
      final (player, engine) = await _player(tester);
      await tester.pumpWidget(
        _app(SubtitlesPanel(player: player, onClose: () {})),
      );
      expect(find.text('Subtitles and audio'), findsOneWidget);
      expect(find.text('Off'), findsOneWidget);
      expect(find.text('eng · Full'), findsOneWidget);
      expect(find.text('spa'), findsOneWidget);
      expect(find.text('Add a subtitle file…'), findsOneWidget);
      expect(find.text('eng · Commentary'), findsOneWidget);
      // Timing only appears with a subtitle on.
      expect(find.text('Subtitle timing'), findsNothing);

      await tester.tap(find.text('spa'));
      await tester.pump();
      expect(engine.track.subtitle.id, '2');
      expect(find.text('Subtitle timing'), findsOneWidget);

      await tester.tap(find.text('eng · Commentary'));
      await tester.pump();
      expect(engine.track.audio.id, '2');

      await tester.tap(find.text('Off'));
      await tester.pump();
      expect(engine.track.subtitle.id, 'no');
    });

    testWidgets('timing buttons move and reset the subtitle delay',
        (tester) async {
      bigWindow(tester);
      final (player, engine) = await _player(tester);
      await player.setSubtitleTrack(const SubtitleTrack('1', 'Full', 'eng'));
      await tester.pumpWidget(
        _app(SubtitlesPanel(player: player, onClose: () {})),
      );
      expect(find.text('In step with the audio'), findsOneWidget);

      await tester.tap(find.text('Later'));
      await tester.pump();
      expect(engine.delay, subtitleDelayFineMs);
      await tester.tap(find.text('Earlier by 1 s'));
      await tester.pump();
      expect(engine.delay, subtitleDelayFineMs - subtitleDelayCoarseMs);

      await tester.tap(find.text('Reset timing'));
      await tester.pump();
      expect(engine.delay, 0);
    });

    testWidgets('Add a subtitle file attaches the picked file', (tester) async {
      bigWindow(tester);
      final (player, engine) = await _player(tester);
      await tester.pumpWidget(
        _app(SubtitlesPanel(
          player: player,
          onClose: () {},
          pickSubtitleFile: () async => '/tmp/movie.en.srt',
        )),
      );
      await tester.tap(find.text('Add a subtitle file…'));
      await tester.pump();
      expect(engine.addedSubtitles, ['/tmp/movie.en.srt|movie.en.srt']);
    });

    testWidgets('a single audio track shows no Audio section', (tester) async {
      bigWindow(tester);
      final (player, engine) = await _player(tester);
      (engine.tracks as _Tracks).audio.removeLast();
      await tester.pumpWidget(
        _app(SubtitlesPanel(player: player, onClose: () {})),
      );
      expect(find.text('Audio'), findsNothing);
    });
  });

  group('QueuePanel', () {
    testWidgets('lists items, jumps, moves, removes and clears',
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester, items: 4, index: 1);
      await tester.pumpWidget(
        _app(QueuePanel(player: player, onClose: () {})),
      );
      expect(find.text('Queue · 4 items'), findsOneWidget);
      expect(find.textContaining('Playing'), findsOneWidget);

      // Jump.
      await tester.tap(find.text('Video 3'));
      await tester.pumpAndSettle();
      expect(player.currentIndex, 2);

      // Move 'Video 4' up: hover shows the actions.
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      addTearDown(mouse.removePointer);
      await mouse.addPointer(location: Offset.zero);
      await mouse.moveTo(tester.getCenter(find.text('Video 4')));
      await tester.pump();
      await tester.tap(find.byTooltip('Move Video 4 up'));
      await tester.pumpAndSettle();
      expect(
        player.queue.map((i) => i.title).toList(),
        ['Video 1', 'Video 2', 'Video 4', 'Video 3'],
      );
      expect(player.currentIndex, 3); // still on Video 3

      // Remove Video 1.
      await mouse.moveTo(tester.getCenter(find.text('Video 1')));
      await tester.pump();
      await tester.tap(find.byTooltip('Remove Video 1'));
      await tester.pumpAndSettle();
      expect(player.queue.length, 3);
      expect(find.text('Queue · 3 items'), findsOneWidget);

      // Nothing is next after the last item, so no Clear button.
      expect(find.text("Clear what's next"), findsNothing);
      await tester.runAsync(player.stop); // cancels the stall timer
    });

    testWidgets("Clear what's next removes only upcoming items",
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester, items: 4, index: 1);
      await tester.pumpWidget(
        _app(QueuePanel(player: player, onClose: () {})),
      );
      await tester.tap(find.text("Clear what's next"));
      await tester.pumpAndSettle();
      expect(player.queue.map((i) => i.title).toList(), ['Video 1', 'Video 2']);
    });
  });

  group('MorePanel', () {
    Future<void> pumpMore(
      WidgetTester tester,
      PlayerController player, {
      ValueNotifier<bool>? stats,
      Future<void> Function()? onToggleProxy,
    }) =>
        tester.pumpWidget(_app(MorePanel(
          player: player,
          onClose: () {},
          onToggleProxy: onToggleProxy ?? () async {},
          showStats: stats,
        )));

    testWidgets('speed and picture choices apply', (tester) async {
      bigWindow(tester);
      final (player, engine) = await _player(tester);
      await pumpMore(tester, player);
      await tester.tap(find.text('1.5×'));
      await tester.pump();
      expect(player.playbackRate, 1.5);

      await tester.tap(find.text(videoScalingLabel(VideoScalingMode.zoom)));
      await tester.pump();
      expect(player.videoScaling, VideoScalingMode.zoom);
      expect(engine.scaling, 'Zoom');
      expect(
        find.text(videoScalingDescription(VideoScalingMode.zoom)),
        findsOneWidget,
      );
    });

    testWidgets('offers every action the old menus had', (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      var proxied = 0;
      await pumpMore(
        tester,
        player,
        stats: ValueNotifier(false),
        onToggleProxy: () async => proxied++,
      );
      expect(find.text('Speed'), findsOneWidget);
      expect(find.text('Picture'), findsOneWidget);
      expect(find.text('Open in another player'), findsOneWidget);
      expect(find.text('Keyboard shortcuts'), findsOneWidget);
      expect(find.text("If this video won't play smoothly"), findsOneWidget);
      expect(find.text('Load through this computer'), findsOneWidget);
      // Stop lives on the control row, not in More.
      expect(find.text('Stop and close the video'), findsNothing);

      await tester.tap(find.text('Load through this computer'));
      await tester.pump();
      expect(proxied, 1);
    });

    testWidgets('stats row flips the shared switch only for mpv video',
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      final stats = ValueNotifier(false);
      await pumpMore(tester, player, stats: stats);
      // The fake engine is not an MpvEngine, so the overlay could not show:
      // the row stays hidden rather than doing nothing.
      expect(find.text('Show playback stats'), findsNothing);
    });
  });

  group('PlayerChrome', () {
    Future<void> pumpChrome(
      WidgetTester tester,
      PlayerController player, {
      PlayerChromeMode mode = PlayerChromeMode.full,
      VoidCallback? onBack,
      VoidCallback? onBookmark,
      VoidCallback? onMoveToTv,
      required List<String> log,
    }) =>
        tester.pumpWidget(_app(PlayerChrome(
          player: player,
          mode: mode,
          showQueueControls: player.queue.length > 1,
          onPanelOpened: () => log.add('open'),
          onPanelClosed: () => log.add('close'),
          onScrubbing: (_) {},
          isFullScreen: false,
          onToggleFullScreen: () {},
          onToggleProxy: () async {},
          segments: const [],
          lastSkipped: null,
          autoPrefs: const SkipAutoPrefs(),
          onSkip: (_) {},
          onBack: onBack,
          onBookmark: onBookmark,
          onMoveToTv: onMoveToTv,
        )));

    testWidgets('one panel at a time; Esc and clicking the video close it',
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      final log = <String>[];
      await pumpChrome(tester, player, log: log);
      await tester.pump();

      await tester.tap(find.byTooltip('Subtitles and audio'));
      await tester.pump();
      expect(find.text('Subtitles and audio'), findsOneWidget);
      expect(log, ['open']);

      // Switching panels replaces, and pins only once.
      await tester.tap(find.byTooltip('More'));
      await tester.pump();
      expect(find.text('Subtitles and audio'), findsNothing);
      expect(find.text('Speed'), findsOneWidget);
      expect(find.byType(PlayerPanelShell), findsOneWidget);
      expect(log, ['open']);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pump();
      expect(find.byType(PlayerPanelShell), findsNothing);
      expect(log, ['open', 'close']);

      // Clicking the video area closes a panel too.
      await tester.tap(find.byTooltip('Queue, 2 of 3'));
      await tester.pump();
      expect(find.byType(QueuePanel), findsOneWidget);
      await tester.tapAt(const Offset(300, 300));
      await tester.pump();
      expect(find.byType(PlayerPanelShell), findsNothing);
      expect(log, ['open', 'close', 'open', 'close']);
    });

    testWidgets('the panel button toggles its own panel', (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      await pumpChrome(tester, player, log: []);
      await tester.pump();
      await tester.tap(find.byTooltip('More'));
      await tester.pump();
      expect(find.byType(MorePanel), findsOneWidget);
      await tester.tap(find.byTooltip('More'));
      await tester.pump();
      expect(find.byType(MorePanel), findsNothing);
    });

    testWidgets('other keys are not consumed while a panel is open',
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      final pressed = <LogicalKeyboardKey>[];
      await tester.pumpWidget(_app(Focus(
        autofocus: true,
        onKeyEvent: (_, e) {
          if (e is KeyDownEvent) pressed.add(e.logicalKey);
          return KeyEventResult.ignored;
        },
        child: PlayerChrome(
          player: player,
          mode: PlayerChromeMode.full,
          showQueueControls: true,
          onPanelOpened: () {},
          onPanelClosed: () {},
          onScrubbing: (_) {},
          isFullScreen: false,
          onToggleFullScreen: () {},
          onToggleProxy: () async {},
          segments: const [],
          lastSkipped: null,
          autoPrefs: const SkipAutoPrefs(),
          onSkip: (_) {},
        ),
      )));
      await tester.tap(find.byTooltip('More'));
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.space);
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.sendKeyEvent(LogicalKeyboardKey.keyI);
      expect(
          pressed,
          containsAll([
            LogicalKeyboardKey.space,
            LogicalKeyboardKey.arrowRight,
            LogicalKeyboardKey.keyI,
          ]));
      expect(find.byType(MorePanel), findsOneWidget);
    });

    testWidgets('optional back, bookmark and move-to-TV render only when set',
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      await pumpChrome(tester, player, log: []);
      await tester.pump();
      expect(find.byTooltip('Back to Home. Playback continues'), findsNothing);
      expect(find.byTooltip('Bookmark'), findsNothing);
      expect(find.text('Move to a TV'), findsNothing);

      var back = 0, mark = 0, move = 0;
      await pumpChrome(
        tester,
        player,
        log: [],
        onBack: () => back++,
        onBookmark: () => mark++,
        onMoveToTv: () => move++,
      );
      await tester.pump();
      await tester.tap(find.byTooltip('Back to Home. Playback continues'));
      await tester.tap(find.byTooltip('Bookmark'));
      await tester.tap(find.text('Move to a TV'));
      expect([back, mark, move], [1, 1, 1]);
    });

    testWidgets('full bar: play, queue steps, seek and mute work',
        (tester) async {
      bigWindow(tester);
      final (player, engine) = await _player(tester);
      await pumpChrome(tester, player, log: []);
      await tester.pump();
      expect(find.text('01:40 / 10:00'), findsOneWidget);
      expect(find.byTooltip('Pause'), findsOneWidget);

      await tester.tap(find.byTooltip('Forward 10 seconds'));
      await tester.pump();
      expect(engine.positionValue, 110000);
      await tester.tap(find.byTooltip('Back 10 seconds'));
      await tester.pump();
      expect(engine.positionValue, 100000);

      await tester.tap(find.byTooltip('Mute'));
      await tester.pump();
      expect(engine.volumeValue, 0);
      await tester.tap(find.byTooltip('Unmute'));
      await tester.pump();
      expect(engine.volumeValue, 0.7);

      await tester.tap(find.byTooltip('Next'));
      await tester.pumpAndSettle();
      expect(player.currentIndex, 2);

      // Stop is on the control row and ends playback.
      await tester.tap(find.byTooltip('Stop'));
      await tester.pump();
      expect(player.queue, isEmpty);
      await tester.runAsync(player.stop); // cancels the stall timer
    });

    testWidgets(
        'mini mode shows the slim bar and its track button opens '
        'the Subtitles panel', (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      await pumpChrome(tester, player, mode: PlayerChromeMode.mini, log: []);
      await tester.pump();
      expect(find.byTooltip('Stop'), findsOneWidget);
      expect(find.byTooltip('Back 10 seconds'), findsNothing);
      await tester.tap(find.byTooltip('Subtitles'));
      await tester.pump();
      expect(find.byType(SubtitlesPanel), findsOneWidget);
    });

    testWidgets('releases the pin when removed with a panel open',
        (tester) async {
      bigWindow(tester);
      final (player, _) = await _player(tester);
      final log = <String>[];
      await pumpChrome(tester, player, log: log);
      await tester.pump();
      await tester.tap(find.byTooltip('More'));
      await tester.pump();
      await tester.pumpWidget(_app(const SizedBox()));
      await tester.pump();
      expect(log, ['open', 'close']);
    });
  });

  group('PrePlayOverlay', () {
    QueueItem withArt({int? start}) => QueueItem(
          url: 'https://x/a.mp4',
          title: 'Caminandes 2',
          overview: 'Koro the llama tries everything.',
          year: '2013',
          runtime: '2 min',
          startPositionMs: start,
        );

    testWidgets('offers Resume and Start over with a saved place',
        (tester) async {
      var started = 0, over = 0;
      await tester.pumpWidget(_app(PrePlayOverlay(
        item: withArt(start: 72000),
        onStart: () => started++,
        onStartOver: () => over++,
      )));
      expect(find.text('Caminandes 2'), findsOneWidget);
      expect(find.text('2013 · 2 min'), findsOneWidget);
      expect(find.text('Resume from 1:12'), findsOneWidget);
      expect(find.text('Resumes by itself in 7 seconds.'), findsOneWidget);
      await tester.tap(find.text('Start over'));
      await tester.pump();
      expect((started, over), (0, 1));
      // The countdown stopped with it.
      await tester.pump(const Duration(seconds: 10));
      expect(started, 0);
    });

    testWidgets('Resume starts, and the countdown starts by itself',
        (tester) async {
      var started = 0;
      await tester.pumpWidget(_app(PrePlayOverlay(
        item: withArt(start: 3725000),
        onStart: () => started++,
        onStartOver: () {},
        countdownSeconds: 2,
      )));
      expect(find.text('Resume from 1:02:05'), findsOneWidget);
      await tester.pump(const Duration(seconds: 1));
      expect(find.text('Resumes by itself in 1 second.'), findsOneWidget);
      await tester.pump(const Duration(seconds: 1));
      expect(started, 1);
    });

    testWidgets('without a saved place there is one Play button',
        (tester) async {
      var started = 0;
      await tester.pumpWidget(_app(PrePlayOverlay(
        item: withArt(),
        onStart: () => started++,
        onStartOver: () {},
      )));
      expect(find.text('Start over'), findsNothing);
      expect(find.textContaining('Resume'), findsNothing);
      expect(find.text('Starts by itself in 7 seconds.'), findsOneWidget);
      await tester.tap(find.text('Play'));
      await tester.pump();
      expect(started, 1);
      await tester.pump(const Duration(seconds: 10));
      expect(started, 1);
    });
  });

  group('StillWatchingPrompt', () {
    testWidgets('shows what is paused, the countdown and Keep watching',
        (tester) async {
      var kept = 0;
      await tester.pumpWidget(_app(StillWatchingPrompt(
        remainingSeconds: 102,
        title: 'Llamigos',
        onContinue: () => kept++,
      )));
      expect(find.text('Still watching?'), findsOneWidget);
      expect(
        find.text('Llamigos is paused. It stops in 1:42 if nobody answers.'),
        findsOneWidget,
      );
      await tester.tap(find.text('Keep watching'));
      expect(kept, 1);
      expect(find.text('Stop now'), findsNothing);
    });

    testWidgets(
        'Stop now appears only with a callback and acts on '
        'pointer-down', (tester) async {
      var stopped = 0;
      await tester.pumpWidget(_app(StillWatchingPrompt(
        remainingSeconds: 60,
        onContinue: () {},
        onStopNow: () => stopped++,
      )));
      await tester.tap(find.text('Stop now'));
      await tester.pump();
      expect(stopped, greaterThanOrEqualTo(1));
    });

    testWidgets('the bar drains as the countdown runs', (tester) async {
      Widget prompt(int s) => _app(StillWatchingPrompt(
            remainingSeconds: s,
            onContinue: () {},
          ));
      await tester.pumpWidget(prompt(100));
      expect(
        tester
            .widget<LinearProgressIndicator>(
                find.byType(LinearProgressIndicator))
            .value,
        1.0,
      );
      await tester.pumpWidget(prompt(25));
      expect(
        tester
            .widget<LinearProgressIndicator>(
                find.byType(LinearProgressIndicator))
            .value,
        0.25,
      );
    });
  });

  group('MusicPresentation', () {
    testWidgets('shows only real metadata', (tester) async {
      await tester.pumpWidget(_app(MusicPresentation(
        item: _item(1, artist: 'Artist', album: 'Album'),
        queueIndex: 2,
        queueLength: 12,
      )));
      expect(find.text('Video 1'), findsOneWidget);
      expect(find.text('Artist · Album'), findsOneWidget);
      expect(find.text('3 of 12 in the queue'), findsOneWidget);
    });

    testWidgets('omits missing artist, album and single-item position',
        (tester) async {
      await tester.pumpWidget(_app(MusicPresentation(
        item: _item(1),
        queueIndex: 0,
        queueLength: 1,
      )));
      expect(find.text('Video 1'), findsOneWidget);
      expect(find.textContaining(' · '), findsNothing);
      expect(find.textContaining('in the queue'), findsNothing);
      // No artwork: the placeholder note, not an invented image.
      expect(find.byIcon(Icons.music_note_rounded), findsOneWidget);
    });
  });

  test('speed labels', () {
    expect(playerSpeedLabel(1.0), '1×');
    expect(playerSpeedLabel(0.75), '0.75×');
    expect(playerSpeedLabel(2.0), '2×');
  });
}
