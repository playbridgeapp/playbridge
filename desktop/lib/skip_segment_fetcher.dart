import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'skip_segment.dart';

/// Fetches skip segments from IntroDB and/or TheIntroDB, matching TV
/// `SkipSegmentFetcher`.
class SkipSegmentFetcher {
  SkipSegmentFetcher({HttpClient Function()? clientFactory})
      : _clientFactory = clientFactory ?? HttpClient.new;

  static const defaultIntroDbUrl = 'https://api.introdb.app';
  static const defaultTheIntroDbUrl = 'https://api.theintrodb.org';

  final HttpClient Function() _clientFactory;

  Future<List<SkipSegment>> fetch({
    required String provider,
    String? imdbId,
    String? tmdbId,
    int? season,
    int? episode,
    String introDbUrl = defaultIntroDbUrl,
    String theIntroDbUrl = defaultTheIntroDbUrl,
    String introDbApiKey = '',
  }) async {
    if (!skipFetchAllowed(
      imdbId: imdbId,
      tmdbId: tmdbId,
      season: season,
      episode: episode,
    )) {
      return const [];
    }

    var introDb = const <SkipSegment>[];
    if (provider != 'theintrodb' &&
        imdbId != null &&
        imdbId.isNotEmpty &&
        season != null &&
        episode != null) {
      introDb = await _fetchIntroDb(
        baseUrl: introDbUrl,
        apiKey: introDbApiKey,
        imdbId: imdbId,
        season: season,
        episode: episode,
      );
    }

    var theIntroDb = const <SkipSegment>[];
    if (provider != 'introdb') {
      theIntroDb = await _fetchTheIntroDb(
        baseUrl: theIntroDbUrl,
        imdbId: imdbId,
        tmdbId: tmdbId,
        season: season,
        episode: episode,
      );
    }

    return mergeSkipProviders(introDb: introDb, theIntroDb: theIntroDb);
  }

  Future<List<SkipSegment>> _fetchIntroDb({
    required String baseUrl,
    required String apiKey,
    required String imdbId,
    required int season,
    required int episode,
  }) async {
    final root = baseUrl.trim().replaceAll(RegExp(r'/$'), '');
    final uri = Uri.parse(
      '$root/segments?imdb_id=$imdbId&season=$season&episode=$episode',
    );
    try {
      final body = await _get(uri, apiKey: apiKey);
      if (body == null) return const [];
      final json = decodeJsonObject(body);
      if (json == null) return const [];
      return parseIntroDbSegments(json);
    } catch (e) {
      debugPrint('[skip] IntroDB fetch failed (${e.runtimeType})');
      return const [];
    }
  }

  Future<List<SkipSegment>> _fetchTheIntroDb({
    required String baseUrl,
    String? imdbId,
    String? tmdbId,
    int? season,
    int? episode,
  }) async {
    try {
      final saved = baseUrl.trim();
      final root = Uri.parse(saved.isEmpty ? defaultTheIntroDbUrl : saved);
      final path = root.pathSegments.toList();
      while (path.isNotEmpty && path.last.isEmpty) {
        path.removeLast();
      }
      // Accept saved versioned roots without losing a custom proxy path or query.
      if (path.isNotEmpty && RegExp(r'^v[1-4]$').hasMatch(path.last)) {
        path.removeLast();
      }
      final query = <String, dynamic>{...root.queryParametersAll};
      for (final key in [
        'tvdb_id',
        'tmdb_id',
        'imdb_id',
        'season',
        'episode'
      ]) {
        query.remove(key);
      }
      if (tmdbId != null && tmdbId.trim().isNotEmpty) {
        query['tmdb_id'] = tmdbId.trim();
      } else if (imdbId != null && imdbId.trim().isNotEmpty) {
        query['imdb_id'] = imdbId.trim();
      } else {
        return const [];
      }
      // v4 resolves TMDB episode coordinates server-side; no guessed mapping order.
      if (season != null && episode != null) {
        query['season'] = '$season';
        query['episode'] = '$episode';
      }
      final uri = root.replace(
        pathSegments: [...path, 'v4', 'media'],
        queryParameters: query,
        fragment: '',
      );
      final body = await _get(uri);
      if (body == null) return const [];
      final json = decodeJsonObject(body);
      if (json == null) return const [];
      return parseTheIntroDbSegments(json);
    } catch (e) {
      debugPrint('[skip] TheIntroDB fetch failed (${e.runtimeType})');
      return const [];
    }
  }

  Future<String?> _get(Uri uri, {String apiKey = ''}) async {
    final client = _clientFactory()
      ..connectionTimeout = const Duration(seconds: 8);
    try {
      final request = await client.getUrl(uri);
      request.headers.set(HttpHeaders.userAgentHeader, 'PlayBridgeDesktop/1.0');
      if (apiKey.isNotEmpty) {
        if (apiKey.startsWith('ey')) {
          request.headers
              .set(HttpHeaders.authorizationHeader, 'Bearer $apiKey');
        } else {
          request.headers.set('x-api-key', apiKey);
          request.headers
              .set(HttpHeaders.authorizationHeader, 'Bearer $apiKey');
        }
      }
      final response = await request.close();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.drain<void>();
        return null;
      }
      return await utf8.decodeStream(response);
    } finally {
      client.close(force: true);
    }
  }
}
