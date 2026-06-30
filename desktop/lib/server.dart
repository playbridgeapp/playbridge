import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:uuid/uuid.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import 'cert_manager.dart';
import 'logging/log_store.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'protocol.dart';
import 'sas_crypto.dart';
import 'system_volume.dart';

const int kDefaultPort = 8765;

/// Surfaced to the UI so it can show an approval prompt or the player view.
enum PairingPhase {
  /// No client connected.
  idle,

  /// A phone connected and sent `pairing_commit` — SAS handshake in progress.
  awaitingApproval,

  /// SAS code computed — UI should display the 6-digit PIN.
  awaitingCode,

  /// At least one authenticated client is connected.
  authenticated,
}

/// Per-connection SAS handshake state (receiver side).
class _ConnectionHandshake {
  final String deviceName;
  final String deviceUUID;
  final String commit;
  final Uint8List tvEphPriv;
  final Uint8List tvEphPub;
  final Uint8List nonceT;
  Uint8List? senderEphPub;
  Uint8List? nonceS;
  Uint8List? sharedSecret;
  String? sasCode;

  _ConnectionHandshake({
    required this.deviceName,
    required this.deviceUUID,
    required this.commit,
    required this.tvEphPriv,
    required this.tvEphPub,
    required this.nonceT,
  });
}

class PendingPairingRequest {
  final String deviceName;
  final String deviceUUID;
  final String sasCode;
  final WebSocketChannel channel;
  final Completer<bool> approval;

  PendingPairingRequest({
    required this.deviceName,
    required this.deviceUUID,
    required this.sasCode,
    required this.channel,
    Completer<bool>? approval,
  }) : approval = approval ?? Completer<bool>();
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

  /// In-progress SAS handshakes keyed by WebSocket channel.
  final Map<WebSocketChannel, _ConnectionHandshake> _inProgressHandshakes = {};

  /// Rate-limiting: failed pairing attempts by IP.
  final Map<String, int> _failedAttempts = {};

  /// Rate-limiting: lockout expiry (epoch ms) by IP.
  final Map<String, int> _lockoutUntil = {};

  /// Remote IPs of pending ws upgrades, consumed in accept order by [_onClient].
  /// shelf_web_socket's onConnection callback doesn't carry the request, so we
  /// capture the IP in the route wrapper and hand it off here.
  final List<String> _pendingClientIps = [];

  /// Source IP per connected channel, so pairing rate-limiting/lockout keys on a
  /// real address instead of the (per-connection, trivially-rotated) channel identity.
  final Map<WebSocketChannel, String> _channelIps = {};

  // Dedupe rapid-fire duplicate play commands from the phone.
  String? _lastPlayUrl;
  DateTime _lastPlayAt = DateTime.fromMillisecondsSinceEpoch(0);

  PairingPhase get phase {
    // A pending pairing request (its SAS code waiting to be entered) must win over
    // `authenticated`: when one device is already connected and a *second* device
    // starts pairing, the code still has to be shown. Checking `_authed` first
    // would mask it behind the "Connected" view and the user could never pair the
    // second device.
    if (_pendingPairingRequest != null) return PairingPhase.awaitingCode;
    if (_authed.isNotEmpty) return PairingPhase.authenticated;
    if (_inProgressHandshakes.isNotEmpty) return PairingPhase.awaitingApproval;
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
    final handler = _withLogsRoute(webSocketHandler(_onClient));

    // Encrypted wss:// on port — the default, preferred transport.
    var wssUp = false;
    try {
      final cert = await CertManager.loadOrCreate(commonName: store.deviceName);
      _certFingerprint = cert.fingerprint;
      final https = await shelf_io.serve(
        handler,
        InternetAddress.anyIPv4,
        _port,
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

    tlsError = !wssUp ? 'Secure server failed to start' : null;

    // Notify so UI (e.g. the Cast screen address) reflects the bound wss port.
    notifyListeners();
  }

  /// Wraps the WebSocket handler so `GET /logs` (download) and `DELETE /logs`
  /// (clear) are served as plain HTTP, falling through to the ws upgrade for
  /// every other request. Gated: returns 403 when logging is disabled so a device
  /// on the LAN can't pull logs (which may contain stream URLs and headers).
  Handler _withLogsRoute(Handler wsHandler) {
    return (Request request) {
      if (request.url.path == 'logs') {
        if (request.method == 'GET') {
          if (!LogStore.instance.enabled) {
            return Response.forbidden('Logging is disabled on this device.');
          }
          final text = LogStore.instance.combinedText();
          return Response.ok(
            text.isEmpty ? 'No log entries.' : text,
            headers: {
              'content-type': 'text/plain',
              'content-disposition':
                  'attachment; filename="playbridge_desktop_logs.txt"',
            },
          );
        }
        if (request.method == 'DELETE') {
          LogStore.instance.clear();
          return Response.ok('Logs cleared.');
        }
      }
      // Capture the client IP for the upcoming ws upgrade so pairing rate-limiting
      // keys on a real address. Only ws-upgrade requests reach here (the /logs HTTP
      // routes returned above), and upgrades fire onConnection in accept order.
      final ip =
          (request.context['shelf.io.connection_info'] as HttpConnectionInfo?)
                  ?.remoteAddress
                  .address ??
              'unknown';
      _pendingClientIps.add(ip);
      return wsHandler(request);
    };
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
    _inProgressHandshakes.clear();
    _channelIps.clear();
    _pendingClientIps.clear();
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
    // Pair this channel with the IP captured for its upgrade (accept-order FIFO).
    final ip = _pendingClientIps.isNotEmpty
        ? _pendingClientIps.removeAt(0)
        : 'unknown';
    _channelIps[channel] = ip;
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
        _inProgressHandshakes.remove(channel);
        _channelIps.remove(channel);
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
        _inProgressHandshakes.remove(channel);
        _channelIps.remove(channel);
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

      case PairingCommitCmd(
          :final commit,
          :final deviceName,
          :final deviceUUID
        ):
        return _handlePairingCommit(channel, commit, deviceName, deviceUUID);

      case PairingRevealCmd(:final senderEphPub, :final nonceS):
        _handlePairingReveal(channel, senderEphPub, nonceS);
        return false;

      case PairingConfirmationCmd(:final mac):
        _handlePairingConfirmation(channel, mac);
        return false;

      // Legacy pairing_request — treat as unknown (SAS is now required).
      case PairingRequestCmd():
        channel.sink.add(pairingDeniedJson());
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

  /// SAS Step 1: phone sends `pairing_commit`.
  bool _handlePairingCommit(
    WebSocketChannel channel,
    String commit,
    String deviceName,
    String deviceUUID,
  ) {
    // Rate-limiting: check lockout (keyed on the captured remote IP).
    final ip = _ipFor(channel);
    final lockout = _lockoutUntil[ip];
    if (lockout != null && DateTime.now().millisecondsSinceEpoch < lockout) {
      debugPrint('[server] IP $ip is locked out from pairing');
      channel.sink.add(pairingDeniedJson());
      return false;
    }

    // Only one pairing at a time.
    if (_pendingPairingRequest != null || _inProgressHandshakes.isNotEmpty) {
      channel.sink.add(pairingDeniedJson());
      return false;
    }

    // Generate TV ephemeral keypair + nonce.
    final tvKey = SasCrypto.generateX25519KeyPair();
    final nonceT = SasCrypto.generateNonce(16);

    _inProgressHandshakes[channel] = _ConnectionHandshake(
      deviceName: deviceName,
      deviceUUID: deviceUUID,
      commit: commit,
      tvEphPriv: tvKey.privateKey,
      tvEphPub: tvKey.publicKey,
      nonceT: nonceT,
    );

    // Send challenge.
    channel.sink.add(pairingChallengeJson(
      tvEphPub: base64.encode(tvKey.publicKey),
      nonceT: base64.encode(nonceT),
    ));
    notifyListeners();
    return false;
  }

  /// SAS Step 3: phone sends `pairing_reveal`.
  void _handlePairingReveal(
      WebSocketChannel channel, String senderEphPubB64, String nonceSB64) {
    final handshake = _inProgressHandshakes[channel];
    if (handshake == null) {
      channel.sink.add(pairingDeniedJson());
      return;
    }

    final senderEphPub = Uint8List.fromList(base64.decode(senderEphPubB64));
    final nonceS = Uint8List.fromList(base64.decode(nonceSB64));

    // Verify commitment: commit == SHA-256(senderEphPub || nonceS)
    final calculatedCommitBytes =
        SasCrypto.sha256(Uint8List.fromList(senderEphPub + nonceS));
    final calculatedCommit = base64.encode(calculatedCommitBytes);
    if (calculatedCommit != handshake.commit) {
      debugPrint('[server] Commitment mismatch — denying pairing');
      channel.sink.add(pairingDeniedJson());
      _inProgressHandshakes.remove(channel);
      _recordPairingFailure(channel);
      notifyListeners();
      return;
    }

    handshake.senderEphPub = senderEphPub;
    handshake.nonceS = nonceS;

    // Compute ECDH shared secret.
    final sharedSecret =
        SasCrypto.calculateECDH(handshake.tvEphPriv, senderEphPub);
    handshake.sharedSecret = sharedSecret;

    // Compute transcript and SAS.
    final commitBytes = Uint8List.fromList(base64.decode(handshake.commit));
    final transcript = Uint8List.fromList(
      commitBytes +
          handshake.tvEphPub +
          handshake.nonceT +
          senderEphPub +
          nonceS,
    );
    final sas = SasCrypto.generateSAS(sharedSecret, transcript);
    handshake.sasCode = sas;

    // Create pending pairing request with the SAS code for the UI.
    final approval = Completer<bool>();
    _pendingPairingRequest = PendingPairingRequest(
      deviceName: handshake.deviceName,
      deviceUUID: handshake.deviceUUID,
      sasCode: sas,
      channel: channel,
      approval: approval,
    );

    // Auto-deny after 60 seconds.
    _approvalTimeout?.cancel();
    _approvalTimeout = Timer(const Duration(seconds: 60), () {
      if (!approval.isCompleted) approval.complete(false);
    });

    notifyListeners();

    // Wait for the confirmation MAC from the phone.
    approval.future.then((approved) {
      _approvalTimeout?.cancel();
      _approvalTimeout = null;

      if (approved) {
        _failedAttempts.remove(_ipFor(channel));
        final token = const Uuid().v4();
        final device = PairedDeviceRecord(
          deviceUUID: handshake.deviceUUID,
          deviceName: handshake.deviceName,
          token: token,
          lastConnected: DateTime.now(),
        );
        unawaited(store.addPairedDevice(device));

        channel.sink.add(pairingApprovedJson(
          token,
          certFingerprint: _certFingerprint,
          players: _capabilityPlayers,
        ));
        _authed.add(channel);
      } else {
        channel.sink.add(pairingDeniedJson());
        _recordPairingFailure(channel);
      }

      _inProgressHandshakes.remove(channel);
      _pendingPairingRequest = null;
      notifyListeners();
      if (approved) _sendPlaylistStatusTo(channel);
    });
  }

  /// SAS Step 4: phone sends `pairing_confirmation` with MAC.
  void _handlePairingConfirmation(WebSocketChannel channel, String mac) {
    final handshake = _inProgressHandshakes[channel];
    final pending = _pendingPairingRequest;
    if (handshake == null || pending == null || pending.channel != channel) {
      channel.sink.add(pairingDeniedJson());
      return;
    }
    if (pending.approval.isCompleted) return;

    // Derive confirmation key and expected MAC.
    final commitBytes = Uint8List.fromList(base64.decode(handshake.commit));
    final transcript = Uint8List.fromList(
      commitBytes +
          handshake.tvEphPub +
          handshake.nonceT +
          handshake.senderEphPub! +
          handshake.nonceS!,
    );
    final prk = SasCrypto.hkdfExtract(salt: null, ikm: handshake.sharedSecret!);
    final confirmationKey = SasCrypto.hkdfExpand(prk,
        info: Uint8List.fromList('confirmationKey'.codeUnits), length: 32);
    final expectedMacBytes = SasCrypto.hmacSha256(confirmationKey, transcript);
    final expectedMac = base64.encode(expectedMacBytes);

    if (mac == expectedMac) {
      pending.approval.complete(true);
    } else {
      debugPrint('[server] Confirmation MAC mismatch — denying pairing');
      pending.approval.complete(false);
    }
  }

  /// Source IP for a channel, used for pairing rate-limiting. Falls back to the
  /// channel identity only if the IP wasn't captured (degrades to old behavior
  /// rather than crashing).
  String _ipFor(WebSocketChannel c) => _channelIps[c] ?? c.hashCode.toString();

  /// Record a failed pairing attempt for rate-limiting.
  void _recordPairingFailure(WebSocketChannel channel) {
    final ip = _ipFor(channel);
    final count = (_failedAttempts[ip] ?? 0) + 1;
    _failedAttempts[ip] = count;
    if (count >= 3) {
      // Lock out for 60 seconds after 3 failures.
      _lockoutUntil[ip] = DateTime.now().millisecondsSinceEpoch + 60 * 1000;
      _failedAttempts.remove(ip);
      debugPrint('[server] IP $ip locked out after $count failed pairings');
    }
  }

  // No manual "approve" entry point: approval is driven exclusively by the SAS
  // confirmation MAC (see _handleConfirmation), so a UI Allow button can't bypass
  // key confirmation. Removed in Phase 1.

  void denyPairing() {
    final pending = _pendingPairingRequest;
    if (pending == null || pending.approval.isCompleted) return;
    pending.approval.complete(false);
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
      case RemoteCmd(:final key):
        _handleRemoteKey(key);
      case UnknownCmd(:final type):
        debugPrint('[server] unknown command: $type');
      default:
        break;
    }
  }

  /// Apply a phone remote key. Only volume is meaningful for the desktop player;
  /// other (TV-browser) keys are ignored.
  void _handleRemoteKey(String key) {
    switch (key) {
      case 'volume_up':
        unawaited(_adjustVolume(up: true));
      case 'volume_down':
        unawaited(_adjustVolume(up: false));
      default:
        debugPrint('[server] ignoring remote key: $key');
    }
  }

  // Drop overlapping volume events: a swipe fires many in a row and each backend
  // call spawns a process, so we coalesce while one is in flight.
  bool _volumeBusy = false;

  /// Move the **host/OS** volume one step; fall back to the player's own volume
  /// only when no system backend is available (e.g. headless Linux).
  Future<void> _adjustVolume({required bool up}) async {
    if (_volumeBusy) return;
    _volumeBusy = true;
    try {
      final handled = await SystemVolume.step(up: up);
      if (!handled) {
        const step = 0.05;
        final next =
            (player.volume + (up ? step : -step)).clamp(0.0, 1.0).toDouble();
        await player.setVolume(next);
        debugPrint('[server] player volume -> $next (no system backend)');
      } else {
        debugPrint('[server] system volume ${up ? 'up' : 'down'}');
      }
    } finally {
      _volumeBusy = false;
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
