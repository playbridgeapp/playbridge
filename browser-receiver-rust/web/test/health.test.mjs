import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PLAYBACK_FAILED,
  PLAYBACK_STALLED,
  STARTUP_STALLED,
  bufferedAhead,
  createStallWatchdog,
  playbackErrorMessage
} from '../src/shared/health.js';

function fakeClock() {
  let time = 0;
  return { now: () => time, advance: (ms) => (time += ms) };
}

test('media error codes become fixed sentences; other text never passes', () => {
  assert.equal(playbackErrorMessage({ code: 2, message: 'GET https://cdn.test/a.mp4?token=x failed' }),
    "The browser couldn't reach the video server.");
  assert.equal(playbackErrorMessage({ code: 4 }), "The browser can't play this video format.");
  assert.equal(playbackErrorMessage(new Error('https://cdn.test/secret'), 'The video couldn\'t be loaded.'),
    "The video couldn't be loaded.");
  assert.equal(playbackErrorMessage('raw text'), PLAYBACK_FAILED);
  assert.equal(playbackErrorMessage(null), PLAYBACK_FAILED);
});

test('buffered ahead reads the range around the position', () => {
  const ranges = { length: 2, start: (i) => [0, 30][i], end: (i) => [10, 50][i] };
  assert.equal(bufferedAhead(ranges, 4), 6);
  assert.equal(bufferedAhead(ranges, 35), 15);
  assert.equal(bufferedAhead(ranges, 20), 0);
  assert.equal(bufferedAhead(null, 4), 0);
});

test('a load that never progresses reports a startup stall after 30 s, once', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  clock.advance(29999);
  assert.equal(watchdog.observe({}), null);
  clock.advance(1);
  assert.equal(watchdog.observe({}), STARTUP_STALLED);
  clock.advance(60000);
  assert.equal(watchdog.observe({}), null);
});

test('buffer growth before playback keeps the startup window open', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  clock.advance(25000);
  assert.equal(watchdog.observe({ buffered: 2 }), null);
  clock.advance(25000);
  assert.equal(watchdog.observe({ buffered: 2 }), null);
  clock.advance(5000);
  assert.equal(watchdog.observe({ buffered: 2 }), STARTUP_STALLED);
});

test('playback that stops moving reports a stall after 45 s', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  clock.advance(1000);
  assert.equal(watchdog.observe({ position: 1, buffered: 5 }), null);
  clock.advance(44999);
  assert.equal(watchdog.observe({ position: 1, buffered: 5 }), null);
  clock.advance(1);
  assert.equal(watchdog.observe({ position: 1, buffered: 5 }), PLAYBACK_STALLED);
});

test('pausing and seeking do not count as a stall', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  clock.advance(1000);
  watchdog.observe({ position: 1 });
  clock.advance(600000);
  assert.equal(watchdog.observe({ position: 1, paused: true }), null);
  clock.advance(40000);
  assert.equal(watchdog.observe({ position: 1 }), null);
  assert.equal(watchdog.observe({ position: 300, seeking: true }), null);
  clock.advance(40000);
  assert.equal(watchdog.observe({ position: 300 }), null);
  clock.advance(5000);
  assert.equal(watchdog.observe({ position: 300 }), PLAYBACK_STALLED);
});

test('a stopped watchdog reports nothing', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  watchdog.stop();
  clock.advance(120000);
  assert.equal(watchdog.observe({}), null);
});

test('a load that never gets metadata stalls even though the element is paused', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  clock.advance(29000);
  assert.equal(watchdog.observe({ paused: true, readyState: 0 }), null);
  clock.advance(1000);
  assert.equal(watchdog.observe({ paused: true, readyState: 0 }), STARTUP_STALLED);
});

test('a paused load with metadata is waiting on the person, not stalled', () => {
  const clock = fakeClock();
  const watchdog = createStallWatchdog({ now: clock.now });
  watchdog.start();
  clock.advance(60000);
  assert.equal(watchdog.observe({ paused: true, readyState: 1 }), null);
});

test('a format error before metadata reads as a load failure', () => {
  assert.equal(playbackErrorMessage({ code: 4 }, null, { loaded: false }), "The video couldn't be loaded.");
  assert.equal(playbackErrorMessage({ code: 4 }, null, { loaded: true }), "The browser can't play this video format.");
  assert.equal(playbackErrorMessage({ code: 2 }, null, { loaded: false }), "The browser couldn't reach the video server.");
});
