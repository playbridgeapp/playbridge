/// Pure policy. The caller supplies monotonic milliseconds and buffer ahead, not buffer end.
class PlaybackStallDetector {
  static const startupTimeoutMs = 30000;
  static const midPlayTimeoutMs = 45000;
  static const message = 'The video stopped loading';

  int? _sinceMs;
  int _positionMs = 0;
  int _bufferedAheadMs = 0;
  bool _reported = false;

  void reset() {
    _sinceMs = null;
    _reported = false;
  }

  bool sample(
      {required int nowMs,
      required bool buffering,
      required bool playbackRequested,
      required bool hasFirstFrame,
      required int positionMs,
      required int bufferedAheadMs,
      bool seeking = false}) {
    final progress =
        positionMs != _positionMs || bufferedAheadMs > _bufferedAheadMs;
    _positionMs = positionMs;
    _bufferedAheadMs = bufferedAheadMs;
    if (!buffering || !playbackRequested || seeking || progress) reset();
    if (!buffering || !playbackRequested || seeking) return false;
    final since = _sinceMs ??= nowMs;
    final timeout = hasFirstFrame ? midPlayTimeoutMs : startupTimeoutMs;
    if (!_reported && nowMs - since >= timeout) {
      _reported = true;
      return true;
    }
    return false;
  }
}
