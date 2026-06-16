import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'bridge_paths.dart';
import 'tv_sender_controller.dart';

/// Local IPC endpoint the browser extension reaches **via the native-messaging
/// host** (decision D2). The browser-blessed host stub relays the extension's
/// stdio frames to this loopback socket and back.
///
/// Security: the socket is loopback-only and **token-gated** — the app writes
/// `{port, token}` to `bridge.json` in its app-support dir (chmod 600 on POSIX),
/// and only a client that presents the token is accepted. A web page can't reach
/// the host (browser-enforced allowlist) nor read the token file, so it can't
/// drive the bridge even though there is a loopback port.
///
/// Wire format: newline-delimited JSON, one object per line.
/// - client → app: `{"token":"…"}` (first line), then `{"cmd":…}`.
/// - app → client: `{"type":"hello"|"state"|"result", …}`.
class ExtensionBridge {
  ExtensionBridge(this._controller);

  final TvSenderController _controller;

  ServerSocket? _server;
  final Set<Socket> _authed = {};
  String _token = '';

  Future<void> start() async {
    if (_server != null) return;
    _token = _randomToken();
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    _server = server;
    await _writeBridgeInfo(server.port, _token);
    server.listen(_onClient);
    _controller.addListener(_pushState);
    debugPrint('[ext-bridge] listening on 127.0.0.1:${server.port}');
  }

  void _onClient(Socket socket) {
    var authed = false;
    final buf = <int>[];
    socket.listen(
      (data) {
        buf.addAll(data);
        // Split on newline (0x0A) — safe against UTF-8 since continuation bytes
        // are always >= 0x80, never 0x0A.
        int nl;
        while ((nl = buf.indexOf(0x0A)) >= 0) {
          final line = utf8.decode(buf.sublist(0, nl)).trim();
          buf.removeRange(0, nl + 1);
          if (line.isEmpty) continue;
          if (!authed) {
            authed = _tryAuth(line, socket);
            if (!authed) {
              socket.destroy();
              return;
            }
          } else {
            _handleCommand(line, socket);
          }
        }
      },
      onDone: () => _authed.remove(socket),
      onError: (_) => _authed.remove(socket),
      cancelOnError: true,
    );
  }

  bool _tryAuth(String line, Socket socket) {
    try {
      final obj = jsonDecode(line);
      if (obj is Map && obj['token'] == _token) {
        _authed.add(socket);
        _send(socket, {'type': 'hello', 'ok': true});
        _sendStateTo(socket);
        return true;
      }
    } catch (_) {}
    return false;
  }

  void _handleCommand(String line, Socket socket) {
    final Map obj;
    try {
      final decoded = jsonDecode(line);
      if (decoded is! Map) return;
      obj = decoded;
    } catch (_) {
      return;
    }

    switch (obj['cmd']) {
      case 'list_devices':
        _sendStateTo(socket);
        break;
      case 'cast':
        final url = obj['url'] as String?;
        if (url == null || url.isEmpty) {
          _send(
              socket, {'type': 'result', 'ok': false, 'error': 'missing url'});
          break;
        }
        final headers =
            (obj['headers'] as Map?)?.map((k, v) => MapEntry('$k', '$v'));
        final ok = _controller.castUrl(url,
            headers: headers, title: obj['title'] as String?);
        _send(socket, {
          'type': 'result',
          'ok': ok,
          if (!ok) 'error': 'no TV connected',
        });
        break;
      case 'cast_file':
        // Cast a local file by absolute path — used by the OS "Play on TV"
        // context-menu entry, which shells `playbridge_cast <path>` and relays
        // it here. castLocalFile is async (it spins up the LAN file server), so
        // reply once it resolves.
        final path = obj['path'] as String?;
        if (path == null || path.isEmpty) {
          _send(
              socket, {'type': 'result', 'ok': false, 'error': 'missing path'});
          break;
        }
        final fileTitle = obj['title'] as String?;
        unawaited(() async {
          final ok =
              await _controller.castLocalFile(File(path), title: fileTitle);
          _send(socket, {
            'type': 'result',
            'ok': ok,
            if (!ok) 'error': 'no TV connected or file not found',
          });
        }());
        break;
      case 'control':
        final action = obj['action'] as String?;
        final ok = action != null && _controller.sendControl(action);
        _send(socket, {'type': 'result', 'ok': ok});
        break;
      default:
        _send(socket, {'type': 'result', 'ok': false, 'error': 'unknown cmd'});
    }
  }

  void _pushState() {
    if (_authed.isEmpty) return;
    final frame = _stateFrame();
    for (final s in _authed) {
      _send(s, frame);
    }
  }

  void _sendStateTo(Socket socket) => _send(socket, _stateFrame());

  Map<String, dynamic> _stateFrame() => {
        'type': 'state',
        'connected': _controller.isConnected,
        'state': _controller.state.name,
        'activeTv': _controller.activeTv?.name,
        'devices': [
          for (final d in _controller.discovered)
            {
              'uuid': d.uuid,
              'name': d.name,
              'paired': _controller.pairedTvs.any((p) => p.uuid == d.uuid),
            },
        ],
      };

  void _send(Socket socket, Map<String, dynamic> obj) {
    try {
      socket.write('${jsonEncode(obj)}\n');
    } catch (_) {}
  }

  Future<void> _writeBridgeInfo(int port, String token) async {
    try {
      final dir = Directory(bridgeDirPath());
      if (!dir.existsSync()) dir.createSync(recursive: true);
      final file = File(bridgeFilePath());
      await file.writeAsString(jsonEncode({'port': port, 'token': token}),
          flush: true);
      if (!Platform.isWindows) {
        try {
          await Process.run('chmod', ['600', file.path]);
        } catch (_) {}
      }
    } catch (e) {
      debugPrint('[ext-bridge] failed to write bridge.json: $e');
    }
  }

  String _randomToken() {
    final r = Random.secure();
    return base64Url
        .encode(List<int>.generate(24, (_) => r.nextInt(256)))
        .replaceAll('=', '');
  }

  Future<void> stop() async {
    _controller.removeListener(_pushState);
    for (final s in _authed) {
      try {
        await s.close();
      } catch (_) {}
    }
    _authed.clear();
    await _server?.close();
    _server = null;
  }
}
