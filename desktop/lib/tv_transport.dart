import 'dart:async';
import 'dart:convert';

import 'dlna_client.dart';
import 'protocol.dart';
import 'roku_client.dart';
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
  bool castVideo(PlayPayload video);

  /// Send a playlist payload.
  bool castPlaylist(PlaylistPayload playlist);

  /// Send a control command string (e.g. 'play', 'pause', 'toggle', 'seek_forward', 'seek_back', 'seek_to').
  bool sendControl(String command);

  /// Request context query update from the receiver.
  bool sendContextQuery() => false;

  /// Jump to specific playlist index.
  bool playlistJump(int index);

  /// Queue an item.
  bool queueAdd(PlayPayload item);

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
  bool castVideo(PlayPayload video) =>
      _client.send(senderSingleVideoCommandJson(video));

  @override
  bool castPlaylist(PlaylistPayload playlist) =>
      _client.send(senderPlaylistCommandJson(playlist));

  @override
  bool sendControl(String command) =>
      _client.send(senderControlCommandJson(command));

  @override
  bool sendContextQuery() => _client.send(senderContextQueryJson());

  @override
  bool playlistJump(int index) => _client.send(senderPlaylistJumpJson(index));

  @override
  bool queueAdd(PlayPayload item) => _client.send(senderQueueAddJson(item));

  @override
  Future<void> dispose() => _client.dispose();
}

/// Transport implementation for DLNA (UPnP AVTransport & RenderingControl).
class DlnaTransport extends TvTransport {
  DlnaTransport({DlnaClient? client}) : _client = client ?? DlnaClient();

  final DlnaClient _client;
  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();
  SenderConnectionState _current = SenderConnectionState.disconnected;
  Timer? _pollTimer;

  String? _currentTitle;
  String _lastPlayState = 'stopped';

  @override
  TvProtocol get protocol => TvProtocol.dlna;

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
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    _current = SenderConnectionState.connecting;
    _state.add(_current);

    final location = tv.location;
    if (location == null || location.isEmpty) {
      _current = SenderConnectionState.error;
      _state.add(_current);
      return;
    }

    final ok = await _client.loadDescription(location);
    if (ok) {
      _current = SenderConnectionState.connected;
      _state.add(_current);
    } else {
      _current = SenderConnectionState.error;
      _state.add(_current);
    }
  }

  @override
  Future<void> disconnect() async {
    _stopPollTimer();
    _current = SenderConnectionState.disconnected;
    _state.add(_current);
  }

  @override
  bool castVideo(PlayPayload video) {
    if (!isConnected) return false;
    final url = video.url;
    if (url.isEmpty) return false;
    final title = video.hasTitle() ? video.title : 'Media';
    _currentTitle = title;
    _lastPlayState = 'playing';

    unawaited(_startPlayback(url, title));
    return true;
  }

  Future<void> _startPlayback(String url, String title) async {
    final okSet = await _client.setAvTransportUri(url, title: title);
    if (!okSet) return;
    final okPlay = await _client.play();
    if (okPlay) {
      _startPollTimer();
    }
  }

  @override
  bool castPlaylist(PlaylistPayload playlist) {
    if (playlist.items.isEmpty) return false;
    return castVideo(playlist.items.first);
  }

  @override
  bool sendControl(String command) {
    if (!isConnected) return false;

    if (command.startsWith('seek_to:')) {
      final ms = int.tryParse(command.substring('seek_to:'.length));
      if (ms != null) {
        unawaited(_client.seek(ms));
        return true;
      }
      return false;
    }

    switch (command) {
      case 'play':
      case 'resume':
        _lastPlayState = 'playing';
        unawaited(_client.play());
        _startPollTimer();
        return true;
      case 'pause':
        _lastPlayState = 'paused';
        unawaited(_client.pause());
        return true;
      case 'toggle':
        if (_lastPlayState == 'paused') {
          _lastPlayState = 'playing';
          unawaited(_client.play());
          _startPollTimer();
        } else {
          _lastPlayState = 'paused';
          unawaited(_client.pause());
        }
        return true;
      case 'stop':
        _stopPollTimer();
        _lastPlayState = 'stopped';
        unawaited(_client.stop());
        _emitStatus('stopped', 0, 0);
        return true;
      case 'seek_forward':
        unawaited(_seekRelative(10000));
        return true;
      case 'seek_back':
        unawaited(_seekRelative(-10000));
        return true;
      default:
        return false;
    }
  }

  @override
  bool playlistJump(int index) => false;

  @override
  bool queueAdd(PlayPayload item) => false;

  Future<void> _seekRelative(int deltaMs) async {
    final pos = await _client.getPositionInfo();
    if (pos == null) return;
    final target = (pos.positionMs + deltaMs)
        .clamp(0, pos.durationMs > 0 ? pos.durationMs : 24 * 3600 * 1000);
    await _client.seek(target);
  }

  void _startPollTimer() {
    _stopPollTimer();
    _pollTimer = Timer.periodic(
        const Duration(seconds: 1), (_) => unawaited(_pollStatus()));
  }

  void _stopPollTimer() {
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  Future<void> _pollStatus() async {
    if (!isConnected) return;
    final stateStr = await _client.getTransportState();
    final posInfo = await _client.getPositionInfo();

    var playState = 'playing';
    if (stateStr != null) {
      final upper = stateStr.toUpperCase();
      if (upper.contains('PAUSED')) {
        playState = 'paused';
      } else if (upper.contains('STOPPED') || upper.contains('NO_MEDIA')) {
        playState = 'stopped';
        _stopPollTimer();
      } else if (upper.contains('TRANSITIONING')) {
        playState = 'buffering';
      }
    }

    _lastPlayState = playState;
    final posMs = posInfo?.positionMs ?? 0;
    final durMs = posInfo?.durationMs ?? 0;

    _emitStatus(playState, posMs, durMs);
  }

  void _emitStatus(String state, int posMs, int durMs) {
    if (_messages.isClosed) return;
    final json = jsonEncode({
      'type': 'status',
      'state': state,
      'position': posMs,
      'duration': durMs,
      if (_currentTitle != null) 'title': _currentTitle,
    });
    _messages.add(json);
  }

  @override
  Future<void> dispose() async {
    _stopPollTimer();
    _client.close();
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}

/// Transport implementation for Roku ECP (External Control Protocol).
class RokuTransport extends TvTransport {
  RokuTransport({RokuClient? client}) : _client = client ?? RokuClient();

  final RokuClient _client;
  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();
  SenderConnectionState _current = SenderConnectionState.disconnected;
  Timer? _pollTimer;
  String? _currentTitle;
  String _lastPlayState = 'stopped';

  @override
  TvProtocol get protocol => TvProtocol.roku;

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
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    _current = SenderConnectionState.connecting;
    _state.add(_current);

    final port = tv.port ?? 8060;
    _client.init(tv.host, port: port);

    final ok = await _client.queryDeviceInfo();
    if (ok || tv.host.isNotEmpty) {
      _current = SenderConnectionState.connected;
      _state.add(_current);
    } else {
      _current = SenderConnectionState.error;
      _state.add(_current);
    }
  }

  @override
  Future<void> disconnect() async {
    _stopPollTimer();
    _current = SenderConnectionState.disconnected;
    _state.add(_current);
  }

  @override
  bool castVideo(PlayPayload video) {
    if (!isConnected) return false;
    final url = video.url;
    if (url.isEmpty) return false;
    final title = video.hasTitle() ? video.title : 'Media';
    _currentTitle = title;
    _lastPlayState = 'playing';

    unawaited(_startPlayback(url, title));
    return true;
  }

  Future<void> _startPlayback(String url, String title) async {
    final ok = await _client.launchMedia(url, title: title);
    if (ok) {
      _startPollTimer();
    }
  }

  @override
  bool castPlaylist(PlaylistPayload playlist) {
    if (playlist.items.isEmpty) return false;
    return castVideo(playlist.items.first);
  }

  @override
  bool sendControl(String command) {
    if (!isConnected) return false;

    switch (command) {
      case 'play':
      case 'resume':
        _lastPlayState = 'playing';
        unawaited(_client.play());
        _startPollTimer();
        return true;
      case 'pause':
        _lastPlayState = 'paused';
        unawaited(_client.pause());
        return true;
      case 'toggle':
        if (_lastPlayState == 'paused') {
          _lastPlayState = 'playing';
          unawaited(_client.play());
          _startPollTimer();
        } else {
          _lastPlayState = 'paused';
          unawaited(_client.pause());
        }
        return true;
      case 'stop':
        _stopPollTimer();
        _lastPlayState = 'stopped';
        unawaited(_client.stop());
        _emitStatus('stopped', 0, 0);
        return true;
      case 'seek_forward':
        unawaited(_client.seekForward());
        return true;
      case 'seek_back':
        unawaited(_client.seekRewind());
        return true;
      default:
        return false;
    }
  }

  @override
  bool playlistJump(int index) => false;

  @override
  bool queueAdd(PlayPayload item) => false;

  void _startPollTimer() {
    _stopPollTimer();
    _pollTimer = Timer.periodic(
        const Duration(seconds: 1), (_) => unawaited(_pollStatus()));
  }

  void _stopPollTimer() {
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  Future<void> _pollStatus() async {
    if (!isConnected) return;
    final status = await _client.getMediaPlayerStatus();
    if (status == null) return;

    var playState = 'playing';
    final upper = status.state.toUpperCase();
    if (upper == 'PAUSE') {
      playState = 'paused';
    } else if (upper == 'NONE' || upper == 'CLOSE') {
      playState = 'stopped';
      _stopPollTimer();
    } else if (upper == 'BUFFER') {
      playState = 'buffering';
    }

    _lastPlayState = playState;
    _emitStatus(playState, status.positionMs, status.durationMs);
  }

  void _emitStatus(String state, int posMs, int durMs) {
    if (_messages.isClosed) return;
    final json = jsonEncode({
      'type': 'status',
      'state': state,
      'position': posMs,
      'duration': durMs,
      if (_currentTitle != null) 'title': _currentTitle,
    });
    _messages.add(json);
  }

  @override
  Future<void> dispose() async {
    _stopPollTimer();
    _client.close();
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}

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
    }
  }
}
