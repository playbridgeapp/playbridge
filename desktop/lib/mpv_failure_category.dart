/// Returns only a bounded category code. Raw native logs must never be retained or displayed.
String? mpvFailureCategory(String prefix, String text) {
  if (prefix != 'cplayer' &&
      prefix != 'ffmpeg' &&
      !prefix.startsWith('ffmpeg/')) {
    return null;
  }
  final http = RegExp(r'\bHTTP error ([1-5][0-9]{2})\b').firstMatch(text);
  if (http != null) return 'mpv_http_${http.group(1)}';
  if (text.startsWith('Failed to recognize file format') ||
      text.startsWith('Unrecognized file format')) {
    return 'mpv_unsupported_format';
  }
  if ([
    'Failed to resolve hostname',
    'Connection timed out',
    'TLS handshake failed',
    'Connection refused',
    'Network is unreachable'
  ].any(text.startsWith)) {
    return 'mpv_network_failed';
  }
  return null;
}

/// Retain only categories observed since the last healthy playback point.
class MpvFailureLogState {
  String? code;
  Duration _lastPosition = Duration.zero;

  void record(String prefix, String level, String text) {
    if (level != 'error' && level != 'fatal') return;
    final category = mpvFailureCategory(prefix, text);
    if (category != null) code = category;
  }

  void playbackHealthy() => code = null;

  void beginFile() {
    playbackHealthy();
    _lastPosition = Duration.zero;
  }

  void positionChanged(Duration position,
      {required bool playing, required bool buffering}) {
    if (playing && !buffering && position > _lastPosition) playbackHealthy();
    _lastPosition = position;
  }
}
