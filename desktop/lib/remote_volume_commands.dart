import 'dart:async';

class RemoteVolumeChange {
  const RemoteVolumeChange({this.steps = 0, this.level, this.mute = false});
  final int steps;
  final double? level;
  final bool mute;
}

/// Serializes commands; adjacent steps coalesce and a pending absolute wins.
class RemoteVolumeCommands {
  RemoteVolumeCommands(this.apply);
  final Future<void> Function(RemoteVolumeChange) apply;
  final _pending = <RemoteVolumeChange>[];
  Future<void>? _drain;
  bool _closed = false;
  Future<void> add(RemoteVolumeChange change) {
    if (_closed) return Future.value();
    if (change.level != null) {
      while (_pending.isNotEmpty && !_pending.last.mute) {
        _pending.removeLast();
      }
    } else if (!change.mute && _pending.isNotEmpty && !_pending.last.mute) {
      final last = _pending.removeLast();
      // Keep the two kinds distinct: native steps must use native increments.
      if (last.level == null && (last.steps > 0) == (change.steps > 0)) {
        change = RemoteVolumeChange(steps: last.steps + change.steps);
      } else {
        _pending.add(last);
      }
    }
    _pending.add(change);
    return _drain ??= Future<void>.microtask(() async {
      try {
        while (!_closed && _pending.isNotEmpty) {
          final change = _pending.removeAt(0);
          if (change.mute || change.level != null || change.steps != 0) {
            await apply(change);
          }
        }
      } finally {
        _drain = null;
      }
    });
  }

  void close() {
    _closed = true;
    _pending.clear();
  }
}

String? remoteVolumeFeedback(Map<String, Object?> frame) {
  if (frame['muted'] == true) return 'Muted';
  final level = frame['level'];
  if (level is num && level.isFinite && level >= 0 && level <= 1) {
    return 'Volume ${(level * 100).round()}';
  }
  return frame['muted'] == false ? 'Unmuted' : null;
}
