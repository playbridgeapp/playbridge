import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/tv_sender_client.dart';
import 'package:web_socket_channel/io.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

void main() {
  const timeout = Duration(milliseconds: 50);
  TvSenderClient make(_Channel Function() channel) => TvSenderClient(
        connectTimeout: timeout,
        authTimeout: timeout,
        connectChannel: (_, __) => channel(),
      );
  Future<void> connect(TvSenderClient client) => client.connect(
        host: '192.0.2.1',
        port: 8443,
        deviceName: 'Test',
        deviceUUID: 'test',
        token: 'test-token',
        expectedPin: 'sha256/test',
      );

  test('Cancel during socket opening cannot authenticate a late socket',
      () async {
    final channel = _Channel();
    final client = make(() => channel);
    addTearDown(client.dispose);
    final attempt = connect(client);
    await Future<void>.delayed(Duration.zero);
    await client.disconnect();
    channel.open.complete();
    await attempt;
    await Future<void>.delayed(Duration.zero);
    expect(channel.output.closed, isTrue);
    expect(channel.output.messages, isEmpty);
    expect(client.currentState, SenderConnectionState.disconnected);
    expect(client.send('ignored'), isFalse);
  });

  test('an older pending socket cannot replace a newer connection', () async {
    final old = _Channel();
    final fresh = _Channel()..open.complete();
    var count = 0;
    final client = make(() => count++ == 0 ? old : fresh);
    addTearDown(client.dispose);
    final first = connect(client);
    await Future<void>.delayed(Duration.zero);
    await connect(client);
    fresh.input.add('{"type":"auth_response","success":true}');
    await Future<void>.delayed(Duration.zero);
    old.open.complete();
    await first;
    await Future<void>.delayed(Duration.zero);
    expect(old.output.messages, isEmpty);
    expect(old.output.closed, isTrue);
    expect(client.currentState, SenderConnectionState.connected);
    expect(client.send('context'), isTrue);
    expect(fresh.output.messages.last, 'context');
  });

  test('socket opening and authentication each have a bounded timeout',
      () async {
    final opening = _Channel();
    final ready = _Channel()..open.complete();
    var count = 0;
    final client = make(() => count++ == 0 ? opening : ready);
    addTearDown(client.dispose);
    final attempt = connect(client);
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(timeout);
    await attempt;
    expect(client.currentState, SenderConnectionState.error);
    expect(opening.output.closed, isTrue);
    opening.open.complete();
    await Future<void>.delayed(Duration.zero);
    expect(opening.output.messages, isEmpty);

    final authFailed =
        client.state.firstWhere((s) => s == SenderConnectionState.error);
    await connect(client);
    expect(ready.output.messages.single, contains('test-token'));
    await authFailed;
    await Future<void>.delayed(Duration.zero);
    expect(client.currentState, SenderConnectionState.error);
    expect(ready.output.closed, isTrue);
    ready.input.add('{"type":"auth_response","success":true}');
    await Future<void>.delayed(Duration.zero);
    expect(client.currentState, SenderConnectionState.error);
  });

  test('successful or rejected auth cancels the watchdog', () async {
    for (final approved in [true, false]) {
      final channel = _Channel()..open.complete();
      final client = make(() => channel);
      await connect(client);
      channel.input.add('{"type":"auth_response","success":$approved}');
      await Future<void>.delayed(Duration.zero);
      final expected = approved
          ? SenderConnectionState.connected
          : SenderConnectionState.authFailed;
      expect(client.currentState, expected);
      await Future<void>.delayed(timeout * 2);
      expect(client.currentState, expected);
      await client.dispose();
    }
  });

  test('dispose invalidates a pending socket and future connects', () async {
    final channel = _Channel();
    var calls = 0;
    final client = make(() {
      calls++;
      return channel;
    });
    final attempt = connect(client);
    await Future<void>.delayed(Duration.zero);
    await client.dispose();
    channel.open.complete();
    await attempt;
    await connect(client);
    await Future<void>.delayed(timeout * 2);
    expect(calls, 1);
    expect(channel.output.closed, isTrue);
    expect(channel.output.messages, isEmpty);
  });
}

class _Channel implements IOWebSocketChannel {
  final open = Completer<void>();
  final input = StreamController<dynamic>.broadcast();
  final output = _Sink();
  @override
  Future<void> get ready => open.future;
  @override
  Stream<dynamic> get stream => input.stream;
  @override
  WebSocketSink get sink => output;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Sink implements WebSocketSink {
  final messages = <dynamic>[];
  bool closed = false;
  @override
  void add(dynamic data) {
    if (!closed) messages.add(data);
  }

  @override
  Future<void> close([int? code, String? reason]) {
    closed = true;
    return Future<void>.value();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
