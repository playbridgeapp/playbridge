import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

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

/// Persistent pairing identity for the desktop receiver.
class PairingStore {
  PairingStore._(this._prefs);

  static const _kDeviceId = 'pb.device_id';
  static const _kDeviceName = 'pb.device_name';
  static const _kEngineType = 'pb.engine_type';
  static const _kPairedDevices = 'pb.paired_devices';
  static const _kAllowInsecure = 'pb.allow_insecure';
  static const _kShowStats = 'pb.show_stats';

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

  /// When false (default) the receiver serves wss:// only; ws:// is enabled
  /// only as an opt-in for legacy senders that can't pin a self-signed cert.
  bool get allowInsecure => _prefs.getBool(_kAllowInsecure) ?? false;

  Future<void> setAllowInsecure(bool value) =>
      _prefs.setBool(_kAllowInsecure, value);

  /// Whether the live playback-stats overlay is shown (toggleable via the `i`
  /// hotkey or the Settings switch).
  bool get showStats => _prefs.getBool(_kShowStats) ?? false;

  Future<void> setShowStats(bool value) => _prefs.setBool(_kShowStats, value);

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

  bool isTokenAuthorized(String token) =>
      pairedDevices.any((d) => d.token == token);

  Future<void> addPairedDevice(PairedDeviceRecord device) async {
    final devices = pairedDevices.toList();
    final idx = devices.indexWhere((d) => d.deviceUUID == device.deviceUUID);
    if (idx >= 0) {
      devices[idx] = device;
    } else {
      devices.add(device);
    }
    await _savePairedDevices(devices);
  }

  Future<void> updateLastConnected(String token) async {
    final devices = pairedDevices.toList();
    final idx = devices.indexWhere((d) => d.token == token);
    if (idx < 0) return;
    final d = devices[idx];
    devices[idx] = PairedDeviceRecord(
      deviceUUID: d.deviceUUID,
      deviceName: d.deviceName,
      token: d.token,
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
