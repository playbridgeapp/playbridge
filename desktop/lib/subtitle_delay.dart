/// Subtitle cue offset, matching Android TV (`PlayerControlsViewModel`).
/// Positive values advance cues relative to video. Clamp ±120 seconds.
const int subtitleDelayMinMs = -120000;
const int subtitleDelayMaxMs = 120000;
const int subtitleDelayFineMs = 100;
const int subtitleDelayCoarseMs = 1000;

int clampSubtitleDelayMs(int ms) {
  if (ms < subtitleDelayMinMs) return subtitleDelayMinMs;
  if (ms > subtitleDelayMaxMs) return subtitleDelayMaxMs;
  return ms;
}

int adjustSubtitleDelayMs(int currentMs, int deltaMs) =>
    clampSubtitleDelayMs(currentMs + deltaMs);

/// OSD / menu label. `Synced` at 0; otherwise `+100 ms` / `-1000 ms`.
String subtitleDelayLabel(int ms) {
  final clamped = clampSubtitleDelayMs(ms);
  if (clamped == 0) return 'Synced';
  final sign = clamped > 0 ? '+' : '';
  return '$sign$clamped ms';
}

/// Seconds string for mpv `sub-delay`.
String subtitleDelayMpvSeconds(int ms) =>
    (clampSubtitleDelayMs(ms) / 1000.0).toString();
