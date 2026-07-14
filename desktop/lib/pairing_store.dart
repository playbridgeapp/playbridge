import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';
import 'package:crypto/crypto.dart';

import 'player_engine.dart';

class PairedDeviceRecord {
  final String deviceUUID;
  final String deviceName;
  final String token;
  final DateTime lastConnected;

  const PairedDeviceRecord({
    required this.deviceUUID,
    required this.deviceName,
    required this.token,
    required this.lastConnected,
  });

  Map<String, dynamic> toJson() => {
        'deviceUUID': deviceUUID,
        'deviceName': deviceName,
        'token': token,
        'lastConnected': lastConnected.millisecondsSinceEpoch,
      };

  static PairedDeviceRecord fromJson(Map<String, dynamic> j) =>
      PairedDeviceRecord(
        deviceUUID: j['deviceUUID'] as String,
        deviceName: j['deviceName'] as String,
        token: j['token'] as String,
        lastConnected:
            DateTime.fromMillisecondsSinceEpoch(j['lastConnected'] as int),
      );
}

enum StreamProxyMode {
  off,
  auto,
  always,
}

/// Persistent pairing identity for the desktop receiver.
class PairingStore {
  PairingStore._(this._prefs);

  /// Creates a store around injected preferences for deterministic tests.
  PairingStore.forTest(SharedPreferences prefs) : _prefs = prefs;

  static const _kDeviceId = 'pb.device_id';
  static const _kDeviceName = 'pb.device_name';
  static const _kEngineType = 'pb.engine_type';
  static const _kPairedDevices = 'pb.paired_devices';
  static const _kShowStats = 'pb.show_stats';
  static const _kAutoFullScreen = 'pb.auto_fullscreen';
  static const _kPauseOnWindowHide = 'pb.pause_on_window_hide';
  static const _kEnableHistory = 'pb.enable_history';
  static const _kPreselectHlsQuality = 'pb.preselect_hls_quality';
  static const _kStreamProxyMode = 'pb.stream_proxy_mode';
  static const _kStillWatchingEnabled = 'pb.still_watching_enabled';
  static const _kStillWatchingThresholdMin = 'pb.still_watching_threshold_min';
  static const _kStillWatchingResponseSec = 'pb.still_watching_response_sec';

  static const stillWatchingPresets = <int>[30, 60, 90, 120, 180, 240];
  static const stillWatchingResponsePresets = <int>[30, 60, 120, 300, 600];

  final SharedPreferences _prefs;

  static Future<PairingStore> load() async {
    final p = await SharedPreferences.getInstance();
    return PairingStore._(p);
  }

  // Desktop ships only the embedded MPV engine; the setting is retained for API
  // compatibility but always resolves to internal MPV.
  EngineType get engineType => EngineType.mpvInternal;

  Future<void> setEngineType(EngineType type) =>
      _prefs.setString(_kEngineType, 'mpv_int');

  String get deviceId {
    var id = _prefs.getString(_kDeviceId);
    if (id == null) {
      id = const Uuid().v4();
      _prefs.setString(_kDeviceId, id);
    }
    return id;
  }

  String get deviceName {
    final saved = _prefs.getString(_kDeviceName);
    if (saved != null && saved.isNotEmpty) return saved;
    return _defaultName();
  }

  Future<void> setDeviceName(String name) =>
      _prefs.setString(_kDeviceName, name);

  /// Whether the live playback-stats overlay is shown (toggleable via the `i`
  /// hotkey or the Settings switch).
  bool get showStats => _prefs.getBool(_kShowStats) ?? false;

  Future<void> setShowStats(bool value) => _prefs.setBool(_kShowStats, value);

  /// Whether playback automatically enters full screen when a video starts.
  /// Enabled by default.
  bool get autoFullScreen => _prefs.getBool(_kAutoFullScreen) ?? true;

  Future<void> setAutoFullScreen(bool value) =>
      _prefs.setBool(_kAutoFullScreen, value);

  /// When true, the red close button (hide to tray) also pauses local playback.
  /// Default false: keep playing in the background like a cast receiver.
  bool get pauseOnWindowHide => _prefs.getBool(_kPauseOnWindowHide) ?? false;

  Future<void> setPauseOnWindowHide(bool value) =>
      _prefs.setBool(_kPauseOnWindowHide, value);

  bool get enableHistory => _prefs.getBool(_kEnableHistory) ?? true;

  Future<void> setEnableHistory(bool value) =>
      _prefs.setBool(_kEnableHistory, value);

  /// Resolve HLS master playlists to the best compatible rendition before
  /// handing them to mpv. Disabled by default so mpv receives the original
  /// master playlist unless the user opts into preselection.
  bool get preselectHlsQuality =>
      _prefs.getBool(_kPreselectHlsQuality) ?? false;

  Future<void> setPreselectHlsQuality(bool value) =>
      _prefs.setBool(_kPreselectHlsQuality, value);

  StreamProxyMode get streamProxyMode {
    final index = _prefs.getInt(_kStreamProxyMode) ?? StreamProxyMode.off.index;
    if (index >= 0 && index < StreamProxyMode.values.length) {
      return StreamProxyMode.values[index];
    }
    return StreamProxyMode.off;
  }

  Future<void> setStreamProxyMode(StreamProxyMode value) =>
      _prefs.setInt(_kStreamProxyMode, value.index);

  bool get stillWatchingEnabled =>
      _prefs.getBool(_kStillWatchingEnabled) ?? false;

  Future<void> setStillWatchingEnabled(bool value) =>
      _prefs.setBool(_kStillWatchingEnabled, value);

  int get stillWatchingThresholdMinutes {
    final saved = _prefs.getInt(_kStillWatchingThresholdMin);
    return stillWatchingPresets.contains(saved) ? saved! : 90;
  }

  Future<void> setStillWatchingThresholdMinutes(int value) => _prefs.setInt(
        _kStillWatchingThresholdMin,
        stillWatchingPresets.contains(value) ? value : 90,
      );

  int get stillWatchingResponseSeconds {
    final saved = _prefs.getInt(_kStillWatchingResponseSec);
    return stillWatchingResponsePresets.contains(saved) ? saved! : 300;
  }

  Future<void> setStillWatchingResponseSeconds(int value) => _prefs.setInt(
        _kStillWatchingResponseSec,
        stillWatchingResponsePresets.contains(value) ? value : 300,
      );

  // ─── Paired devices ──────────────────────────────────────────────────────

  List<PairedDeviceRecord> get pairedDevices {
    final raw = _prefs.getString(_kPairedDevices);
    if (raw == null) return [];
    try {
      final list = jsonDecode(raw) as List;
      return list
          .whereType<Map<String, dynamic>>()
          .map(PairedDeviceRecord.fromJson)
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> _savePairedDevices(List<PairedDeviceRecord> devices) =>
      _prefs.setString(
          _kPairedDevices, jsonEncode(devices.map((d) => d.toJson()).toList()));

  bool isTokenAuthorized(String token) {
    final digest = _tokenDigest(token);
    return pairedDevices.any((d) => d.token == token || d.token == digest);
  }

  Future<void> addPairedDevice(PairedDeviceRecord device) async {
    final devices = pairedDevices.toList();
    final protected = PairedDeviceRecord(
      deviceUUID: device.deviceUUID,
      deviceName: device.deviceName,
      token: _tokenDigest(device.token),
      lastConnected: device.lastConnected,
    );
    final idx = devices.indexWhere((d) => d.deviceUUID == device.deviceUUID);
    if (idx >= 0) {
      devices[idx] = protected;
    } else {
      devices.add(protected);
    }
    await _savePairedDevices(devices);
  }

  Future<void> updateLastConnected(String token) async {
    final devices = pairedDevices.toList();
    final digest = _tokenDigest(token);
    final idx =
        devices.indexWhere((d) => d.token == token || d.token == digest);
    if (idx < 0) return;
    final d = devices[idx];
    devices[idx] = PairedDeviceRecord(
      deviceUUID: d.deviceUUID,
      deviceName: d.deviceName,
      // Transparently migrate legacy plaintext records after successful auth.
      token: digest,
      lastConnected: DateTime.now(),
    );
    await _savePairedDevices(devices);
  }

  Future<void> forgetDevice(String deviceUUID) async {
    final devices =
        pairedDevices.where((d) => d.deviceUUID != deviceUUID).toList();
    await _savePairedDevices(devices);
  }

  Future<void> forgetAllDevices() => _prefs.remove(_kPairedDevices);

  static String _tokenDigest(String token) =>
      'sha256:${sha256.convert(utf8.encode(token))}';

  static String _defaultName() {
    try {
      final host = Platform.localHostname;
      if (host.isNotEmpty) return host;
    } catch (_) {}
    if (Platform.isMacOS) return 'Mac';
    if (Platform.isWindows) return 'PC';
    if (Platform.isLinux) return 'Linux';
    return 'PlayBridge Desktop';
  }
}
