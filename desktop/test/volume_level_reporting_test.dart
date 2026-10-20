import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/receiver_capability_policy.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _VolumeTransport implements TvTransport {
  _VolumeTransport(this.protocol);
  @override
  final TvProtocol protocol;
  @override
  Map<String, dynamic> get capabilities => {'volume': true};
  @override
  Future<void> dispose() async {}
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test(
      'external idle status publishes measured volume and clears unknown/fixed reports',
      () async {
    for (final entry in {
      TvProtocol.googleCast: 'Receiver volume',
      TvProtocol.dlna: 'Renderer volume',
      TvProtocol.webBrowser: 'App volume',
      TvProtocol.roku: 'Receiver volume',
    }.entries) {
      final sender = TvSenderController(
        identity: await PairingStore.load(),
        store: await TvConnectionStore.load(),
        transport: _VolumeTransport(entry.key),
      );
      addTearDown(sender.dispose);
      sender.handleReceiverMessage(
          '{"type":"status","state":"idle","volumeSupported":true,"volumeLevel":0.37,"muted":false}');
      expect(
          sender.volumeStatus,
          entry.key == TvProtocol.roku
              ? entry.value
              : '${entry.value} 37/100 · Unmuted');
      sender.handleReceiverMessage('{"type":"status","state":"idle"}');
      expect(sender.volumeLevel, isNull);
      expect(sender.isMuted, isNull);
      sender.handleReceiverMessage(
          '{"type":"status","volumeSupported":false,"volumeLevel":0.5,"muted":true}');
      expect(sender.volumeStatus, entry.value);
      expect(sender.canAdjustVolume, entry.key == TvProtocol.roku);
    }
  });
  test('native reported app volume reaches the display with explicit mute', () {
    final policy = resolveReceiverCapabilityPolicy(
      rawDescriptor: {
        'version': 1,
        'surfaces': <String, dynamic>{},
        'volume': {
          'scope': 'app',
          'operations': [
            'relative_step',
            'absolute',
            'mute_toggle',
            'level_report',
            'mute_report'
          ]
        }
      },
      volumeState:
          const ReceiverVolumeState(scope: 'app', level: .37, muted: false),
    );
    expect(policy.currentVolumeLevel, .37);
    expect(
        senderVolumeStatus(
            policy.volumeLabel!, policy.currentVolumeLevel, policy.isMuted),
        'App volume 37/100 · Unmuted');
  });
  test('system receiver fallback displays actual app scope and known level',
      () {
    final policy = resolveReceiverCapabilityPolicy(
      rawDescriptor: {
        'version': 1,
        'surfaces': <String, dynamic>{},
        'volume': {
          'scope': 'system',
          'fallbackScope': 'app',
          'operations': ['relative_step', 'level_report']
        }
      },
      volumeState: const ReceiverVolumeState(scope: 'app', level: .25),
    );
    expect(
        senderVolumeStatus(
            policy.volumeLabel!, policy.currentVolumeLevel, policy.isMuted),
        'App volume 25/100');
  });
  test('external labels do not invent a missing level or infer zero as mute',
      () {
    expect(senderVolumeStatus('Receiver volume', 0, false),
        'Receiver volume 0/100 · Unmuted');
    expect(senderVolumeStatus('Renderer volume', .62, true),
        'Renderer volume 62/100 · Muted');
    expect(
        senderVolumeStatus('Receiver volume', null, null), 'Receiver volume');
    expect(senderVolumeStatus('Receiver volume', double.nan, null),
        'Receiver volume');
  });
}
