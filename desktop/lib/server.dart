import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:uuid/uuid.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import 'pairing_store.dart';
import 'player_controller.dart';
import 'protocol.dart';

const int kDefaultPort = 8765;

/// Surfaced to the UI so it can show an approval prompt or the player view.
enum PairingPhase {
  /// No client connected.
  idle,

  /// A phone connected and sent `pairing_request` — UI should show Allow/Deny.
  awaitingApproval,

  /// At least one authenticated client is connected.
  authenticated,
}

class PendingPairingRequest {
  final String deviceName;
  final String deviceUUID;
  final WebSocketChannel channel;

  const PendingPairingRequest({
    required this.deviceName,
    required this.deviceUUID,
    required this.channel,
  });
}

class ReceiverServer extends ChangeNotifier {
  ReceiverServer({required this.player, required this.store});

  final PlayerController player;
  final PairingStore store;

  HttpServer? _http;
  Timer? _statusTimer;
  Timer? _approvalTimeout;
  bool _disposed = false;

  // All connected channels — only authed ones receive status broadcasts
  // and can issue commands.
  final Set<WebSocketChannel> _all = {};
  final Set<WebSocketChannel> _authed = {};

  PendingPairingRequest? _pendingPairingRequest;

  // Dedupe rapid-fire duplicate play commands from the phone.
  String? _lastPlayUrl;
  DateTime _lastPlayAt = DateTime.fromMillisecondsSinceEpoch(0);

  PairingPhase get phase {
    if (_authed.isNotEmpty) return PairingPhase.authenticated;
    if (_pendingPairingRequest != null) return PairingPhase.awaitingApproval;
    return PairingPhase.idle;
  }

  PendingPairingRequest? get pendingPairingRequest => _pendingPairingRequest;

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
    _approvalTimeout?.cancel();
    player.removeListener(_broadcastStatus);
    player.indexChanges.removeListener(_broadcastPlaylistStatus);
    for (final c in _all.toList()) {
      await c.sink.close();
    }
    _all.clear();
    _authed.clear();
    _pendingPairingRequest = null;
    await _http?.close(force: true);
  }

  int get connectedClientCount => _all.length;
  int get authedClientCount => _authed.length;

  /// Disconnect all authenticated clients (forces re-auth / pairing).
  Future<void> kickAll() async {
    for (final c in _authed.toList()) {
      await c.sink.close();
    }
  }

  void _onClient(WebSocketChannel channel, String? subprotocol) {
    debugPrint('[server] client connected');
    _all.add(channel);

    channel.stream.listen(
      (raw) {
        if (raw is! String) return;
        final isAuthed = _authed.contains(channel);
        debugPrint('[recv${isAuthed ? '' : ' pre-auth'}] $raw');
        if (isAuthed) {
          _handleAuthed(channel, raw);
        } else {
          final shouldAuth = _handleAuthFrame(channel, raw);
          if (shouldAuth) {
            _authed.add(channel);
            notifyListeners();
            _sendPlaylistStatusTo(channel);
          }
        }
      },
      onDone: () {
        debugPrint('[server] client disconnected');
        _all.remove(channel);
        _authed.remove(channel);
        // Clear pending request if this was the pending channel
        if (_pendingPairingRequest?.channel == channel) {
          _approvalTimeout?.cancel();
          _approvalTimeout = null;
          _pendingPairingRequest = null;
        }
        notifyListeners();
      },
      onError: (e) {
        debugPrint('[server] client error: $e');
        _all.remove(channel);
        _authed.remove(channel);
        if (_pendingPairingRequest?.channel == channel) {
          _approvalTimeout?.cancel();
          _approvalTimeout = null;
          _pendingPairingRequest = null;
        }
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

      case PairingRequestCmd(:final deviceName, :final deviceUUID):
        // Deny immediately if another pairing is already pending.
        if (_pendingPairingRequest != null) {
          channel.sink.add(pairingDeniedJson());
          channel.sink.close();
          return false;
        }
        _pendingPairingRequest = PendingPairingRequest(
          deviceName: deviceName,
          deviceUUID: deviceUUID,
          channel: channel,
        );
        // Auto-deny after 30 seconds.
        _approvalTimeout?.cancel();
        _approvalTimeout = Timer(const Duration(seconds: 30), denyPairing);
        notifyListeners();
        return false;

      case AuthCmd(:final token):
        if (token != null && store.isTokenAuthorized(token)) {
          channel.sink.add(authResponseJson(success: true, token: token));
          unawaited(store.updateLastConnected(token));
          return true;
        }
        channel.sink.add(authResponseJson(success: false));
        return false;

      default:
        return false;
    }
  }

  void approvePairing() {
    final pending = _pendingPairingRequest;
    if (pending == null) return;

    _approvalTimeout?.cancel();
    _approvalTimeout = null;
    _pendingPairingRequest = null;

    final token = const Uuid().v4();
    final device = PairedDeviceRecord(
      deviceUUID: pending.deviceUUID,
      deviceName: pending.deviceName,
      token: token,
      lastConnected: DateTime.now(),
    );
    unawaited(store.addPairedDevice(device));

    pending.channel.sink.add(pairingApprovedJson(token));
    // Immediately treat as authed — phone won't send a separate auth after pairing_approved.
    pending.channel.sink.add(authResponseJson(success: true, token: token));
    _authed.add(pending.channel);
    notifyListeners();
    _sendPlaylistStatusTo(pending.channel);
  }

  void denyPairing() {
    final pending = _pendingPairingRequest;
    if (pending == null) return;

    _approvalTimeout?.cancel();
    _approvalTimeout = null;
    _pendingPairingRequest = null;

    pending.channel.sink.add(pairingDeniedJson());
    unawaited(pending.channel.sink.close());
    notifyListeners();
  }

  void _handleAuthed(WebSocketChannel channel, String raw) {
    final cmd = parseCommand(raw);
    switch (cmd) {
      case PingCmd():
        channel.sink.add(pongJson());
      case AuthCmd():
        // Already authed — re-auth is a no-op success.
        channel.sink.add(authResponseJson(success: true));
      case ContextQueryCmd():
        channel.sink.add(contextJson(player.state == 'idle' ? 'idle' : 'player'));
      case PlayCmd(:final payload):
        final url = payload.url;
        final now = DateTime.now();
        if (url == _lastPlayUrl &&
            now.difference(_lastPlayAt) < const Duration(seconds: 2)) {
          debugPrint('[server] dropping duplicate play for $url');
          break;
        }
        _lastPlayUrl = url;
        _lastPlayAt = now;
        unawaited(player.playUrl(
          url,
          title: payload.titleOrNull,
          headers: payload.headersOrNull,
          subtitles: payload.subtitlesOrNull,
          isRemote: true,
        ));
      case PlaylistCmd(:final items, :final startIndex):
        unawaited(player.playPlaylist(
          items
              .map((p) => (
                    url: p.url,
                    title: p.titleOrNull ?? p.url,
                    headers: p.headersOrNull,
                    subtitles: p.subtitlesOrNull,
                  ))
              .toList(),
          startIndex,
          isRemote: true,
        ));
        _broadcastPlaylistStatus();
      case PlaylistJumpCmd(:final index):
        unawaited(player.jumpTo(index));
        _broadcastPlaylistStatus();
      case QueueAddCmd(:final item):
        if (player.queue.isEmpty) {
          unawaited(player.playUrl(
            item.url,
            title: item.titleOrNull,
            headers: item.headersOrNull,
            subtitles: item.subtitlesOrNull,
            isRemote: true,
          ));
        }
      case ControlCmd(:final command):
        debugPrint('[server] control: $command');
        switch (command) {
          case 'play':
            unawaited(player.resume());
          case 'pause':
            unawaited(player.pause());
          case 'stop':
            unawaited(player.pause());
        }
      case UnknownCmd(:final type):
        debugPrint('[server] unknown command: $type');
      default:
        break;
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
