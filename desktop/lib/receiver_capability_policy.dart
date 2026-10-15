import 'dart:convert';
import 'protocol.dart';

/// Typed representation of a Stage 5 v1 ReceiverCapabilities descriptor.
class ReceiverDescriptor {
  final int version;
  final Map<String, ReceiverSurface> surfaces;
  final ReceiverVolume? volume;
  final ReceiverHost? host;
  final Set<String> companionHints;

  const ReceiverDescriptor({
    required this.version,
    required this.surfaces,
    this.volume,
    this.host,
    this.companionHints = const {},
  });
}

class ReceiverSurface {
  final Map<String, ReceiverEngine> engines;

  const ReceiverSurface({required this.engines});
}

class ReceiverEngine {
  final Set<String> transport;
  final Set<String> keys;
  final Set<String> pointer;
  final Set<String> imageTransforms;
  final Set<String> text;
  final Set<String> settings;
  final Set<String> tracks;
  final Set<String> queue;
  final Set<String> browser;
  final Set<String> reporting;

  const ReceiverEngine({
    this.transport = const {},
    this.keys = const {},
    this.pointer = const {},
    this.imageTransforms = const {},
    this.text = const {},
    this.settings = const {},
    this.tracks = const {},
    this.queue = const {},
    this.browser = const {},
    this.reporting = const {},
  });
}

class ReceiverVolume {
  final String scope;
  final Set<String> operations;
  final double? step;
  final String? fallbackScope;

  const ReceiverVolume({
    required this.scope,
    this.operations = const {},
    this.step,
    this.fallbackScope,
  });
}

class ReceiverHost {
  final String? osFamily;
  final String? deviceClass;

  const ReceiverHost({this.osFamily, this.deviceClass});
}

class ReceiverVolumeState {
  final String scope;
  final double? level;
  final bool? muted;

  const ReceiverVolumeState({
    required this.scope,
    this.level,
    this.muted,
  });
}

class ReceiverSettingsAvailability {
  final bool? subtitleOffsetAvailable;
  final bool? engineAvailable;
  final bool? loopAvailable;
  final bool? speedAvailable;
  final bool? scalingAvailable;
  final bool? audioBoostAvailable;
  final bool? qualityAvailable;

  const ReceiverSettingsAvailability({
    this.subtitleOffsetAvailable,
    this.engineAvailable,
    this.loopAvailable,
    this.speedAvailable,
    this.scalingAvailable,
    this.audioBoostAvailable,
    this.qualityAvailable,
  });
}

/// Session-scoped metadata for an authenticated receiver connection.
class ReceiverProtocolState {
  final ReceiverDescriptor? descriptor;
  final bool malformedV1;
  final ReceiverVolumeState? volume;
  final ReceiverSettingsAvailability settingsAvailability;
  final String? contextEngine;

  const ReceiverProtocolState({
    this.descriptor,
    this.malformedV1 = false,
    this.volume,
    this.settingsAvailability = const ReceiverSettingsAvailability(),
    this.contextEngine,
  });

  ReceiverProtocolState copyWith({
    ReceiverDescriptor? descriptor,
    bool clearDescriptor = false,
    bool? malformedV1,
    ReceiverVolumeState? volume,
    ReceiverSettingsAvailability? settingsAvailability,
    String? contextEngine,
  }) {
    return ReceiverProtocolState(
      descriptor: clearDescriptor ? null : (descriptor ?? this.descriptor),
      malformedV1: malformedV1 ?? this.malformedV1,
      volume: volume ?? this.volume,
      settingsAvailability: settingsAvailability ?? this.settingsAvailability,
      contextEngine: contextEngine ?? this.contextEngine,
    );
  }
}

/// One owner for authenticated metadata and live capability updates.
/// Session generation guards asynchronous retired-socket callbacks.
class ReceiverProtocolSession {
  int _generation = 0;
  bool _authenticated = false;
  bool _supportsUpdates = false;
  bool _subscribed = false;
  int _revision = 0;
  ReceiverProtocolState _state = const ReceiverProtocolState();

  int get generation => _generation;
  bool get isAuthenticated => _authenticated;
  bool get supportsUpdates => _supportsUpdates;
  bool get isSubscribed => _subscribed;
  int get revision => _revision;
  ReceiverProtocolState get state => _state;

  /// Begin a new session; invalidates any previous session callbacks and resets revision watermark.
  int begin([int? session]) {
    if (session != null) {
      _generation = session;
    } else {
      _generation++;
    }
    _authenticated = false;
    _supportsUpdates = false;
    _subscribed = false;
    _revision = 0;
    _state = const ReceiverProtocolState();
    return _generation;
  }

  /// Clears session metadata on disconnect or receiver switch.
  void clear([int? session]) {
    begin(session);
  }

  /// Called only after successful auth or verified decryption/pin checks of first-pairing credentials.
  bool acceptCredentials(int session, Map<dynamic, dynamic> envelope) {
    if (session != _generation) return false;
    _authenticated = true;
    final features = envelope['features'];
    _supportsUpdates = features is List &&
        features.any((f) => f.toString() == receiverCapabilitiesV1Feature);

    // Repeated credentials on the same socket must not rewind a live revision
    // or opt in a second time. Only begin()/clear() establish a new baseline.
    if (!_supportsUpdates) _subscribed = false;

    final raw = envelope['capabilities'];
    final recognized = ReceiverDescriptorParser.recognizedV1(raw);
    final descriptor = recognized ? ReceiverDescriptorParser.parse(raw) : null;
    final malformedV1 = recognized && descriptor == null;

    _state = ReceiverProtocolState(
      descriptor: descriptor,
      malformedV1: malformedV1,
    );
    return true;
  }

  /// Called after authenticated metadata is accepted, including protected first pairing.
  String? subscription(int session) {
    if (session != _generation ||
        !_authenticated ||
        !_supportsUpdates ||
        _subscribed) {
      return null;
    }
    _subscribed = true;
    return senderReceiverCapabilitiesSubscribeJson();
  }

  /// Observes an incoming protocol envelope on the authenticated socket.
  bool observe(int session, Map<dynamic, dynamic> frame) {
    if (session != _generation || !_authenticated) return false;
    final type = frame['type'];
    if (type == 'receiver_capabilities') {
      if (!_subscribed) return false;
      final keys = frame.keys.map((k) => k.toString()).toSet();
      if (keys.length != 3 ||
          !keys.containsAll(['type', 'revision', 'capabilities'])) {
        return false;
      }
      final rev = frame['revision'];
      if (rev is! num || rev is bool) return false;
      final intVal = rev.toInt();
      if (intVal != rev) return false;
      if (intVal < 1 ||
          intVal > maxReceiverCapabilitiesRevision ||
          intVal <= _revision) {
        return false;
      }
      final raw = frame['capabilities'];
      if (raw is! Map) return false;
      final version = raw['version'];
      if (version is! num && version is! String) return false;

      // Even an opaque future version advances ordering, so a delayed older v1
      // cannot replace the current snapshot after a newer revision was observed.
      _revision = intVal;
      if (!ReceiverDescriptorParser.recognizedV1(raw)) {
        // Keep current descriptor
        return true;
      }
      final descriptor = ReceiverDescriptorParser.parse(raw);
      _state = _state.copyWith(
        descriptor: descriptor,
        malformedV1: descriptor == null,
        clearDescriptor: descriptor == null,
      );
      return true;
    } else if (type == 'volume_state') {
      final parsed = _parseVolume(frame);
      if (parsed != null) {
        final prev =
            _state.volume?.scope == parsed.scope ? _state.volume : null;
        _state = _state.copyWith(
          volume: ReceiverVolumeState(
            scope: parsed.scope,
            level: parsed.level ?? prev?.level,
            muted: parsed.muted ?? prev?.muted,
          ),
        );
        return true;
      }
    } else if (type == 'player_settings') {
      final eng = frame['engine']?.toString();
      _state = _state.copyWith(
        contextEngine:
            (eng != null && eng.isNotEmpty) ? eng : _state.contextEngine,
        settingsAvailability: ReceiverSettingsAvailability(
          subtitleOffsetAvailable: (frame['subtitleOffsetAvailable'] ??
              frame['subtitle_offset_available']) as bool?,
          engineAvailable:
              (frame['engineAvailable'] ?? frame['engine_available']) as bool?,
          loopAvailable:
              (frame['loopAvailable'] ?? frame['loop_available']) as bool?,
          speedAvailable:
              (frame['speedAvailable'] ?? frame['speed_available']) as bool?,
          scalingAvailable: (frame['scalingAvailable'] ??
              frame['scaling_available']) as bool?,
          audioBoostAvailable: (frame['audioBoostAvailable'] ??
              frame['audio_boost_available']) as bool?,
          qualityAvailable: (frame['qualityAvailable'] ??
              frame['quality_available']) as bool?,
        ),
      );
      return true;
    } else if (type == 'context') {
      final eng = frame['engine']?.toString();
      if (eng != null && eng.isNotEmpty) {
        _state = _state.copyWith(contextEngine: eng);
        return true;
      }
    }
    return false;
  }

  ReceiverVolumeState? _parseVolume(Map<dynamic, dynamic> frame) {
    try {
      final scope = frame['scope']?.toString();
      if (scope == null || scope.isEmpty) return null;
      if (!frame.containsKey('level') && !frame.containsKey('muted')) {
        return null;
      }
      final level = (frame['level'] as num?)?.toDouble();
      final muted = frame['muted'] as bool?;
      return ReceiverVolumeState(
        scope: scope,
        level: level,
        muted: muted,
      );
    } catch (_) {
      return null;
    }
  }
}

/// Pure parser and validator for Stage 5 ReceiverCapabilities v1.
class ReceiverDescriptorParser {
  static const int maxBytes = 64 * 1024;
  static const Set<String> operationFields = {
    'transport',
    'keys',
    'pointer',
    'imageTransforms',
    'text',
    'settings',
    'tracks',
    'queue',
    'browser',
    'reporting',
  };

  /// Returns true if the raw payload contains a recognized numeric version 1.
  static bool recognizedV1(dynamic raw) {
    if (raw is! Map) return false;
    final version = raw['version'];
    return version is num && version == 1 && version is! String;
  }

  /// Parses and validates a bounded v1 descriptor. Returns null on failure.
  static ReceiverDescriptor? parse(dynamic raw) {
    try {
      if (raw is! Map) return null;
      if (!recognizedV1(raw)) return null;

      final map = raw.cast<dynamic, dynamic>();
      final serialized = jsonEncode(map);
      if (utf8.encode(serialized).length > maxBytes) return null;

      const allowedTop = {
        'version',
        'surfaces',
        'volume',
        'host',
        'companionHints'
      };
      for (final k in map.keys) {
        if (!allowedTop.contains(k)) return null;
      }

      final surfacesRaw = map['surfaces'];
      if (surfacesRaw is! Map || surfacesRaw.length > 16) return null;

      final surfaces = <String, ReceiverSurface>{};
      for (final entry in surfacesRaw.entries) {
        final surfaceId = _identifier(entry.key);
        if (surfaceId == null) return null;
        final surfaceObj = entry.value;
        if (surfaceObj is! Map) return null;
        for (final k in surfaceObj.keys) {
          if (k != 'engines') return null;
        }
        final enginesRaw = surfaceObj['engines'];
        if (enginesRaw is! Map || enginesRaw.length > 16) return null;

        final engines = <String, ReceiverEngine>{};
        for (final engEntry in enginesRaw.entries) {
          final engineId = _identifier(engEntry.key);
          if (engineId == null) return null;
          final engObj = engEntry.value;
          if (engObj is! Map) return null;
          for (final k in engObj.keys) {
            if (!operationFields.contains(k)) return null;
          }

          engines[engineId] = ReceiverEngine(
            transport: _identifiers(engObj['transport']) ?? const {},
            keys: _identifiers(engObj['keys']) ?? const {},
            pointer: _identifiers(engObj['pointer']) ?? const {},
            imageTransforms:
                _identifiers(engObj['imageTransforms']) ?? const {},
            text: _identifiers(engObj['text']) ?? const {},
            settings: _identifiers(engObj['settings']) ?? const {},
            tracks: _identifiers(engObj['tracks']) ?? const {},
            queue: _identifiers(engObj['queue']) ?? const {},
            browser: _identifiers(engObj['browser']) ?? const {},
            reporting: _identifiers(engObj['reporting']) ?? const {},
          );
          if (engObj['transport'] != null &&
              engines[engineId]!.transport.isEmpty &&
              (engObj['transport'] as List).isNotEmpty) {
            return null;
          }
        }
        surfaces[surfaceId] = ReceiverSurface(engines: engines);
      }

      ReceiverVolume? volume;
      if (map.containsKey('volume')) {
        final volObj = map['volume'];
        if (volObj is! Map) return null;
        const allowedVol = {'scope', 'operations', 'step', 'fallbackScope'};
        for (final k in volObj.keys) {
          if (!allowedVol.contains(k)) return null;
        }
        final scope = _identifier(volObj['scope']);
        if (scope == null) return null;
        final ops = _identifiers(volObj['operations']);
        if (ops == null) return null;

        double? step;
        if (volObj.containsKey('step')) {
          final s = volObj['step'];
          if (s is! num || s is String || s <= 0 || s > 1.0) return null;
          step = s.toDouble();
        }

        String? fallbackScope;
        if (volObj.containsKey('fallbackScope')) {
          fallbackScope = _identifier(volObj['fallbackScope']);
          if (fallbackScope == null) return null;
        }

        volume = ReceiverVolume(
          scope: scope,
          operations: ops,
          step: step,
          fallbackScope: fallbackScope,
        );
      }

      ReceiverHost? host;
      if (map.containsKey('host')) {
        final hostObj = map['host'];
        if (hostObj is! Map) return null;
        const allowedHost = {'osFamily', 'deviceClass'};
        for (final k in hostObj.keys) {
          if (!allowedHost.contains(k)) return null;
        }
        String? osFamily;
        if (hostObj.containsKey('osFamily')) {
          osFamily = _identifier(hostObj['osFamily']);
          if (osFamily == null) return null;
        }
        String? deviceClass;
        if (hostObj.containsKey('deviceClass')) {
          deviceClass = _identifier(hostObj['deviceClass']);
          if (deviceClass == null) return null;
        }
        host = ReceiverHost(osFamily: osFamily, deviceClass: deviceClass);
      }

      Set<String> companionHints = const {};
      if (map.containsKey('companionHints')) {
        final hints = _identifiers(map['companionHints']);
        if (hints == null) return null;
        companionHints = hints;
      }

      return ReceiverDescriptor(
        version: 1,
        surfaces: surfaces,
        volume: volume,
        host: host,
        companionHints: companionHints,
      );
    } catch (_) {
      return null;
    }
  }

  static String? _identifier(dynamic value) {
    if (value is! String) return null;
    if (value.isEmpty || value.length > 64) return null;
    return value;
  }

  static Set<String>? _identifiers(dynamic value) {
    if (value == null) return const {};
    if (value is! List || value.length > 64) return null;
    final result = <String>{};
    for (final item in value) {
      final id = _identifier(item);
      if (id == null) return null;
      if (result.contains(id)) return null; // Duplicate check
      result.add(id);
    }
    return result;
  }
}

/// The pure resolver's output describing all authorized sender actions and labels.
class ResolvedReceiverPolicy {
  final bool isLegacy;
  final bool isMalformedV1;
  final bool isDescriptorV1;

  // Transport
  final bool canPlayPause;
  final bool canPlay;
  final bool canPause;
  final bool canStop;
  final bool canSeekAbsolute;
  final bool canSeekRelative;

  // Remote Keys
  final Set<String> supportedKeys;

  // Volume
  final bool hasVolume;
  final String? volumeScope;
  final String? fallbackScope;
  final String? volumeLabel;
  final bool canAdjustVolume;
  final bool canSetVolume;
  final bool canToggleMute;
  final double? volumeStep;
  final double? currentVolumeLevel;
  final bool? isMuted;

  // Settings
  final bool canLoop;
  final bool canSwitchEngine;
  final Set<String> availablePlayerEngines;
  final bool canSpeed;
  final bool canScaling;
  final bool canQuality;
  final bool canAudioBoost;
  final bool canSubtitleOffset;

  // Queue
  final bool canPlaylistJumpIndex;
  final bool canPlaylistJumpItem;
  final bool canQueueAppend;
  final bool canQueueRemove;
  final bool canQueueMove;
  final bool canQueueClear;

  // Tracks
  final bool canSelectAudio;
  final bool canSelectSubtitle;
  final bool canAddSubtitleUrl;
  final bool canAddSubtitleResource;

  // Browser
  final bool canLaunchBrowser;
  final Set<String> launchBrowserEngines;
  final bool canBrowserForward;
  final bool canBrowserReload;
  final bool canBrowserCycleSource;
  final bool canBrowserUnmute;
  final bool canBrowserFullscreen;
  final bool canManageUserScripts;
  final bool canManageUserAgent;

  // Pointer & Image
  final bool canPointerMove;
  final bool canPointerClick;
  final bool canPointerDrag;
  final bool canPointerScroll;
  final bool canPointerZoom;
  final bool canImagePan;
  final bool canImageZoom;
  final bool canImageRotate;
  final bool canImageAnchor;
  final bool canImageReset;

  const ResolvedReceiverPolicy({
    required this.isLegacy,
    required this.isMalformedV1,
    required this.isDescriptorV1,
    this.canPlayPause = false,
    this.canPlay = false,
    this.canPause = false,
    this.canStop = false,
    this.canSeekAbsolute = false,
    this.canSeekRelative = false,
    this.supportedKeys = const {},
    this.hasVolume = false,
    this.volumeScope,
    this.fallbackScope,
    this.volumeLabel,
    this.canAdjustVolume = false,
    this.canSetVolume = false,
    this.canToggleMute = false,
    this.volumeStep,
    this.currentVolumeLevel,
    this.isMuted,
    this.canLoop = false,
    this.canSwitchEngine = false,
    this.availablePlayerEngines = const {},
    this.canSpeed = false,
    this.canScaling = false,
    this.canQuality = false,
    this.canAudioBoost = false,
    this.canSubtitleOffset = false,
    this.canPlaylistJumpIndex = false,
    this.canPlaylistJumpItem = false,
    this.canQueueAppend = false,
    this.canQueueRemove = false,
    this.canQueueMove = false,
    this.canQueueClear = false,
    this.canSelectAudio = false,
    this.canSelectSubtitle = false,
    this.canAddSubtitleUrl = false,
    this.canAddSubtitleResource = false,
    this.canLaunchBrowser = false,
    this.launchBrowserEngines = const {},
    this.canBrowserForward = false,
    this.canBrowserReload = false,
    this.canBrowserCycleSource = false,
    this.canBrowserUnmute = false,
    this.canBrowserFullscreen = false,
    this.canManageUserScripts = false,
    this.canManageUserAgent = false,
    this.canPointerMove = false,
    this.canPointerClick = false,
    this.canPointerDrag = false,
    this.canPointerScroll = false,
    this.canPointerZoom = false,
    this.canImagePan = false,
    this.canImageZoom = false,
    this.canImageRotate = false,
    this.canImageAnchor = false,
    this.canImageReset = false,
  });

  bool canSendKey(String key) => supportedKeys.contains(key);
  bool get isAppScopeVolume => volumeScope == 'app';
}

/// Compute the intersection of all operation sets across a collection of engines.
ReceiverEngine intersectEngines(Iterable<ReceiverEngine> engines) {
  if (engines.isEmpty) return const ReceiverEngine();
  Set<String> common(Set<String> Function(ReceiverEngine) select) {
    Set<String>? acc;
    for (final e in engines) {
      final s = select(e);
      acc = acc == null ? Set<String>.from(s) : acc.intersection(s);
    }
    return acc ?? const {};
  }

  return ReceiverEngine(
    transport: common((e) => e.transport),
    keys: common((e) => e.keys),
    pointer: common((e) => e.pointer),
    imageTransforms: common((e) => e.imageTransforms),
    text: common((e) => e.text),
    settings: common((e) => e.settings),
    tracks: common((e) => e.tracks),
    queue: common((e) => e.queue),
    browser: common((e) => e.browser),
    reporting: common((e) => e.reporting),
  );
}

/// Pure resolver: maps raw descriptor + runtime state to authorized operations.
ResolvedReceiverPolicy resolveReceiverCapabilityPolicy({
  required dynamic rawDescriptor,
  bool malformedV1 = false,
  String? activeSurface,
  String? activeEngine,
  List<String> legacyPlayers = const [],
  Set<String> legacyFeatures = const {},
  ReceiverSettingsAvailability settingsAvailability =
      const ReceiverSettingsAvailability(),
  ReceiverVolumeState? volumeState,
  String? mediaKind,
  bool isLive = false,
  bool hasDuration = true,
  bool isPlaying = false,
}) {
  final bool recognized;
  final ReceiverDescriptor? descriptor;
  final bool isMalformed;

  if (rawDescriptor is ReceiverDescriptor) {
    recognized = true;
    descriptor = rawDescriptor;
    isMalformed = malformedV1;
  } else {
    recognized = ReceiverDescriptorParser.recognizedV1(rawDescriptor);
    descriptor =
        recognized ? ReceiverDescriptorParser.parse(rawDescriptor) : null;
    isMalformed = malformedV1 || (recognized && descriptor == null);
  }

  // 1. Absent or unknown version => frozen legacy Desktop sender behavior.
  if (!recognized && descriptor == null && !isMalformed) {
    final isImage = mediaKind == 'image';
    final seekable = !isImage && !isLive && hasDuration;
    return ResolvedReceiverPolicy(
      isLegacy: true,
      isMalformedV1: false,
      isDescriptorV1: false,
      canPlayPause: true,
      canPlay: true,
      canPause: true,
      canStop: true,
      canSeekAbsolute: seekable,
      canSeekRelative: seekable,
      supportedKeys: const {},
      hasVolume: true,
      volumeScope: 'system',
      fallbackScope: 'app',
      volumeLabel: 'TV volume',
      canAdjustVolume: true,
      canSetVolume: false,
      canToggleMute: false,
      canLoop: false,
      canSwitchEngine: false,
      canPlaylistJumpIndex: true,
      canQueueAppend: true,
    );
  }

  // 2. Recognized v1 but malformed => fail closed.
  if (isMalformed || descriptor == null) {
    return const ResolvedReceiverPolicy(
      isLegacy: false,
      isMalformedV1: true,
      isDescriptorV1: false,
    );
  }

  // 3. Valid v1 descriptor => declared operations only.
  final isPlayer = activeSurface == null || activeSurface == 'player';
  final isBrowser = activeSurface == 'browser';
  final surfaceKey = isPlayer ? 'player' : (isBrowser ? 'browser' : null);

  final engines = surfaceKey != null
      ? (descriptor.surfaces[surfaceKey]?.engines ?? const {})
      : const <String, ReceiverEngine>{};

  // Engine selection:
  // 1) activeEngine if in engines
  // 2) if player and single engine in descriptor, use it
  // 3) otherwise intersection of ALL engines on this surface
  String? candidate = activeEngine != null && engines.containsKey(activeEngine)
      ? activeEngine
      : null;
  if (candidate == null && isPlayer) {
    if (engines.length == 1) {
      candidate = engines.keys.single;
    } else if (legacyPlayers.isNotEmpty) {
      final matching = legacyPlayers.where(engines.containsKey).toSet();
      if (matching.length == 1) candidate = matching.single;
    }
  }

  final ReceiverEngine ops = candidate != null
      ? (engines[candidate] ?? const ReceiverEngine())
      : intersectEngines(engines.values);

  final isImage = mediaKind == 'image';
  final isVideo = mediaKind == 'video';
  final seekable = !isImage && !isLive && hasDuration;

  // Transport
  final canPlayPause = isPlayer
      ? (ops.transport.contains('toggle') ||
          (isPlaying
              ? ops.transport.contains('pause')
              : ops.transport.contains('play')))
      : (isBrowser && ops.transport.contains('toggle'));
  final canPlay =
      ops.transport.contains('play') || ops.transport.contains('toggle');
  final canPause =
      ops.transport.contains('pause') || ops.transport.contains('toggle');
  final canStop = isPlayer && ops.transport.contains('stop');
  final canSeekAbsolute = seekable && ops.transport.contains('seek_absolute');
  final canSeekRelative = seekable && ops.transport.contains('seek_relative');

  // Keys
  const knownNavKeys = {
    'dpad_up',
    'dpad_down',
    'dpad_left',
    'dpad_right',
    'dpad_center',
    'back',
    'home',
    'key_enter',
  };
  final supportedKeys = ops.keys.intersection(knownNavKeys).toSet();

  // Volume
  final vol = descriptor.volume;
  final hasVolume = vol != null &&
      const {'system', 'app'}.contains(vol.scope) &&
      vol.operations.isNotEmpty;

  final volumeScope = hasVolume ? vol.scope : null;
  final fallbackScope = hasVolume ? vol.fallbackScope : null;
  final canAdjustVolume = hasVolume && vol.operations.contains('relative_step');
  final canSetVolume = hasVolume && vol.operations.contains('absolute');
  final canToggleMute = hasVolume && vol.operations.contains('mute_toggle');
  final volumeStep = hasVolume ? vol.step : null;

  if (canToggleMute) {
    supportedKeys.add('mute');
  }

  // Volume label:
  // App scope => 'App volume'
  // System scope => 'TV volume', unless reported volume state confirms actual app scope
  String? volumeLabel;
  if (hasVolume) {
    if (vol.scope == 'app') {
      volumeLabel = 'App volume';
    } else {
      final reportScope = volumeState?.scope;
      if (vol.fallbackScope == 'app' &&
          reportScope == 'app' &&
          (volumeState?.level != null || volumeState?.muted != null)) {
        volumeLabel = 'App volume';
      } else {
        volumeLabel = 'TV volume';
      }
    }
  }

  // Volume reporting
  double? currentVolumeLevel;
  bool? isMuted;
  if (hasVolume && volumeState != null) {
    final scopeMatches = volumeState.scope == vol.scope ||
        (vol.scope == 'system' &&
            vol.fallbackScope == 'app' &&
            volumeState.scope == 'app' &&
            canAdjustVolume);
    if (scopeMatches) {
      if (vol.operations.contains('level_report')) {
        currentVolumeLevel = volumeState.level;
      }
      if (vol.operations.contains('mute_report')) {
        isMuted = volumeState.muted;
      }
    }
  }

  // Settings (player only, non-image)
  final canLoop = isPlayer &&
      !isImage &&
      !isLive &&
      ops.settings.contains('loop') &&
      settingsAvailability.loopAvailable != false;

  final playerEngines =
      descriptor.surfaces['player']?.engines.keys.toSet() ?? const {};
  final canSwitchEngine = isPlayer &&
      !isImage &&
      ops.settings.contains('engine') &&
      settingsAvailability.engineAvailable != false &&
      playerEngines.length > 1;

  final canSpeed = isPlayer &&
      !isImage &&
      ops.settings.contains('speed') &&
      settingsAvailability.speedAvailable != false;

  final canScaling = isPlayer &&
      !isImage &&
      ops.settings.contains('scaling') &&
      settingsAvailability.scalingAvailable != false;

  final canQuality = isPlayer &&
      !isImage &&
      ops.settings.contains('quality') &&
      settingsAvailability.qualityAvailable != false;

  final canAudioBoost = isPlayer &&
      !isImage &&
      ops.settings.contains('audio_boost') &&
      settingsAvailability.audioBoostAvailable != false;

  final canSubtitleOffset = isPlayer &&
      !isImage &&
      ops.settings.contains('subtitle_offset') &&
      settingsAvailability.subtitleOffsetAvailable != false;

  // Queue
  final canPlaylistJumpIndex = isPlayer && ops.queue.contains('jump_index');
  final canPlaylistJumpItem = isPlayer && ops.queue.contains('jump_item');
  final canQueueAppend = isPlayer && ops.queue.contains('append');
  final canQueueRemove = isPlayer && ops.queue.contains('remove');
  final canQueueMove = isPlayer && ops.queue.contains('move');
  final canQueueClear = isPlayer && ops.queue.contains('clear');

  // Tracks
  final canSelectAudio =
      isPlayer && !isImage && ops.tracks.contains('audio_select');
  final canSelectSubtitle =
      isPlayer && isVideo && ops.tracks.contains('subtitle_select');
  final canAddSubtitleUrl =
      isPlayer && isVideo && ops.tracks.contains('subtitle_url_add');
  final canAddSubtitleResource = isPlayer &&
      isVideo &&
      ops.tracks.contains('subtitle_resource_add') &&
      legacyFeatures.contains('subtitle_resource_add_v1');

  // Browser
  final browserEngines = descriptor.surfaces['browser']?.engines ?? const {};
  final launchBrowserEngines = browserEngines.entries
      .where((e) => e.value.browser.contains('launch'))
      .map((e) => e.key)
      .toSet();
  final canLaunchBrowser = launchBrowserEngines.isNotEmpty;

  final canBrowserForward = isBrowser && ops.browser.contains('forward');
  final canBrowserReload = isBrowser && ops.browser.contains('reload');
  final canBrowserCycleSource =
      isBrowser && ops.browser.contains('cycle_source');
  final canBrowserUnmute = isBrowser && ops.browser.contains('unmute');
  final canBrowserFullscreen = isBrowser && ops.browser.contains('fullscreen');
  final canManageUserScripts =
      isBrowser && ops.browser.contains('manage_user_scripts');
  final canManageUserAgent =
      isBrowser && ops.browser.contains('manage_user_agent');

  // Pointer & Image transforms
  final canPointerMove = isBrowser && ops.pointer.contains('move');
  final canPointerClick = isBrowser && ops.pointer.contains('click');
  final canPointerDrag = isBrowser &&
      ops.pointer.contains('down') &&
      ops.pointer.contains('move') &&
      ops.pointer.contains('up');
  final canPointerScroll = isBrowser && ops.pointer.contains('scroll');
  final canPointerZoom = isBrowser && ops.pointer.contains('zoom');

  final canImagePan =
      isPlayer && isImage && ops.imageTransforms.contains('pan');
  final canImageZoom =
      isPlayer && isImage && ops.imageTransforms.contains('zoom');
  final canImageRotate =
      isPlayer && isImage && ops.imageTransforms.contains('rotate');
  final canImageAnchor =
      isPlayer && isImage && ops.imageTransforms.contains('anchor');
  final canImageReset =
      isPlayer && isImage && ops.imageTransforms.contains('reset');

  return ResolvedReceiverPolicy(
    isLegacy: false,
    isMalformedV1: false,
    isDescriptorV1: true,
    canPlayPause: canPlayPause,
    canPlay: canPlay,
    canPause: canPause,
    canStop: canStop,
    canSeekAbsolute: canSeekAbsolute,
    canSeekRelative: canSeekRelative,
    supportedKeys: supportedKeys,
    hasVolume: hasVolume,
    volumeScope: volumeScope,
    fallbackScope: fallbackScope,
    volumeLabel: volumeLabel,
    canAdjustVolume: canAdjustVolume,
    canSetVolume: canSetVolume,
    canToggleMute: canToggleMute,
    volumeStep: volumeStep,
    currentVolumeLevel: currentVolumeLevel,
    isMuted: isMuted,
    canLoop: canLoop,
    canSwitchEngine: canSwitchEngine,
    availablePlayerEngines: canSwitchEngine ? playerEngines : const {},
    canSpeed: canSpeed,
    canScaling: canScaling,
    canQuality: canQuality,
    canAudioBoost: canAudioBoost,
    canSubtitleOffset: canSubtitleOffset,
    canPlaylistJumpIndex: canPlaylistJumpIndex,
    canPlaylistJumpItem: canPlaylistJumpItem,
    canQueueAppend: canQueueAppend,
    canQueueRemove: canQueueRemove,
    canQueueMove: canQueueMove,
    canQueueClear: canQueueClear,
    canSelectAudio: canSelectAudio,
    canSelectSubtitle: canSelectSubtitle,
    canAddSubtitleUrl: canAddSubtitleUrl,
    canAddSubtitleResource: canAddSubtitleResource,
    canLaunchBrowser: canLaunchBrowser,
    launchBrowserEngines: launchBrowserEngines,
    canBrowserForward: canBrowserForward,
    canBrowserReload: canBrowserReload,
    canBrowserCycleSource: canBrowserCycleSource,
    canBrowserUnmute: canBrowserUnmute,
    canBrowserFullscreen: canBrowserFullscreen,
    canManageUserScripts: canManageUserScripts,
    canManageUserAgent: canManageUserAgent,
    canPointerMove: canPointerMove,
    canPointerClick: canPointerClick,
    canPointerDrag: canPointerDrag,
    canPointerScroll: canPointerScroll,
    canPointerZoom: canPointerZoom,
    canImagePan: canImagePan,
    canImageZoom: canImageZoom,
    canImageRotate: canImageRotate,
    canImageAnchor: canImageAnchor,
    canImageReset: canImageReset,
  );
}
