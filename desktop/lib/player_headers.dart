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

/// Fallback when the capture had no User-Agent — same family as the phone's
/// `VideoDetector.mediaHeaders` default.
const defaultPlayerUserAgent =
    'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) '
    'Chrome/120.0.0.0 Mobile Safari/537.36';

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

/// Phone-aligned header map for casting / player open.
///
/// Beyond [sanitizePlayerHeaders]:
/// - canonical key casing for headers Exo looks up case-sensitively (User-Agent)
/// - collapse `Accept-Language: en-US,en;q=0.9` → `en-US` so MPV's
///   comma-separated `http-header-fields` cannot mis-split the value
/// - ensure a User-Agent is always present
Map<String, String>? mediaHeadersForPlayer(Map<String, String>? headers) {
  final sanitized = sanitizePlayerHeaders(headers);
  final out = <String, String>{...?sanitized};

  String? take(String name) {
    final match = out.entries
        .where((e) => e.key.toLowerCase() == name.toLowerCase())
        .firstOrNull;
    if (match == null) return null;
    out.remove(match.key);
    return match.value;
  }

  final userAgent = take('User-Agent');
  final referer = take('Referer');
  final origin = take('Origin');
  final accept = take('Accept');
  final acceptLanguage = take('Accept-Language');
  final cookie = take('Cookie');
  final authorization = take('Authorization');

  // Rebuild with stable casing (matches typical browser / phone payloads).
  out['User-Agent'] =
      (userAgent != null && userAgent.trim().isNotEmpty)
          ? userAgent.trim()
          : defaultPlayerUserAgent;
  if (referer != null && referer.isNotEmpty) out['Referer'] = referer;
  if (origin != null && origin.isNotEmpty) out['Origin'] = origin;
  if (accept != null && accept.isNotEmpty) {
    // Keep a simple Accept like the phone (`*/*`) when the browser sent a
    // weighted list — media players do not need the full negotiation string.
    out['Accept'] = accept.contains(',') ? '*/*' : accept;
  }
  if (acceptLanguage != null && acceptLanguage.isNotEmpty) {
    final primary = acceptLanguage.split(RegExp(r'[,;]')).first.trim();
    if (primary.isNotEmpty) out['Accept-Language'] = primary;
  }
  if (cookie != null && cookie.isNotEmpty) out['Cookie'] = cookie;
  if (authorization != null && authorization.isNotEmpty) {
    out['Authorization'] = authorization;
  }

  return out.isEmpty ? null : out;
}
