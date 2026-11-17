import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// A device this computer added to a party, so a rejoin still counts it as
/// this computer's own.
@immutable
class SavedOwnDevice {
  const SavedOwnDevice(this.member, this.boxKey);

  final String member;
  final String boxKey;

  @override
  bool operator ==(Object other) =>
      other is SavedOwnDevice &&
      other.member == member &&
      other.boxKey == boxKey;

  @override
  int get hashCode => Object.hash(member, boxKey);
}

/// A party this computer joined recently, kept so it can come back to it after
/// the app was closed: as the host if it started the party, otherwise as the
/// same guest.
@immutable
class SavedParty {
  const SavedParty({
    required this.roomId,
    required this.relay,
    required this.host,
    required this.saved,
    required this.joinedAtMs,
    required this.endsAtMs,
    this.hostName,
    this.own = const [],
  });

  /// The room ID, which is not secret.
  final String roomId;
  final String relay;

  /// This computer started the party.
  final bool host;

  /// The engine's saved party: the invite and this computer's keys. Secret;
  /// never log it.
  final String saved;
  final int joinedAtMs;

  /// When the room ends, from its ID, or [SavedParties.undatedLifetimeMs]
  /// after joining.
  final int endsAtMs;

  /// The host's name, once a guest has seen it.
  final String? hostName;
  final List<SavedOwnDevice> own;

  String get title {
    if (host) return 'Your party';
    final name = hostName;
    if (name != null && name.isNotEmpty) return "$name's party";
    return 'Watch party';
  }

  SavedParty copyWith({
    String? hostName,
    List<SavedOwnDevice>? own,
  }) =>
      SavedParty(
        roomId: roomId,
        relay: relay,
        host: host,
        saved: saved,
        joinedAtMs: joinedAtMs,
        endsAtMs: endsAtMs,
        hostName: hostName ?? this.hostName,
        own: own ?? this.own,
      );

  Map<String, Object?> toJson() => {
        'room': roomId,
        'relay': relay,
        'host': host,
        'saved': saved,
        'joined_at': joinedAtMs,
        'ends_at': endsAtMs,
        if (hostName != null) 'host_name': hostName,
        'own': [
          for (final device in own)
            {'member': device.member, 'box_pk': device.boxKey},
        ],
      };

  static SavedParty? fromJson(Object? json) {
    if (json is! Map) return null;
    final room = json['room'];
    final saved = json['saved'];
    final joinedAt = json['joined_at'];
    final endsAt = json['ends_at'];
    if (room is! String ||
        room.isEmpty ||
        saved is! String ||
        saved.isEmpty ||
        joinedAt is! num ||
        endsAt is! num) {
      return null;
    }
    final hostName = json['host_name'];
    return SavedParty(
      roomId: room,
      relay: json['relay'] is String ? json['relay'] as String : '',
      host: json['host'] == true,
      saved: saved,
      joinedAtMs: joinedAt.toInt(),
      endsAtMs: endsAt.toInt(),
      hostName: hostName is String && hostName.isNotEmpty ? hostName : null,
      own: [
        for (final device
            in (json['own'] is List ? json['own'] as List : const []))
          if (device is Map &&
              device['member'] is String &&
              device['box_pk'] is String)
            SavedOwnDevice(
                device['member'] as String, device['box_pk'] as String),
      ],
    );
  }

  @override
  String toString() =>
      'SavedParty(room=$roomId, relay=$relay, host=$host, endsAt=$endsAtMs)';

  @override
  bool operator ==(Object other) =>
      other is SavedParty &&
      other.roomId == roomId &&
      other.relay == relay &&
      other.host == host &&
      other.saved == saved &&
      other.joinedAtMs == joinedAtMs &&
      other.endsAtMs == endsAtMs &&
      other.hostName == hostName &&
      listEquals(other.own, own);

  @override
  int get hashCode => Object.hash(roomId, relay, host, saved, joinedAtMs,
      endsAtMs, hostName, Object.hashAll(own));
}

abstract final class SavedParties {
  /// How long a party from before rooms carried their end time is kept: the
  /// relay's party length.
  static const undatedLifetimeMs = 8 * 60 * 60 * 1000;
  static const maxSaved = 20;

  static int endsAt(int? roomEndsAtMs, int joinedAtMs) =>
      roomEndsAtMs ?? joinedAtMs + undatedLifetimeMs;

  /// The parties that haven't ended, newest first.
  static List<SavedParty> live(Iterable<SavedParty> parties, int nowMs) =>
      (parties.where((p) => p.endsAtMs > nowMs).toList()
            ..sort((a, b) => b.joinedAtMs.compareTo(a.joinedAtMs)))
          .take(maxSaved)
          .toList();

  static String endsIn(SavedParty party, int nowMs) {
    final minutes = (party.endsAtMs - nowMs) ~/ 60000;
    if (minutes < 1) return 'Ends in less than a minute';
    if (minutes < 60) return 'Ends in $minutes min';
    if (minutes % 60 == 0) return 'Ends in ${minutes ~/ 60} h';
    return 'Ends in ${minutes ~/ 60} h ${minutes % 60} min';
  }
}

/// Where saved parties live, one entry per room.
abstract interface class SavedPartyVault {
  Future<Map<String, String>> readAll();
  Future<void> write(String roomId, String value);
  Future<void> delete(String roomId);
}

/// The platform's secret store: the macOS login Keychain (this device only),
/// Windows DPAPI, or the Linux Secret Service.
class SecureSavedPartyVault implements SavedPartyVault {
  SecureSavedPartyVault()
      : _storage = const FlutterSecureStorage(
          mOptions: MacOsOptions(
            accountName: 'app.playbridge.desktop.watch-together',
            accessibility: KeychainAccessibility.first_unlock_this_device,
            // The app isn't sandboxed and has no keychain access group; the
            // login Keychain needs no entitlement.
            useDataProtectionKeyChain: false,
          ),
        );

  final FlutterSecureStorage _storage;
  static const _prefix = 'pb.together.saved.';

  @override
  Future<Map<String, String>> readAll() async {
    final all = await _storage.readAll();
    return {
      for (final entry in all.entries)
        if (entry.key.startsWith(_prefix))
          entry.key.substring(_prefix.length): entry.value,
    };
  }

  @override
  Future<void> write(String roomId, String value) =>
      _storage.write(key: '$_prefix$roomId', value: value);

  @override
  Future<void> delete(String roomId) => _storage.delete(key: '$_prefix$roomId');
}

/// For tests.
class MemorySavedPartyVault implements SavedPartyVault {
  final items = <String, String>{};

  @override
  Future<Map<String, String>> readAll() async => Map.of(items);

  @override
  Future<void> write(String roomId, String value) async =>
      items[roomId] = value;

  @override
  Future<void> delete(String roomId) async => items.remove(roomId);
}

/// The parties this computer can rejoin. Ended parties are dropped whenever
/// the list is loaded or changed. If the platform's secret store doesn't work
/// (no Secret Service on Linux, say), nothing is saved.
class SavedPartyStore extends ChangeNotifier {
  SavedPartyStore(this._vault, {int Function()? nowMs})
      : _nowMs = nowMs ?? (() => DateTime.now().millisecondsSinceEpoch);

  final SavedPartyVault _vault;
  final int Function() _nowMs;
  final _all = <String, SavedParty>{};
  bool _loaded = false;
  Future<void>? _loading;

  /// The live parties, newest first.
  List<SavedParty> get parties => SavedParties.live(_all.values, _nowMs());

  SavedParty? get(String roomId) {
    final party = _all[roomId];
    return party != null && party.endsAtMs > _nowMs() ? party : null;
  }

  /// Reads the saved parties once, then drops the ones that ended.
  Future<void> load() async {
    if (!_loaded) {
      await (_loading ??= _read());
      _loaded = true;
    }
    await prune();
  }

  Future<void> _read() async {
    final Map<String, String> stored;
    try {
      stored = await _vault.readAll();
    } catch (e) {
      debugPrint('[together] saved parties unavailable: ${e.runtimeType}');
      return;
    }
    for (final MapEntry(key: room, :value) in stored.entries) {
      SavedParty? party;
      try {
        party = SavedParty.fromJson(jsonDecode(value));
      } on FormatException {
        party = null;
      }
      if (party != null && party.roomId == room) {
        _all.putIfAbsent(room, () => party!);
      } else {
        await _safely(() => _vault.delete(room));
      }
    }
  }

  Future<void> put(SavedParty party) async {
    _all[party.roomId] = party;
    await _safely(() => _vault.write(party.roomId, jsonEncode(party.toJson())));
    await prune();
  }

  Future<void> update(
      String roomId, SavedParty Function(SavedParty) change) async {
    final current = _all[roomId];
    if (current == null) return;
    final next = change(current);
    if (next != current) await put(next);
  }

  Future<void> remove(String roomId) async {
    _all.remove(roomId);
    await _safely(() => _vault.delete(roomId));
    notifyListeners();
  }

  /// Drops ended parties and all but the newest [SavedParties.maxSaved].
  Future<void> prune() async {
    final keep = parties.map((p) => p.roomId).toSet();
    for (final room in _all.keys.toList()) {
      if (keep.contains(room)) continue;
      _all.remove(room);
      await _safely(() => _vault.delete(room));
    }
    notifyListeners();
  }

  Future<void> _safely(Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      debugPrint('[together] saved party store failed: ${e.runtimeType}');
    }
  }
}

final _roomPath = RegExp(r'^https?://[^/?#]+(?:/[^?#]*)?/j/([0-9a-f]{32})[?#]');

/// The room ID in a Watch Together invite link, or null for other text. Not
/// secret, unlike the link.
String? watchTogetherRoomId(String link) =>
    _roomPath.firstMatch(link.trim())?.group(1);
