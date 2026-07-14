import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'bridge_paths.dart';
import 'extension_request_debug_log.dart';
import 'player_controller.dart';
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
/// Routing: when a TV/device is linked ([TvSenderController.isConnected]), cast
/// and control go to that receiver. When nothing is connected, the same
/// commands play on **this** desktop ([PlayerController]) so the extension
/// always has a working target.
///
/// Wire format: newline-delimited JSON, one object per line.
/// - client → app: `{"token":"…"}` (first line), then `{"cmd":…}`.
/// - app → client: `{"type":"hello"|"state"|"result", …}`.
class ExtensionBridge {
  ExtensionBridge(
    this._sender,
    this._player, {
    this.isPlaybackPromptActive,
    this.onPromptContinue,
    this.onPromptStop,
    this.onPlaybackActivity,
    this.onNewMedia,
  });

  final TvSenderController _sender;
  final PlayerController _player;
  final bool Function()? isPlaybackPromptActive;
  final VoidCallback? onPromptContinue;
  final VoidCallback? onPromptStop;
  final VoidCallback? onPlaybackActivity;
  final VoidCallback? onNewMedia;

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
    _sender.addListener(_pushState);
    _player.addListener(_pushState);
    debugPrint('[ext-bridge] listening on 127.0.0.1:${server.port}');
  }

  void _onClient(Socket socket) {
    void dropClient() => _authed.remove(socket);

    // Socket.listen handles errors from the inbound stream, but Socket is also
    // an IOSink: a browser/native-host disconnect can complete `done` with an
    // asynchronous ECONNRESET after `write` has already returned. Observe that
    // future so a normal client shutdown never becomes an unhandled Flutter
    // exception. Non-socket failures are still surfaced for diagnosis.
    unawaited(socket.done.then<void>(
      (_) => dropClient(),
      onError: (Object error, StackTrace _) {
        dropClient();
        if (error is! SocketException) {
          debugPrint('[ext-bridge] client sink failed: $error');
        }
      },
    ));

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
      onDone: dropClient,
      onError: (_) => dropClient(),
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
        final title = obj['title'] as String?;
        debugLogExtensionCastRequest(url: url, headers: headers);
        if (_sender.isConnected) {
          unawaited(() async {
            final ok = await _sender.castUrl(url, headers: headers, title: title);
            _send(socket, {
              'type': 'result',
              'ok': ok,
              'target': 'tv',
              if (!ok) 'error': 'send failed',
            });
          }());
        } else {
          onNewMedia?.call();
          // No cast target — play on this machine (extension → desktop).
          if (kDebugMode) {
            debugPrint('[ext-bridge] cast → local player');
          }
          unawaited(_player.playUrl(
            url,
            headers: headers,
            title: title,
            isRemote: true,
          ));
          _send(socket, {
            'type': 'result',
            'ok': true,
            'target': 'local',
          });
        }
        break;
      case 'cast_file':
        // Cast a local file by absolute path — used by the OS "Play on TV"
        // context-menu entry, which shells `playbridge_cast <path>` and relays
        // it here. When a TV is linked, serve over LAN; otherwise open in the
        // local player.
        final path = obj['path'] as String?;
        if (path == null || path.isEmpty) {
          _send(
              socket, {'type': 'result', 'ok': false, 'error': 'missing path'});
          break;
        }
        final fileTitle = obj['title'] as String?;
        unawaited(() async {
          final file = File(path);
          if (!file.existsSync()) {
            _send(socket, {
              'type': 'result',
              'ok': false,
              'error': 'file not found',
            });
            return;
          }
          if (_sender.isConnected) {
            final ok = await _sender.castLocalFile(file, title: fileTitle);
            _send(socket, {
              'type': 'result',
              'ok': ok,
              'target': 'tv',
              if (!ok) 'error': 'send failed',
            });
            return;
          }
          final name = fileTitle ??
              (file.uri.pathSegments.isNotEmpty
                  ? file.uri.pathSegments.last
                  : path);
          onNewMedia?.call();
          await _player.playUrl(
            file.uri.toString(),
            title: name,
            isRemote: true,
          );
          debugPrint('[ext-bridge] cast_file → local player: $path');
          _send(socket, {
            'type': 'result',
            'ok': true,
            'target': 'local',
          });
        }());
        break;
      case 'control':
        final action = obj['action'] as String?;
        if (action == null || action.isEmpty) {
          _send(socket, {'type': 'result', 'ok': false});
          break;
        }
        final ok = _sender.isConnected
            ? _sender.sendControl(action)
            : _localControl(action);
        _send(socket, {
          'type': 'result',
          'ok': ok,
          'target': _sender.isConnected ? 'tv' : 'local',
        });
        break;
      default:
        _send(socket, {'type': 'result', 'ok': false, 'error': 'unknown cmd'});
    }
  }

  /// Apply a control action to the local [PlayerController] (same vocabulary
  /// as the TV receiver / [TvSenderController.sendControl]).
  bool _localControl(String action) {
    try {
      if (isPlaybackPromptActive?.call() ?? false) {
        if (action == 'play') {
          onPromptContinue?.call();
          return true;
        }
        if (action == 'stop') {
          onPromptStop?.call();
          return true;
        }
        onPromptContinue?.call();
        return true;
      }
      onPlaybackActivity?.call();
      if (action.startsWith('seek_to:')) {
        final ms = int.tryParse(action.substring('seek_to:'.length));
        if (ms == null) return false;
        final dur = _player.durationMs;
        var target = ms < 0 ? 0 : ms;
        if (dur > 0 && target > dur) target = dur;
        unawaited(_player.seek(Duration(milliseconds: target)));
        return true;
      }
      switch (action) {
        case 'play':
          unawaited(_player.resume());
          return true;
        case 'pause':
          unawaited(_player.pause());
          return true;
        case 'toggle':
          unawaited(
              _player.state == 'playing' ? _player.pause() : _player.resume());
          return true;
        case 'stop':
          unawaited(_player.stop());
          return true;
        case 'seek_back':
          final pos = _player.positionMs - 10000;
          unawaited(_player.seek(Duration(milliseconds: pos < 0 ? 0 : pos)));
          return true;
        case 'seek_forward':
          final dur = _player.durationMs;
          var target = _player.positionMs + 10000;
          if (dur > 0 && target > dur) target = dur;
          unawaited(_player.seek(Duration(milliseconds: target)));
          return true;
        default:
          debugPrint('[ext-bridge] local control ignored: $action');
          return false;
      }
    } catch (e) {
      debugPrint('[ext-bridge] local control failed: $e');
      return false;
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

  Map<String, dynamic> _stateFrame() {
    final tvLinked = _sender.isConnected;
    return {
      'type': 'state',
      // Always ready to accept cast — either a TV or this desktop.
      'connected': true,
      'target': tvLinked ? 'tv' : 'local',
      'state': tvLinked ? _sender.state.name : _player.state,
      'activeTv': tvLinked ? _sender.activeTv?.name : 'This computer',
      'devices': [
        for (final d in _sender.discovered)
          {
            'uuid': d.uuid,
            'name': d.name,
            'paired': _sender.pairedTvs.any((p) => p.uuid == d.uuid),
          },
      ],
    };
  }

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
    _sender.removeListener(_pushState);
    _player.removeListener(_pushState);
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
