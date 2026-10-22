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
  if (text.startsWith('Failed to initialize a decoder') ||
      text.startsWith('Error while decoding frame')) {
    return 'mpv_decoder_failure';
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
