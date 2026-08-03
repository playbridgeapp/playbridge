import test from 'node:test';
import assert from 'node:assert/strict';
import { applyReceiverMediaToLoadRequest, startCastReceiver } from '../src/cast-receiver.js';

const messages = {
  MessageType: { LOAD: 'LOAD' },
  HlsSegmentFormat: { TS_AAC: 'ts-aac-constant' },
  HlsVideoSegmentFormat: { MPEG2_TS: 'mpeg2-ts-constant' }
};

test('LOAD uses contentUrl, strips auth hints, and preserves standard fields', () => {
  const request = {
    currentTime: 14,
    media: {
      contentUrl: 'http://192.168.1.5/proxy/live.m3u8',
      contentId: 'fallback',
      contentType: 'application/x-mpegURL',
      streamType: 'LIVE',
      hlsFormat: 'ts',
      customData: { headers: { Authorization: 'secret' }, route: 'phone' },
      tracks: [{ type: 'TEXT', trackContentId: 'https://cdn.test/en.vtt' }]
    }
  };
  const result = applyReceiverMediaToLoadRequest(request, messages);
  assert.equal(result.request.media.contentUrl, 'http://192.168.1.5/proxy/live.m3u8');
  assert.equal(result.request.media.streamType, 'LIVE');
  assert.equal(result.request.media.hlsSegmentFormat, 'ts-aac-constant');
  assert.equal(result.request.media.hlsVideoSegmentFormat, 'mpeg2-ts-constant');
  assert.deepEqual(result.request.media.customData, { route: 'phone' });
  assert.equal(result.request.media.tracks.length, 1);
  assert.equal(result.normalized.startPosition, 14);
});

test('CAF starts with Shaka HLS, intercepts only LOAD, and STOP returns to ready', async () => {
  const interceptors = new Map();
  const playerListeners = new Map();
  const contextListeners = new Map();
  const calls = [];
  const presentation = {
    showReady: () => calls.push('ready'),
    showMedia: (_, state) => calls.push(state),
    showStatus: (message) => calls.push(message)
  };
  const playerManager = {
    setMessageInterceptor: (type, handler) => interceptors.set(type, handler),
    addEventListener: (type, handler) => playerListeners.set(type, handler),
  };
  const context = {
    getPlayerManager: () => playerManager,
    addEventListener: (type, handler) => contextListeners.set(type, handler),
    start: (options) => calls.push(options.useShakaForHls ? 'shaka-start' : 'no-shaka')
  };
  class Options {}
  const castApi = {
    framework: {
      messages,
      events: { EventType: { PLAYER_LOADING: 'PLAYER_LOADING', PLAYER_LOAD_COMPLETE: 'PLAYER_LOAD_COMPLETE', SEGMENT_DOWNLOADED: 'SEGMENT_DOWNLOADED', BUFFERING: 'BUFFERING', PLAYING: 'PLAYING', ERROR: 'ERROR', REQUEST_STOP: 'REQUEST_STOP', MEDIA_FINISHED: 'MEDIA_FINISHED' } },
      system: { EventType: { READY: 'READY', SHUTDOWN: 'SHUTDOWN' } },
      CastReceiverOptions: Options,
      CastReceiverContext: { getInstance: () => context }
    }
  };
  globalThis.WebSocket = class ForbiddenWebSocket {
    constructor() { throw new Error('Cast receiver must not create WebSockets'); }
  };
  startCastReceiver({ castApi, context, playerManager, presentation, logger: { info() {}, error() {} } });
  assert.deepEqual([...interceptors.keys()], ['LOAD']);
  assert.ok(calls.includes('shaka-start'));
  contextListeners.get('READY')();
  const request = await interceptors.get('LOAD')({ media: { contentId: 'https://cdn.test/movie.mp4' } });
  assert.equal(request.media.contentUrl, 'https://cdn.test/movie.mp4');
  playerListeners.get('REQUEST_STOP')();
  assert.ok(calls.filter((value) => value === 'ready').length >= 3);
  delete globalThis.WebSocket;
});

test('invalid LOAD rejects through CAF interceptor', async () => {
  assert.throws(() => applyReceiverMediaToLoadRequest({ media: { contentId: 'data:video/mp4;base64,x' } }, messages), /HTTP and HTTPS/);
});
