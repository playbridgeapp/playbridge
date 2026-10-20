import 'dart:async';
import 'package:flutter/services.dart';
import 'package:playbridge_desktop/system_volume.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/remote_volume_commands.dart';
import 'package:playbridge_desktop/playback_osd.dart';
import 'package:playbridge_desktop/receiver_capabilities.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
      'native set returns the applied state without a get/read-back round trip',
      () async {
    final methods = <String>[];
    const channel = MethodChannel('playbridge/system_volume');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      expect(call.arguments, {'level': .45});
      return {'available': true, 'level': .45, 'muted': false};
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final applied = await SystemVolume.applyMac(level: .45);
    expect(applied!.frame()['level'], .45);
    expect(methods, ['set']);
    expect(remoteVolumeFeedback(applied.frame()), 'Volume 45');
  });
  test('burst counts every relative step, including arrivals during a write',
      () async {
    final gate = Completer<void>();
    final applied = <int>[];
    final queue = RemoteVolumeCommands((change) async {
      applied.add(change.steps);
      if (applied.length == 1) await gate.future;
    });
    final finished = queue.add(const RemoteVolumeChange(steps: 1));
    await Future<void>.delayed(Duration.zero);
    for (var i = 0; i < 20; i++) {
      queue.add(const RemoteVolumeChange(steps: 1));
    }
    queue.add(const RemoteVolumeChange(steps: -1));
    gate.complete();
    await finished;
    expect(applied, [1, 20, -1]);
  });
  test('pending absolutes use the last writer and preserve mute barriers',
      () async {
    final applied = <RemoteVolumeChange>[];
    final queue = RemoteVolumeCommands((change) async {
      applied.add(change);
    });
    queue.add(const RemoteVolumeChange(level: .2));
    queue.add(const RemoteVolumeChange(level: .4));
    queue.add(const RemoteVolumeChange(mute: true));
    await queue.add(const RemoteVolumeChange(level: .6));
    expect(applied.map((c) => c.level), [.4, null, .6]);
    expect(applied[1].mute, true);
  });
  test('applied values generate feedback without inferring mute from zero', () {
    expect(remoteVolumeFeedback({'level': .45, 'muted': false}), 'Volume 45');
    expect(remoteVolumeFeedback({'level': 0, 'muted': false}), 'Volume 0');
    expect(remoteVolumeFeedback({'level': .45, 'muted': true}), 'Muted');
    expect(remoteVolumeFeedback({}), isNull);
    final caps = buildDesktopReceiverCapabilities(
        operatingSystem: 'macos', macNativeVolume: false);
    expect((caps['volume'] as Map)['operations'], ['relative_step']);
    expect((caps['volume'] as Map)['step'], .0625);
  });
  testWidgets('same OSD renders an applied remote volume on an idle shell',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
        home: Stack(children: [
      const Text('Home'),
      PlaybackOsd(message: remoteVolumeFeedback({'level': .45})!)
    ])));
    expect(find.text('Volume 45'), findsOneWidget);
    expect(find.byType(IgnorePointer), findsWidgets);
  });
}
