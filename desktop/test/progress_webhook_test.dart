import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/progress_webhook.dart';
import 'package:playbridge_desktop/protocol.dart';

void main() {
  final config = ProgressWebhook.parse({
    'url': 'https://sync.example.com/v1/progress',
    'bearerToken': 'secret'
  })!;
  ProgressSnapshot snapshot(
          {String state = 'playing',
          String item = 'episode-1',
          int position = 1000}) =>
      ProgressSnapshot(
          playbackId: 'playback',
          itemId: item,
          content: {
            'type': 'series',
            'contentId': 'tt123',
            'videoId': item,
            'season': 1,
            'episode': item == 'episode-1' ? 1 : 2
          },
          state: state,
          positionMs: position,
          durationMs: 200000);

  test('rejects unsafe URLs and credential injection', () {
    for (final url in [
      'http://example.com/',
      'https://localhost:123/',
      'https://127.0.0.1/',
      'https://[::1]/',
      'https://[::ffff:127.0.0.1]/',
      'https://user:pass@example.com/',
      'https://example.com/?secret=x',
      'https://example.com/#secret'
    ]) {
      expect(
          ProgressWebhook.parse({'url': url, 'bearerToken': 'secret'}), isNull,
          reason: url);
    }
    expect(
        ProgressWebhook.parse(
            {'url': 'https://example.com/', 'bearerToken': 'a\r\nb'}),
        isNull);
    expect(config.toString(), isNot(contains('secret')));
    expect(config.toString(), isNot(contains('example')));
    expect(
        validProgressIdentity(
            {'type': 'series', 'contentId': 'show', 'videoId': 'ep'}),
        isNull);
    expect(
        validProgressIdentity({
          'type': 'series',
          'contentId': 'show',
          'videoId': 'ep',
          'season': 1,
          'episode': 2,
          'secret': 'hidden'
        }),
        isNot(containsPair('secret', 'hidden')));
  });

  test('only accepts public addresses including mapped IPv6', () {
    for (final ip in [
      '0.0.0.0',
      '10.1.1.1',
      '127.0.0.1',
      '169.254.169.254',
      '172.16.0.1',
      '192.168.1.1',
      '100.64.0.1',
      '224.1.1.1',
      '::',
      '::1',
      'fe80::1',
      'fc00::1',
      'ff02::1',
      '::ffff:192.168.0.1',
      '2001:db8::1',
      '2002:7f00:1::'
    ]) {
      expect(isPublicWebhookAddress(InternetAddress(ip)), isFalse, reason: ip);
    }
    for (final ip in ['8.8.8.8', '2606:4700:4700::1111', '::ffff:8.8.8.8']) {
      expect(isPublicWebhookAddress(InternetAddress(ip)), isTrue, reason: ip);
    }
  });

  test('heartbeat, pause, resume, and episode boundaries preserve identities',
      () async {
    var now = DateTime.utc(2026);
    final events = <Map<String, Object?>>[];
    final reporter = ProgressWebhookReporter(
        now: () => now, transport: (_, body) async => events.add(body));
    reporter.configure(config);
    reporter.update(snapshot(state: 'buffering'));
    reporter.update(snapshot());
    now = now.add(const Duration(seconds: 29));
    reporter.update(snapshot(position: 30000));
    now = now.add(const Duration(seconds: 1));
    reporter.update(snapshot(position: 31000));
    reporter.update(snapshot(state: 'paused', position: 32000));
    reporter.update(snapshot(state: 'paused', position: 32000));
    reporter.update(snapshot(position: 33000));
    reporter.terminal('ended', snapshot: snapshot(position: 200000));
    reporter.terminal('stopped', snapshot: snapshot(position: 200000));
    reporter.update(snapshot(item: 'episode-2'));
    reporter.terminal('stopped',
        sessionEnded: true,
        snapshot: snapshot(item: 'episode-2', position: 9000));
    reporter.update(snapshot(item: 'episode-2'));
    await reporter.drained;
    expect(events.map((e) => e['event']), [
      'started',
      'progress',
      'paused',
      'started',
      'ended',
      'started',
      'stopped'
    ]);
    expect((events.last['content'] as Map)['episode'], 2);
    expect(events.last['positionMs'], 9000);
    expect(events.map((e) => e['eventId']).toSet().length, events.length);
    expect(events.toString(), isNot(contains('secret')));
  });

  test('rejects invalid identity and non-ASCII token', () {
    expect(
        validProgressIdentity({
          'type': 'series',
          'contentId': 'id',
          'videoId': 'ep',
          'episode': -1
        }),
        isNull);
    expect(
        validProgressIdentity(
            {'type': 'other', 'contentId': 'id', 'videoId': 'ep'}),
        isNull);
    expect(
        ProgressWebhook.parse(
            {'url': 'https://example.com/', 'bearerToken': 'sécret'}),
        isNull);
  });

  test('terminal preserves last valid sample when engine clears duration',
      () async {
    final events = <Map<String, Object?>>[];
    final reporter =
        ProgressWebhookReporter(transport: (_, body) async => events.add(body));
    reporter.configure(config);
    reporter.update(snapshot(position: 90000));
    final empty = ProgressSnapshot(
        playbackId: 'playback',
        itemId: 'episode-1',
        content: snapshot().content,
        state: 'idle',
        positionMs: 0,
        durationMs: 0);
    reporter.update(empty);
    reporter.terminal('stopped', sessionEnded: true, snapshot: empty);
    await reporter.drained;
    expect(events.last['positionMs'], 90000);
    expect(events.last['durationMs'], 200000);
  });

  test('unknown duration waits for a valid sample and keeps it at EOF',
      () async {
    final events = <Map<String, Object?>>[];
    final reporter =
        ProgressWebhookReporter(transport: (_, body) async => events.add(body));
    reporter.configure(config);
    const content = {
      'type': 'series',
      'contentId': 'show',
      'videoId': 'show:1:2',
      'season': 1,
      'episode': 2
    };
    ProgressSnapshot sample(String state, int duration,
            {int position = 5000}) =>
        ProgressSnapshot(
            playbackId: 'p',
            itemId: 'i',
            content: content,
            state: state,
            positionMs: position,
            durationMs: duration);
    reporter.update(sample('playing', 0));
    reporter.update(sample('playing', 20000));
    reporter.update(sample('ended', 20000, position: 0));
    await reporter.drained;
    expect(events.map((e) => e['event']).toList(), ['started', 'ended']);
    expect(events.last['durationMs'], 20000);
    expect(events.last['positionMs'], 5000);
  });

  test('retry reuses event ID and auth failures do not retry', () async {
    final bodies = <Map<String, Object?>>[];
    final reporter = ProgressWebhookReporter(transport: (_, body) async {
      bodies.add(body);
      if (bodies.length == 1) throw WebhookHttpFailure(true);
    });
    reporter.configure(config);
    reporter.update(snapshot());
    await reporter.drained;
    expect(bodies.length, 2);
    expect(bodies[0]['eventId'], bodies[1]['eventId']);
    var failures = 0;
    final denied = ProgressWebhookReporter(transport: (_, __) async {
      failures++;
      throw WebhookHttpFailure(false);
    });
    denied.configure(config);
    denied.update(snapshot());
    await denied.drained;
    expect(failures, 1);
  });

  test('webhook stays separate from media and malformed config is optional',
      () {
    final cmd = parseCommand(
            '{"type":"command","action":"playlist","payload":{"items":[{"url":"https://media.example/video.mkv"}],"progressWebhook":{"url":"https://sync.example.com/v1/progress","bearerToken":"secret"}}}')
        as PlaylistCmd;
    expect(cmd.progressWebhook, isNotNull);
    expect(
        cmd.items.first.toProto3Json().toString(), isNot(contains('secret')));
    final invalid = parseCommand(
            '{"type":"command","action":"playlist","payload":{"items":[{"url":"https://media.example/video.mkv"}],"progressWebhook":{"url":"http://localhost/","bearerToken":"secret"}}}')
        as PlaylistCmd;
    expect(invalid.items.length, 1);
    expect(invalid.progressWebhook, isNull);
  });
}
