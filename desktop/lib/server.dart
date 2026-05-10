import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import 'pairing_store.dart';
import 'player_controller.dart';
import 'protocol.dart';

const int kDefaultPort = 8765;

/// Surfaced to the UI so it can show a pairing PIN prompt or the player view.
enum PairingPhase {
  /// No client connected.
  idle,

  /// Client connected and explicitly asked to pair (sent `request_pairing`)
  /// or hasn't sent auth yet — UI should show the PIN.
  awaitingPin,

  /// At least one authenticated client is connected.
  authenticated,
}

class ReceiverServer extends ChangeNotifier {
  ReceiverServer({required this.player, required this.store});

  final PlayerController player;
  final PairingStore store;

  HttpServer? _http;
  Timer? _statusTimer;
  bool _disposed = false;

  // All connected channels — but only authed ones receive status broadcasts
  // and can issue commands.
  final Set<WebSocketChannel> _all = {};
  final Set<WebSocketChannel> _authed = {};
  final Set<WebSocketChannel> _awaitingPin = {};

  PairingPhase get phase {
    if (_authed.isNotEmpty) return PairingPhase.authenticated;
    if (_awaitingPin.isNotEmpty) return PairingPhase.awaitingPin;
    return PairingPhase.idle;
  }

  Future<void> start({int port = kDefaultPort}) async {
    final handler = webSocketHandler(_onClient);
    _http = await shelf_io.serve(handler, InternetAddress.anyIPv4, port);
    debugPrint('[server] listening on ${_http!.address.address}:${_http!.port}');

    player.addListener(_broadcastStatus);
    player.indexChanges.addListener(_broadcastPlaylistStatus);
    _statusTimer = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => _broadcastStatus(),
    );
  }

  Future<void> stop() async {
    if (_disposed) return;
    _disposed = true;
    _statusTimer?.cancel();
    player.removeListener(_broadcastStatus);
    player.indexChanges.removeListener(_broadcastPlaylistStatus);
    for (final c in _all.toList()) {
      await c.sink.close();
    }
    _all.clear();
    _authed.clear();
    _awaitingPin.clear();
    await _http?.close(force: true);
  }

  void _onClient(WebSocketChannel channel, String? subprotocol) {
    debugPrint('[server] client connected');
    _all.add(channel);

    var authed = false;

    channel.stream.listen(
      (raw) {
        if (raw is! String) return;
        debugPrint('[recv${authed ? '' : ' pre-auth'}] $raw');
        if (authed) {
          _handleAuthed(channel, raw);
        } else {
          authed = _handleAuthFrame(channel, raw);
          if (authed) {
            _awaitingPin.remove(channel);
            _authed.add(channel);
            notifyListeners();
            _sendPlaylistStatusTo(channel);
          }
        }
      },
      onDone: () {
        // Playback intentionally continues — disconnect only removes the
        // channel from broadcast sets, never touches the player.
        debugPrint('[server] client disconnected (player state=${player.state}, queue=${player.queue.length})');
        _all.remove(channel);
        _authed.remove(channel);
        _awaitingPin.remove(channel);
        notifyListeners();
      },
      onError: (e) {
        debugPrint('[server] client error: $e');
        _all.remove(channel);
        _authed.remove(channel);
        _awaitingPin.remove(channel);
        notifyListeners();
      },
      cancelOnError: true,
    );
  }

  /// Pre-auth message handler. Returns true once the client is authenticated.
  bool _handleAuthFrame(WebSocketChannel channel, String text) {
    final cmd = parseCommand(text);
    switch (cmd) {
      case PingCmd():
        channel.sink.add(pongJson());
        return false;
      case RequestPairingCmd():
        // Phone is signaling "I'm about to pair" — surface PIN to the UI.
        _awaitingPin.add(channel);
        notifyListeners();
        return false;
      case AuthCmd(:final pin, :final token):
        if (token != null && token == store.authToken) {
          channel.sink.add(authResponseJson(success: true));
          unawaited(store.markPaired());
          return true;
        }
        if (pin != null && pin.toUpperCase() == store.pin) {
          // PIN match — return the long-lived token so the phone can cache it.
          channel.sink.add(authResponseJson(success: true, token: store.authToken));
          unawaited(store.markPaired());
          return true;
        }
        channel.sink.add(authResponseJson(success: false));
        return false;
      default:
        // Anything else before auth is ignored, mirroring the TV.
        return false;
    }
  }

  void _handleAuthed(WebSocketChannel channel, String raw) {
    final cmd = parseCommand(raw);
    switch (cmd) {
      case PingCmd():
        channel.sink.add(pongJson());
      case AuthCmd():
        // Already authed — re-auth is a no-op success.
        channel.sink.add(authResponseJson(success: true));
      case RequestPairingCmd():
        // Already authed — nothing to do.
        break;
      case ContextQueryCmd():
        channel.sink.add(contextJson(player.state == 'idle' ? 'idle' : 'player'));
      case PlayCmd(:final url, :final title, :final headers, :final subtitles):
        unawaited(player.playUrl(
          url,
          title: title,
          headers: headers,
          subtitles: subtitles,
        ));
      case PlaylistCmd(:final items, :final startIndex):
        unawaited(player.playPlaylist(
          items
              .map((p) => (
                    url: p.url,
                    title: p.title ?? p.url,
                    headers: p.headers,
                    subtitles: p.subtitles,
                  ))
              .toList(),
          startIndex,
        ));
        _broadcastPlaylistStatus();
      case PlaylistJumpCmd(:final index):
        unawaited(player.jumpTo(index));
        _broadcastPlaylistStatus();
      case QueueAddCmd(:final item):
        if (player.queue.isEmpty) {
          unawaited(player.playUrl(
            item.url,
            title: item.title,
            headers: item.headers,
            subtitles: item.subtitles,
          ));
        }
      case ControlCmd(:final command):
        debugPrint('[server] control: $command (queue=${player.queue.length}, state=${player.state})');
        switch (command) {
          case 'play':
            unawaited(player.resume());
          case 'pause':
            unawaited(player.pause());
          case 'stop':
            // Don't tear down the queue on a remote stop — keeps playback
            // controllable from the desktop side if the phone has gone away.
            unawaited(player.pause());
        }
      case UnknownCmd(:final type):
        debugPrint('[server] unknown command: $type');
    }
  }

  void _broadcastStatus() {
    if (_authed.isEmpty) return;
    final msg = statusJson(
      state: player.state,
      positionMs: player.positionMs,
      durationMs: player.durationMs,
      title: player.currentTitle,
    );
    for (final c in _authed) {
      c.sink.add(msg);
    }
  }

  void _broadcastPlaylistStatus() {
    if (_authed.isEmpty) return;
    final items = [
      for (var i = 0; i < player.queue.length; i++)
        (index: i, title: player.queue[i].title),
    ];
    final msg = playlistStatusJson(
      items: items,
      currentIndex: player.currentIndex.clamp(0, player.queue.length),
    );
    for (final c in _authed) {
      c.sink.add(msg);
    }
  }

  void _sendPlaylistStatusTo(WebSocketChannel c) {
    if (player.queue.isEmpty) return;
    final items = [
      for (var i = 0; i < player.queue.length; i++)
        (index: i, title: player.queue[i].title),
    ];
    c.sink.add(playlistStatusJson(
      items: items,
      currentIndex: player.currentIndex.clamp(0, player.queue.length),
    ));
  }

}
