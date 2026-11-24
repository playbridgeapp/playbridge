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

/// FFmpeg HTTP reconnect options for `stream-lavf-o` and `demuxer-lavf-o`, matching the
/// Android and Apple players. Reconnects on network errors and 429/5xx responses with backoff
/// capped at 5 seconds. The bundled FFmpeg (Lavf 60) has no `reconnect_max_retries`; the delay
/// cap still limits a disconnect to a few quick tries. `reconnect_streamed` is left out: with it
/// FFmpeg treats the normal end of a response of unknown length (such as a gzip-compressed HLS
/// playlist) as a dropped connection.
const mpvLavfReconnectOptions = 'reconnect=1,reconnect_on_network_error=1,'
    'reconnect_on_http_error="429,5xx",reconnect_delay_max=5';

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
