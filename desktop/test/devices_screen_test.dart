import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/devices/browser_receiver_page.dart';
import 'package:playbridge_desktop/devices/connection_banners.dart';
import 'package:playbridge_desktop/devices/extension_setup.dart';
import 'package:playbridge_desktop/devices/pair_tv_card.dart';
import 'package:playbridge_desktop/devices/phones_section.dart';
import 'package:playbridge_desktop/devices_screen.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:playbridge_desktop/ui/protocol_chip.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _IdleTransport implements TvTransport {
  final states = StreamController<SenderConnectionState>.broadcast();
  final submitted = <String>[];
  bool cancelled = false;

  @override
  Stream<SenderConnectionState> get state => states.stream;

  @override
  Stream<String> get messages => const Stream.empty();

  @override
  Stream<TvCredentials> get credentials => const Stream.empty();

  @override
  Stream<String> get sasCode => const Stream.empty();

  @override
  bool get lastSasWrong => false;

  @override
  int get sasAttemptsLeft => 3;

  @override
  bool submitSasCode(String code) {
    submitted.add(code);
    return true;
  }

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {}

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<void> disconnect() async {
    cancelled = true;
  }

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

DiscoveredTv _tv(String uuid, String name, TvProtocol protocol,
        {int? wssPort, int? port = 8765, String host = '192.168.1.20'}) =>
    DiscoveredTv(
      uuid: uuid,
      protocol: protocol,
      name: name,
      host: host,
      port: port,
      wssPort: wssPort,
      location: protocol == TvProtocol.dlna ? 'http://$host/desc.xml' : null,
    );

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<
      ({
        TvSenderController sender,
        PairingStore store,
        List<int> changed,
        _IdleTransport transport,
      })> pump(
    WidgetTester tester, {
    List<DiscoveredTv> discovered = const [],
    List<TvRecord> paired = const [],
    List<PairedDeviceRecord> phones = const [],
  }) async {
    tester.view.physicalSize = const Size(1280, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = await PairingStore.load();
    for (final p in phones) {
      await store.addPairedDevice(p);
    }
    final tvStore = await TvConnectionStore.load();
    for (final t in paired) {
      await tvStore.upsert(t);
    }
    final transport = _IdleTransport();
    final sender = TvSenderController(
      identity: store,
      store: tvStore,
      transport: transport,
    );
    addTearDown(sender.dispose);
    sender.bindTransportForTest(discoveredDevices: discovered);
    final changed = <int>[];
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(
        body: DevicesScreen(
          sender: sender,
          store: store,
          onPairedDevicesChanged: () => changed.add(1),
          onOpenNowCasting: () {},
        ),
      ),
    ));
    return (
      sender: sender,
      store: store,
      changed: changed,
      transport: transport,
    );
  }

  testWidgets('lists TVs with real kind and state, and phones with Forget',
      (tester) async {
    final env = await pump(
      tester,
      discovered: [
        _tv('a', 'Living Room', TvProtocol.playBridge),
        _tv('b', 'Bedroom Roku', TvProtocol.roku, port: null),
      ],
      paired: [
        TvRecord(
          uuid: 'c',
          name: 'Den TV',
          host: '192.168.1.30',
          port: 8765,
          token: 't',
          lastConnected: DateTime(2026, 1, 1),
        ),
      ],
      phones: [
        PairedDeviceRecord(
          deviceUUID: 'p1',
          deviceName: 'Pixel 9',
          token: 'tok',
          lastConnected: DateTime.now(),
        ),
      ],
    );

    expect(find.text('Devices'), findsOneWidget);
    expect(find.text('TVs you can send to'), findsOneWidget);
    expect(find.text('Search again'), findsOneWidget);
    expect(find.text('Living Room'), findsOneWidget);
    // Found but never used: both sit under Nearby, each with a protocol chip.
    expect(find.text('Nearby'), findsOneWidget);
    expect(find.text('Saved'), findsNothing);
    expect(find.byType(ProtocolChip), findsNWidgets(2));
    expect(find.text('PlayBridge'), findsOneWidget);
    expect(find.textContaining('on your network'), findsNWidgets(2));
    expect(find.textContaining('not encrypted'), findsOneWidget);
    expect(find.text('Bedroom Roku'), findsOneWidget);
    expect(find.text('Roku'), findsOneWidget);
    // A paired TV that is not on the network is folded away until expanded.
    expect(find.text('Den TV'), findsNothing);
    await tester.tap(find.text('Not available now · 1'));
    await tester.pump();
    expect(find.text('Den TV'), findsOneWidget);
    expect(find.textContaining('not found right now'), findsOneWidget);
    // Pairing needed for the new PlayBridge TV, plain connect for Roku.
    expect(find.text('Pair'), findsOneWidget);
    expect(find.text('Connect'), findsOneWidget);
    expect(find.text('Reconnect'), findsOneWidget);

    expect(find.text('Phones that can cast here'), findsOneWidget);
    expect(find.text('Pixel 9'), findsOneWidget);
    expect(find.text('Last here today'), findsOneWidget);

    // Forget the phone and the offline TV (the phone's Forget is the last one).
    await tester.tap(find.text('Forget').last);
    await tester.pumpAndSettle();
    expect(env.changed, [1]);
    expect(env.store.pairedDevices, isEmpty);
    expect(find.text('Pixel 9'), findsNothing);
    expect(find.textContaining('No phones yet'), findsOneWidget);

    await tester.tap(find.text('Forget'));
    await tester.pumpAndSettle();
    expect(env.sender.pairedTvs, isEmpty);
    expect(find.text('Den TV'), findsNothing);
  });

  testWidgets('empty state gives one line of guidance', (tester) async {
    await pump(tester);
    expect(find.textContaining('No TVs found'), findsOneWidget);
    expect(find.textContaining('No phones yet'), findsOneWidget);
  });

  testWidgets('TV without the app opens the receiver page and goes back',
      (tester) async {
    await pump(tester);
    expect(find.text('Browser extension'), findsOneWidget);
    expect(find.textContaining('Set up automatically'), findsOneWidget);

    await tester.tap(find.text('TV without the app'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Nothing is installed on it'), findsOneWidget);
    expect(find.text('Turn on'), findsOneWidget);
    expect(find.text('Turn off'), findsNothing);

    await tester.tap(find.text('Devices'));
    await tester.pumpAndSettle();
    expect(find.text('TVs you can send to'), findsOneWidget);
  });

  testWidgets('Advanced is collapsed and holds the proxy switch',
      (tester) async {
    final env = await pump(tester);
    expect(find.text('Send casts through this computer'), findsNothing);
    await tester.tap(find.text('Advanced'));
    await tester.pumpAndSettle();
    final before = env.sender.castRouteThroughProxy;
    await tester.tap(find.text('Send casts through this computer'));
    await tester.pumpAndSettle();
    expect(env.sender.castRouteThroughProxy, !before);
  });

  testWidgets('pairing code card appears over Devices and drives the sender',
      (tester) async {
    final env = await pump(
      tester,
      discovered: [_tv('a', 'Living Room', TvProtocol.playBridge)],
    );
    await tester.tap(find.text('Pair'));
    env.transport.states.add(SenderConnectionState.waitingForCodeInput);
    await tester.pumpAndSettle();
    expect(find.text('Pair with Living Room'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '123456');
    expect(env.transport.submitted, ['123456']);

    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(env.transport.cancelled, isTrue);
  });

  group('PairTvCard', () {
    Future<void> show(
      WidgetTester tester, {
      bool verifying = false,
      bool wrong = false,
      int attempts = 2,
      List<String>? submitted,
      VoidCallback? onCancel,
    }) =>
        tester.pumpWidget(MaterialApp(
          theme: buildPbTheme(),
          home: Scaffold(
            body: Center(
              child: PairTvCard(
                tvName: 'Den TV',
                isVerifying: verifying,
                lastCodeWrong: wrong,
                attemptsLeft: attempts,
                onSubmit: (c) => submitted?.add(c),
                onCancel: onCancel ?? () {},
              ),
            ),
          ),
        ));

    testWidgets('names the TV, submits at six digits and cancels',
        (tester) async {
      final submitted = <String>[];
      var cancelled = false;
      await show(tester,
          submitted: submitted, onCancel: () => cancelled = true);
      expect(find.text('Pair with Den TV'), findsOneWidget);
      expect(find.text('You only do this once for each TV.'), findsOneWidget);

      await tester.enterText(find.byType(TextField), '482');
      await tester.tap(find.text('Pair'));
      expect(submitted, isEmpty);

      await tester.enterText(find.byType(TextField), '482913');
      expect(submitted, ['482913']);

      await tester.tap(find.text('Cancel'));
      expect(cancelled, isTrue);
    });

    testWidgets('a wrong code replaces the helper line', (tester) async {
      await show(tester, wrong: true, attempts: 2);
      expect(
          find.text("That code didn't match. 2 tries left."), findsOneWidget);
      expect(find.text('You only do this once for each TV.'), findsNothing);
      await show(tester, wrong: true, attempts: 1);
      expect(find.text("That code didn't match. 1 try left."), findsOneWidget);
    });

    testWidgets('verifying disables input and Pair', (tester) async {
      await show(tester, verifying: true);
      expect(find.text('Checking the code…'), findsOneWidget);
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
    });
  });

  group('helpers', () {
    test('formatLastHere uses calendar days', () {
      final now = DateTime(2026, 11, 11, 9);
      expect(formatLastHere(DateTime(2026, 11, 11, 1), now), 'Last here today');
      expect(formatLastHere(DateTime(2026, 11, 10, 23), now),
          'Last here yesterday');
      expect(
          formatLastHere(DateTime(2026, 11, 8), now), 'Last here 3 days ago');
      expect(
          formatLastHere(DateTime(2026, 10, 1), now), 'Last here 2026-10-01');
    });

    test('displayAddress drops http and trailing slash only', () {
      expect(displayAddress('http://192.168.1.40:8770/'), '192.168.1.40:8770');
      expect(displayAddress('https://tv.local:8770'), 'https://tv.local:8770');
    });

    test('describeConnection keeps every error state and hides idle ones', () {
      for (final s in [
        SenderConnectionState.disconnected,
        SenderConnectionState.connected,
        SenderConnectionState.waitingForCodeInput,
        SenderConnectionState.verifyingCode,
      ]) {
        expect(
            describeConnection(state: s, activeTv: null, reconnectStatus: null),
            isNull);
      }
      String text(SenderConnectionState s) =>
          describeConnection(state: s, activeTv: null, reconnectStatus: null)!
              .text;
      expect(text(SenderConnectionState.pairingDenied),
          'Pairing was denied on the TV.');
      expect(text(SenderConnectionState.authFailed), contains('Pair again'));
      expect(text(SenderConnectionState.pinMismatch),
          contains("certificate changed"));
      expect(text(SenderConnectionState.error), contains('Connection error'));
      expect(text(SenderConnectionState.connecting), 'Connecting…');
      expect(
        describeConnection(
          state: SenderConnectionState.error,
          activeTv: null,
          reconnectStatus: const ReceiverReconnectStatus(
              attempt: 1, maxAttempts: 3, deviceName: 'Den TV'),
        )!
            .text,
        'Reconnecting to Den TV (1/3)…',
      );
    });

    test('describeExtensionStatus reports only what a run found', () {
      expect(describeExtensionStatus(null), contains('Set up automatically'));
      expect(describeExtensionStatus(null, busy: true), 'Setting up…');
      expect(
        describeExtensionStatus(const ExtensionSetupResult(
          host: {
            'Chrome': 'ready',
            'Firefox': 'ready',
            'Brave': 'not installed (skipped)'
          },
          menu: {},
        )),
        'Set up for Chrome, Firefox · set up again',
      );
      expect(
        describeExtensionStatus(const ExtensionSetupResult(
          host: {'host binary': 'not found — build it'},
          menu: {},
        )),
        'Setup needs attention · try again',
      );
    });
  });
}
