import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';

/// Actual output state; mute is independent of the level and may be unavailable.
class SystemVolumeState {
  const SystemVolumeState(this.level, this.muted);
  final double level;
  final bool? muted;
  Map<String, Object?> frame() => {
        'type': 'volume_state',
        'scope': 'system',
        'level': level,
        if (muted != null) 'muted': muted,
      };
}

SystemVolumeState? parseWpctlVolume(String output) {
  final match =
      RegExp(r'^\s*Volume:\s*([0-9]+(?:\.[0-9]+)?)\s*(\[MUTED\])?\s*$')
          .firstMatch(output);
  final level = match == null ? null : double.tryParse(match[1]!);
  if (level == null || !level.isFinite) return null;
  return SystemVolumeState(level.clamp(0.0, 1.0), match![2] != null);
}

SystemVolumeState? parsePactlVolume(String volume, String mute) {
  final values = RegExp(r'/\s*([0-9]+)%')
      .allMatches(volume)
      .map((m) => int.parse(m[1]!))
      .toList();
  final match = RegExp(r'^\s*Mute:\s*(yes|no)\s*$').firstMatch(mute);
  if (values.isEmpty || match == null) return null;
  final level = values.reduce((a, b) => a + b) / values.length / 100;
  return SystemVolumeState(level.clamp(0.0, 1.0), match[1] == 'yes');
}

SystemVolumeState? parseAmixerVolume(String output) {
  final lines =
      output.split('\n').where((line) => line.contains('Playback')).join('\n');
  final values = RegExp(r'\[([0-9]+)%\]')
      .allMatches(lines)
      .map((m) => int.parse(m[1]!))
      .toList();
  if (values.isEmpty) return null;
  final switches =
      RegExp(r'\[(on|off)\]').allMatches(lines).map((m) => m[1]).toList();
  final level = values.reduce((a, b) => a + b) / values.length / 100;
  return SystemVolumeState(level.clamp(0.0, 1.0),
      switches.isEmpty ? null : switches.every((v) => v == 'off'));
}

SystemVolumeState? parseWindowsVolume(Map<Object?, Object?>? value) {
  final level = value?['level'];
  final muted = value?['muted'];
  if (level is! num ||
      !level.isFinite ||
      level < 0 ||
      level > 1 ||
      muted is! bool) {
    return null;
  }
  return SystemVolumeState(level.toDouble(), muted);
}

/// No shell interpolation of user data; Windows queries use Core Audio, not a process.
class SystemVolume {
  static const stepPercent = 5;
  static const _channel = MethodChannel('playbridge/system_volume');
  static const _events = EventChannel('playbridge/system_volume/events');
  static bool macNativeAvailable = false;
  static bool get usesEvents => Platform.isMacOS;
  static SystemVolumeState? _macState(Map<Object?, Object?>? value) {
    if (value == null) return null;
    macNativeAvailable = value['available'] == true;
    final level = value['level'];
    if (!macNativeAvailable ||
        level is! num ||
        !level.isFinite ||
        level < 0 ||
        level > 1) {
      return null;
    }
    return SystemVolumeState(level.toDouble(),
        value['muted'] is bool ? value['muted'] as bool : null);
  }

  static Stream<SystemVolumeState?> get changes => _events
      .receiveBroadcastStream()
      .map((value) => _macState((value as Map).cast<Object?, Object?>()));
  static Future<SystemVolumeState?> applyMac(
      {int steps = 0, double? level, bool mute = false}) async {
    try {
      return _macState(await _channel.invokeMapMethod<Object?, Object?>(
          mute
              ? 'mute'
              : level != null
                  ? 'set'
                  : 'step',
          mute
              ? null
              : level != null
                  ? {'level': level}
                  : {'steps': steps}));
    } catch (_) {
      return null;
    }
  }

  static String? _linuxBackend;
  static bool get isSupported =>
      Platform.isMacOS || Platform.isLinux || Platform.isWindows;

  static Future<String?> _run(String command, List<String> arguments) async {
    try {
      final result =
          await Process.run(command, arguments, environment: {'LC_ALL': 'C'})
              .timeout(const Duration(seconds: 2));
      return result.exitCode == 0 ? result.stdout.toString() : null;
    } catch (_) {
      return null; // Never log process output or exception messages.
    }
  }

  static Future<SystemVolumeState?> read() async {
    try {
      if (Platform.isMacOS) {
        return _macState(
            await _channel.invokeMapMethod<Object?, Object?>('get'));
      }
      if (Platform.isWindows) {
        return parseWindowsVolume(
            await _channel.invokeMapMethod<Object?, Object?>('get'));
      }
      if (Platform.isLinux) {
        final wpctl =
            await _run('wpctl', ['get-volume', '@DEFAULT_AUDIO_SINK@']);
        final pipewire = wpctl == null ? null : parseWpctlVolume(wpctl);
        if (pipewire != null) {
          _linuxBackend = 'wpctl';
          return pipewire;
        }
        final volume =
            await _run('pactl', ['get-sink-volume', '@DEFAULT_SINK@']);
        final mute = await _run('pactl', ['get-sink-mute', '@DEFAULT_SINK@']);
        final pulse = volume == null || mute == null
            ? null
            : parsePactlVolume(volume, mute);
        if (pulse != null) {
          _linuxBackend = 'pactl';
          return pulse;
        }
        final amixer = await _run('amixer', ['get', 'Master']);
        final alsa = amixer == null ? null : parseAmixerVolume(amixer);
        if (alsa != null) {
          _linuxBackend = 'amixer';
          return alsa;
        }
      }
    } catch (_) {/* Missing method/backend: omit unavailable state. */}
    return null;
  }

  static Future<bool> step({required bool up}) async {
    if (Platform.isWindows) return _windows('step', {'up': up});
    if (Platform.isMacOS) return await applyMac(steps: up ? 1 : -1) != null;
    if (Platform.isLinux) return _linux('step', up: up);
    return false;
  }

  static Future<bool> setLevel(double level) async {
    if (!level.isFinite || level < 0 || level > 1) return false;
    if (Platform.isWindows) return _windows('set', {'level': level});
    if (Platform.isMacOS) return await applyMac(level: level) != null;
    if (Platform.isLinux) return _linux('set', level: level);
    return false;
  }

  static Future<bool> toggleMute() async {
    if (Platform.isWindows) return _windows('mute', null);
    if (Platform.isMacOS) return await applyMac(mute: true) != null;
    if (Platform.isLinux) return _linux('mute');
    return false;
  }

  static Future<bool> _windows(
      String method, Map<String, Object?>? arguments) async {
    try {
      await _channel.invokeMethod<void>(method, arguments);
      return true;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> _linux(String action,
      {bool up = true, double level = 0}) async {
    final preferred = _linuxBackend;
    final backends = [
      if (preferred != null) preferred,
      ...['wpctl', 'pactl', 'amixer'].where((b) => b != preferred)
    ];
    for (final backend in backends) {
      final amount = action == 'set'
          ? '${(level * 100).round()}%'
          : '$stepPercent%${up ? '+' : '-'}';
      final args = switch (backend) {
        'wpctl' => action == 'mute'
            ? ['set-mute', '@DEFAULT_AUDIO_SINK@', 'toggle']
            : ['set-volume', '-l', '1.0', '@DEFAULT_AUDIO_SINK@', amount],
        'pactl' => action == 'mute'
            ? ['set-sink-mute', '@DEFAULT_SINK@', 'toggle']
            : [
                'set-sink-volume',
                '@DEFAULT_SINK@',
                action == 'step' ? '${up ? '+' : '-'}$stepPercent%' : amount
              ],
        _ => ['-q', 'sset', 'Master', action == 'mute' ? 'toggle' : amount],
      };
      if (await _run(backend, args) != null) {
        _linuxBackend = backend;
        return true;
      }
    }
    return false;
  }
}

/// Initial/resync state never consumes the broadcast cache, including scope changes.
class VolumeStatePublication {
  Map<String, Object?>? _previous;
  Map<String, Object?>? changed(Map<String, Object?> frame) {
    if (mapEquals(_previous, frame)) return null;
    _previous = Map<String, Object?>.unmodifiable(frame);
    return _previous;
  }

  Map<String, Object?> sync(Map<String, Object?> frame) => frame;
  void reset() => _previous = null;
}
