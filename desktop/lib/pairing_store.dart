import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import 'player_engine.dart';

/// Persistent pairing identity for the desktop receiver.
///
/// Mirrors the TV's contract:
///   - `authToken` is a stable UUID, generated once and reused forever
///   - `PIN`       is the first 4 chars of the token, uppercased
///   - `deviceId`  is a stable UUID published via NSD's `uuid` TXT record
///   - `deviceName` is the human-readable name shown by the phone in its picker
class PairingStore {
  PairingStore._(this._prefs);

  static const _kAuthToken = 'pb.auth_token';
  static const _kDeviceId = 'pb.device_id';
  static const _kDeviceName = 'pb.device_name';
  static const _kHasPaired = 'pb.has_paired';
  static const _kEngineType = 'pb.engine_type';

  final SharedPreferences _prefs;

  static Future<PairingStore> load() async {
    final p = await SharedPreferences.getInstance();
    return PairingStore._(p);
  }

  EngineType get engineType {
    final val = _prefs.getString(_kEngineType);
    if (val == 'fvp') return EngineType.fvp;
    return EngineType.mpv;
  }

  Future<void> setEngineType(EngineType type) =>
      _prefs.setString(_kEngineType, type == EngineType.fvp ? 'fvp' : 'mpv');

  String get authToken {
    var t = _prefs.getString(_kAuthToken);
    if (t == null) {
      t = const Uuid().v4();
      _prefs.setString(_kAuthToken, t);
    }
    return t;
  }

  String get pin => authToken.substring(0, 4).toUpperCase();

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

  Future<void> regenerateToken() async {
    await _prefs.setString(_kAuthToken, const Uuid().v4());
  }

  /// True once any client has successfully paired (PIN or token). Used to
  /// decide whether to show the window at launch.
  bool get hasPairedClient => _prefs.getBool(_kHasPaired) ?? false;

  Future<void> markPaired() => _prefs.setBool(_kHasPaired, true);

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
