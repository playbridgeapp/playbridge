import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/together/desktop_together.dart';
import 'package:playbridge_desktop/together/together_screen.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/ui/pb_theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'desktop_linked_playback_test.dart' show Engine;
import 'extension_device_selection_test.dart' as fixtures;
import 'watch_together_test.dart' show FakeRoom, invite, room;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() async => StreamProxyServer.instance.stop());

  Future<({DesktopTogether together, FakeRoom room, TogetherClient client})>
      make(WidgetTester tester) async {
    final store = await TvConnectionStore.load();
    final identity = await PairingStore.load();
    final sender = TvSenderController(
        identity: identity, store: store, transport: fixtures.FakeTransport());
    final player = PlayerController(engineForTest: Engine());
    final fake = FakeRoom();
    final client =
        TogetherClient(native: fake, pollInterval: const Duration(hours: 1));
    final together = DesktopTogether(
      player: player,
      sender: sender,
      prefs: identity.prefs,
      deviceName: () => 'Office Mac',
      createClient: () => client,
    );
    addTearDown(sender.dispose);
    addTearDown(player.dispose);
    return (together: together, room: fake, client: client);
  }

  void deliver(
      ({DesktopTogether together, FakeRoom room, TogetherClient client}) f,
      Map<String, Object?> event) {
    f.room.pending.add(jsonEncode(event));
    f.client.poll();
  }

  Future<void> pump(WidgetTester tester, DesktopTogether together) async {
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: buildPbTheme(),
      home: Scaffold(body: TogetherScreen(together: together)),
    ));
  }

  testWidgets('start screen keeps start, join, show a code and own relay',
      (tester) async {
    final f = await make(tester);
    await pump(tester, f.together);

    expect(find.text('Watch Together'), findsOneWidget);
    expect(find.text('Start a party'), findsWidgets);
    expect(find.text('Join with a link'), findsOneWidget);
    expect(find.text('Add this computer from a phone'), findsOneWidget);
    expect(find.text('Show a code'), findsOneWidget);
    expect(find.text('Watch on this computer'), findsOneWidget);
    expect(find.text('Use your own relay…'), findsOneWidget);

    await tester.tap(find.text('Use your own relay…'));
    await tester.pumpAndSettle();
    expect(find.text('Save relay'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Start a party').last);
    await tester.pump();
    expect(f.together.state, isA<TogetherStarting>());
    await tester.pumpWidget(const SizedBox());
    f.together.dispose();
  });

  testWidgets('an invite asks to join or decline', (tester) async {
    final f = await make(tester);
    f.together.showCode();
    deliver(f, {'event': 'pairing_invite', 'from': 'Pixel 9', 'relay': ''});
    await pump(tester, f.together);
    expect(f.together.state, isA<TogetherInvited>());
    expect(find.text('Join the watch party?'), findsOneWidget);
    expect(
        find.textContaining('Pixel 9 invited this computer'), findsOneWidget);
    expect(find.text('Decline'), findsOneWidget);
    await tester.tap(find.text('Join'));
    await tester.pump();
    expect(f.room.names, contains('pair_accept'));
    await tester.pumpWidget(const SizedBox());
    f.together.dispose();
  });

  testWidgets('showing a code offers the grouped code and Cancel',
      (tester) async {
    final f = await make(tester);
    f.together.showCode();
    deliver(f, {
      'event': 'pairing_code',
      'code': '482913057',
      'qr': 'x',
      'qr_size': 0,
      'qr_modules': '',
      'expires_at': 0,
    });
    await pump(tester, f.together);
    expect(find.text('482 913 057'), findsOneWidget);
    expect(find.text('Add this computer from a phone'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pump();
    expect(f.together.state, isNot(isA<TogetherShowingCode>()));
    await tester.pumpWidget(const SizedBox());
    f.together.dispose();
  });

  testWidgets('party screen shows devices, controls and the invite dialog',
      (tester) async {
    final f = await make(tester);
    f.together.join(invite, watchHere: true);
    deliver(f, room(host: true));
    deliver(f, {
      'event': 'member',
      'member': 'me',
      'name': 'Office Mac',
      'kind': 'desktop',
      'host': true
    });
    deliver(f, {
      'event': 'member',
      'member': 'tv',
      'name': 'Sam TV',
      'kind': 'tv',
      'host': false
    });
    deliver(f, {
      'event': 'video',
      'item': {
        'id': 'a',
        'title': 'Clip a',
        'source': {'type': 'url', 'url': 'https://media.example/a.mp4'}
      }
    });
    deliver(f, {
      'event': 'connected',
      'members': ['me', 'tv']
    });
    deliver(f, {
      'event': 'play_requested',
      'request': 'r1',
      'from': 'tv',
      'title': 'Big Buck Bunny',
      'site': 'peach.blender.org',
    });
    await pump(tester, f.together);
    await tester.pump();

    expect(f.together.party!.host, isTrue);
    expect(find.text('Your watch party'), findsOneWidget);
    expect(find.text('Clip a'), findsOneWidget);
    expect(find.text('Devices'), findsOneWidget);
    expect(find.text('Office Mac (you)'), findsOneWidget);
    expect(find.text('Sam TV'), findsOneWidget);
    expect(find.text('Who can change the video'), findsOneWidget);
    expect(find.text('Only me'), findsOneWidget);
    expect(find.text('Ask me'), findsOneWidget);
    expect(find.text('Anyone'), findsOneWidget);
    expect(find.text('Lock play, pause and seek'), findsOneWidget);
    expect(find.text('Share sign-in'), findsOneWidget);
    expect(find.text('Suggestions'), findsOneWidget);
    expect(find.text('Big Buck Bunny'), findsOneWidget);
    expect(find.text('Always allow'), findsOneWidget);
    expect(find.text('Leave the party'), findsOneWidget);
    expect(find.text('Play something else'), findsOneWidget);
    expect(find.text('Open the video'), findsNothing,
        reason: 'only when the shell passes onOpenVideo');

    await tester.tap(find.text('Ask me'));
    await tester.pump();
    expect(f.room.last('policy')['policy'], 'ask');

    await tester.tap(find.text('Invite'));
    await tester.pumpAndSettle();
    expect(find.text('Invite to your party'), findsOneWidget);
    expect(find.text('Copy invite link'), findsOneWidget);
    expect(find.text('Send invite'), findsOneWidget);
    await tester.tap(find.byTooltip('Close'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Share sign-in'));
    await tester.pumpAndSettle();
    expect(find.text('Share your sign-in with Sam TV?'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Leave the party'));
    await tester.pump();
    expect(f.room.names, contains('leave'));
    await tester.pumpWidget(const SizedBox());
    f.together.dispose();
  });

  test('pairing codes group in threes', () {
    expect(groupPartyCode('482913057'), '482 913 057');
    expect(groupPartyCode('AB12'), 'AB12');
  });
}
