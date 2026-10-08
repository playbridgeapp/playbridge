/// Elapsed / remaining clocks for the desktop seekbar.
String formatPlaybackClock(int ms) {
  final totalSeconds = (ms / 1000).floor().clamp(0, 24 * 3600);
  final hours = totalSeconds ~/ 3600;
  final minutes = (totalSeconds % 3600) ~/ 60;
  final seconds = totalSeconds % 60;
  final m = minutes.toString().padLeft(2, '0');
  final s = seconds.toString().padLeft(2, '0');
  if (hours > 0) return '$hours:$m:$s';
  return '$m:$s';
}

/// TV-style remaining label: `-M:SS` (or `-H:MM:SS`).
String formatRemainingClock(int positionMs, int durationMs) {
  final remaining =
      durationMs > 0 ? (durationMs - positionMs).clamp(0, durationMs) : 0;
  return '-${formatPlaybackClock(remaining)}';
}

/// Arrow-key seek: ±10s, or ±50s after 10 key-repeat ticks (TV
/// `PlayerHostActivity` hold).
int seekStepMs(int repeatCount) => repeatCount > 10 ? 50000 : 10000;
