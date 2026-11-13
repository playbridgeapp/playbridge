import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/history_store.dart';
import 'package:playbridge_desktop/home_screen.dart';
import 'package:playbridge_desktop/now_casting_screen.dart';
import 'package:playbridge_desktop/pair_screen.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/shell/main_drop_overlay.dart';
import 'package:playbridge_desktop/shell/paste_link_dialog.dart';
import 'package:playbridge_desktop/shell/play_request.dart';
import 'package:playbridge_desktop/shell/play_target.dart';
import 'package:playbridge_desktop/shell/playback_bar.dart';
import 'package:playbridge_desktop/shell/receiver_status.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Transport implements TvTransport {
  @override
  Set<String> get features => const {};

  @override
  Stream<SenderConnectionState> get state => const Stream.empty();

  @override
  Stream<String> get messages => const Stream.empty();

  @override
  Stream<TvCredentials> get credentials => const Stream.empty();

  @override
  Stream<String> get sasCode => const Stream.empty();

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<void> disconnect() async {}

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

DiscoveredTv _tv(String uuid, String name,
        {TvProtocol protocol = TvProtocol.playBridge}) =>
    DiscoveredTv(
      uuid: uuid,
      protocol: protocol,
      name: name,
      host: '192.168.1.${uuid.length + 20}',
      port: 8765,
      wssPort: 8766,
      location: protocol == TvProtocol.dlna ? 'http://tv/desc.xml' : null,
    );

Future<TvSenderController> _sender({List<DiscoveredTv> tvs = const []}) async {
  final sender = TvSenderController(
    identity: await PairingStore.load(),
    store: await TvConnectionStore.load(),
    transport: _Transport(),
  );
  sender.bindTransportForTest(discoveredDevices: tvs);
  addTearDown(sender.dispose);
  return sender;
}

Widget _host(Widget child) => MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(body: child),
    );

ReceiverStatusInfo _ready() => describeReceiverStatus(
      deviceName: 'Studio Mac',
      hostInfo: '192.168.1.5',
      phase: PairingPhase.idle,
    );

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('play requests', () {
    test('classifies playable files and http links, ignores the rest', () {
      final r = classifyPlayablePaths([
        '/a/b/movie.MKV',
        '/a/b/notes.txt',
        'https://example.com/v.mp4',
        'ftp://example.com/v.mp4',
        '/a/b/song.flac',
      ]);
      expect(r.files.map((f) => f.path), ['/a/b/movie.MKV', '/a/b/song.flac']);
      expect(r.urls, ['https://example.com/v.mp4']);
      expect(r.count, 3);
      expect(classifyPlayablePaths(['/x/readme']).isEmpty, isTrue);
    });

    test('parses pasted links', () {
      expect(parsePlayableLink('  https://example.com/a.mp4 '),
          'https://example.com/a.mp4');
      expect(parsePlayableLink('http://192.168.1.4:8080/x'), isNotNull);
      expect(parsePlayableLink(''), isNull);
      expect(parsePlayableLink('example.com/a.mp4'), isNull);
      expect(parsePlayableLink('magnet:?xt=urn:btih:1'), isNull);
      expect(parsePlayableLink('https://a b.com'), isNull);
    });
  });

  group('drop overlay', () {
    test('picks the half under the pointer', () {
      const wide = Size(1000, 600);
      expect(dropZoneAt(const Offset(100, 300), wide), DropZone.here);
      expect(dropZoneAt(const Offset(900, 300), wide), DropZone.tv);
      const narrow = Size(500, 800);
      expect(dropZoneAt(const Offset(250, 100), narrow), DropZone.here);
      expect(dropZoneAt(const Offset(250, 700), narrow), DropZone.tv);
    });

    testWidgets('is a single "Play here" target without a TV', (tester) async {
      await tester.pumpWidget(_host(const MainDropOverlay(
        computerName: 'Studio Mac',
      )));
      expect(find.text('Play here'), findsOneWidget);
      expect(find.text('On this computer, Studio Mac'), findsOneWidget);
      expect(find.textContaining('Send to'), findsNothing);
    });

    testWidgets('splits in two when a TV is selected', (tester) async {
      await tester.pumpWidget(_host(const MainDropOverlay(
        computerName: 'Studio Mac',
        tvName: 'Living Room TV',
        pointer: Offset(700, 300),
      )));
      expect(find.text('Play here'), findsOneWidget);
      expect(find.text('Send to Living Room TV'), findsOneWidget);
    });
  });

  group('Plays on', () {
    testWidgets('lists this computer, known TVs and Find more devices',
        (tester) async {
      tester.view.physicalSize = const Size(1200, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final sender = await _sender(tvs: [
        _tv('tv-1', 'Living Room TV'),
        _tv('roku-1', 'Bedroom Roku', protocol: TvProtocol.roku),
      ]);
      var findMore = 0;
      await tester.pumpWidget(_host(Align(
        alignment: Alignment.topLeft,
        child: PlayOnPill(
          sender: sender,
          computerName: 'Studio Mac',
          onFindDevices: () => findMore++,
          onMessage: (_) {},
        ),
      )));
      expect(find.text('This computer'), findsOneWidget);

      await tester.tap(find.text('This computer'));
      await tester.pumpAndSettle();
      expect(find.text('Play on'), findsOneWidget);
      expect(find.text('Studio Mac'), findsOneWidget);
      expect(find.text('Living Room TV'), findsOneWidget);
      // The protocol is a chip; the line beside it is the state only.
      expect(find.text('PlayBridge'), findsOneWidget);
      expect(find.text('needs pairing'), findsOneWidget);
      expect(find.text('Bedroom Roku'), findsOneWidget);
      expect(find.text('Roku'), findsOneWidget);
      expect(find.text('on your network'), findsOneWidget);

      // A TV that was never paired sends the user to Devices instead of
      // inventing a connect path.
      await tester.tap(find.text('Living Room TV'));
      await tester.pumpAndSettle();
      expect(findMore, 1);
      expect(find.text('Play on'), findsNothing);

      await tester.tap(find.text('This computer'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Find more devices…'));
      await tester.pumpAndSettle();
      expect(findMore, 2);
    });

    test('options mark the current destination and sort offline last',
        () async {
      final sender =
          await _sender(tvs: [_tv('a', 'Zed', protocol: TvProtocol.dlna)]);
      final options = buildPlayTargetOptions(sender, 'Studio Mac');
      expect(options.first.isComputer, isTrue);
      expect(options.first.selected, isTrue);
      expect(options.length, 2);
      expect(options[1].name, 'Zed');
      expect(options[1].selected, isFalse);
      expect(playsOnTv(sender), isFalse);
      expect(playTargetLabel(sender), 'This computer');
    });
  });

  group('HomeScreen', () {
    Future<Widget> home({
      bool withHistory = false,
      List<String>? played,
      VoidCallback? onChoose,
      VoidCallback? onPaste,
      VoidCallback? onFind,
    }) async {
      final store = await PairingStore.load();
      final history = await HistoryStore.load(recentsEnabled: true);
      if (withHistory) {
        await history.addOrBump(url: 'https://x.test/a.mp4', title: 'Sintel');
        await history.toggleFavorite('https://x.test/a.mp4');
      }
      final sender = await _sender();
      return _host(HomeScreen(
        store: store,
        history: history,
        sender: sender,
        deviceName: 'Studio Mac',
        status: _ready(),
        onChooseFiles: onChoose ?? () {},
        onPasteLink: onPaste ?? () {},
        onFindDevices: onFind ?? () {},
        onSeeBookmarks: () {},
        onPlayHistoryItem: (item) => played?.add(item.title),
        onMessage: (_) {},
      ));
    }

    testWidgets('first run shows the welcome and no Plays on pill',
        (tester) async {
      tester.view.physicalSize = const Size(1200, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var chose = 0, pasted = 0, found = 0;
      await tester.pumpWidget(await home(
        onChoose: () => chose++,
        onPaste: () => pasted++,
        onFind: () => found++,
      ));
      expect(find.text('Welcome to PlayBridge'), findsOneWidget);
      expect(find.text('Cast from your phone'), findsOneWidget);
      expect(find.text('Waiting for a phone'), findsOneWidget);
      expect(find.text('Send to a TV'), findsOneWidget);
      expect(find.text('Plays on'), findsNothing);
      await tester.tap(find.text('Find TVs'));
      await tester.tap(find.text('Choose files…'));
      await tester.tap(find.text('Paste link'));
      expect([chose, pasted, found], [1, 1, 1]);
    });

    testWidgets('after first play shows Play something, bookmarks and recent',
        (tester) async {
      tester.view.physicalSize = const Size(1200, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final played = <String>[];
      await tester.pumpWidget(await home(withHistory: true, played: played));
      expect(find.text('Home'), findsOneWidget);
      expect(find.text('Play something'), findsOneWidget);
      expect(find.text('Plays on'), findsOneWidget);
      expect(find.text('This computer'), findsOneWidget);
      expect(find.text('Bookmarks'), findsOneWidget);
      expect(find.text('Recent'), findsOneWidget);
      expect(find.text('Sintel'), findsNWidgets(2));
      expect(find.text('Played today'), findsNWidgets(2));
      await tester.tap(find.text('Sintel').first);
      expect(played, ['Sintel']);
    });

    test('describes when something was played', () {
      final now = DateTime(2026, 11, 11, 12);
      expect(describePlayedAt(now, DateTime(2026, 11, 11, 1)), 'Played today');
      expect(describePlayedAt(now, DateTime(2026, 11, 10, 23)),
          'Played yesterday');
      expect(describePlayedAt(now, DateTime(2026, 11, 8)), 'Played Sunday');
      expect(describePlayedAt(now, DateTime(2026, 9, 3)), 'Played 3 Sep 2026');
    });
  });

  group('PlaybackBar', () {
    testWidgets('shows title, place and time; transport and open work',
        (tester) async {
      tester.view.physicalSize = const Size(1200, 400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var toggled = 0, back = 0, forward = 0, opened = 0;
      await tester.pumpWidget(_host(Align(
        alignment: Alignment.bottomCenter,
        child: PlaybackBar(
          title: 'Sintel',
          playingOn: 'Playing on Living Room TV',
          positionMs: 402000,
          durationMs: 888000,
          playing: true,
          onOpen: () => opened++,
          onPlayPause: () => toggled++,
          onBack: () => back++,
          onForward: () => forward++,
        ),
      )));
      expect(find.text('Sintel'), findsOneWidget);
      expect(find.text('Playing on Living Room TV · 6:42 / 14:48'),
          findsOneWidget);
      await tester.tap(find.byTooltip('Pause'));
      await tester.tap(find.byTooltip('Back 10 seconds'));
      await tester.tap(find.byTooltip('Forward 10 seconds'));
      await tester.tap(find.text('Open controls'));
      await tester.tap(find.text('Sintel'));
      expect([toggled, back, forward, opened], [1, 1, 1, 2]);
    });

    testWidgets('live items show no time and disabled seek', (tester) async {
      await tester.pumpWidget(_host(Align(
        alignment: Alignment.bottomCenter,
        child: PlaybackBar(
          title: 'Live',
          playingOn: 'Playing on this computer',
          positionMs: 0,
          durationMs: 0,
          playing: false,
          onOpen: () {},
          onPlayPause: () {},
        ),
      )));
      expect(find.text('Playing on this computer'), findsOneWidget);
      expect(find.byTooltip('Play'), findsOneWidget);
    });

    test('formats clock', () {
      expect(formatPlaybackClock(0), '0:00');
      expect(formatPlaybackClock(402000), '6:42');
      expect(formatPlaybackClock(3723000), '1:02:03');
    });
  });

  group('pairing request', () {
    PendingPairingRequest request() => const PendingPairingRequest(
          connectionId: 1,
          deviceName: 'Pixel 9',
          deviceUUID: 'u',
          sasCode: '482913',
        );

    testWidgets('shows the phone, the code, a countdown and denies',
        (tester) async {
      var denied = 0;
      await tester.pumpWidget(_host(PairRequestOverlay(
        phase: PairingPhase.awaitingCode,
        pendingRequest: request(),
        onDeny: () => denied++,
      )));
      expect(find.text('Pixel 9 wants to pair'), findsOneWidget);
      expect(find.text('Type this code on the phone to allow it to cast here.'),
          findsOneWidget);
      for (final d in ['4', '8', '2', '9', '1', '3']) {
        expect(find.text(d), findsOneWidget);
      }
      expect(find.text('Expires in 60 seconds'), findsOneWidget);
      await tester.pump(const Duration(seconds: 8));
      expect(find.text('Expires in 52 seconds'), findsOneWidget);
      await tester.tap(find.text("Don't allow"));
      expect(denied, 1);
      // Unmount so the countdown timer is cancelled.
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('shows the connecting state and hides when idle',
        (tester) async {
      await tester.pumpWidget(_host(const PairRequestOverlay(
        phase: PairingPhase.awaitingApproval,
        pendingRequest: null,
        onDeny: _noop,
      )));
      expect(find.text('Connecting…'), findsOneWidget);
      await tester.pumpWidget(_host(const PairRequestOverlay(
        phase: PairingPhase.idle,
        pendingRequest: null,
        onDeny: _noop,
      )));
      expect(find.text('Connecting…'), findsNothing);
      expect(find.text("Don't allow"), findsNothing);
    });
  });

  testWidgets('paste link dialog accepts only http(s) links', (tester) async {
    String? result;
    await tester.pumpWidget(_host(Builder(
      builder: (context) => TextButton(
        onPressed: () async => result = await showPasteLinkDialog(context),
        child: const Text('open'),
      ),
    )));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'not a link');
    await tester.tap(find.text('Play'));
    await tester.pump();
    expect(find.textContaining('http://'), findsOneWidget);
    expect(result, isNull);
    await tester.enterText(find.byType(TextField), 'https://example.com/v.mp4');
    await tester.tap(find.text('Play'));
    await tester.pumpAndSettle();
    expect(result, 'https://example.com/v.mp4');
  });

  testWidgets('remote view keeps its controls and offers a way back',
      (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final sender = await _sender();
    sender.handleReceiverMessage(jsonEncode({
      'type': 'status',
      'state': 'playing',
      'title': 'Sintel',
    }));
    var back = 0;
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(
        body: NowCastingScreen(controller: sender, onBack: () => back++),
      ),
    ));
    expect(find.text('Sintel'), findsOneWidget);
    expect(find.byTooltip('Back to Home. Playback continues'), findsOneWidget);
    expect(find.text('Stop'), findsOneWidget);
    await tester.tap(find.byTooltip('Back to Home. Playback continues'));
    expect(back, 1);
  });
}

void _noop() {}
