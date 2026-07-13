/// Headers that must NOT be forwarded to a media player or cast target.
///
/// They're browser-request artifacts; passing them breaks playback. Most
/// importantly:
/// - a fixed `Range: bytes=0-` makes every request (including seeks) ask for
///   the whole file
/// - `Sec-Fetch-Site: same-origin` / `cross-site` causes many CDNs to reject
///   segment fetches from a non-browser player
///
/// Mirrors the phone's `VideoDetector.PLAYER_SKIP_HEADERS`.
const playerSkipHeaders = <String>{
  'range',
  'accept-encoding',
  'host',
  'connection',
  'content-length',
  'sec-fetch-dest',
  'sec-fetch-mode',
  'sec-fetch-site',
  'sec-fetch-storage-access',
  'sec-gpc',
  'sec-ch-ua',
  'sec-ch-ua-mobile',
  'sec-ch-ua-platform',
  'priority',
  'upgrade-insecure-requests',
  'te',
  'pragma',
};

/// Strips [playerSkipHeaders], keeping the ones a player needs (User-Agent,
/// Referer, Cookie, Authorization, Origin, Accept, …). Returns null if empty.
Map<String, String>? sanitizePlayerHeaders(Map<String, String>? headers) {
  if (headers == null || headers.isEmpty) return headers;
  final out = <String, String>{};
  headers.forEach((k, v) {
    if (!playerSkipHeaders.contains(k.toLowerCase())) out[k] = v;
  });
  return out.isEmpty ? null : out;
}
