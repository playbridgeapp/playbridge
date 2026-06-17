import 'dart:io';

import 'package:flutter/foundation.dart';

/// Adjusts the **operating-system** output volume (not the player's own volume),
/// so the phone remote behaves like a real TV volume rocker. Best-effort and
/// platform-specific; [step] returns true when a backend handled the change.
///
/// - macOS  → AppleScript (`osascript`), clamped to 0–100.
/// - Linux  → tries PipeWire (`wpctl`), then PulseAudio (`pactl`), then ALSA (`amixer`).
/// - Windows→ sends the media Volume Up/Down key via `WScript.Shell` (PowerShell).
class SystemVolume {
  /// Per-press change, in percent (macOS/Linux). Windows uses the OS key step.
  static const int stepPercent = 5;

  static bool get isSupported =>
      Platform.isMacOS || Platform.isLinux || Platform.isWindows;

  static Future<bool> step({required bool up}) async {
    try {
      if (Platform.isMacOS) return await _macStep(up);
      if (Platform.isLinux) return await _linuxStep(up);
      if (Platform.isWindows) return await _windowsStep(up);
    } catch (e) {
      debugPrint('[volume] system volume failed: $e');
    }
    return false;
  }

  static Future<bool> _macStep(bool up) async {
    final delta = up ? stepPercent : -stepPercent;
    final r = await Process.run('osascript', [
      '-e',
      'set v to (output volume of (get volume settings)) + ($delta)',
      '-e',
      'if v > 100 then set v to 100',
      '-e',
      'if v < 0 then set v to 0',
      '-e',
      'set volume output volume v',
    ]);
    return r.exitCode == 0;
  }

  static Future<bool> _linuxStep(bool up) async {
    final sign = up ? '+' : '-';
    // Try PipeWire, then PulseAudio, then ALSA — whichever is installed.
    final attempts = <(String, List<String>)>[
      (
        'wpctl',
        [
          'set-volume',
          '-l',
          '1.0',
          '@DEFAULT_AUDIO_SINK@',
          '$stepPercent%$sign'
        ]
      ),
      ('pactl', ['set-sink-volume', '@DEFAULT_SINK@', '$sign$stepPercent%']),
      ('amixer', ['-q', 'sset', 'Master', '$stepPercent%$sign']),
    ];
    for (final (bin, args) in attempts) {
      try {
        final r = await Process.run(bin, args);
        if (r.exitCode == 0) return true;
      } on ProcessException {
        // Binary not present — try the next backend.
      }
    }
    return false;
  }

  static Future<bool> _windowsStep(bool up) async {
    // char 175 = Volume Up media key, 174 = Volume Down.
    final code = up ? 175 : 174;
    final r = await Process.run('powershell', [
      '-NoProfile',
      '-Command',
      '(New-Object -ComObject WScript.Shell).SendKeys([char]$code)',
    ]);
    return r.exitCode == 0;
  }
}
