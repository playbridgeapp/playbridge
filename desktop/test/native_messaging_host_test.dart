import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/native_messaging_host.dart';

List<int> frame(Map<String, dynamic> value) {
  final body = utf8.encode(jsonEncode(value));
  final header = Uint8List(4);
  ByteData.sublistView(header).setUint32(0, body.length, Endian.little);
  return [...header, ...body];
}

Future<void> waitFor(bool Function() condition) async {
  final deadline = DateTime.now().add(const Duration(seconds: 2));
  while (!condition() && DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  expect(condition(), isTrue);
}

void main() {
  test(
      'missing app reports a code but stays alive; unrelated intents never launch',
      () async {
    final input = StreamController<List<int>>();
    final output = <Map>[];
    var launches = 0;
    var exited = false;
    final host = NativeMessagingHost(
        input: input.stream,
        writeMessage: (value) => output.add(value as Map),
        readBridgeInfo: () async => null,
        launchApp: () async {
          launches++;
          return true;
        });
    final running = host.run().then((_) => exited = true);
    await waitFor(() => output.isNotEmpty);
    expect(output.single, {
      'type': 'error',
      'error': 'PlayBridge is not running',
      'code': 'app_not_running'
    });
    final bytes = frame({'cmd': 'cast', 'url': 'https://fixture/media'});
    input.add(bytes.sublist(0, 2));
    input.add(bytes.sublist(2));
    await waitFor(() => output.length == 2);
    expect(launches, 0);
    expect(exited, isFalse);
    await input.close();
    await running.timeout(const Duration(seconds: 1));
    expect(exited, isTrue);
  });

  test('list_devices reconnects after manual app startup and restart',
      () async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final peers = <Socket>[];
    final received = <Map>[];
    final subscription = server.listen((socket) {
      peers.add(socket);
      var authed = false;
      socket
          .cast<List<int>>()
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .listen((line) {
        final message = jsonDecode(line) as Map;
        if (!authed) {
          expect(message, {'token': 'fixture-token'});
          authed = true;
          socket.writeln(jsonEncode({'type': 'hello', 'ok': true}));
        } else {
          received.add(message);
        }
        socket.writeln(jsonEncode({
          'type': 'state',
          'selection': {'kind': 'local'}
        }));
      }, onError: (Object _) {});
    });
    final input = StreamController<List<int>>();
    final output = <Map>[];
    var appAvailable = false;
    var launches = 0;
    final host = NativeMessagingHost(
        input: input.stream,
        writeMessage: (value) => output.add(value as Map),
        readBridgeInfo: () async => appAvailable
            ? {'port': server.port, 'token': 'fixture-token'}
            : null,
        launchApp: () async {
          launches++;
          return true;
        });
    final running = host.run();
    try {
      await waitFor(() => output.isNotEmpty);
      expect(output.single['code'], 'app_not_running');
      for (var attempt = 1; attempt <= 2; attempt++) {
        appAvailable = true;
        input.add(frame({'cmd': 'list_devices'}));
        await waitFor(() =>
            received.length == attempt &&
            output.where((value) => value['type'] == 'state').length ==
                attempt * 2);
        expect(received.last, {'cmd': 'list_devices'});
        expect(
            output.where((value) => value['type'] == 'hello').length, attempt);
        expect(launches, 0);
        if (attempt == 1) {
          appAvailable = false;
          peers.last.destroy();
          await waitFor(() => output.last['code'] == 'app_not_running');
        }
      }
    } finally {
      await input.close();
      await running;
      for (final peer in peers) {
        peer.destroy();
      }
      await subscription.cancel();
      await server.close();
    }
  });

  test(
      'failed explicit launch stays alive and reports fixed, secret-free errors',
      () async {
    final input = StreamController<List<int>>();
    final output = <Map>[];
    var launches = 0;
    final host = NativeMessagingHost(
        input: input.stream,
        writeMessage: (value) => output.add(value as Map),
        readBridgeInfo: () async => {'port': 'bad', 'token': 'fixture-secret'},
        launchApp: () async {
          launches++;
          throw StateError('fixture-secret');
        });
    final running = host.run();
    await waitFor(() => output.isNotEmpty);
    input.add(frame({'cmd': 'launch_app'}));
    await waitFor(() => output.length == 2);
    expect(output.last['code'], 'launch_failed');
    expect(jsonEncode(output), isNot(contains('fixture-secret')));
    input.add(frame({'cmd': 'launch_app'}));
    await waitFor(() => launches == 2 && output.length == 3);
    await input.close();
    await running;
  });

  test('explicit launch polls, authenticates, relays and survives app shutdown',
      () async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final peers = <Socket>[];
    final received = <Map>[];
    final subscription = server.listen((socket) {
      peers.add(socket);
      var authed = false;
      socket
          .cast<List<int>>()
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .listen((line) {
        final message = jsonDecode(line) as Map;
        if (!authed) {
          expect(message, {'token': 'fixture-token'});
          authed = true;
          socket.writeln(jsonEncode({'type': 'hello', 'ok': true}));
          socket.writeln(jsonEncode({
            'type': 'state',
            'selection': {'kind': 'local'}
          }));
        } else {
          received.add(message);
          socket.writeln(jsonEncode({'type': 'result', 'ok': true}));
        }
      }, onError: (Object _) {});
    });
    final input = StreamController<List<int>>();
    final output = <Map>[];
    var launches = 0;
    var reads = 0;
    final host = NativeMessagingHost(
        input: input.stream,
        writeMessage: (value) => output.add(value as Map),
        readBridgeInfo: () async {
          reads++;
          if (launches == 0 || reads < 3) return null;
          return {'port': server.port, 'token': 'fixture-token'};
        },
        launchApp: () async {
          launches++;
          return true;
        },
        pollInterval: const Duration(milliseconds: 5));
    final running = host.run();
    try {
      await waitFor(() => output.isNotEmpty);
      expect(launches, 0);
      input.add(frame({'cmd': 'launch_app'}));
      await waitFor(() => output.any((value) => value['type'] == 'state'));
      expect(launches, 1);
      expect(reads, greaterThanOrEqualTo(3));
      input.add(frame({'cmd': 'select_local'}));
      await waitFor(
          () => received.isNotEmpty && output.last['type'] == 'result');
      expect(received.single, {'cmd': 'select_local'});
      expect(jsonEncode(output), isNot(contains('fixture-token')));
      peers.last.destroy();
      await waitFor(() => output.last['code'] == 'app_not_running');
      expect(launches, 1);
      input.add(frame({'cmd': 'launch_app'}));
      await waitFor(() => launches == 2 && output.last['type'] == 'state');
    } finally {
      await input.close();
      await running;
      for (final peer in peers) {
        peer.destroy();
      }
      await subscription.cancel();
      await server.close();
    }
  });

  for (final reply in [false, true]) {
    test(
        'launch deadline bounds ${reply ? 'rejected' : 'missing'} auth and stdin closure',
        () async {
      final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
      final peers = <Socket>[];
      final subscription = server.listen((socket) {
        peers.add(socket);
        // Rejected authentication deliberately closes the peer immediately.
        unawaited(socket.done.then((_) {}, onError: (Object _) {}));
        socket.listen((_) {
          if (reply) {
            socket.writeln(jsonEncode({'type': 'hello', 'ok': false}));
            socket.writeln(jsonEncode({'type': 'state', 'untrusted': true}));
          }
        }, onError: (Object _) {});
      });
      final input = StreamController<List<int>>();
      final output = <Map>[];
      var launched = false;
      final host = NativeMessagingHost(
          input: input.stream,
          writeMessage: (value) => output.add(value as Map),
          readBridgeInfo: () async =>
              launched ? {'port': server.port, 'token': 'fixture-token'} : null,
          launchApp: () async {
            launched = true;
            return true;
          },
          launchTimeout: const Duration(milliseconds: 60),
          connectTimeout: const Duration(milliseconds: 20),
          pollInterval: const Duration(milliseconds: 5));
      final running = host.run();
      try {
        await waitFor(() => output.isNotEmpty);
        input.add(frame({'cmd': 'launch_app'}));
        await waitFor(
            () => output.any((value) => value['code'] == 'launch_failed'));
        expect(output.any((value) => value['type'] == 'state'), isFalse);
      } finally {
        await input.close();
        await running.timeout(const Duration(seconds: 1));
        for (final peer in peers) {
          peer.destroy();
        }
        await subscription.cancel();
        await server.close();
      }
    });
  }

  test('stdin closes promptly even during a launch that never completes',
      () async {
    final input = StreamController<List<int>>();
    final output = <Map>[];
    final launch = Completer<bool>();
    var started = false;
    final host = NativeMessagingHost(
        input: input.stream,
        writeMessage: (value) => output.add(value as Map),
        readBridgeInfo: () async => null,
        launchApp: () {
          started = true;
          return launch.future;
        });
    final running = host.run();
    await waitFor(() => output.isNotEmpty);
    input.add(frame({'cmd': 'launch_app'}));
    await waitFor(() => started);
    await input.close();
    await running.timeout(const Duration(milliseconds: 100));
  });
}
