import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:uuid/uuid.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import 'cert_manager.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'player_engine.dart';
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

  final List<HttpServer> _servers = [];
  Timer? _statusTimer;
  Timer? _approvalTimeout;
  bool _disposed = false;

  /// Players this receiver advertises to the phone at auth. Desktop only ships the
  /// embedded MPV engine, so the phone's player picker shows just "TV Default" + MPV.
  /// (No browsers — desktop has no web view.)
  static const List<String> _capabilityPlayers = ['internal_mpv'];

  /// SPKI pin of our TLS cert, sent to senders at pairing. Null until the
  /// wss:// listener starts.
  String? _certFingerprint;

  /// Set when the receiver is unreachable: wss:// failed to start and ws:// is
  /// not enabled. Surfaced in the UI so the user knows to enable "Allow insecure".
  String? tlsError;

  /// Bound port of the wss:// listener, or null if TLS failed to start.
  /// Advertised over mDNS so senders know where to connect.
  int? _wssPort;
  int? get wssPort => _wssPort;

  int _port = kDefaultPort;

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
    _port = port;
    await _bindListeners();

    player.addListener(_broadcastStatus);
    player.indexChanges.addListener(_broadcastPlaylistStatus);
    player.queueChanges.addListener(_broadcastPlaylistStatus);
    _statusTimer = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => _broadcastStatus(),
    );
  }

  /// (Re)binds the wss:// (always) and ws:// (opt-in) listeners. Re-runnable
  /// after [reloadListeners] closes the previous sockets.
  Future<void> _bindListeners() async {
    final handler = webSocketHandler(_onClient);

    // Encrypted wss:// on port+1 — the default, preferred transport.
    var wssUp = false;
    try {
      final cert = await CertManager.loadOrCreate(commonName: store.deviceName);
      _certFingerprint = cert.fingerprint;
      final https = await shelf_io.serve(
        handler,
        InternetAddress.anyIPv4,
        _port + 1,
        securityContext: cert.securityContext,
      );
      _servers.add(https);
      _wssPort = https.port;
      wssUp = true;
      debugPrint(
        '[server] wss listening on ${https.address.address}:${https.port} '
        '(pin ${cert.fingerprint})',
      );
    } catch (e) {
      debugPrint('[server] TLS listener failed to start: $e');
    }

    // Plaintext ws:// only when the user explicitly opts into insecure connections.
    // We do NOT auto-fall-back to ws on wss failure — that would be a silent
    // plaintext downgrade. Instead we fail closed and surface a hint.
    if (store.allowInsecure) {
      final http =
          await shelf_io.serve(handler, InternetAddress.anyIPv4, _port);
      _servers.add(http);
      debugPrint(
          '[server] ws  listening on ${http.address.address}:${http.port} (insecure allowed)');
    }

    tlsError = (!wssUp && !store.allowInsecure)
        ? 'Secure server failed to start — enable "Allow insecure" in Settings to connect.'
        : null;

    // Notify so UI (e.g. the Cast screen address) reflects the bound wss port.
    notifyListeners();
  }

  /// Rebinds listeners after the insecure-connection setting changes.
  Future<void> reloadListeners() async {
    for (final s in _servers) {
      await s.close(force: true);
    }
    _servers.clear();
    _wssPort = null;
    await _bindListeners();
    notifyListeners();
  }

  Future<void> stop() async {
    if (_disposed) return;
    _disposed = true;
    _statusTimer?.cancel();
    _approvalTimeout?.cancel();
    player.removeListener(_broadcastStatus);
    player.indexChanges.removeListener(_broadcastPlaylistStatus);
    player.queueChanges.removeListener(_broadcastPlaylistStatus);
    for (final c in _all.toList()) {
      await c.sink.close();
    }
    _all.clear();
    _authed.clear();
    _pendingPairingRequest = null;
    for (final s in _servers) {
      await s.close(force: true);
    }
    _servers.clear();
    _wssPort = null;
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
          channel.sink.add(authResponseJson(
            success: true,
            token: token,
            certFingerprint: _certFingerprint,
            players: _capabilityPlayers,
          ));
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

    pending.channel.sink.add(
      pairingApprovedJson(
        token,
        certFingerprint: _certFingerprint,
        players: _capabilityPlayers,
      ),
    );
    // Immediately treat as authed — phone won't send a separate auth after pairing_approved.
    pending.channel.sink.add(authResponseJson(
      success: true,
      token: token,
      certFingerprint: _certFingerprint,
      players: _capabilityPlayers,
    ));
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
        channel.sink.add(authResponseJson(
          success: true,
          certFingerprint: _certFingerprint,
          players: _capabilityPlayers,
        ));
      case ContextQueryCmd():
        channel.sink
            .add(contextJson(player.state == 'idle' ? 'idle' : 'player'));
      case PlaylistCmd(:final items, :final startIndex):
        // A single video arrives as a one-item playlist (the `play` command was removed).
        // Keep the old single-video duplicate-cast guard for that case.
        final startUrl = (startIndex >= 0 && startIndex < items.length)
            ? items[startIndex].url
            : (items.isNotEmpty ? items.first.url : null);
        final now = DateTime.now();
        if (items.length == 1 &&
            startUrl == _lastPlayUrl &&
            now.difference(_lastPlayAt) < const Duration(seconds: 2)) {
          debugPrint('[server] dropping duplicate play for $startUrl');
          break;
        }
        _lastPlayUrl = startUrl;
        _lastPlayAt = now;
        unawaited(player.playPlaylist(
          items.map(_toQueueItem).toList(),
          startIndex,
          isRemote: true,
        ));
        _broadcastPlaylistStatus();
      case PlaylistJumpCmd(:final index):
        unawaited(player.jumpTo(index));
        _broadcastPlaylistStatus();
      case QueueAddCmd(:final item):
        // Appends to the live queue; if idle, starts playback.
        // playlist_status broadcast happens via the queueChanges listener.
        unawaited(player.queueAdd(_toQueueItem(item), isRemote: true));
      case ControlCmd(:final command):
        debugPrint('[server] control: $command');
        // Parameterized commands (phone seekbar + track pickers).
        if (command.startsWith('seek_to:')) {
          final ms = int.tryParse(command.substring('seek_to:'.length));
          if (ms != null) {
            final dur = player.durationMs;
            var target = ms < 0 ? 0 : ms;
            if (dur > 0 && target > dur) target = dur;
            unawaited(player.seek(Duration(milliseconds: target)));
          }
          break;
        }
        if (command.startsWith('audio_track:')) {
          unawaited(player
              .selectAudioTrackById(command.substring('audio_track:'.length)));
          break;
        }
        if (command.startsWith('sub_track:')) {
          unawaited(player
              .selectSubtitleTrackById(command.substring('sub_track:'.length)));
          break;
        }
        switch (command) {
          case 'play':
            unawaited(player.resume());
          case 'pause':
            unawaited(player.pause());
          case 'toggle':
            unawaited(
                player.state == 'playing' ? player.pause() : player.resume());
          case 'stop':
            // Real stop: clear the queue and report idle so the phone's
            // remote leaves player mode (Android finishes the activity here).
            unawaited(player.stop());
            for (final c in _authed) {
              c.sink.add(contextJson('idle'));
            }
          case 'seek_back':
            final pos = player.positionMs - 10000;
            unawaited(player.seek(Duration(milliseconds: pos < 0 ? 0 : pos)));
          case 'seek_forward':
            final dur = player.durationMs;
            var target = player.positionMs + 10000;
            if (dur > 0 && target > dur) target = dur;
            unawaited(player.seek(Duration(milliseconds: target)));
        }
      case UnknownCmd(:final type):
        debugPrint('[server] unknown command: $type');
      default:
        break;
    }
  }

  /// Map an incoming [PlayPayload] onto a [QueueItem], carrying the resume
  /// point and the metadata echoed back in `playlist_status`.
  QueueItem _toQueueItem(PlayPayload p) => QueueItem(
        url: p.url,
        title: p.titleOrNull ?? p.url,
        headers: p.headersOrNull,
        subtitles: p.subtitlesOrNull,
        startPositionMs: p.startPositionMsOrNull,
        bingeGroup: p.bingeGroupOrNull,
        season: p.seasonOrNull,
        episode: p.episodeOrNull,
        imdbId: p.imdbIdOrNull,
        backdropUrl: p.backdropUrlOrNull,
        posterUrl: p.posterUrlOrNull,
        logoUrl: p.logoUrlOrNull,
        overview: p.overviewOrNull,
        year: p.yearOrNull,
        rating: p.ratingOrNull,
        runtime: p.runtimeOrNull,
        episodeTitle: p.episodeTitleOrNull,
      );

  List<PlaylistStatusItem> _playlistStatusItems() => [
        for (var i = 0; i < player.queue.length; i++)
          (
            index: i,
            title: player.queue[i].title,
            season: player.queue[i].season,
            episode: player.queue[i].episode,
            imdbId: player.queue[i].imdbId,
            bingeGroup: player.queue[i].bingeGroup,
          ),
      ];

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
    _broadcastTracksIfChanged();
  }

  /// Last `tracks` JSON sent, to avoid re-sending an unchanged list on every
  /// status tick.
  String? _lastTracksJson;

  String _currentTracksJson() => tracksJson(
        audio: [
          for (final t in player.audioTrackInfos)
            (id: t.id, name: t.name, selected: t.selected),
        ],
        subtitle: [
          for (final t in player.subtitleTrackInfos)
            (id: t.id, name: t.name, selected: t.selected),
        ],
      );

  void _broadcastTracksIfChanged() {
    if (_authed.isEmpty) return;
    final msg = _currentTracksJson();
    if (msg == _lastTracksJson) return;
    _lastTracksJson = msg;
    for (final c in _authed) {
      c.sink.add(msg);
    }
  }

  void _broadcastPlaylistStatus() {
    if (_authed.isEmpty) return;
    final msg = playlistStatusJson(
      items: _playlistStatusItems(),
      currentIndex: player.currentIndex.clamp(0, player.queue.length),
    );
    for (final c in _authed) {
      c.sink.add(msg);
    }
  }

  void _sendPlaylistStatusTo(WebSocketChannel c) {
    if (player.queue.isEmpty) return;
    c.sink.add(playlistStatusJson(
      items: _playlistStatusItems(),
      currentIndex: player.currentIndex.clamp(0, player.queue.length),
    ));
    // Sync the track pickers too for a client that connected mid-playback.
    c.sink.add(_currentTracksJson());
  }
}
