// Plain Dart: the native host keeps stdin alive even when the GUI is absent.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'app_launcher.dart';
import 'bridge_paths.dart';

class NativeMessagingHost {
  NativeMessagingHost({
    required this.input,
    required this.writeMessage,
    Future<Map<String, dynamic>?> Function()? readBridgeInfo,
    Future<bool> Function()? launchApp,
    this.launchTimeout = const Duration(seconds: 20),
    this.pollInterval = const Duration(milliseconds: 250),
    this.connectTimeout = const Duration(seconds: 2),
  })  : readBridgeInfo = readBridgeInfo ?? _readBridgeInfo,
        launchApp = launchApp ?? launchDesktopApp;

  final Stream<List<int>> input;
  final void Function(Object) writeMessage;
  final Future<Map<String, dynamic>?> Function() readBridgeInfo;
  final Future<bool> Function() launchApp;
  final Duration launchTimeout;
  final Duration pollInterval;
  final Duration connectTimeout;
  final _closed = Completer<void>();
  Socket? _socket;
  bool _authenticated = false;
  bool get _isClosed => _closed.isCompleted;

  void _error(String code, String message) {
    if (_isClosed) return;
    writeMessage({'type': 'error', 'code': code, 'error': message});
  }

  void _notRunning() => _error('app_not_running', 'PlayBridge is not running');
  void _launchFailed() =>
      _error('launch_failed', 'Could not launch PlayBridge');

  Future<void> run() async {
    Future<void> pending = _connect(connectTimeout).then<void>((ok) {
      if (!ok) _notRunning();
    });
    final bytes = <int>[];
    void close() {
      if (!_isClosed) _closed.complete();
      _socket?.destroy();
    }

    final subscription = input.listen((chunk) {
      bytes.addAll(chunk);
      while (bytes.length >= 4) {
        final size =
            ByteData.sublistView(Uint8List.fromList(bytes.sublist(0, 4)))
                .getUint32(0, Endian.little);
        if (size > 64 * 1024 * 1024) {
          close();
          return;
        }
        if (bytes.length < 4 + size) break;
        final frame = bytes.sublist(4, 4 + size);
        bytes.removeRange(0, 4 + size);
        try {
          final message = jsonDecode(utf8.decode(frame));
          if (message is Map<String, dynamic>) {
            pending =
                pending.then((_) => _handle(message)).catchError((Object _) {
              if (message['cmd'] == 'launch_app') {
                _launchFailed();
              } else {
                _notRunning();
              }
            });
          }
        } on Object {
          // Malformed frames may contain sensitive input; never log them.
        }
      }
    }, onDone: close, onError: (Object _) => close());
    await _closed.future;
    await subscription.cancel();
    _socket?.destroy();
  }

  Future<void> _handle(Map<String, dynamic> message) async {
    if (_isClosed) return;
    if (_authenticated) {
      _socket?.writeln(jsonEncode(message));
      return;
    }
    if (message['cmd'] != 'launch_app') {
      if (await _connect(connectTimeout)) {
        if (!_isClosed) _socket?.writeln(jsonEncode(message));
      } else {
        _notRunning();
      }
      return;
    }
    final deadline = DateTime.now().add(launchTimeout);
    var launched = false;
    try {
      launched = await _wait(launchApp(), launchTimeout) == true;
    } on Object {/* Fixed failure below. */}
    if (_isClosed) return;
    while (launched && !_isClosed && DateTime.now().isBefore(deadline)) {
      final remaining = deadline.difference(DateTime.now());
      if (await _connect(
          remaining < connectTimeout ? remaining : connectTimeout)) {
        return;
      }
      final pause = deadline.difference(DateTime.now());
      if (pause <= Duration.zero) break;
      await Future.any([
        Future<void>.delayed(pause < pollInterval ? pause : pollInterval),
        _closed.future,
      ]);
    }
    _launchFailed();
  }

  Future<T?> _wait<T>(Future<T> value, Duration timeout) async {
    final done = Completer<T?>();
    void finish(T? result) {
      if (!done.isCompleted) done.complete(result);
    }

    final timer = Timer(timeout, () => finish(null));
    unawaited(value.then((result) => finish(result),
        onError: (Object _) => finish(null)));
    unawaited(_closed.future.then((_) => finish(null)));
    try {
      return await done.future;
    } finally {
      timer.cancel();
    }
  }

  /// Do not declare the link ready until the app accepts the token with hello.
  Future<bool> _connect(Duration timeout) async {
    if (_isClosed || timeout <= Duration.zero) return false;
    final watch = Stopwatch()..start();
    Duration remaining() => timeout - watch.elapsed;
    Socket? candidate;
    try {
      final info = await _wait(readBridgeInfo(), remaining());
      final port = info?['port'];
      final token = info?['token'];
      if (port is! int ||
          port < 1 ||
          port > 65535 ||
          token is! String ||
          token.isEmpty) {
        return false;
      }
      if (_isClosed || remaining() <= Duration.zero) return false;
      candidate = await Socket.connect(InternetAddress.loopbackIPv4, port,
          timeout: remaining());
      if (_isClosed) {
        candidate.destroy();
        return false;
      }
      final socket = candidate;
      _socket = socket;
      _authenticated = false;
      final ready = Completer<bool>();
      final buffer = <int>[];
      void lost() {
        if (!ready.isCompleted) ready.complete(false);
        if (identical(_socket, socket)) {
          final wasAuthenticated = _authenticated;
          _authenticated = false;
          _socket = null;
          if (wasAuthenticated) _notRunning();
        }
      }

      unawaited(socket.done.then((_) {}, onError: (Object _) => lost()));
      socket.listen((chunk) {
        if (_isClosed || !identical(_socket, socket)) return;
        buffer.addAll(chunk);
        int newline;
        while ((newline = buffer.indexOf(10)) >= 0) {
          final line = buffer.sublist(0, newline);
          buffer.removeRange(0, newline + 1);
          try {
            final value = jsonDecode(utf8.decode(line));
            if (value is! Map<String, dynamic>) continue;
            if (!_authenticated) {
              if (value['type'] != 'hello' || value['ok'] != true) {
                if (!ready.isCompleted) ready.complete(false);
                socket.destroy();
                return;
              }
              _authenticated = true;
              if (!ready.isCompleted) ready.complete(true);
            }
            if (!_isClosed && identical(_socket, socket)) writeMessage(value);
          } on Object {/* Malformed app lines may contain a token. */}
        }
      }, onDone: lost, onError: (Object _) => lost(), cancelOnError: true);
      socket.writeln(jsonEncode({'token': token}));
      final ok = await _wait(ready.future, remaining()) == true;
      if (!ok) {
        socket.destroy();
        if (identical(_socket, socket)) _socket = null;
      }
      return ok;
    } on Object {
      candidate?.destroy();
      return false;
    }
  }
}

Future<Map<String, dynamic>?> _readBridgeInfo() async {
  try {
    final value = jsonDecode(await File(bridgeFilePath()).readAsString());
    return value is Map<String, dynamic> ? value : null;
  } on Object {
    return null;
  }
}
