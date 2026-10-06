import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/bridge_paths.dart';
import 'package:playbridge_desktop/extension_bridge.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Redirect only the handshake file/directory, never overwrite the user's bridge.
final class _BridgeFiles extends IOOverrides {
  _BridgeFiles(this.directory);
  final Directory directory;

  @override
  Directory createDirectory(String path) =>
      super.createDirectory(path == bridgeDirPath() ? directory.path : path);

  @override
  File createFile(String path) => super.createFile(
      path == bridgeFilePath() ? '${directory.path}/bridge.json' : path);
}

class _Transport implements TvTransport {
  int loads = 0;

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<bool> castVideo(PlayPayload video) async {
    loads++;
    return true;
  }

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

// A linked target without discovery/network/native-service startup.
class _LinkedSender extends TvSenderController {
  _LinkedSender(
      {required super.identity,
      required super.store,
      required super.transport,
      required super.retainProxyUrls});

  @override
  bool get isConnected => true;
}

class _Player extends ChangeNotifier implements PlayerController {
  @override
  Future<void> dispose() async => super.dispose();

  @override
  String get state => 'idle';

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
      'authenticated extension cast receives a failed result on acquisition timeout',
      () async {
    SharedPreferences.setMockInitialValues({});
    final directory =
        await Directory.systemTemp.createTemp('playbridge-bridge-timeout-');
    try {
      await IOOverrides.runWithIOOverrides(() async {
        final transport = _Transport();
        final sender = _LinkedSender(
            identity: await PairingStore.load(),
            store: await TvConnectionStore.load(),
            transport: transport,
            retainProxyUrls: (_) async =>
                throw TimeoutException('fixture acquisition timeout'));
        final player = _Player();
        final bridge = ExtensionBridge(sender, player);
        Socket? socket;
        StreamIterator<String>? frames;
        try {
          await bridge.start();
          final info =
              jsonDecode(await File(bridgeFilePath()).readAsString()) as Map;
          socket = await Socket.connect(
              InternetAddress.loopbackIPv4, info['port'] as int);
          final it = StreamIterator(socket
              .cast<List<int>>()
              .transform(utf8.decoder)
              .transform(const LineSplitter()));
          frames = it;
          Future<Map> nextFrame() async {
            expect(await it.moveNext().timeout(const Duration(seconds: 5)),
                isTrue);
            return jsonDecode(it.current) as Map;
          }

          socket.writeln(jsonEncode({'token': info['token']}));
          expect(await nextFrame(), {'type': 'hello', 'ok': true});
          expect((await nextFrame())['target'], 'tv');
          socket.writeln(jsonEncode(
              {'cmd': 'cast', 'url': 'https://example.invalid/media.mp4'}));
          expect(await nextFrame(), {
            'type': 'result',
            'ok': false,
            'target': 'tv',
            'error': 'send failed'
          });
          expect(transport.loads, 0);
        } finally {
          socket?.destroy();
          await frames?.cancel();
          // Let the server consume client EOF before iterating live connections.
          await Future<void>.delayed(const Duration(milliseconds: 50));
          await bridge.stop();
          sender.dispose();
          await player.dispose();
        }
      }, _BridgeFiles(directory));
    } finally {
      await directory.delete(recursive: true);
    }
  });
}
