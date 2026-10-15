import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _PolicyTestTransport extends TvTransport {
  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();

  SenderConnectionState _current = SenderConnectionState.disconnected;
  Map<String, dynamic> _caps = {};
  final List<String> sentControls = [];
  final List<String> sentRemoteKeys = [];
  final List<({String scope, double level})> sentVolumes = [];
  final List<String?> sentBrowserLaunches = [];
  final List<String> sentBrowserControls = [];
  final List<String> sentMessages = [];

  void setCapabilities(Map<String, dynamic> caps) {
    _caps = caps;
  }

  void emitState(SenderConnectionState state) {
    _current = state;
    if (!_state.isClosed) _state.add(state);
  }

  void emitMessage(String msg) {
    if (!_messages.isClosed) _messages.add(msg);
  }

  void emitCredentials(TvCredentials creds) {
    if (!_credentials.isClosed) _credentials.add(creds);
  }

  @override
  Stream<SenderConnectionState> get state => _state.stream;

  @override
  SenderConnectionState get currentState => _current;

  @override
  Stream<String> get messages => _messages.stream;

  @override
  Stream<TvCredentials> get credentials => _credentials.stream;

  @override
  Map<String, dynamic> get capabilities => _caps;

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    emitState(SenderConnectionState.connected);
  }

  @override
  Future<void> disconnect() async {
    emitState(SenderConnectionState.disconnected);
  }

  @override
  Future<bool> castVideo(PlayPayload video) async => true;

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async => true;

  @override
  Future<bool> queueAdd(PlayPayload item) async => true;

  @override
  Future<bool> sendControl(String command) async {
    sentControls.add(command);
    return true;
  }

  @override
  Future<bool> sendRemoteKey(String key) async {
    sentRemoteKeys.add(key);
    return true;
  }

  @override
  Future<bool> sendVolume(String scope, double level) async {
    sentVolumes.add((scope: scope, level: level));
    return true;
  }

  @override
  Future<bool> sendBrowserLaunch({String? engine}) async {
    sentBrowserLaunches.add(engine);
    return true;
  }

  @override
  Future<bool> sendBrowserControl(String action) async {
    sentBrowserControls.add(action);
    return true;
  }

  @override
  Future<bool> playlistJump(int index) async => true;

  @override
  Future<void> dispose() async {
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  final androidTvDescriptor = <String, dynamic>{
    'version': 1,
    'surfaces': {
      'player': {
        'engines': {
          'mpv': {
            'transport': [
              'play',
              'pause',
              'stop',
              'seek_absolute',
              'seek_relative'
            ],
            'settings': ['loop', 'engine'],
            'queue': ['jump_index'],
          },
          'exo': {
            'transport': [
              'play',
              'pause',
              'stop',
              'seek_absolute',
              'seek_relative'
            ],
            'settings': ['loop', 'engine'],
            'queue': ['jump_index'],
          },
        },
      },
    },
    'host': {
      'osFamily': 'android',
      'deviceClass': 'tv',
    },
  };

  final appleTvWithAppVolumeDescriptor = <String, dynamic>{
    'version': 1,
    'surfaces': {
      'player': {
        'engines': {
          'avplayer': {
            'transport': ['play', 'pause', 'stop', 'seek_absolute'],
          },
        },
      },
    },
    'volume': {
      'scope': 'app',
      'step': 0.05,
      'operations': ['relative_step', 'absolute', 'mute_toggle'],
    },
    'host': {
      'osFamily': 'tvos',
      'deviceClass': 'tv',
    },
  };

  final appleTvNoVolumeDescriptor = <String, dynamic>{
    'version': 1,
    'surfaces': {
      'player': {
        'engines': {
          'avplayer': {
            'transport': ['play', 'pause', 'stop', 'seek_absolute'],
          },
        },
      },
    },
    'host': {
      'osFamily': 'tvos',
      'deviceClass': 'tv',
    },
  };

  Future<
      ({
        TvSenderController sender,
        _PolicyTestTransport transport,
        TvRecord record,
        TvConnectionStore store
      })> createFixture({Map<String, dynamic>? descriptor}) async {
    final transport = _PolicyTestTransport();
    if (descriptor != null) {
      transport.setCapabilities(descriptor);
    }
    final store = await TvConnectionStore.load();
    final record = TvRecord(
      uuid: 'test-receiver-uuid',
      protocol: TvProtocol.playBridge,
      name: 'Test Receiver',
      host: '192.0.2.50',
      port: 8765,
      wssPort: 8443,
      token: 'secret-token-12345',
      capabilities: descriptor ?? const {},
      lastConnected: DateTime(2026, 1, 1),
    );
    await store.upsert(record);

    final sender = TvSenderController(
      identity: await PairingStore.load(),
      store: store,
      transport: transport,
    );
    addTearDown(sender.dispose);
    sender.bindTransportForTest();
    return (sender: sender, transport: transport, record: record, store: store);
  }

  group('TvSenderController Policy Gating', () {
    testWidgets('unspecified descriptor falls back to legacy policy',
        (tester) async {
      final f = await createFixture(descriptor: null);
      await f.sender.reconnect(f.record);
      await tester.pump();

      expect(f.sender.policy.isLegacy, isTrue);
      expect(f.sender.hasVolume, isTrue);
      expect(f.sender.canLoop, isFalse);
      expect(f.sender.canSwitchEngine, isFalse);
    });

    testWidgets(
        'Android TV descriptor: player operations permitted, volume rejected',
        (tester) async {
      final f = await createFixture(descriptor: androidTvDescriptor);
      await f.sender.reconnect(f.record);
      await tester.pump();

      expect(f.sender.policy.isLegacy, isFalse);
      expect(f.sender.hasVolume, isFalse);
      expect(f.sender.policy.isAppScopeVolume, isFalse);
      expect(f.sender.canLoop, isTrue);
      expect(f.sender.canSwitchEngine, isTrue);
      expect(f.sender.availablePlayerEngines, equals({'mpv', 'exo'}));

      // Volume commands should be rejected and not reach transport
      expect(await f.sender.adjustVolume(1), isFalse);
      expect(await f.sender.setVolume(0.5), isFalse);
      expect(await f.sender.toggleMute(), isFalse);
      expect(f.transport.sentVolumes, isEmpty);

      // Permitted player commands should reach transport
      expect(await f.sender.sendControl('pause'), isTrue);
      expect(f.transport.sentControls, contains('pause'));

      // Switch player to valid engine
      expect(await f.sender.switchPlayer('exo'), isTrue);
      expect(f.transport.sentControls, contains('switch_player:exo'));

      // Switch player to disallowed engine should be rejected
      expect(await f.sender.switchPlayer('vlc'), isFalse);
    });

    testWidgets(
        'Apple TV with App volume descriptor: volume enabled and marked App volume',
        (tester) async {
      final f = await createFixture(descriptor: appleTvWithAppVolumeDescriptor);
      await f.sender.reconnect(f.record);
      await tester.pump();

      expect(f.sender.hasVolume, isTrue);
      expect(f.sender.policy.isAppScopeVolume, isTrue);
      expect(f.sender.policy.volumeScope, equals('app'));
      expect(f.sender.canSwitchEngine, isFalse);
      expect(f.sender.canLoop, isFalse);

      // Volume adjust succeeds
      expect(await f.sender.adjustVolume(1), isTrue);
      expect(f.transport.sentRemoteKeys, contains('volume_up'));

      // Engine switch rejected
      expect(await f.sender.switchPlayer('exo'), isFalse);
    });

    testWidgets('Apple TV with no volume block: volume completely disabled',
        (tester) async {
      final f = await createFixture(descriptor: appleTvNoVolumeDescriptor);
      await f.sender.reconnect(f.record);
      await tester.pump();

      expect(f.sender.hasVolume, isFalse);
      expect(f.sender.canAdjustVolume, isFalse);
      expect(f.sender.canSetVolume, isFalse);
      expect(f.sender.canToggleMute, isFalse);

      expect(await f.sender.adjustVolume(-1), isFalse);
      expect(await f.sender.setVolume(0.8), isFalse);
      expect(await f.sender.toggleMute(), isFalse);
      expect(f.transport.sentVolumes, isEmpty);
      expect(f.transport.sentRemoteKeys, isEmpty);
    });

    testWidgets('Dynamic context update modifies active policy and gating',
        (tester) async {
      final f = await createFixture(descriptor: {
        'version': 1,
        'surfaces': {
          'player': {
            'engines': {
              'mpv': {
                'transport': ['play', 'pause', 'stop', 'seek_to'],
              },
            },
          },
          'browser': {
            'engines': {
              'chromium': {
                'browser': ['forward', 'reload'],
              },
            },
          },
          'idle': {
            'engines': {
              'system': {},
            },
          },
        },
      });
      await f.sender.reconnect(f.record);
      await tester.pump();

      // Initially on player surface
      expect(f.sender.canPlayPause, isTrue);

      // Context changes to browser
      f.transport.emitMessage(jsonEncode({
        'type': 'context',
        'surface': 'browser',
        'engine': 'chromium',
      }));
      await tester.pump();

      expect(f.sender.policy.canBrowserForward, isTrue);
      expect(await f.sender.sendBrowserControl('forward'), isTrue);
      expect(f.transport.sentBrowserControls, contains('forward'));

      // Context changes to idle
      f.transport.emitMessage(jsonEncode({
        'type': 'context',
        'surface': 'idle',
      }));
      await tester.pump();

      // Controls gated off during idle
      expect(f.sender.canPlayPause, isFalse);
      expect(await f.sender.sendControl('pause'), isFalse);
      expect(await f.sender.sendBrowserControl('forward'), isFalse);
    });

    testWidgets(
        'Dynamic player_settings update gates settings-backed operations',
        (tester) async {
      final f = await createFixture(descriptor: androidTvDescriptor);
      await f.sender.reconnect(f.record);
      await tester.pump();

      expect(f.sender.canLoop, isTrue);

      // Receiver notifies that loop is temporarily unavailable
      f.transport.emitMessage(jsonEncode({
        'type': 'player_settings',
        'loop_available': false,
      }));
      await tester.pump();

      expect(f.sender.canLoop, isFalse);
      expect(await f.sender.setLoop(true), isFalse);
    });

    testWidgets('Credentials, tokens, pins, and descriptors are never logged',
        (tester) async {
      final logs = <String>[];
      final originalDebugPrint = debugPrint;
      debugPrint = (String? message, {int? wrapWidth}) {
        if (message != null) logs.add(message);
      };

      try {
        final f = await createFixture(descriptor: androidTvDescriptor);
        await f.sender.reconnect(f.record);
        await tester.pump();

        // Emit simulated credentials with secrets
        f.transport.emitCredentials(TvCredentials(
          'super-sensitive-jwt-token-xyz',
          'sha256/certpin',
          capabilities: androidTvDescriptor,
        ));
        await tester.pump();

        // Assert no sensitive info leaked
        for (final log in logs) {
          expect(log.contains('super-sensitive-jwt-token-xyz'), isFalse,
              reason: 'Token must not be logged');
          expect(log.contains('capabilities'), isFalse,
              reason: 'Capabilities descriptor payload must not be logged');
        }
      } finally {
        debugPrint = originalDebugPrint;
      }
    });
  });
}
