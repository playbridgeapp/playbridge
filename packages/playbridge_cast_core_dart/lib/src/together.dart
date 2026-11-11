import 'dart:async';
import 'dart:convert';
import 'dart:ffi';

import 'package:ffi/ffi.dart';

import 'cast_core.dart';

/// Watch Together commands and events, mirroring `cast/ffi/src/together.rs`.
/// See `docs/design-watch-together.md`.
///
/// Invite links and [TogetherItem.headers] can hold secrets: never log them.

const togetherAbiVersion = 1;

enum TogetherDeviceKind {
  tv('tv'),
  phone('phone'),
  desktop('desktop'),
  cli('cli');

  const TogetherDeviceKind(this.wire);
  final String wire;

  static TogetherDeviceKind from(Object? wire) => values.firstWhere(
        (kind) => kind.wire == wire,
        orElse: () => TogetherDeviceKind.phone,
      );
}

/// Who can change the video.
enum TogetherPolicy {
  onlyHost('only_host'),
  ask('ask'),
  anyone('anyone');

  const TogetherPolicy(this.wire);
  final String wire;

  static TogetherPolicy from(Object? wire) => values.firstWhere(
        (policy) => policy.wire == wire,
        orElse: () => TogetherPolicy.onlyHost,
      );
}

final class TogetherDevice {
  const TogetherDevice(this.name, this.kind, {required this.screen});

  final String name;
  final TogetherDeviceKind kind;

  /// Plays the video here; false for a remote.
  final bool screen;

  Map<String, Object?> toJson() =>
      {'name': name, 'kind': kind.wire, 'screen': screen};
}

/// Where the video comes from. Each member resolves it itself.
sealed class TogetherSource {
  const TogetherSource();

  Map<String, Object?> toJson();

  static TogetherSource? fromJson(Object? json) {
    if (json is! Map) return null;
    final url = json['url'];
    switch (json['type']) {
      case 'url':
        return url is String ? TogetherUrl(url) : null;
      case 'page':
        return url is String ? TogetherPage(url) : null;
      case 'app':
        final app = json['app'];
        final id = json['id'];
        return app is String && id is String ? TogetherApp(app, id) : null;
    }
    return null;
  }
}

/// A direct media link (signed links included).
final class TogetherUrl extends TogetherSource {
  const TogetherUrl(this.url);
  final String url;

  @override
  Map<String, Object?> toJson() => {'type': 'url', 'url': url};
}

/// A web page each receiver extracts the video from.
final class TogetherPage extends TogetherSource {
  const TogetherPage(this.url);
  final String url;

  @override
  Map<String, Object?> toJson() => {'type': 'page', 'url': url};
}

/// A Bridged App item, for example Invidious or Jellyfin.
final class TogetherApp extends TogetherSource {
  const TogetherApp(this.app, this.id);
  final String app;
  final String id;

  @override
  Map<String, Object?> toJson() => {'type': 'app', 'app': app, 'id': id};
}

final class TogetherItem {
  const TogetherItem({
    this.id = '',
    required this.title,
    this.site,
    required this.source,
    this.headers = const {},
    this.subtitles = const [],
    this.startMs = 0,
    this.sharedBy = '',
  });

  /// Empty lets the engine pick one.
  final String id;
  final String title;
  final String? site;
  final TogetherSource source;
  final Map<String, String> headers;
  final List<String> subtitles;
  final int startMs;

  /// Member ID of whoever shared it; set by the engine.
  final String sharedBy;

  Map<String, Object?> toJson() => {
        if (id.isNotEmpty) 'id': id,
        'title': title,
        if (site != null) 'site': site,
        'source': source.toJson(),
        'headers': headers,
        'subtitles': subtitles,
        'start_ms': startMs,
      };

  static TogetherItem? fromJson(Object? json) {
    if (json is! Map) return null;
    final id = json['id'];
    final source = TogetherSource.fromJson(json['source']);
    if (id is! String || source == null) return null;
    return TogetherItem(
      id: id,
      title: _string(json['title']) ?? '',
      site: _string(json['site']),
      source: source,
      headers: _stringMap(json['headers']),
      subtitles: _strings(json['subtitles']),
      startMs: _int(json['start_ms']),
      sharedBy: _string(json['shared_by']) ?? '',
    );
  }

  @override
  String toString() => 'TogetherItem(id=$id, site=$site)';
}

sealed class TogetherPlayerCommand {
  const TogetherPlayerCommand();

  static TogetherPlayerCommand? fromJson(Object? json) {
    if (json is! Map) return null;
    switch (json['action']) {
      case 'load':
        final item = TogetherItem.fromJson(json['item']);
        if (item == null) return null;
        return TogetherLoad(
          item,
          headers: _stringMap(json['headers']),
          startMs: _int(json['start_ms']),
        );
      case 'play':
        return const TogetherPlay();
      case 'pause':
        return const TogetherPause();
      case 'seek':
        return TogetherSeek(_int(json['position_ms']));
      case 'rate':
        final rate = json['rate'];
        return TogetherRate(rate is num ? rate.toDouble() : 1.0);
      case 'go_live':
        return const TogetherGoLive();
    }
    return null;
  }
}

/// Load paused. [headers] includes sign-in headers when this device may have them.
final class TogetherLoad extends TogetherPlayerCommand {
  const TogetherLoad(this.item, {required this.headers, required this.startMs});
  final TogetherItem item;
  final Map<String, String> headers;
  final int startMs;

  @override
  String toString() => 'TogetherLoad(${item.id}, startMs=$startMs)';
}

final class TogetherPlay extends TogetherPlayerCommand {
  const TogetherPlay();
}

final class TogetherPause extends TogetherPlayerCommand {
  const TogetherPause();
}

final class TogetherSeek extends TogetherPlayerCommand {
  const TogetherSeek(this.positionMs);
  final int positionMs;
}

final class TogetherRate extends TogetherPlayerCommand {
  const TogetherRate(this.rate);
  final double rate;
}

/// A live stream: jump to this device's live point and play.
final class TogetherGoLive extends TogetherPlayerCommand {
  const TogetherGoLive();
}

enum TogetherStop { left, unauthorized, roomFull, expired, replaced, error }

sealed class TogetherEvent {
  const TogetherEvent();

  /// Parses one event, or null for an unknown or malformed one.
  static TogetherEvent? parse(String text) {
    try {
      final json = jsonDecode(text);
      return json is Map ? _parse(json) : null;
    } on FormatException {
      return null;
    }
  }

  static TogetherEvent? _parse(Map json) {
    String? s(String name) => _string(json[name]);
    bool b(String name) => json[name] == true;
    switch (json['event']) {
      case 'room':
        final invite = s('invite');
        final member = s('member');
        final boxKey = s('box_pk');
        final signKey = s('sign_pk');
        if (invite == null ||
            member == null ||
            boxKey == null ||
            signKey == null) {
          return null;
        }
        return TogetherRoom(
          roomId: s('room') ?? '',
          invite: invite,
          relay: s('relay') ?? '',
          member: member,
          boxKey: boxKey,
          signKey: signKey,
          host: b('host'),
        );
      case 'connected':
        return TogetherConnected(_strings(json['members']));
      case 'disconnected':
        return const TogetherDisconnected();
      case 'member_joined':
        final member = s('member');
        return member == null ? null : TogetherMemberJoined(member);
      case 'member_left':
        final member = s('member');
        return member == null ? null : TogetherMemberLeft(member);
      case 'member':
        final member = s('member');
        if (member == null) return null;
        return TogetherMember(
          member: member,
          name: s('name') ?? '',
          kind: TogetherDeviceKind.from(json['kind']),
          host: b('host'),
        );
      case 'video':
        final item = TogetherItem.fromJson(json['item']);
        return item == null ? null : TogetherVideo(item);
      case 'timeline':
        final item = s('item');
        if (item == null) return null;
        return TogetherTimeline(
          item: item,
          playing: b('playing'),
          positionMs: _int(json['position_ms']),
          rev: _int(json['rev']),
        );
      case 'waiting':
        return TogetherWaiting(_strings(json['members']));
      case 'continue_without_available':
        return TogetherContinueWithoutAvailable(_strings(json['members']));
      case 'play_requested':
        final request = s('request');
        if (request == null) return null;
        return TogetherPlayRequested(
          request: request,
          from: s('from') ?? '',
          title: s('title') ?? '',
          site: s('site'),
        );
      case 'request_answered':
        final request = s('request');
        return request == null
            ? null
            : TogetherRequestAnswered(request, allowed: b('allowed'));
      case 'request_expired':
        final request = s('request');
        return request == null ? null : TogetherRequestExpired(request);
      case 'locked':
        return TogetherLocked(b('locked'));
      case 'policy':
        return TogetherPolicyChanged(TogetherPolicy.from(json['policy']));
      case 'credentials':
        final item = s('item');
        return item == null ? null : TogetherCredentialsReceived(item);
      case 'warning':
        return TogetherWarning(s('message') ?? '');
      case 'live':
        return TogetherLive(b('live'));
      case 'rejected':
        return TogetherRejected(s('reason') ?? '');
      case 'player':
        final command = TogetherPlayerCommand.fromJson(json['command']);
        return command == null ? null : TogetherPlayer(command);
      case 'pairing_code':
        final code = s('code');
        final qr = s('qr');
        if (code == null || qr == null) return null;
        return TogetherPairingCode(
          code: code,
          qr: qr,
          qrSize: _int(json['qr_size']),
          qrModules: s('qr_modules') ?? '',
          expiresAtMs: _int(json['expires_at']),
        );
      case 'pairing_invite':
        return TogetherPairingInvite(
            from: s('from') ?? '', relay: s('relay') ?? '');
      case 'pair_sent':
        return TogetherPairSent(s('id'));
      case 'error':
        return TogetherError(
          context: s('context') ?? '',
          message: s('message') ?? '',
          id: s('id'),
        );
      case 'stopped':
        return TogetherStopped(
          roomId: s('room') ?? '',
          reason: switch (json['reason']) {
            'left' => TogetherStop.left,
            'unauthorized' => TogetherStop.unauthorized,
            'room_full' => TogetherStop.roomFull,
            'expired' => TogetherStop.expired,
            'replaced' => TogetherStop.replaced,
            _ => TogetherStop.error,
          },
        );
    }
    return null;
  }
}

/// Entered a room. [invite] is the shareable link and holds the room key.
final class TogetherRoom extends TogetherEvent {
  const TogetherRoom({
    required this.roomId,
    required this.invite,
    required this.relay,
    required this.member,
    required this.boxKey,
    required this.signKey,
    required this.host,
  });

  /// The room ID, which is not secret; [TogetherStopped.roomId] names the same room.
  final String roomId;
  final String invite;
  final String relay;
  final String member;
  final String boxKey;
  final String signKey;
  final bool host;

  @override
  String toString() =>
      'TogetherRoom(room=$roomId, relay=$relay, member=$member, host=$host)';
}

final class TogetherConnected extends TogetherEvent {
  const TogetherConnected(this.members);
  final List<String> members;
}

final class TogetherDisconnected extends TogetherEvent {
  const TogetherDisconnected();
}

final class TogetherMemberJoined extends TogetherEvent {
  const TogetherMemberJoined(this.member);
  final String member;
}

final class TogetherMemberLeft extends TogetherEvent {
  const TogetherMemberLeft(this.member);
  final String member;
}

final class TogetherMember extends TogetherEvent {
  const TogetherMember({
    required this.member,
    required this.name,
    required this.kind,
    required this.host,
  });
  final String member;
  final String name;
  final TogetherDeviceKind kind;
  final bool host;
}

final class TogetherVideo extends TogetherEvent {
  const TogetherVideo(this.item);
  final TogetherItem item;
}

final class TogetherTimeline extends TogetherEvent {
  const TogetherTimeline({
    required this.item,
    required this.playing,
    required this.positionMs,
    required this.rev,
  });
  final String item;
  final bool playing;
  final int positionMs;
  final int rev;
}

final class TogetherWaiting extends TogetherEvent {
  const TogetherWaiting(this.members);
  final List<String> members;
}

final class TogetherContinueWithoutAvailable extends TogetherEvent {
  const TogetherContinueWithoutAvailable(this.members);
  final List<String> members;
}

final class TogetherPlayRequested extends TogetherEvent {
  const TogetherPlayRequested({
    required this.request,
    required this.from,
    required this.title,
    this.site,
  });
  final String request;
  final String from;
  final String title;
  final String? site;
}

final class TogetherRequestAnswered extends TogetherEvent {
  const TogetherRequestAnswered(this.request, {required this.allowed});
  final String request;
  final bool allowed;
}

final class TogetherRequestExpired extends TogetherEvent {
  const TogetherRequestExpired(this.request);
  final String request;
}

final class TogetherLocked extends TogetherEvent {
  const TogetherLocked(this.locked);
  final bool locked;
}

final class TogetherPolicyChanged extends TogetherEvent {
  const TogetherPolicyChanged(this.policy);
  final TogetherPolicy policy;
}

final class TogetherCredentialsReceived extends TogetherEvent {
  const TogetherCredentialsReceived(this.item);
  final String item;
}

final class TogetherWarning extends TogetherEvent {
  const TogetherWarning(this.message);
  final String message;
}

/// Whether the video is a live stream: the party shares play and pause only.
final class TogetherLive extends TogetherEvent {
  const TogetherLive(this.live);
  final bool live;
}

/// A message the engine dropped; for logs only.
final class TogetherRejected extends TogetherEvent {
  const TogetherRejected(this.reason);
  final String reason;
}

final class TogetherPlayer extends TogetherEvent {
  const TogetherPlayer(this.command);
  final TogetherPlayerCommand command;
}

/// A receiver's pairing code. [qr] is the link the QR code carries; [qrModules]
/// is that QR code as [qrSize] rows of `1` (dark) and `0`, ready to draw.
final class TogetherPairingCode extends TogetherEvent {
  const TogetherPairingCode({
    required this.code,
    required this.qr,
    required this.qrSize,
    required this.qrModules,
    required this.expiresAtMs,
  });
  final String code;
  final String qr;
  final int qrSize;
  final String qrModules;
  final int expiresAtMs;
}

/// An invite arrived for this receiver; ask before calling `pairAccept`.
final class TogetherPairingInvite extends TogetherEvent {
  const TogetherPairingInvite({required this.from, required this.relay});
  final String from;
  final String relay;
}

final class TogetherPairSent extends TogetherEvent {
  const TogetherPairSent(this.id);
  final String? id;
}

final class TogetherError extends TogetherEvent {
  const TogetherError({required this.context, required this.message, this.id});
  final String context;
  final String message;
  final String? id;
}

final class TogetherStopped extends TogetherEvent {
  const TogetherStopped({required this.roomId, required this.reason});
  final String roomId;
  final TogetherStop reason;
}

/// The native calls a [TogetherClient] makes; tests swap in a fake.
abstract interface class TogetherNative {
  int abiVersion();

  /// A new engine handle, or 0 when it could not start.
  int start();
  bool submit(int handle, String commandJson);

  /// The next event as JSON, or null when none is waiting.
  String? next(int handle);
  void cancel(int handle);
  void free(int handle);
}

/// One Watch Together session: at most one room and one receiver pairing code.
/// Command JSON holds room keys and sign-in headers, so it is never logged.
final class TogetherClient {
  TogetherClient({
    TogetherNative? native,
    Duration pollInterval = const Duration(milliseconds: 25),
  }) : _native = native ?? FfiTogetherNative() {
    if (_native.abiVersion() != togetherAbiVersion) {
      throw UnsupportedError('Watch Together library mismatch');
    }
    _handle = _native.start();
    if (_handle == 0) throw StateError('Watch Together could not start');
    _timer = Timer.periodic(pollInterval, (_) => poll());
  }

  final TogetherNative _native;
  late int _handle;
  late final Timer _timer;
  final _events = StreamController<TogetherEvent>.broadcast(sync: true);

  Stream<TogetherEvent> get events => _events.stream;
  bool get isClosed => _handle == 0;

  bool host(String relay, TogetherDevice device) =>
      _submit({'command': 'host', 'relay': relay, ...device.toJson()});

  bool join(String invite, TogetherDevice device) =>
      _submit({'command': 'join', 'invite': invite, ...device.toJson()});

  /// Receiver: show a code and wait for an invite.
  bool pairRegister(String pairing) =>
      _submit({'command': 'pair_register', 'pairing': pairing});

  /// Receiver: join the invite from the last [TogetherPairingInvite].
  bool pairAccept(TogetherDevice device) =>
      _submit({'command': 'pair_accept', ...device.toJson()});

  bool pairCancel() => _submit({'command': 'pair_cancel'});

  /// Sender: invite a receiver by its code or the link in its QR code.
  bool pairSend(String pairing, String target, {String? id}) => _submit({
        'command': 'pair_send',
        'pairing': pairing,
        'target': target,
        if (id != null) 'id': id,
      });

  /// What the local player is doing; send it about once a second and after every change.
  bool snapshot(int positionMs,
          {required bool playing, required bool buffering}) =>
      _submit({
        'command': 'snapshot',
        'position_ms': positionMs,
        'playing': playing,
        'buffering': buffering,
      });

  /// The player finished loading [item] (paused). No [durationMs] for live streams.
  bool loaded(String item, int? durationMs) => _submit({
        'command': 'loaded',
        'item': item,
        if (durationMs != null && durationMs > 0) 'duration_ms': durationMs,
      });

  bool load(TogetherItem item) =>
      _submit({'command': 'load', 'item': item.toJson()});
  bool requestPlay(TogetherItem item) =>
      _submit({'command': 'request_play', 'item': item.toJson()});
  bool play() => _submit({'command': 'play'});
  bool pause() => _submit({'command': 'pause'});
  bool seek(int positionMs) =>
      _submit({'command': 'seek', 'position_ms': positionMs});
  bool lock(bool locked) => _submit({'command': 'lock', 'locked': locked});
  bool policy(TogetherPolicy policy) =>
      _submit({'command': 'policy', 'policy': policy.wire});

  bool reply(String request, {required bool allowed, bool always = false}) =>
      _submit({
        'command': 'reply',
        'request': request,
        'allowed': allowed,
        'always': always,
      });

  /// Hand host duties to one of this device's own devices.
  bool delegate(String member, String signKey) =>
      _submit({'command': 'delegate', 'member': member, 'sign_pk': signKey});

  /// A device this one added over its paired LAN connection.
  bool addOwn(String member, String boxKey) =>
      _submit({'command': 'add_own', 'member': member, 'box_pk': boxKey});

  bool continueWithout(String member) =>
      _submit({'command': 'continue_without', 'member': member});
  bool shareCredentials(String member) =>
      _submit({'command': 'share_credentials', 'member': member});

  /// Starts or stops playing the video on this device; starting loads it where the party is.
  bool setScreen(bool screen) =>
      _submit({'command': 'screen', 'screen': screen});
  bool leave() => _submit({'command': 'leave'});

  /// Delivers waiting events. The timer calls it; tests may call it directly.
  void poll() {
    while (_handle != 0) {
      final text = _native.next(_handle);
      if (text == null) break;
      final event = TogetherEvent.parse(text);
      if (event != null) _events.add(event);
    }
  }

  /// Leaves any room and releases the native handle.
  void close() {
    final handle = _handle;
    if (handle == 0) return;
    _timer.cancel();
    poll();
    _handle = 0;
    _native.cancel(handle);
    _native.free(handle);
    _events.close();
  }

  bool _submit(Map<String, Object?> command) =>
      _handle != 0 && _native.submit(_handle, jsonEncode(command));
}

typedef _AbiNative = Uint32 Function();
typedef _AbiDart = int Function();
typedef _StartNative = Pointer<Void> Function();
typedef _StartDart = Pointer<Void> Function();
typedef _SubmitNative = Bool Function(Pointer<Void>, Pointer<Utf8>);
typedef _SubmitDart = bool Function(Pointer<Void>, Pointer<Utf8>);
typedef _NextNative = Pointer<Utf8> Function(Pointer<Void>, Uint64);
typedef _NextDart = Pointer<Utf8> Function(Pointer<Void>, int);
typedef _HandleNative = Void Function(Pointer<Void>);
typedef _HandleDart = void Function(Pointer<Void>);
typedef _StringFreeNative = Void Function(Pointer<Utf8>);
typedef _StringFreeDart = void Function(Pointer<Utf8>);

/// `pb_together_*` from the bundled PlayBridge library.
final class FfiTogetherNative implements TogetherNative {
  factory FfiTogetherNative({String? libraryPath}) =>
      FfiTogetherNative._(_open(libraryPath));

  FfiTogetherNative._(DynamicLibrary library)
      : _abi = library
            .lookupFunction<_AbiNative, _AbiDart>('pb_together_abi_version'),
        _start = library
            .lookupFunction<_StartNative, _StartDart>('pb_together_start'),
        _submit = library.lookupFunction<_SubmitNative, _SubmitDart>(
            'pb_together_submit_json'),
        _next = library
            .lookupFunction<_NextNative, _NextDart>('pb_together_next_json'),
        _cancel = library
            .lookupFunction<_HandleNative, _HandleDart>('pb_together_cancel'),
        _free = library
            .lookupFunction<_HandleNative, _HandleDart>('pb_together_free'),
        _freeString =
            library.lookupFunction<_StringFreeNative, _StringFreeDart>(
                'pb_string_free');

  final _AbiDart _abi;
  final _StartDart _start;
  final _SubmitDart _submit;
  final _NextDart _next;
  final _HandleDart _cancel;
  final _HandleDart _free;
  final _StringFreeDart _freeString;

  @override
  int abiVersion() => _abi();

  @override
  int start() => _start().address;

  @override
  bool submit(int handle, String commandJson) {
    final encoded = commandJson.toNativeUtf8();
    try {
      return _submit(Pointer.fromAddress(handle), encoded);
    } finally {
      calloc.free(encoded);
    }
  }

  @override
  String? next(int handle) {
    final pointer = _next(Pointer.fromAddress(handle), 0);
    if (pointer == nullptr) return null;
    try {
      return pointer.toDartString();
    } finally {
      _freeString(pointer);
    }
  }

  @override
  void cancel(int handle) => _cancel(Pointer.fromAddress(handle));

  @override
  void free(int handle) => _free(Pointer.fromAddress(handle));

  static DynamicLibrary _open(String? libraryPath) {
    if (libraryPath != null) return DynamicLibrary.open(libraryPath);
    Object? lastError;
    for (final path in CastCoreLibrary.candidateLibraryPaths()) {
      try {
        return DynamicLibrary.open(path);
      } on ArgumentError catch (error) {
        lastError = error;
      }
    }
    throw StateError('Unable to load Watch Together. Last error: $lastError');
  }
}

String? _string(Object? value) => value is String ? value : null;

int _int(Object? value) => value is num ? value.toInt() : 0;

List<String> _strings(Object? value) =>
    value is List ? value.whereType<String>().toList() : const [];

Map<String, String> _stringMap(Object? value) => value is Map
    ? {
        for (final entry in value.entries)
          if (entry.key is String && entry.value is String)
            entry.key as String: entry.value as String,
      }
    : const {};
