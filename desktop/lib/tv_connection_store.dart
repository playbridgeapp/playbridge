import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// A TV this desktop (acting as a *sender*) has paired with.
///
/// Symmetric to the receiver-side [PairingStore] (which records senders that
/// paired with this desktop) and mirrors the phone's `ConnectionStore`: it holds
/// the bearer [token] for token reconnects and the [certFingerprint] SPKI pin
/// used to trust the TV's self-signed `wss://` cert.
class TvRecord {
  final String uuid;
  final String name;

  /// Last-known LAN host (IP). May change across DHCP leases — discovery
  /// re-resolves it, so it's a hint, not an identity (uuid is the identity).
  final String host;

  /// Plain `ws://` port the TV advertises (legacy / fallback).
  final int port;

  /// Encrypted `wss://` port when the TV advertises one; null = ws-only TV.
  final int? wssPort;

  /// Bearer token issued by the TV at pairing; replayed on reconnect (`auth`).
  final String token;

  /// SPKI pin `sha256/<base64>` of the TV's cert, pinned at pairing. Null until
  /// the TV delivers one (older/ws-only receivers).
  final String? certFingerprint;

  final DateTime lastConnected;

  const TvRecord({
    required this.uuid,
    required this.name,
    required this.host,
    required this.port,
    required this.wssPort,
    required this.token,
    required this.certFingerprint,
    required this.lastConnected,
  });

  TvRecord copyWith({
    String? name,
    String? host,
    int? port,
    int? wssPort,
    String? token,
    String? certFingerprint,
    DateTime? lastConnected,
  }) =>
      TvRecord(
        uuid: uuid,
        name: name ?? this.name,
        host: host ?? this.host,
        port: port ?? this.port,
        wssPort: wssPort ?? this.wssPort,
        token: token ?? this.token,
        certFingerprint: certFingerprint ?? this.certFingerprint,
        lastConnected: lastConnected ?? this.lastConnected,
      );

  Map<String, dynamic> toJson() => {
        'uuid': uuid,
        'name': name,
        'host': host,
        'port': port,
        if (wssPort != null) 'wssPort': wssPort,
        'token': token,
        if (certFingerprint != null) 'certFingerprint': certFingerprint,
        'lastConnected': lastConnected.millisecondsSinceEpoch,
      };

  static TvRecord fromJson(Map<String, dynamic> j) => TvRecord(
        uuid: j['uuid'] as String,
        name: j['name'] as String? ?? '',
        host: j['host'] as String? ?? '',
        port: (j['port'] as num?)?.toInt() ?? 8765,
        wssPort: (j['wssPort'] as num?)?.toInt(),
        token: j['token'] as String? ?? '',
        certFingerprint: j['certFingerprint'] as String?,
        lastConnected: DateTime.fromMillisecondsSinceEpoch(
            (j['lastConnected'] as num?)?.toInt() ?? 0),
      );
}

/// Persistent list of TVs the desktop sender has paired with.
class TvConnectionStore {
  TvConnectionStore._(this._prefs);

  static const _kTvs = 'pb.sender.paired_tvs';

  final SharedPreferences _prefs;

  static Future<TvConnectionStore> load() async {
    final p = await SharedPreferences.getInstance();
    return TvConnectionStore._(p);
  }

  List<TvRecord> get tvs {
    final raw = _prefs.getString(_kTvs);
    if (raw == null) return [];
    try {
      final list = jsonDecode(raw) as List;
      return list
          .whereType<Map<String, dynamic>>()
          .map(TvRecord.fromJson)
          .toList();
    } catch (_) {
      return [];
    }
  }

  TvRecord? byUuid(String uuid) {
    for (final t in tvs) {
      if (t.uuid == uuid) return t;
    }
    return null;
  }

  bool isPaired(String uuid) => byUuid(uuid) != null;

  Future<void> _save(List<TvRecord> list) => _prefs.setString(
      _kTvs, jsonEncode(list.map((t) => t.toJson()).toList()));

  /// Insert or replace by [TvRecord.uuid] (the stable identity).
  Future<void> upsert(TvRecord tv) async {
    final list = tvs.toList();
    final idx = list.indexWhere((t) => t.uuid == tv.uuid);
    if (idx >= 0) {
      list[idx] = tv;
    } else {
      list.add(tv);
    }
    await _save(list);
  }

  /// Refresh the volatile fields after a successful (re)connect without
  /// disturbing identity. No-op if the TV isn't known.
  Future<void> markConnected(
    String uuid, {
    String? host,
    int? port,
    int? wssPort,
    String? token,
    String? certFingerprint,
  }) async {
    final list = tvs.toList();
    final idx = list.indexWhere((t) => t.uuid == uuid);
    if (idx < 0) return;
    list[idx] = list[idx].copyWith(
      host: host,
      port: port,
      wssPort: wssPort,
      token: token,
      certFingerprint: certFingerprint,
      lastConnected: DateTime.now(),
    );
    await _save(list);
  }

  Future<void> forget(String uuid) async {
    final list = tvs.where((t) => t.uuid != uuid).toList();
    await _save(list);
  }

  Future<void> forgetAll() => _prefs.remove(_kTvs);
}
