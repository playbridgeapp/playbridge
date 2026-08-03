import test from 'node:test';
import assert from 'node:assert/strict';
import { detectStreamKind, inferContentType, normalizeReceiverMedia } from '../src/shared/media.js';
import { createLoadLifecycle } from '../src/shared/lifecycle.js';
import { redactUrl, redactUrlsInText } from '../src/shared/presentation.js';

test('normalizes progressive video and audio', () => {
  assert.equal(inferContentType('https://cdn.test/movie.mp4'), 'video/mp4');
  assert.equal(inferContentType('https://cdn.test/song.mp3'), 'audio/mpeg');
  assert.equal(detectStreamKind('https://cdn.test/movie.mp4', 'video/mp4'), 'progressive');
});

test('normalizes HLS, DASH, LIVE, metadata, captions, and position', () => {
  const media = normalizeReceiverMedia({
    contentId: 'http://192.168.1.8:9000/live.m3u8',
    contentType: 'application/vnd.apple.mpegurl',
    streamType: 'LIVE',
    currentTime: 12,
    metadata: { title: 'Channel', images: [{ url: 'https://cdn.test/art.jpg' }] },
    tracks: [{ type: 'TEXT', trackId: 7, trackContentId: 'https://cdn.test/en.vtt', language: 'en' }]
  });
  assert.equal(media.sourceKind, 'hls');
  assert.equal(media.streamType, 'LIVE');
  assert.equal(media.live, true);
  assert.equal(media.title, 'Channel');
  assert.equal(media.startPosition, 12);
  assert.equal(media.subtitleTracks[0].id, 7);
  assert.equal(detectStreamKind('https://cdn.test/manifest.mpd'), 'dash');
});

test('prefers contentUrl, accepts LAN HTTP, and supplies missing metadata', () => {
  const media = normalizeReceiverMedia({
    contentUrl: 'http://10.0.0.2/proxy/video.mp4',
    contentId: 'https://wrong.test/video.mp4'
  });
  assert.equal(media.url, 'http://10.0.0.2/proxy/video.mp4');
  assert.equal(media.title, 'Media');
  assert.equal(media.streamType, 'BUFFERED');
});

test('recognizes explicit TS proxy hints without inventing them', () => {
  assert.equal(normalizeReceiverMedia({ url: 'http://10.0.0.2/live.m3u8', hlsFormat: 'ts' }).explicitTs, true);
  assert.equal(normalizeReceiverMedia({ url: 'http://10.0.0.2/live.m3u8' }).explicitTs, false);
});

test('rejects non-network media URLs', () => {
  assert.throws(() => normalizeReceiverMedia({ url: 'file:///tmp/media.mp4' }), /HTTP and HTTPS/);
});

test('load lifecycle rejects stale generations', () => {
  const lifecycle = createLoadLifecycle();
  const first = lifecycle.begin();
  const second = lifecycle.begin();
  assert.equal(lifecycle.isCurrent(first), false);
  assert.equal(lifecycle.isCurrent(second), true);
  lifecycle.cancel();
  assert.equal(lifecycle.isCurrent(second), false);
});

test('diagnostics redact sensitive query values in URLs and messages', () => {
  assert.equal(
    redactUrl('https://cdn.test/media.m3u8?token=secret&quality=high'),
    'https://cdn.test/media.m3u8?token=%3Credacted%3E&quality=high'
  );
  assert.equal(
    redactUrlsInText('failed https://cdn.test/media.m3u8?sig=secret'),
    'failed https://cdn.test/media.m3u8?sig=%3Credacted%3E'
  );
});
