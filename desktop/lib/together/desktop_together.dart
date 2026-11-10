import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../player_controller.dart';
import '../player_engine.dart';
import '../tv_sender_controller.dart';
import 'watch_together_join.dart';

/// PlayBridge's shared relay: up to 4 devices for 3 hours.
const sharedRelayUrl = 'https://party.playbridge.app';
const defaultPairingUrl = 'https://pair.playbridge.app';

class PartyMember {
  const PartyMember(this.name, this.kind, {required this.host});
  final String name;
  final TogetherDeviceKind kind;
  final bool host;
}

class PartyRequest {
  const PartyRequest(this.id, this.from, this.title, this.site);
  final String id;
  final String from;
  final String title;
  final String? site;
}

class Party {
  const Party({
    required this.roomId,
    required this.invite,
    required this.relay,
    required this.me,
    required this.host,
    this.watching = false,
    this.connected = false,
    this.members = const {},
    this.own = const {},
    this.item,
    this.playing = false,
    this.positionMs = 0,
    this.positionAt = 0,
    this.waitingFor = const [],
    this.continueWithout = const [],
    this.locked = false,
    this.policy = TogetherPolicy.onlyHost,
    this.requests = const [],
    this.notice,
  });

  final String roomId;

  /// The shareable link; it holds the room key, so never log it.
  final String invite;
  final String relay;
  final String me;
  final bool host;

  /// This computer plays the video too; otherwise it is only a remote.
  final bool watching;
  final bool connected;
  final Map<String, PartyMember> members;

  /// Devices this computer added: they get its sign-ins.
  final Set<String> own;
  final TogetherItem? item;
  final bool playing;
  final int positionMs;

  /// When [positionMs] was reported, in [DesktopTogether.clock] milliseconds.
  final int positionAt;
  final List<String> waitingFor;
  final List<String> continueWithout;
  final bool locked;
  final TogetherPolicy policy;
  final List<PartyRequest> requests;
  final String? notice;

  /// Whether this computer may change the video without asking.
  bool get canChangeVideo =>
      host || (policy == TogetherPolicy.anyone && !locked);

  Party copyWith({
    bool? watching,
    bool? connected,
    Map<String, PartyMember>? members,
    Set<String>? own,
    TogetherItem? item,
    bool? playing,
    int? positionMs,
    int? positionAt,
    List<String>? waitingFor,
    List<String>? continueWithout,
    bool? locked,
    TogetherPolicy? policy,
    List<PartyRequest>? requests,
    String? Function()? notice,
  }) =>
      Party(
        roomId: roomId,
        invite: invite,
        relay: relay,
        me: me,
        host: host,
        watching: watching ?? this.watching,
        connected: connected ?? this.connected,
        members: members ?? this.members,
        own: own ?? this.own,
        item: item ?? this.item,
        playing: playing ?? this.playing,
        positionMs: positionMs ?? this.positionMs,
        positionAt: positionAt ?? this.positionAt,
        waitingFor: waitingFor ?? this.waitingFor,
        continueWithout: continueWithout ?? this.continueWithout,
        locked: locked ?? this.locked,
        policy: policy ?? this.policy,
        requests: requests ?? this.requests,
        notice: notice != null ? notice() : this.notice,
      );

  @override
  String toString() => 'Party(room=$roomId, relay=$relay, host=$host)';
}

/// A pairing code this computer shows so a phone can add it to a party.
class PartyCode {
  const PartyCode(this.code, this.qrSize, this.qrModules, this.expiresAtMs);
  final String code;
  final int qrSize;
  final String qrModules;
  final int expiresAtMs;
}

sealed class TogetherState {
  const TogetherState();
}

class TogetherIdle extends TogetherState {
  const TogetherIdle();
}

class TogetherStarting extends TogetherState {
  const TogetherStarting();
}

/// Waiting for the pairing service to hand out a code.
class TogetherRegistering extends TogetherState {
  const TogetherRegistering();
}

class TogetherShowingCode extends TogetherState {
  const TogetherShowingCode(this.code);
  final PartyCode code;
}

/// A phone sent an invite to the code; ask before joining.
class TogetherInvited extends TogetherState {
  const TogetherInvited(this.from, this.relay);
  final String from;
  final String relay;
}

class TogetherInParty extends TogetherState {
  const TogetherInParty(this.party);
  final Party party;
}

class TogetherEnded extends TogetherState {
  const TogetherEnded(this.message);
  final String message;
}

/// Watch Together on the desktop (`docs/design-watch-together.md`): hosts or
/// joins a party, plays its video in this computer's player when watching here,
/// acts as its remote, adds the connected TV, and joins parties a paired phone
/// adds it to.
///
/// Party videos never go into history or resume points. The invite link and item
/// headers can hold secrets: never log them.
class DesktopTogether extends ChangeNotifier implements PartyControls {
  DesktopTogether({
    required this.player,
    required this.sender,
    required SharedPreferences prefs,
    required String Function() deviceName,
    TogetherClient Function()? createClient,
    int Function()? clock,
    this.liveWait = const Duration(seconds: 2),
  })  : _prefs = prefs,
        _deviceName = deviceName,
        _createClient = createClient ?? TogetherClient.new,
        clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch) {
    player.addListener(_onPlayerChanged);
  }

  static const _relayKey = 'pb.together.relay_url';
  static const _pairingKey = 'pb.together.pairing_url';

  final PlayerController player;
  final TvSenderController sender;
  final SharedPreferences _prefs;
  final String Function() _deviceName;
  final TogetherClient Function() _createClient;

  /// Milliseconds for extrapolating the party's position.
  final int Function() clock;

  /// How long a loaded video with no length waits before it counts as live.
  final Duration liveWait;

  TogetherClient? _client;
  StreamSubscription<TogetherEvent>? _events;
  TogetherState _state = const TogetherIdle();
  String? _room;
  bool _watchOnJoin = false;
  Completer<WatchTogetherJoinReply>? _phoneJoin;

  /// The party item this computer's player is showing.
  String? _playerItem;

  /// The item the room last asked this computer to load, until it reports loaded.
  String? _loadingItem;
  Timer? _liveTimer;
  Timer? _snapshotTimer;
  bool _applying = false;

  final _toasts = StreamController<String>.broadcast();

  /// One-off messages for a snackbar.
  Stream<String> get toasts => _toasts.stream;

  TogetherState get state => _state;
  Party? get party => switch (_state) {
        TogetherInParty(:final party) => party,
        _ => null,
      };
  bool get isInParty => party != null;

  String get relayUrl =>
      _nonEmpty(_prefs.getString(_relayKey)) ?? sharedRelayUrl;
  String get pairingUrl =>
      _nonEmpty(_prefs.getString(_pairingKey)) ?? defaultPairingUrl;
  bool get usesSharedRelay => relayUrl == sharedRelayUrl;

  Future<void> setRelayUrl(String url) async {
    final trimmed = url.trim();
    if (trimmed.isEmpty || trimmed == sharedRelayUrl) {
      await _prefs.remove(_relayKey);
    } else {
      await _prefs.setString(_relayKey, trimmed);
    }
    notifyListeners();
  }

  /// Play here by default unless a TV is the chosen screen.
  bool get watchHereByDefault => sender.selection.isLocal;

  /// The party position now, moving on while it plays.
  int positionNow(Party party) => party.playing
      ? party.positionMs + (clock() - party.positionAt)
      : party.positionMs;

  // -- Starting, joining and leaving

  void start({required bool watchHere}) {
    final client = _ensureClient();
    if (client == null) return;
    _watchOnJoin = watchHere;
    _set(const TogetherStarting());
    if (!client.host(relayUrl, _device(watchHere))) {
      _set(const TogetherEnded("Couldn't start a watch party."));
    }
  }

  void join(String link, {required bool watchHere}) {
    final client = _ensureClient();
    if (client == null) return;
    _watchOnJoin = watchHere;
    _set(const TogetherStarting());
    if (!client.join(link.trim(), _device(watchHere))) {
      _set(const TogetherEnded("That invite link isn't valid."));
    }
  }

  void leave() {
    _client?.leave();
    _room = null;
    _stopWatchingPlayer();
    _set(const TogetherIdle());
  }

  void dismissEnded() {
    if (_state is TogetherEnded) _set(const TogetherIdle());
  }

  /// Shows a code so a phone can add this computer to its party.
  void showCode() {
    final client = _ensureClient();
    if (client == null || isInParty) return;
    _set(const TogetherRegistering());
    if (!client.pairRegister(pairingUrl)) {
      _set(const TogetherEnded("Couldn't reach the pairing service."));
    }
  }

  void cancelCode() {
    _client?.pairCancel();
    if (!isInParty) _set(const TogetherIdle());
  }

  /// Joins the party from the pending invite, playing it here.
  void acceptInvite() {
    final invited = _state;
    if (invited is! TogetherInvited) return;
    _watchOnJoin = true;
    _set(const TogetherStarting());
    _client?.pairAccept(_device(true));
  }

  void declineInvite() => cancelCode();

  /// A paired phone added this computer to its party. It is already trusted, so
  /// this computer joins without asking and plays the video here.
  Future<WatchTogetherJoinReply> joinFromPhone(String invite, String from) {
    final client = _ensureClient();
    if (client == null) {
      return Future.value(const WatchTogetherJoinReply.failed('unavailable'));
    }
    _phoneJoin?.complete(const WatchTogetherJoinReply.failed('unavailable'));
    final reply = _phoneJoin = Completer<WatchTogetherJoinReply>();
    client.pairCancel();
    _watchOnJoin = true;
    _set(const TogetherStarting());
    if (!client.join(invite, _device(true))) {
      _phoneJoin = null;
      _set(const TogetherIdle());
      return Future.value(
          const WatchTogetherJoinReply.failed('invalid_invite'));
    }
    if (from.isNotEmpty) _toast('$from added this computer to a watch party');
    return reply.future.timeout(
      const Duration(seconds: 20),
      onTimeout: () {
        _phoneJoin = null;
        return const WatchTogetherJoinReply.failed('unavailable');
      },
    );
  }

  /// Starts or stops playing the party's video here; this computer stays in the party.
  void setWatchHere(bool watch) {
    final current = party;
    if (current == null || current.watching == watch) return;
    if (_client?.setScreen(watch) != true) return;
    _updateParty((p) => p.copyWith(watching: watch));
    if (!watch) _stopWatchingPlayer(stopPlayer: true);
  }

  // -- Remote controls

  bool playParty() => _client?.play() ?? false;
  bool pauseParty() => _client?.pause() ?? false;
  bool seekParty(int positionMs) =>
      _client?.seek(positionMs < 0 ? 0 : positionMs) ?? false;

  void seekBy(int deltaMs) {
    final current = party;
    if (current == null) return;
    seekParty(positionNow(current) + deltaMs);
  }

  void lock(bool locked) => _client?.lock(locked);
  void setPolicy(TogetherPolicy policy) => _client?.policy(policy);
  void continueWithout(String member) => _client?.continueWithout(member);
  void shareSignIn(String member) {
    if (_client?.shareCredentials(member) == true) {
      final name = party?.members[member]?.name;
      _toast('Shared your sign-in${name == null ? '' : ' with $name'}');
    }
  }

  void answer(PartyRequest request,
      {required bool allowed, bool always = false}) {
    _client?.reply(request.id, allowed: allowed, always: always);
    _updateParty((p) => p.copyWith(
        requests: p.requests.where((r) => r.id != request.id).toList()));
  }

  /// Adds the TV this computer is connected to. It joins without asking and
  /// counts as this computer's own.
  Future<void> addConnectedTv() async {
    final current = party;
    if (current == null) return;
    final name = sender.selectionName;
    final reply =
        await sender.addTvToWatchTogether(current.invite, _deviceName());
    final member = reply.member;
    final boxKey = reply.boxKey;
    final signKey = reply.signKey;
    if (!reply.ok || member == null || boxKey == null || signKey == null) {
      _toast("$name couldn't join (${reply.error ?? 'unavailable'})");
      return;
    }
    _client?.addOwn(member, boxKey);
    // The host's own TV can carry on as host if this computer leaves.
    if (current.host) _client?.delegate(member, signKey);
    _updateParty((p) => p.copyWith(own: {...p.own, member}));
    _toast('$name joined the party');
  }

  /// Invites a receiver showing [target], a code or the link in its QR code.
  void addTvByCode(String target) {
    if (!isInParty || target.trim().isEmpty) return;
    _client?.pairSend(pairingUrl, target.trim());
  }

  /// Plays a video in the party: changes it when allowed, otherwise asks the
  /// host. [headers] may include sign-in headers; only this computer's own
  /// devices get them. Returns what happened, for the caller to show.
  String shareVideo({
    required String url,
    String? title,
    Map<String, String> headers = const {},
    List<String> subtitles = const [],
    int startMs = 0,
  }) {
    final current = party;
    if (current == null) return 'Not in a watch party';
    final uri = Uri.tryParse(url);
    if (uri == null || !(uri.isScheme('http') || uri.isScheme('https'))) {
      return "Only web links can play in a watch party";
    }
    final site = uri.host.startsWith('www.') ? uri.host.substring(4) : uri.host;
    final item = TogetherItem(
      title: (title?.trim().isNotEmpty ?? false) ? title!.trim() : site,
      site: site.isEmpty ? null : site,
      source: TogetherUrl(url),
      headers: headers,
      subtitles: subtitles,
      startMs: startMs,
    );
    if (current.canChangeVideo) {
      _client?.load(item);
      return 'Playing in the watch party';
    }
    if (current.locked) return 'The host locked the controls';
    if (current.policy == TogetherPolicy.onlyHost) {
      return 'Only the host can change the video';
    }
    _client?.requestPlay(item);
    return 'Asked the host to play it';
  }

  // -- Player bridge (PartyControls)

  @override
  bool get active => _playerItem != null && !_applying;

  @override
  bool play() => _client?.play() ?? false;

  @override
  bool pause() => _client?.pause() ?? false;

  @override
  bool seek(int positionMs) =>
      _client?.seek(positionMs < 0 ? 0 : positionMs) ?? false;

  void _onPlayerCommand(TogetherPlayerCommand command) {
    if (party?.watching != true) return;
    if (command is TogetherLoad) {
      unawaited(_openPlayer(command));
      return;
    }
    if (_playerItem == null) return;
    unawaited(_apply(() => switch (command) {
          TogetherPlay() => player.partyResume(),
          TogetherPause() => player.partyPause(),
          TogetherSeek(:final positionMs) =>
            player.partySeek(Duration(milliseconds: positionMs)),
          TogetherRate(:final rate) => player.partyRate(rate),
          TogetherLoad() => Future<void>.value(),
        }));
  }

  Future<void> _apply(Future<void> Function() action) async {
    _applying = true;
    try {
      await action();
    } finally {
      _applying = false;
    }
    _sendSnapshot();
  }

  Future<void> _openPlayer(TogetherLoad command) async {
    final item = command.item;
    final source = item.source;
    if (source is! TogetherUrl) {
      _updateParty((p) => p.copyWith(
          notice: () => 'This computer can\'t play "${item.title}" yet.'));
      return;
    }
    _loadingItem = item.id;
    _playerItem = item.id;
    _liveTimer?.cancel();
    player.partyControls = this;
    await _apply(() => player.playPlaylist(
          [
            QueueItem(
              url: source.url,
              title: item.title,
              headers: command.headers.isEmpty ? null : command.headers,
              subtitles: item.subtitles.isEmpty ? null : item.subtitles,
              skipPreplay: true,
              skipHistory: true,
              senderItemId: item.id,
            )..startPositionMs = command.startMs > 0 ? command.startMs : null,
          ],
          0,
          isRemote: true,
          startPaused: true,
        ));
    _snapshotTimer ??=
        Timer.periodic(const Duration(seconds: 1), (_) => _sendSnapshot());
    _checkLoaded();
  }

  void _onPlayerChanged() {
    final item = _playerItem;
    if (item == null || _applying) return;
    if (player.currentItemId != item) {
      // Something else started, or the player was stopped: watch no more.
      _playerItem = null;
      _loadingItem = null;
      player.partyControls = null;
      _toast('Stopped watching the party here');
      setWatchHere(false);
      return;
    }
    _checkLoaded();
  }

  /// Reports the party video loaded once it knows its length; a video still
  /// without one after [liveWait] counts as live.
  void _checkLoaded() {
    final item = _loadingItem;
    if (item == null || player.isOpening) return;
    if (player.durationMs > 0 && !player.isLive) {
      _reportLoaded(item, player.durationMs);
    } else if (player.isLive) {
      _reportLoaded(item, null);
    } else {
      _liveTimer ??= Timer(liveWait, () {
        _liveTimer = null;
        final waiting = _loadingItem;
        if (waiting != null && !player.isOpening) {
          _reportLoaded(
              waiting, player.durationMs > 0 ? player.durationMs : null);
        }
      });
    }
  }

  void _reportLoaded(String item, int? durationMs) {
    if (_loadingItem != item) return;
    _loadingItem = null;
    _liveTimer?.cancel();
    _liveTimer = null;
    _client?.loaded(item, durationMs);
    _sendSnapshot();
  }

  void _sendSnapshot() {
    if (_playerItem == null || player.currentItemId != _playerItem) return;
    final state = player.state;
    final requested = player.engine.playbackRequested ??
        (state == 'playing' || state == 'buffering');
    _client?.snapshot(
      player.positionMs,
      playing: requested && state != 'ended',
      buffering: state == 'buffering' || player.isOpening,
    );
  }

  void _stopWatchingPlayer({bool stopPlayer = false}) {
    final showing = _playerItem != null;
    _playerItem = null;
    _loadingItem = null;
    _liveTimer?.cancel();
    _liveTimer = null;
    _snapshotTimer?.cancel();
    _snapshotTimer = null;
    if (identical(player.partyControls, this)) player.partyControls = null;
    if (showing && stopPlayer) unawaited(player.stop());
  }

  // -- Events

  TogetherClient? _ensureClient() {
    final existing = _client;
    if (existing != null) return existing;
    try {
      final client = _createClient();
      _client = client;
      _events = client.events.listen(_onEvent);
      return client;
    } catch (error) {
      debugPrint('[together] unavailable (${error.runtimeType})');
      _set(const TogetherEnded("Watch Together isn't available here."));
      return null;
    }
  }

  void _onEvent(TogetherEvent event) {
    switch (event) {
      case TogetherRoom():
        _room = event.roomId;
        _phoneJoin?.complete(WatchTogetherJoinReply.joined(
          member: event.member,
          boxKey: event.boxKey,
          signKey: event.signKey,
        ));
        _phoneJoin = null;
        _set(TogetherInParty(Party(
          roomId: event.roomId,
          invite: event.invite,
          relay: event.relay,
          me: event.member,
          host: event.host,
          watching: _watchOnJoin,
        )));
      case TogetherPlayer(:final command):
        _onPlayerCommand(command);
      case TogetherConnected():
        _updateParty((p) => p.copyWith(connected: true, notice: () => null));
      case TogetherDisconnected():
        _updateParty(
            (p) => p.copyWith(connected: false, notice: () => 'Reconnecting…'));
      case TogetherMember():
        _updateParty((p) => p.copyWith(members: {
              ...p.members,
              event.member:
                  PartyMember(event.name, event.kind, host: event.host),
            }));
      case TogetherMemberLeft(:final member):
        _updateParty((p) => p.copyWith(
              members: Map.of(p.members)..remove(member),
              own: p.own.difference({member}),
            ));
      case TogetherVideo(:final item):
        _updateParty((p) => p.copyWith(item: item, notice: () => null));
      case TogetherTimeline():
        _updateParty((p) => p.copyWith(
              playing: event.playing,
              positionMs: event.positionMs,
              positionAt: clock(),
            ));
      case TogetherWaiting(:final members):
        _updateParty((p) => p.copyWith(waitingFor: members));
      case TogetherContinueWithoutAvailable(:final members):
        _updateParty((p) => p.copyWith(continueWithout: members));
      case TogetherLocked(:final locked):
        _updateParty((p) => p.copyWith(locked: locked));
      case TogetherPolicyChanged(:final policy):
        _updateParty((p) => p.copyWith(policy: policy));
      case TogetherPlayRequested():
        _updateParty((p) => p.copyWith(requests: [
              ...p.requests,
              PartyRequest(event.request, event.from, event.title, event.site),
            ]));
      case TogetherRequestAnswered(:final allowed):
        _toast(allowed ? 'The host is playing your video' : 'The host said no');
      case TogetherRequestExpired(:final request):
        _updateParty((p) => p.copyWith(
            requests: p.requests.where((r) => r.id != request).toList()));
        _toast("The host didn't respond");
      case TogetherWarning(:final message):
        _updateParty((p) => p.copyWith(notice: () => message));
      case TogetherPairingCode():
        if (_state is TogetherRegistering || _state is TogetherShowingCode) {
          _set(TogetherShowingCode(PartyCode(
            event.code,
            event.qrSize,
            event.qrModules,
            event.expiresAtMs,
          )));
        }
      case TogetherPairingInvite():
        if (!isInParty) _set(TogetherInvited(event.from, event.relay));
      case TogetherPairSent():
        _toast('Invite sent. Confirm on the TV.');
      case TogetherError():
        _onError(event);
      case TogetherStopped():
        _onStopped(event);
      case TogetherRejected(:final reason):
        debugPrint('[together] dropped a room message: $reason');
      case TogetherMemberJoined() || TogetherCredentialsReceived():
        break;
    }
  }

  void _onError(TogetherError event) {
    debugPrint('[together] ${event.context} failed');
    switch (event.context) {
      case 'pair_send':
        _toast("Couldn't invite that TV: ${event.message}");
      case 'host' || 'join':
        _phoneJoin
            ?.complete(const WatchTogetherJoinReply.failed('invalid_invite'));
        _phoneJoin = null;
        _set(TogetherEnded(
            event.message.isEmpty ? "Couldn't join." : event.message));
      case 'pair':
        if (!isInParty) {
          _set(TogetherEnded(event.message.isEmpty
              ? 'The pairing code failed.'
              : event.message));
        }
    }
  }

  void _onStopped(TogetherStopped event) {
    // A room this computer already left. No room ID: the engine itself stopped.
    if (event.roomId.isNotEmpty && event.roomId != _room) return;
    _room = null;
    _stopWatchingPlayer(stopPlayer: true);
    if (_state is TogetherInParty || _state is TogetherStarting) {
      _set(switch (event.reason) {
        TogetherStop.left => const TogetherIdle(),
        TogetherStop.roomFull =>
          const TogetherEnded('This watch party is full.'),
        TogetherStop.expired =>
          const TogetherEnded('This watch party has ended.'),
        TogetherStop.unauthorized =>
          const TogetherEnded("This invite isn't valid any more."),
        TogetherStop.replaced =>
          const TogetherEnded('This computer joined the party somewhere else.'),
        TogetherStop.error =>
          const TogetherEnded('The watch party connection failed.'),
      });
    }
  }

  void _updateParty(Party Function(Party) change) {
    final current = _state;
    if (current is TogetherInParty) {
      _set(TogetherInParty(change(current.party)));
    }
  }

  void _set(TogetherState next) {
    _state = next;
    notifyListeners();
  }

  void _toast(String message) {
    if (!_toasts.isClosed) _toasts.add(message);
  }

  TogetherDevice _device(bool screen) =>
      TogetherDevice(_deviceName(), TogetherDeviceKind.desktop, screen: screen);

  static String? _nonEmpty(String? value) {
    final trimmed = value?.trim();
    return trimmed == null || trimmed.isEmpty ? null : trimmed;
  }

  @override
  void dispose() {
    player.removeListener(_onPlayerChanged);
    _stopWatchingPlayer();
    _phoneJoin?.complete(const WatchTogetherJoinReply.failed('unavailable'));
    _phoneJoin = null;
    unawaited(_events?.cancel());
    _client?.close();
    _client = null;
    unawaited(_toasts.close());
    super.dispose();
  }
}
