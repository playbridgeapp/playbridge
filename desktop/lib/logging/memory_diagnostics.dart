import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:io';
import 'dart:isolate';

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';

import 'log_store.dart';

/// One bounded snapshot every 30 seconds while the user opts into logging.
/// Reads counters only: no heap dumps, GC requests, URLs, or media titles.
class MemoryDiagnostics {
  MemoryDiagnostics({
    required this.enabled,
    required this.readSnapshot,
    required this.write,
    this.interval = const Duration(seconds: 30),
  });

  final ValueListenable<bool> enabled;
  final Future<Map<String, Object?>> Function() readSnapshot;
  final void Function(LogLevel level, String message) write;
  final Duration interval;
  final Stopwatch _uptime = Stopwatch();
  Timer? _timer;
  Timer? _transitionTimer;
  bool _started = false;
  bool _disposed = false;
  bool _reading = false;
  int _generation = 0;
  int? _baseline;
  int? _previous;
  int _peak = 0;
  Object? _playbackKey;

  void start() {
    if (_started || _disposed) return;
    _started = true;
    _uptime.start();
    enabled.addListener(_onEnabledChanged);
    _onEnabledChanged();
  }

  void _onEnabledChanged() {
    _generation++;
    _timer?.cancel();
    _timer = null;
    _transitionTimer?.cancel();
    _baseline = _previous = null;
    _peak = 0;
    if (!enabled.value || _disposed) return;
    unawaited(sample(reason: 'logging_enabled'));
    _timer = Timer.periodic(interval, (_) => unawaited(sample()));
  }

  /// Coarse state/session/engine changes, never position ticks or every frame.
  void playbackChanged(Object key) {
    if (key == _playbackKey) return;
    _playbackKey = key;
    if (_disposed || !enabled.value) return;
    _transitionTimer?.cancel();
    _transitionTimer = Timer(const Duration(seconds: 1),
        () => unawaited(sample(reason: 'playback_changed')));
  }

  Future<void> sample({String reason = 'periodic'}) async {
    if (_disposed || !enabled.value || _reading) return;
    _reading = true;
    final generation = _generation;
    try {
      final snapshot = await readSnapshot();
      if (_disposed || !enabled.value || generation != _generation) return;
      final memory = snapshot['footprintBytes'] ?? snapshot['rssBytes'];
      if (memory is! int) return;
      _baseline ??= memory;
      final growth = _previous == null ? 0 : memory - _previous!;
      _previous = memory;
      if (memory > _peak) _peak = memory;
      final high = memory >= 2 * 1024 * 1024 * 1024;
      final rapid = growth >= 512 * 1024 * 1024;
      write(
          high || rapid ? LogLevel.warn : LogLevel.info,
          jsonEncode({
            ...snapshot,
            'reason': reason,
            'uptimeSeconds': _uptime.elapsed.inSeconds,
            'metric': snapshot['footprintBytes'] is int ? 'footprint' : 'rss',
            'baselineBytes': _baseline,
            'growthBytes': growth,
            'observedPeakBytes': _peak,
            'highMemory': high,
            'rapidGrowth': rapid,
          }));
    } on Object {
      // An unavailable player/service must never interrupt playback or persist
      // exception text, which could contain a media URL.
      if (!_disposed && enabled.value && generation == _generation) {
        write(LogLevel.debug, 'Memory snapshot unavailable');
      }
    } finally {
      _reading = false;
    }
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _generation++;
    _timer?.cancel();
    _transitionTimer?.cancel();
    if (_started) enabled.removeListener(_onEnabledChanged);
    _uptime.stop();
  }
}

const _memoryChannel = MethodChannel('com.playbridge.desktop/memory');

Future<Map<String, Object?>> readProcessMemory() async {
  final result = <String, Object?>{
    'pid': pid,
    'platform': Platform.operatingSystem,
    'buildMode':
        kReleaseMode ? 'release' : (kProfileMode ? 'profile' : 'debug'),
    'dartVersion': Platform.version.split(' ').first,
    'rssBytes': ProcessInfo.currentRss,
    'peakRssBytes': ProcessInfo.maxRss,
  };
  if (Platform.isMacOS) {
    try {
      final native = await _memoryChannel
          .invokeMapMethod<String, Object?>('snapshot')
          .timeout(const Duration(seconds: 1));
      for (final key in ['footprintBytes', 'peakFootprintBytes']) {
        if (native?[key] is int) result[key] = native![key];
      }
    } on Object {
      // Other platforms/older runners still provide RSS counters.
    }
  }
  final cache = PaintingBinding.instance.imageCache;
  result.addAll({
    'imageCacheBytes': cache.currentSizeBytes,
    'cachedImages': cache.currentSize,
    'liveImages': cache.liveImageCount,
    'pendingImages': cache.pendingImageCount,
  });
  final dartMemory = await _readDartMemory();
  result['dartMemoryAvailable'] = dartMemory.isNotEmpty;
  result.addAll(dartMemory);
  return result;
}

Future<Map<String, Object?>> _readDartMemory() async {
  if (kReleaseMode) return const {};
  final client = HttpClient()..connectionTimeout = const Duration(seconds: 1);
  Future<Map<String, Object?>> read() async {
    final service =
        await developer.Service.getInfo().timeout(const Duration(seconds: 1));
    final isolate = developer.Service.getIsolateId(Isolate.current);
    final base = service.serverUri;
    if (base == null || isolate == null) return const {};
    // Keep the VM service authentication URI in memory only.
    final uri = base.resolve('getMemoryUsage').replace(
      queryParameters: {'isolateId': isolate},
    );
    final request =
        await client.getUrl(uri).timeout(const Duration(seconds: 1));
    final response = await request.close().timeout(const Duration(seconds: 1));
    if (response.statusCode != 200) return const {};
    final bytes = <int>[];
    await for (final chunk in response.timeout(const Duration(seconds: 1))) {
      if (bytes.length + chunk.length > 64 * 1024) return const {};
      bytes.addAll(chunk);
    }
    final decoded = jsonDecode(utf8.decode(bytes));
    if (decoded is! Map || decoded['result'] is! Map) return const {};
    final data = decoded['result'] as Map;
    return {
      for (final entry in const {
        'heapUsage': 'dartMainHeapBytes',
        'heapCapacity': 'dartMainHeapCapacityBytes',
        'externalUsage': 'dartMainExternalBytes',
      }.entries)
        if (data[entry.key] is int) entry.value: data[entry.key],
    };
  }

  try {
    return await read().timeout(const Duration(seconds: 2));
  } on Object {
    return const {};
  } finally {
    client.close(force: true);
  }
}
