import 'package:flutter/gestures.dart';

/// Instant click-to-pause with YouTube-style double-click fullscreen.
///
/// The first click toggles play/pause immediately. A second click within
/// [timeout] is a double: undo that toggle (net playback unchanged) and
/// toggle fullscreen.
class PlayerSurfaceClick {
  PlayerSurfaceClick({this.timeout = kDoubleTapTimeout});

  final Duration timeout;
  DateTime? _lastAt;

  /// True when [now] completes a double-click pair.
  bool isDouble(DateTime now) {
    final last = _lastAt;
    _lastAt = now;
    if (last != null && now.difference(last) <= timeout) {
      _lastAt = null;
      return true;
    }
    return false;
  }
}
