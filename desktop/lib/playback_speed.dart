/// Playback rates offered by the desktop player. Matches the Android TV
/// settings list (`MediaSettingsPanel` speed tab), including the 1.0 label.
const List<double> playbackSpeeds = <double>[
  0.25,
  0.5,
  0.75,
  1.0,
  1.25,
  1.5,
  1.75,
  2.0,
];

/// Snap [rate] to the nearest offered speed. Non-finite values become 1x.
double nearestPlaybackSpeed(double rate) {
  if (!rate.isFinite) return 1.0;
  return playbackSpeeds[_playbackSpeedIndex(rate)];
}

/// Step one notch slower (`direction < 0`) or faster (`direction > 0`).
/// Stays put at either end of [playbackSpeeds].
double stepPlaybackSpeed(double current, int direction) {
  if (direction == 0) return nearestPlaybackSpeed(current);
  final index = _playbackSpeedIndex(current);
  final next = (index + (direction > 0 ? 1 : -1))
      .clamp(0, playbackSpeeds.length - 1);
  return playbackSpeeds[next];
}

/// Menu label. `1.0x (Normal)` at 1x; otherwise `0.25x`, `1.5x`, `2.0x`.
String playbackSpeedLabel(double speed) {
  final snapped = nearestPlaybackSpeed(speed);
  if (snapped == 1.0) return '1.0x (Normal)';
  return playbackSpeedChip(snapped);
}

/// Short rate text for the controls bar and center OSD, e.g. `1.5x`.
String playbackSpeedChip(double speed) {
  final snapped = nearestPlaybackSpeed(speed);
  if (snapped == snapped.roundToDouble()) {
    return '${snapped.toStringAsFixed(1)}x';
  }
  return '${snapped}x';
}

int _playbackSpeedIndex(double rate) {
  var best = 0;
  var bestDiff = (playbackSpeeds[0] - rate).abs();
  for (var i = 1; i < playbackSpeeds.length; i++) {
    final diff = (playbackSpeeds[i] - rate).abs();
    if (diff < bestDiff) {
      best = i;
      bestDiff = diff;
    }
  }
  return best;
}
