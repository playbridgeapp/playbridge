import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import 'tv_discovery.dart';

/// A TV this desktop (acting as a *sender*) has paired with.
///
/// Symmetric to the receiver-side [PairingStore] (which records senders that
/// paired with this desktop) and mirrors the phone's `ConnectionStore`: it holds
/// the bearer [token] for token reconnects and the [certFingerprint] SPKI pin
/// used to trust the TV's self-signed `wss://` cert.
class TvRecord {
  final String uuid;
  final TvProtocol protocol;
  final String name;

  /// Last-known LAN host (IP). May change across DHCP leases — discovery
  /// re-resolves it, so it's a hint, not an identity (uuid is the identity).
  final String host;

  /// All last-known receiver addresses. [host] remains the preferred address
  /// for compatibility with older callers.
  final List<String> addresses;

  /// Plain `ws://` port the TV advertises (legacy / fallback).
  final int port;

  /// Encrypted `wss://` port when the TV advertises one; null = ws-only TV.
  final int? wssPort;

  /// Protocol-specific device-description or control endpoint (for example a
  /// DLNA LOCATION URL).
  final String? location;

  /// Bearer token issued by the TV at pairing; replayed on reconnect (`auth`).
  final String token;

  /// SPKI pin `sha256/<base64>` of the TV's cert, pinned at pairing. Null until
  /// the TV delivers one (older/ws-only receivers).
  final String? certFingerprint;

  /// Last-known protocol features advertised or probed for this receiver.
  final Map<String, dynamic> capabilities;

  final DateTime lastConnected;

  const TvRecord({
    required this.uuid,
    this.protocol = TvProtocol.playBridge,
    required this.name,
    required this.host,
    this.addresses = const [],
    required this.port,
    this.wssPort,
    this.location,
    this.token = '',
    this.certFingerprint,
    this.capabilities = const {},
    required this.lastConnected,
  });

  /// Stable receiver identifier, named explicitly for protocol-neutral code.
  String get stableId => uuid;

  /// Persistent identity. A physical TV may legitimately have one record per
  /// supported protocol.
  String get identityKey => '${protocol.name}:$stableId';

  List<String> get allAddresses => List.unmodifiable({
        if (host.isNotEmpty) host,
        ...addresses.where((address) => address.isNotEmpty),
      });

  TvRecord copyWith({
    TvProtocol? protocol,
    String? name,
    String? host,
    List<String>? addresses,
    int? port,
    int? wssPort,
    String? location,
    String? token,
    String? certFingerprint,
    Map<String, dynamic>? capabilities,
    DateTime? lastConnected,
  }) =>
      TvRecord(
        uuid: uuid,
        protocol: protocol ?? this.protocol,
        name: name ?? this.name,
        host: host ?? this.host,
        addresses: addresses ?? this.addresses,
        port: port ?? this.port,
        wssPort: wssPort ?? this.wssPort,
        location: location ?? this.location,
        token: token ?? this.token,
        certFingerprint: certFingerprint ?? this.certFingerprint,
        capabilities: capabilities ?? this.capabilities,
        lastConnected: lastConnected ?? this.lastConnected,
      );

  Map<String, dynamic> toJson() => {
        'uuid': uuid,
        'protocol': protocol.name,
        'name': name,
        'host': host,
        'addresses': allAddresses,
        'port': port,
        if (wssPort != null) 'wssPort': wssPort,
        if (location != null) 'location': location,
        'token': token,
        if (certFingerprint != null) 'certFingerprint': certFingerprint,
        if (capabilities.isNotEmpty) 'capabilities': capabilities,
        'lastConnected': lastConnected.millisecondsSinceEpoch,
      };

  static TvRecord fromJson(Map<String, dynamic> j) {
    final uuid = j['uuid'];
    if (uuid is! String || uuid.isEmpty) {
      throw const FormatException('Saved receiver is missing its UUID');
    }
    final rawAddresses = j['addresses'];
    final rawCapabilities = j['capabilities'];
    return TvRecord(
      uuid: uuid,
      protocol: TvProtocol.fromStorageName(j['protocol'] as String?),
      name: j['name'] as String? ?? '',
      host: j['host'] as String? ?? '',
      addresses: rawAddresses is List
          ? rawAddresses.whereType<String>().toList(growable: false)
          : const [],
      port: (j['port'] as num?)?.toInt() ?? 8765,
      wssPort: (j['wssPort'] as num?)?.toInt(),
      location: j['location'] as String?,
      token: j['token'] as String? ?? '',
      certFingerprint: j['certFingerprint'] as String?,
      capabilities: rawCapabilities is Map
          ? Map<String, dynamic>.from(rawCapabilities)
          : const {},
      lastConnected: DateTime.fromMillisecondsSinceEpoch(
          (j['lastConnected'] as num?)?.toInt() ?? 0),
    );
  }
}

/// Persistent list of TVs the desktop sender has paired with.
class TvConnectionStore {
  TvConnectionStore._(this._prefs);

  static const _kTvs = 'pb.sender.paired_tvs';

  final SharedPreferences _prefs;

  static Future<TvConnectionStore> load() async {
    final p = await SharedPreferences.getInstance();
    final store = TvConnectionStore._(p);
    await store._migrateLegacyRecords();
    return store;
  }

  List<TvRecord> get tvs {
    final raw = _prefs.getString(_kTvs);
    if (raw == null) return [];
    try {
      final list = jsonDecode(raw) as List;
      return [
        for (final value in list)
          if (value is Map<String, dynamic>) tryParseRecord(value),
      ].whereType<TvRecord>().toList();
    } catch (_) {
      return [];
    }
  }

  static TvRecord? tryParseRecord(Map<String, dynamic> value) {
    try {
      return TvRecord.fromJson(value);
    } on Object {
      return null;
    }
  }

  TvRecord? byIdentity(TvProtocol protocol, String uuid) {
    for (final t in tvs) {
      if (t.protocol == protocol && t.uuid == uuid) return t;
    }
    return null;
  }

  /// Compatibility lookup for existing PlayBridge callers. New
  /// protocol-neutral code should pass [protocol] or use [byIdentity].
  TvRecord? byUuid(String uuid,
          {TvProtocol protocol = TvProtocol.playBridge}) =>
      byIdentity(protocol, uuid);

  bool isPaired(String uuid, {TvProtocol protocol = TvProtocol.playBridge}) =>
      byIdentity(protocol, uuid) != null;

  bool isSaved(TvProtocol protocol, String uuid) =>
      byIdentity(protocol, uuid) != null;

  Future<void> _save(List<TvRecord> list) =>
      _prefs.setString(_kTvs, jsonEncode(list.map((t) => t.toJson()).toList()));

  /// Insert or replace by protocol plus stable receiver UUID.
  Future<void> upsert(TvRecord tv) async {
    final list = tvs.toList();
    final idx = list.indexWhere((t) => t.identityKey == tv.identityKey);
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
    TvProtocol protocol = TvProtocol.playBridge,
    String? host,
    List<String>? addresses,
    int? port,
    int? wssPort,
    String? location,
    String? token,
    String? certFingerprint,
    Map<String, dynamic>? capabilities,
  }) async {
    final list = tvs.toList();
    final idx =
        list.indexWhere((t) => t.protocol == protocol && t.uuid == uuid);
    if (idx < 0) return;
    list[idx] = list[idx].copyWith(
      host: host,
      addresses: addresses,
      port: port,
      wssPort: wssPort,
      location: location,
      token: token,
      certFingerprint: certFingerprint,
      capabilities: capabilities,
      lastConnected: DateTime.now(),
    );
    await _save(list);
  }

  Future<void> forget(String uuid,
      {TvProtocol protocol = TvProtocol.playBridge}) async {
    final list =
        tvs.where((t) => t.uuid != uuid || t.protocol != protocol).toList();
    await _save(list);
  }

  Future<void> _migrateLegacyRecords() async {
    final raw = _prefs.getString(_kTvs);
    if (raw == null) return;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return;
      final needsMigration = decoded.whereType<Map<String, dynamic>>().any(
            (record) =>
                !record.containsKey('protocol') ||
                !record.containsKey('addresses'),
          );
      if (needsMigration) await _save(tvs);
    } on Object {
      // Preserve the previous behavior for malformed preference data.
    }
  }

  Future<void> forgetAll() => _prefs.remove(_kTvs);
}
