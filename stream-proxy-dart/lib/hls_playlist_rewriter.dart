class HlsPlaylistRewriter {
  /// Rewrites the URIs inside an HLS playlist (master or media) to point back to the local proxy.
  ///
  /// [content] is the raw string content of the playlist.
  /// [baseUri] is the effective URL of the playlist (after resolving any redirects).
  /// [rewriteUrl] is a callback that takes a resolved absolute target URL and returns the local proxy URL.
  static String rewrite(
    String content,
    Uri baseUri,
    String Function(String) rewriteUrl,
  ) {
    final lines = content.split('\n');
    final rewrittenLines = <String>[];

    // Regex to match URI="..." attributes in tags like EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA, etc.
    final uriAttrRegex = RegExp(r'(URI\s*=\s*")([^"]*)(")', caseSensitive: false);

    for (var line in lines) {
      final trimmed = line.trim();
      if (trimmed.isEmpty) {
        rewrittenLines.add(line);
        continue;
      }

      if (trimmed.startsWith('#')) {
        // It's a metadata tag. Check if it contains a URI="..." attribute.
        if (uriAttrRegex.hasMatch(line)) {
          final rewrittenLine = line.replaceAllMapped(uriAttrRegex, (match) {
            final prefix = match.group(1)!;
            final relativeUri = match.group(2)!;
            final suffix = match.group(3)!;

            try {
              final resolved = baseUri.resolve(relativeUri).toString();
              final rewritten = rewriteUrl(resolved);
              return '$prefix$rewritten$suffix';
            } catch (_) {
              return match.group(0)!;
            }
          });
          rewrittenLines.add(rewrittenLine);
        } else {
          rewrittenLines.add(line);
        }
      } else {
        // It's a resource URI line (segment, variant playlist, etc.)
        try {
          final resolved = baseUri.resolve(trimmed).toString();
          final rewritten = rewriteUrl(resolved);
          rewrittenLines.add(rewritten);
        } catch (_) {
          rewrittenLines.add(line);
        }
      }
    }

    return rewrittenLines.join('\n');
  }
}
