import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'local_file_server.dart';
import 'pairing_store.dart';
import 'protocol.dart';
import 'tv_connection_store.dart';
import 'tv_discovery.dart';
import 'tv_sender_client.dart';

/// Orchestrates the desktop's **sender** role: LAN discovery, the paired-TV
/// store, and the pinned `wss://` client. Connect to a discovered TV (first-time
/// pairing) or reconnect a known one by token; persists the credentials the TV
/// issues. A `ChangeNotifier` so the UI/tray can bind directly.
///
/// Device identity (deviceId / deviceName) is reused from the existing receiver
/// [PairingStore] — the desktop is one device whether sending or receiving.
class TvSenderController extends ChangeNotifier {
  TvSenderController({
    required PairingStore identity,
    required TvConnectionStore store,
  })  : _identity = identity,
        _store = store;

  final PairingStore _identity;
  final TvConnectionStore _store;
  final TvDiscoveryBrowser _discovery = TvDiscoveryBrowser();
  final TvSenderClient _client = TvSenderClient();
  final LocalFileServer _fileServer = LocalFileServer();

  StreamSubscription<List<DiscoveredTv>>? _devSub;
  StreamSubscription<SenderConnectionState>? _stateSub;
  StreamSubscription<TvCredentials>? _credSub;
  StreamSubscription<String>? _msgSub;
  StreamSubscription<String>? _sasSub;

  List<DiscoveredTv> _discoveredRaw = const [];
  SenderConnectionState _state = SenderConnectionState.disconnected;
  DiscoveredTv? _pending; // target of the in-flight / most recent connect
  TvRecord? _activeTv;
  String? _currentSas;

  // Live now-casting snapshot, updated from the TV's `status` messages.
  String? _castingTitle;
  String _remoteState = '';
  int _remotePositionMs = 0;
  int _remoteDurationMs = 0;

  // The playlist currently on the TV, from `playlist_status` echoes (or set
  // optimistically when we cast). Drives the Now Playing tab's item list.
  List<({int index, String title})> _castPlaylist = const [];
  int _castIndex = -1;

  /// Discovered TVs on the LAN, always excluding this app's own receiver
  /// advertisement. Local playback is the default when nothing is linked
  /// (extension bridge / cold-start file); self-cast is not offered.
  List<DiscoveredTv> get discovered => _discoveredRaw
      .where((t) => t.uuid != _identity.deviceId)
      .toList(growable: false);

  List<TvRecord> get pairedTvs => _store.tvs;
  SenderConnectionState get state => _state;
  TvRecord? get activeTv => _activeTv;
  bool get isConnected => _state == SenderConnectionState.connected;
  String? get currentSas => _currentSas;

  String? get castingTitle => _castingTitle;
  String get remoteState => _remoteState;
  int get remotePositionMs => _remotePositionMs;
  int get remoteDurationMs => _remoteDurationMs;

  /// Playlist currently on the TV (index + title), and the active item's index.
  List<({int index, String title})> get castItems => _castPlaylist;
  int get castIndex => _castIndex;

  /// True when something is being cast (drives the Now Playing tab visibility).
  bool get isCasting => _castingTitle != null || _castPlaylist.isNotEmpty;

  /// TV → desktop messages (status / playlist_status / tracks / context).
  Stream<String> get messages => _client.messages;

  Future<void> start() async {
    _devSub = _discovery.devices.listen((d) {
      _discoveredRaw = d;
      notifyListeners();
    });
    _stateSub = _client.state.listen(_onState);
    _credSub = _client.credentials.listen(_onCredentials);
    _msgSub = _client.messages.listen(_onTvMessage);
    _sasSub = _client.sasCode.listen((sas) {
      _currentSas = sas;
      notifyListeners();
    });
    await _discovery.start();
  }

  bool submitSasCode(String code) => _client.submitSasCode(code);

  /// SAS retry hint surfaced to the pairing UI.
  int get sasAttemptsLeft => _client.sasAttemptsLeft;
  bool get lastSasWrong => _client.lastSasWrong;

  /// Connect to a TV found via discovery. Reconnects silently when already
  /// paired; otherwise runs the SAS pairing handshake (the user enters the
  /// 6-digit code shown on the TV).
  Future<void> connectToDiscovered(DiscoveredTv tv) async {
    _pending = tv;
    final known = _store.byUuid(tv.uuid);
    await _client.connect(
      host: tv.host,
      port: tv.port,
      wssPort: tv.wssPort,
      deviceName: _identity.deviceName,
      deviceUUID: _identity.deviceId,
      token: known?.token,
      expectedPin: known?.certFingerprint,
    );
  }

  /// Reconnect a known TV by token (e.g. from the paired list). Prefers a fresh
  /// discovered address when the TV is currently visible (survives DHCP changes).
  Future<void> reconnect(TvRecord tv) async {
    final fresh = _discoveredByUuid(tv.uuid);
    final target = DiscoveredTv(
      uuid: tv.uuid,
      name: tv.name,
      host: fresh?.host ?? tv.host,
      port: fresh?.port ?? tv.port,
      wssPort: fresh?.wssPort ?? tv.wssPort,
    );
    _pending = target;
    await _client.connect(
      host: target.host,
      port: target.port,
      wssPort: target.wssPort,
      deviceName: _identity.deviceName,
      deviceUUID: _identity.deviceId,
      token: tv.token,
      expectedPin: tv.certFingerprint,
    );
  }

  Future<void> disconnect() => _client.disconnect();

  Future<void> forget(String uuid) async {
    if (_activeTv?.uuid == uuid) await _client.disconnect();
    await _store.forget(uuid);
    if (_activeTv?.uuid == uuid) _activeTv = null;
    notifyListeners();
  }

  // ─── Casting ──────────────────────────────────────────────────────────────
  // A single video is sent as a one-item playlist (see senderSingleVideoCommandJson).

  bool castVideo(PlayPayload video) {
    final ok = _client.send(senderSingleVideoCommandJson(video));
    if (ok) {
      _castingTitle =
          video.hasTitle() && video.title.isNotEmpty ? video.title : null;
      notifyListeners();
    }
    return ok;
  }

  bool castPlaylist(PlaylistPayload playlist) =>
      _client.send(senderPlaylistCommandJson(playlist));

  /// Cast a remote URL (e.g. a stream the browser extension detected) with
  /// optional request [headers] (Referer / cookies / auth) and a [title].
  bool castUrl(String url, {Map<String, String>? headers, String? title}) {
    final payload = PlayPayload()..url = url;
    if (headers != null && headers.isNotEmpty) payload.headers.addAll(headers);
    if (title != null && title.isNotEmpty) payload.title = title;
    return castVideo(payload);
  }

  bool queueAdd(PlayPayload item) => _client.send(senderQueueAddJson(item));

  bool playlistJump(int index) => _client.send(senderPlaylistJumpJson(index));

  bool sendControl(String command) =>
      _client.send(senderControlCommandJson(command));

  bool sendContextQuery() => _client.send(senderContextQueryJson());

  // Transport for the active cast (command strings match the TV's InputHandler).
  bool playPause() => sendControl('toggle');
  bool seekForward() => sendControl('seek_forward');
  bool seekBack() => sendControl('seek_back');

  /// Absolute seek to [positionMs]. The TV's InputHandler accepts
  /// `seek_to:<positionMs>` (used by the phone seekbar too).
  bool seekToMs(int positionMs) =>
      sendControl('seek_to:${positionMs < 0 ? 0 : positionMs}');

  /// Stop playback on the TV and clear the local now-casting snapshot so the
  /// card disappears immediately (don't wait for a TV status that may not come).
  bool stopCast() {
    final ok = sendControl('stop');
    _clearNowCasting();
    return ok;
  }

  /// Resets the now-casting snapshot and notifies (hides the card).
  void _clearNowCasting() {
    if (_castingTitle == null &&
        _remoteState.isEmpty &&
        _remotePositionMs == 0 &&
        _remoteDurationMs == 0 &&
        _castPlaylist.isEmpty) {
      return;
    }
    _castingTitle = null;
    _remoteState = '';
    _remotePositionMs = 0;
    _remoteDurationMs = 0;
    _castPlaylist = const [];
    _castIndex = -1;
    notifyListeners();
  }

  /// Jump to a playlist item on the TV by index.
  bool playlistJumpTo(int index) => playlistJump(index);

  /// Cast a **local file** to the connected TV: serve it over LAN HTTP (tokenized
  /// + IP-restricted, D4) and point the TV's player at the URL. Returns false if
  /// no TV is connected, the file is missing, or no LAN address is reachable.
  Future<bool> castLocalFile(File file, {String? title}) =>
      castLocalFiles([file], titles: title != null ? [title] : null);

  /// Cast one or more **local files** as a playlist. Each file is served over
  /// LAN HTTP (tokenized + IP-restricted, D4); a single file becomes a one-item
  /// playlist (how single videos are sent anyway). Returns false if no TV is
  /// connected, no file exists, or no LAN address is reachable.
  Future<bool> castLocalFiles(List<File> files, {List<String>? titles}) async {
    final active = _activeTv;
    if (active == null || !isConnected) return false;

    final present = files.where((f) => f.existsSync()).toList(growable: false);
    if (present.isEmpty) return false;

    await _fileServer.start();
    final host = await _localLanIp(active.host);
    if (host == null) return false;

    final items = <PlayPayload>[];
    for (var i = 0; i < present.length; i++) {
      final file = present[i];
      final filename = file.uri.pathSegments.isNotEmpty
          ? file.uri.pathSegments.last
          : 'video';
      final token = _fileServer.register(file, clientIp: active.host);
      final url = _fileServer.urlFor(token, host, filename: filename);
      final payload = PlayPayload()..url = url;
      final label =
          (titles != null && i < titles.length && titles[i].isNotEmpty)
              ? titles[i]
              : filename;
      if (label.isNotEmpty) payload.title = label;
      items.add(payload);
    }
    if (items.isEmpty) return false;

    final ok = castPlaylist(PlaylistPayload(items: items));
    if (ok) {
      // Optimistic now-casting snapshot; refined by the TV's status /
      // playlist_status echoes.
      _castingTitle = items.length == 1
          ? (items.first.hasTitle() ? items.first.title : null)
          : '${items.length} items';
      _castPlaylist = [
        for (var i = 0; i < items.length; i++)
          (
            index: i,
            title: items[i].hasTitle() ? items[i].title : 'Item ${i + 1}'
          ),
      ];
      _castIndex = 0;
      notifyListeners();
    }
    return ok;
  }

  /// Picks a local IPv4 the TV can reach — preferring an address on the TV's
  /// /24, then any private address, then anything non-link-local.
  Future<String?> _localLanIp(String tvHost) async {
    try {
      final ifaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLoopback: false,
        includeLinkLocal: false,
      );
      final addrs = ifaces
          .expand((i) => i.addresses)
          .map((a) => a.address)
          .where((a) => !a.startsWith('169.254.'))
          .toList();
      final tvPrefix = _slash24(tvHost);
      if (tvPrefix != null) {
        for (final a in addrs) {
          if (_slash24(a) == tvPrefix) return a;
        }
      }
      for (final a in addrs) {
        if (_isPrivate(a)) return a;
      }
      return addrs.isNotEmpty ? addrs.first : null;
    } catch (_) {
      return null;
    }
  }

  String? _slash24(String ip) {
    final p = ip.split('.');
    return p.length == 4 ? '${p[0]}.${p[1]}.${p[2]}' : null;
  }

  bool _isPrivate(String ip) =>
      ip.startsWith('10.') ||
      ip.startsWith('192.168.') ||
      RegExp(r'^172\.(1[6-9]|2\d|3[01])\.').hasMatch(ip);

  // ─── Internals ──────────────────────────────────────────────────────────────
  void _onState(SenderConnectionState s) {
    _state = s;
    if (s != SenderConnectionState.waitingForCodeInput) {
      _currentSas = null;
    }
    switch (s) {
      case SenderConnectionState.connected:
        final p = _pending;
        if (p != null) {
          _activeTv = _store.byUuid(p.uuid);
          // Refresh volatile fields (host may have changed between sessions).
          _store.markConnected(p.uuid,
              host: p.host, port: p.port, wssPort: p.wssPort);
        }
        break;
      case SenderConnectionState.disconnected:
      case SenderConnectionState.authFailed:
      case SenderConnectionState.pinMismatch:
        _activeTv = null;
        _castingTitle = null;
        _remoteState = '';
        _remotePositionMs = 0;
        _remoteDurationMs = 0;
        break;
      default:
        break;
    }
    notifyListeners();
  }

  void _onCredentials(TvCredentials creds) {
    final p = _pending;
    if (p == null) return;
    // upsert is keyed by uuid, so this covers both first-pair and token refresh.
    _store.upsert(TvRecord(
      uuid: p.uuid,
      name: p.name,
      host: p.host,
      port: p.port,
      wssPort: p.wssPort,
      token: creds.token,
      certFingerprint: creds.certFingerprint,
      lastConnected: DateTime.now(),
    ));
    _activeTv = _store.byUuid(p.uuid);
    notifyListeners();
  }

  /// TV states that mean nothing is playing → hide the now-casting card.
  static const _terminalStates = {
    'idle',
    'stopped',
    'ended',
    'finished',
    'complete',
    'none',
    'error',
  };

  void _onTvMessage(String text) {
    try {
      final obj = jsonDecode(text);
      if (obj is! Map) return;
      final type = obj['type'];
      if (type == 'status') {
        final state = (obj['state'] as String?)?.toLowerCase();
        if (state != null && _terminalStates.contains(state)) {
          _clearNowCasting();
          return;
        }
        _remoteState = state ?? _remoteState;
        _remotePositionMs =
            (obj['position'] as num?)?.toInt() ?? _remotePositionMs;
        _remoteDurationMs =
            (obj['duration'] as num?)?.toInt() ?? _remoteDurationMs;
        final t = obj['title'] as String?;
        if (t != null && t.isNotEmpty) _castingTitle = t;
        notifyListeners();
      } else if (type == 'context') {
        // The TV broadcasts context 'idle' when its player activity goes away
        // (playback ended or stopped on the TV) — mirror that here.
        if ((obj['active'] as String?) == 'idle') _clearNowCasting();
      } else if (type == 'playlist_status') {
        final items = obj['items'];
        if (items is List) {
          _castPlaylist = [
            for (final it in items)
              if (it is Map)
                (
                  index: (it['index'] as num?)?.toInt() ?? 0,
                  title: (it['title'] as String?) ?? 'Item',
                ),
          ];
        }
        _castIndex = (obj['currentIndex'] as num?)?.toInt() ?? _castIndex;
        notifyListeners();
      }
    } catch (_) {
      // Non-JSON messages are ignored here.
    }
  }

  DiscoveredTv? _discoveredByUuid(String uuid) {
    for (final d in _discoveredRaw) {
      if (d.uuid == uuid) return d;
    }
    return null;
  }

  @override
  void dispose() {
    _devSub?.cancel();
    _stateSub?.cancel();
    _credSub?.cancel();
    _msgSub?.cancel();
    _sasSub?.cancel();
    _discovery.dispose();
    _client.dispose();
    _fileServer.stop();
    super.dispose();
  }
}
