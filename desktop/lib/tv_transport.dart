import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart' as rust;

import 'protocol.dart';
import 'stream_proxy_server.dart';
import 'tv_discovery.dart';
import 'tv_sender_client.dart';

export 'tv_sender_client.dart' show SenderConnectionState, TvCredentials;

/// Abstract transport contract for interacting with a target TV/receiver
/// (PlayBridge WebSockets, DLNA UPnP, Roku ECP, etc.).
abstract class TvTransport {
  /// Target protocol handled by this transport instance.
  TvProtocol get protocol;

  /// Connection state stream and current state.
  Stream<SenderConnectionState> get state;
  SenderConnectionState get currentState;
  bool get isConnected => currentState == SenderConnectionState.connected;

  /// Raw messages / JSON status events emitted by the target receiver.
  Stream<String> get messages;

  /// Credentials emitted when authenticated or paired.
  Stream<TvCredentials> get credentials;

  /// Last capabilities reported by the receiver probe.
  Map<String, dynamic> get capabilities => const {};

  /// Whether this protocol uses SAS pairing (6-digit PIN handshake).
  bool get supportsPairing => false;
  Stream<String> get sasCode => const Stream.empty();
  int get sasAttemptsLeft => 0;
  bool get lastSasWrong => false;
  bool submitSasCode(String code) => false;

  /// Establish connection or session with the specified [tv].
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  });

  /// Disconnect or terminate session with the active receiver.
  Future<void> disconnect();

  /// Send a single video payload.
  Future<bool> castVideo(PlayPayload video);

  /// Send a playlist payload.
  Future<bool> castPlaylist(PlaylistPayload playlist);

  /// Send a control command string (e.g. 'play', 'pause', 'toggle', 'seek_forward', 'seek_back', 'seek_to').
  Future<bool> sendControl(String command);

  /// Request context query update from the receiver.
  Future<bool> sendContextQuery() async => false;

  /// Jump to specific playlist index.
  Future<bool> playlistJump(int index);

  /// Queue an item.
  Future<bool> queueAdd(PlayPayload item);

  /// Dispose transport resources and subscriptions.
  Future<void> dispose();
}

/// Transport implementation for PlayBridge WebSocket protocol (`wss://`).
class PlayBridgeTransport extends TvTransport {
  final TvSenderClient _client;

  PlayBridgeTransport({TvSenderClient? client})
      : _client = client ?? TvSenderClient();

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Stream<SenderConnectionState> get state => _client.state;

  @override
  SenderConnectionState get currentState => _client.currentState;

  @override
  Stream<String> get messages => _client.messages;

  @override
  Stream<TvCredentials> get credentials => _client.credentials;

  @override
  bool get supportsPairing => true;

  @override
  Stream<String> get sasCode => _client.sasCode;

  @override
  int get sasAttemptsLeft => _client.sasAttemptsLeft;

  @override
  bool get lastSasWrong => _client.lastSasWrong;

  @override
  bool submitSasCode(String code) => _client.submitSasCode(code);

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    if (tv.port == null) return;
    await _client.connect(
      host: tv.host,
      port: tv.port!,
      wssPort: tv.wssPort,
      deviceName: deviceName,
      deviceUUID: deviceUUID,
      token: token,
      expectedPin: expectedPin,
    );
  }

  @override
  Future<void> disconnect() => _client.disconnect();

  @override
  Future<bool> castVideo(PlayPayload video) async =>
      _client.send(senderSingleVideoCommandJson(video));

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async =>
      _client.send(senderPlaylistCommandJson(playlist));

  @override
  Future<bool> sendControl(String command) async =>
      _client.send(senderControlCommandJson(command));

  @override
  Future<bool> sendContextQuery() async =>
      _client.send(senderContextQueryJson());

  @override
  Future<bool> playlistJump(int index) async =>
      _client.send(senderPlaylistJumpJson(index));

  @override
  Future<bool> queueAdd(PlayPayload item) async =>
      _client.send(senderQueueAddJson(item));

  @override
  Future<void> dispose() => _client.dispose();
}

/// Rust-backed transport shared by DLNA, Roku ECP, and Google Cast.
class RustCastTransport extends TvTransport {
  RustCastTransport(this.protocol, {rust.CastCoreLibrary? core}) : _core = core;

  @override
  final TvProtocol protocol;

  rust.CastCoreLibrary? _core;
  rust.CastSession? _session;
  rust.SessionCapabilities? _capabilities;
  StreamSubscription<rust.CastSessionEvent>? _eventSub;
  Timer? _pollTimer;
  bool _polling = false;
  String? _currentTitle;

  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();
  SenderConnectionState _current = SenderConnectionState.disconnected;

  @override
  Stream<SenderConnectionState> get state => _state.stream;

  @override
  SenderConnectionState get currentState => _current;

  @override
  Stream<String> get messages => _messages.stream;

  @override
  Stream<TvCredentials> get credentials => _credentials.stream;

  @override
  bool get supportsPairing => false;

  @override
  Map<String, dynamic> get capabilities => {
        if (_capabilities case final value?) ...{
          'load': value.load,
          'playbackControl': value.playbackControl,
          'seek': value.seek,
          'status': value.status,
          if (value.receiverAppAvailable != null)
            'receiverAppAvailable': value.receiverAppAvailable,
        },
      };

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    await _closeSession(sendDisconnected: false);
    _setState(SenderConnectionState.connecting);
    try {
      final core = _core ??= rust.CastCoreLibrary.open();
      final session = await core.connect(
        rust.ReceiverEndpoint(
          protocol: _rustProtocol(protocol),
          addresses: tv.allAddresses,
          port: tv.port,
          location: tv.location,
        ),
      );
      _session = session;
      final connected = await session.connected;
      _capabilities = connected.capabilities;
      _eventSub = session.events.listen(
        _onSessionEvent,
        onError: (Object error, StackTrace stackTrace) {
          _emitError('session', error);
          _setState(SenderConnectionState.error);
        },
        onDone: () {
          if (identical(_session, session)) {
            _session = null;
            _capabilities = null;
            _stopPolling();
            _setState(SenderConnectionState.disconnected);
          }
        },
      );
      _setState(SenderConnectionState.connected);
    } on Object catch (error) {
      _emitError('connect', error);
      await _closeSession(sendDisconnected: false);
      _setState(SenderConnectionState.error);
    }
  }

  @override
  Future<void> disconnect() => _closeSession(sendDisconnected: true);

  @override
  Future<bool> castVideo(PlayPayload video) async {
    final session = _session;
    if (!isConnected || session == null || video.url.isEmpty) return false;
    if (_capabilities?.load == false) {
      _emitError(
        'load',
        'This receiver does not have a compatible media receiver app',
      );
      return false;
    }
    try {
      final title =
          video.hasTitle() && video.title.isNotEmpty ? video.title : 'Media';
      await session.load(rust.MediaRequest(url: video.url, title: title));
      _currentTitle = title;
      _startPolling();
      await _pollStatus();
      return true;
    } on Object catch (error) {
      _emitError('load', error);
      return false;
    }
  }

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async {
    if (playlist.items.isEmpty) return false;
    return castVideo(playlist.items.first);
  }

  @override
  Future<bool> sendControl(String command) async {
    final session = _session;
    if (!isConnected || session == null) return false;
    try {
      if (command.startsWith('seek_to:')) {
        if (_capabilities?.seek == false) return false;
        final milliseconds = int.tryParse(command.substring('seek_to:'.length));
        if (milliseconds == null) return false;
        await session
            .seek(Duration(milliseconds: milliseconds.clamp(0, 1 << 62)));
      } else {
        switch (command) {
          case 'play':
          case 'resume':
            await session.play();
          case 'pause':
            await session.pause();
          case 'toggle':
            final status = await session.status();
            if (status.state == rust.PlaybackState.paused) {
              await session.play();
            } else {
              await session.pause();
            }
          case 'stop':
            await session.stop();
            _stopPolling();
            _emitStatus(const rust.PlaybackStatus(
              state: rust.PlaybackState.stopped,
              position: Duration.zero,
              duration: Duration.zero,
            ));
          case 'seek_forward':
            await session.relativeSeek(forward: true);
          case 'seek_back':
            await session.relativeSeek(forward: false);
          default:
            return false;
        }
      }
      if (command != 'stop') {
        _startPolling();
        await _pollStatus();
      }
      return true;
    } on Object catch (error) {
      _emitError(command, error);
      return false;
    }
  }

  @override
  Future<bool> playlistJump(int index) async => false;

  @override
  Future<bool> queueAdd(PlayPayload item) async => false;

  void _onSessionEvent(rust.CastSessionEvent event) {
    switch (event) {
      case rust.CastSessionStatus(:final status):
        _emitStatus(status);
        if (status.state == rust.PlaybackState.stopped ||
            status.state == rust.PlaybackState.finished) {
          _stopPolling();
        }
      case rust.CastSessionError(:final operation, :final message):
        _emitError(operation ?? 'session', message);
      case rust.CastSessionFinished():
        _session = null;
        _capabilities = null;
        _stopPolling();
        _setState(SenderConnectionState.disconnected);
      case rust.CastSessionConnected() || rust.CastSessionOperation():
        break;
    }
  }

  void _startPolling() {
    if (_pollTimer != null || _capabilities?.status == false) return;
    _pollTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => unawaited(_pollStatus()),
    );
  }

  void _stopPolling() {
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  Future<void> _pollStatus() async {
    final session = _session;
    if (_polling || session == null || !isConnected) return;
    _polling = true;
    try {
      await session.status();
    } on Object catch (error) {
      debugPrint('[tv-transport] ${protocol.label} status failed: $error');
    } finally {
      _polling = false;
    }
  }

  void _emitStatus(rust.PlaybackStatus status) {
    if (_messages.isClosed) return;
    _messages.add(jsonEncode({
      'type': 'status',
      'state': status.state.name,
      'position': status.position.inMilliseconds,
      'duration': status.duration.inMilliseconds,
      if (_currentTitle != null) 'title': _currentTitle,
    }));
  }

  void _emitError(String operation, Object error) {
    debugPrint('[tv-transport] ${protocol.label} $operation failed: $error');
    if (!_messages.isClosed) {
      _messages.add(jsonEncode({
        'type': 'error',
        'operation': operation,
        'message': error.toString(),
      }));
    }
  }

  void _setState(SenderConnectionState value) {
    _current = value;
    if (!_state.isClosed) _state.add(value);
  }

  Future<void> _closeSession({required bool sendDisconnected}) async {
    _stopPolling();
    await _eventSub?.cancel();
    _eventSub = null;
    final session = _session;
    _session = null;
    _capabilities = null;
    if (session != null && !session.isDisposed) {
      try {
        await session.disconnect();
      } on Object {
        // The receiver may already have closed the native worker.
      }
      session.dispose();
    }
    if (sendDisconnected) _setState(SenderConnectionState.disconnected);
  }

  @override
  Future<void> dispose() async {
    await _closeSession(sendDisconnected: false);
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}

class DlnaTransport extends RustCastTransport {
  DlnaTransport({rust.CastCoreLibrary? core})
      : super(TvProtocol.dlna, core: core);
}

class RokuTransport extends RustCastTransport {
  RokuTransport({rust.CastCoreLibrary? core})
      : super(TvProtocol.roku, core: core);
}

class GoogleCastTransport extends RustCastTransport {
  GoogleCastTransport({rust.CastCoreLibrary? core})
      : super(TvProtocol.googleCast, core: core);
}

/// Transport for a browser page connected to Desktop's on-demand Rust host.
class BrowserTransport extends TvTransport {
  BrowserTransport({rust.SenderServices? services})
      : _services = services ?? StreamProxyServer.instance.services;

  final rust.SenderServices _services;
  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();
  StreamSubscription<Map<String, Object?>>? _events;
  SenderConnectionState _current = SenderConnectionState.disconnected;
  String? _sessionId;
  int _positionMs = 0;
  String _playbackState = 'idle';

  @override
  TvProtocol get protocol => TvProtocol.webBrowser;

  @override
  Stream<SenderConnectionState> get state => _state.stream;

  @override
  SenderConnectionState get currentState => _current;

  @override
  Stream<String> get messages => _messages.stream;

  @override
  Stream<TvCredentials> get credentials => _credentials.stream;

  @override
  Map<String, dynamic> get capabilities => const {
        'load': true,
        'playbackControl': true,
        'seek': true,
        'status': true,
      };

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    // Already bound to this browser session — do not tear it down.
    // Re-entrancy used to call disconnectBrowser(current) and kill a live tab
    // (e.g. when capabilities/status re-triggered activation after connect).
    if (_sessionId == tv.uuid && _current == SenderConnectionState.connected) {
      return;
    }

    final previous = _sessionId;
    _sessionId = null;
    await _events?.cancel();
    _events = null;

    // Only close a *different* host session. Never disconnect the session we
    // are about to adopt (refresh/reconnect hands us a new sessionId).
    if (previous != null && previous != tv.uuid) {
      try {
        await _services.disconnectBrowser(previous);
      } on Object {
        // Previous tab may already have closed during refresh.
      }
    }

    _sessionId = tv.uuid;
    _events = _services.events.listen(_onEvent);
    _setState(SenderConnectionState.connected);
  }

  @override
  Future<void> disconnect() async {
    final sessionId = _sessionId;
    _sessionId = null;
    await _events?.cancel();
    _events = null;
    if (sessionId != null) {
      try {
        await _services.disconnectBrowser(sessionId);
      } on Object {
        // The browser may already have closed its tab or navigated away.
      }
    }
    _setState(SenderConnectionState.disconnected);
  }

  @override
  Future<bool> castVideo(PlayPayload video) async {
    return castBrowserMedia(
      url: video.url,
      title: video.hasTitle() ? video.title : null,
      contentType: _contentTypeForUrl(video.url),
      posterUrl: video.posterUrlOrNull,
      subtitleUrl: video.subtitlesOrNull?.first,
      startPosition: video.startPositionMsOrNull == null
          ? null
          : Duration(milliseconds: video.startPositionMsOrNull!),
    );
  }

  Future<bool> castBrowserMedia({
    required String url,
    String? title,
    String? contentType,
    String? posterUrl,
    String? subtitleUrl,
    Duration? startPosition,
  }) async {
    final sessionId = _sessionId;
    if (sessionId == null || url.isEmpty) return false;
    try {
      await _services.loadBrowser(
        sessionId: sessionId,
        url: url,
        title: title,
        contentType: contentType == null || contentType.isEmpty
            ? _contentTypeForUrl(url)
            : contentType,
        posterUrl: posterUrl,
        subtitleUrl: subtitleUrl,
        startPosition: startPosition,
      );
      return true;
    } on Object catch (error) {
      _emitError('load', error);
      return false;
    }
  }

  String? _contentTypeForUrl(String url) {
    final path = Uri.tryParse(url)?.path.toLowerCase() ?? url.toLowerCase();
    if (path.endsWith('.mpd')) return 'application/dash+xml';
    if (path.endsWith('.m3u8')) return 'application/vnd.apple.mpegurl';
    return null;
  }

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) => playlist.items.isEmpty
      ? Future.value(false)
      : castVideo(playlist.items.first);

  @override
  Future<bool> sendControl(String command) async {
    final sessionId = _sessionId;
    if (sessionId == null) return false;
    String action;
    double? value;
    if (command.startsWith('seek_to:')) {
      action = 'seek';
      final position = int.tryParse(command.substring('seek_to:'.length));
      if (position == null) return false;
      value = position.toDouble();
    } else {
      switch (command) {
        case 'play':
        case 'resume':
          action = 'play';
        case 'pause':
          action = 'pause';
        case 'toggle':
          action = _playbackState == 'paused' ? 'play' : 'pause';
        case 'stop':
          action = 'stop';
        case 'seek_forward':
          action = 'seek';
          value = (_positionMs + 10000).toDouble();
        case 'seek_back':
          action = 'seek';
          value = (_positionMs - 10000).clamp(0, 1 << 62).toDouble();
        default:
          return false;
      }
    }
    try {
      await _services.controlBrowser(
        sessionId: sessionId,
        action: action,
        value: value,
      );
      return true;
    } on Object catch (error) {
      _emitError(command, error);
      return false;
    }
  }

  @override
  Future<bool> playlistJump(int index) async => false;

  @override
  Future<bool> queueAdd(PlayPayload item) async => false;

  void _onEvent(Map<String, Object?> event) {
    final session = event['session'];
    if (session is Map && session['sessionId']?.toString() == _sessionId) {
      if (event['event'] == 'status' || event['event'] == 'ended') {
        final status = session['status'];
        if (status is Map) {
          _playbackState = status['state']?.toString() ?? _playbackState;
          _positionMs = (status['positionMs'] as num?)?.toInt() ?? _positionMs;
          _messages.add(jsonEncode({
            'type': 'status',
            'state': _playbackState,
            'position': _positionMs,
            'duration': (status['durationMs'] as num?)?.toInt() ?? 0,
            if (status['title'] != null) 'title': status['title'],
          }));
        }
      } else if (event['event'] == 'error') {
        _emitError('browser', event['message'] ?? 'Browser playback failed');
      }
    } else if (event['event'] == 'disconnected' &&
        event['session_id']?.toString() == _sessionId) {
      _sessionId = null;
      _setState(SenderConnectionState.disconnected);
    }
  }

  void _emitError(String operation, Object error) {
    if (!_messages.isClosed) {
      _messages.add(jsonEncode({
        'type': 'error',
        'operation': operation,
        'message': error.toString(),
      }));
    }
  }

  void _setState(SenderConnectionState value) {
    _current = value;
    if (!_state.isClosed) _state.add(value);
  }

  @override
  Future<void> dispose() async {
    await disconnect();
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}

rust.ReceiverProtocol _rustProtocol(TvProtocol protocol) => switch (protocol) {
      TvProtocol.dlna => rust.ReceiverProtocol.dlna,
      TvProtocol.roku => rust.ReceiverProtocol.roku,
      TvProtocol.googleCast => rust.ReceiverProtocol.googleCast,
      TvProtocol.playBridge => throw ArgumentError(
          'PlayBridge sessions use the paired WebSocket transport',
        ),
      TvProtocol.webBrowser => throw ArgumentError(
          'Web Browser sessions use the sender-hosted receiver transport',
        ),
    };

/// Factory to instantiate appropriate transport for a receiver protocol.
abstract class TvTransportFactory {
  static TvTransport create(TvProtocol protocol) {
    switch (protocol) {
      case TvProtocol.playBridge:
        return PlayBridgeTransport();
      case TvProtocol.dlna:
        return DlnaTransport();
      case TvProtocol.roku:
        return RokuTransport();
      case TvProtocol.googleCast:
        return GoogleCastTransport();
      case TvProtocol.webBrowser:
        return BrowserTransport();
    }
  }
}
