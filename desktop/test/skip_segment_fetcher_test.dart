import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/skip_segment.dart';
import 'package:playbridge_desktop/skip_segment_fetcher.dart';

void main() {
  late HttpServer server;
  late String base;
  late List<Uri> requests;
  late String response;
  late int status;

  setUp(() async {
    requests = [];
    response = jsonEncode({
      'tvdb_id': 81189,
      'type': 'tv',
      'season': 1,
      'episode': 1,
      'intro': [
        {'start_ms': null, 'end_ms': 23000},
        {'start_ms': 30000, 'end_ms': 90000},
      ],
      'recap': [
        {'start_ms': null, 'end_ms': 12000},
      ],
      'credits': [
        {'start_ms': 5801777, 'end_ms': 6371111},
        {'start_ms': 6408000, 'end_ms': null},
      ],
      'preview': [
        {'start_ms': 1680000, 'end_ms': 1740000},
      ],
    });
    status = HttpStatus.ok;
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    base = 'http://127.0.0.1:${server.port}';
    server.listen((request) async {
      requests.add(request.uri);
      request.response.statusCode = status;
      request.response.headers.contentType = ContentType.json;
      request.response.write(response);
      await request.response.close();
    });
  });
  tearDown(() async => server.close(force: true));

  test(
      'v4 TMDB movie and IMDb episode requests parse all ranges without tmdb_id',
      () async {
    final fetcher = SkipSegmentFetcher();
    final segments = await fetcher.fetch(
      provider: 'theintrodb',
      tmdbId: '550',
      imdbId: 'tt0137523',
      theIntroDbUrl: base,
    );
    expect(requests.single.path, '/v4/media');
    expect(requests.single.queryParameters, {'tmdb_id': '550'});
    expect(segments, const [
      SkipSegment(type: 'intro', startMs: 0, endMs: 23000),
      SkipSegment(type: 'recap', startMs: 0, endMs: 12000),
      SkipSegment(type: 'intro', startMs: 30000, endMs: 90000),
      SkipSegment(type: 'preview', startMs: 1680000, endMs: 1740000),
      SkipSegment(type: 'outro', startMs: 5801777, endMs: 6371111),
      SkipSegment(
          type: 'outro', startMs: 6408000, endMs: skipSegmentOpenEndedMs),
    ]);
    await fetcher.fetch(
      provider: 'theintrodb',
      imdbId: 'tt0903747',
      season: 1,
      episode: 2,
      theIntroDbUrl: base,
    );
    expect(requests.last.path, '/v4/media');
    expect(requests.last.queryParameters,
        {'imdb_id': 'tt0903747', 'season': '1', 'episode': '2'});
    expect(requests.last.queryParameters, isNot(contains('mapping')));
  });

  test('saved versioned roots preserve proxy paths and custom query values',
      () async {
    for (final suffix in ['', '/', '/v1', '/v2/', '/v3', '/v3///', '/v4/']) {
      await SkipSegmentFetcher().fetch(
        provider: 'theintrodb',
        tmdbId: '550',
        theIntroDbUrl: ' $base/proxy$suffix ',
      );
      expect(requests.last.path, '/proxy/v4/media');
      expect(requests.last.queryParameters, {'tmdb_id': '550'});
    }
    await SkipSegmentFetcher().fetch(
      provider: 'theintrodb',
      imdbId: 'tt0137523',
      theIntroDbUrl:
          '$base/proxy%2Froute/v3/?key=a%26b&tvdb_id=123&season=8&episode=9#fragment',
    );
    expect(requests.last.pathSegments, ['proxy/route', 'v4', 'media']);
    expect(
        requests.last.queryParameters, {'key': 'a&b', 'imdb_id': 'tt0137523'});
  });

  test('specials are sent and half-specified episodes never become movies',
      () async {
    final fetcher = SkipSegmentFetcher();
    await fetcher.fetch(
      provider: 'theintrodb',
      tmdbId: '1396',
      season: 0,
      episode: 0,
      theIntroDbUrl: base,
    );
    expect(requests.single.queryParameters,
        {'tmdb_id': '1396', 'season': '0', 'episode': '0'});
    for (final coordinates in [(1, null), (null, 1)]) {
      expect(
        await fetcher.fetch(
          provider: 'theintrodb',
          tmdbId: '1396',
          season: coordinates.$1,
          episode: coordinates.$2,
          theIntroDbUrl: base,
        ),
        isEmpty,
      );
    }
    expect(requests, hasLength(1));
  });

  test('movie null starts work and invalid/no-segment ranges are ignored',
      () async {
    response = jsonEncode({
      'tvdb_id': 247,
      'type': 'movie',
      'intro': [
        {'start_ms': null, 'end_ms': 23000},
        {'start_ms': null, 'end_ms': 0},
        {'start_ms': 0},
        {'end_ms': 2},
        {'start_ms': -1, 'end_ms': 2},
        {'start_ms': 2, 'end_ms': 1},
      ],
      'outro': {},
      'credits': [
        {'start_ms': null, 'end_ms': 5},
        {'start_ms': 90000, 'end_ms': null},
      ],
    });
    expect(
      await SkipSegmentFetcher()
          .fetch(provider: 'theintrodb', tmdbId: '550', theIntroDbUrl: base),
      const [
        SkipSegment(type: 'intro', startMs: 0, endMs: 23000),
        SkipSegment(
            type: 'outro', startMs: 90000, endMs: skipSegmentOpenEndedMs),
      ],
    );
  });

  test('HTTP errors and v4 fallback bodies return no segments', () async {
    final fetcher = SkipSegmentFetcher();
    status = HttpStatus.notFound;
    expect(
      await fetcher.fetch(
          provider: 'theintrodb', tmdbId: '550', theIntroDbUrl: base),
      isEmpty,
    );
    status = HttpStatus.ok;
    for (final fallback in ['versions', 'episodes']) {
      response = jsonEncode({'tvdb_id': 247, 'type': 'movie', fallback: []});
      expect(
        await fetcher.fetch(
            provider: 'theintrodb', tmdbId: '550', theIntroDbUrl: base),
        isEmpty,
      );
    }
  });

  test('malformed responses never log custom URL secrets or JSON', () async {
    final messages = <String>[];
    final originalPrint = debugPrint;
    debugPrint = (String? message, {int? wrapWidth}) {
      if (message != null) messages.add(message);
    };
    try {
      response = 'not-json-secret-response';
      expect(
        await SkipSegmentFetcher().fetch(
          provider: 'theintrodb',
          tmdbId: '550',
          theIntroDbUrl: '$base/v3?token=test-only-secret',
        ),
        isEmpty,
      );
      expect(messages, hasLength(1));
      expect(messages.single, contains('FormatException'));
      expect(messages.join(), isNot(contains('test-only-secret')));
      expect(messages.join(), isNot(contains('not-json-secret-response')));
      expect(messages.join(), isNot(contains(base)));
    } finally {
      debugPrint = originalPrint;
    }
  });
}
