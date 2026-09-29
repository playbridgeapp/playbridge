import test from 'node:test';
import assert from 'node:assert/strict';
import { createProgressReporter } from '../src/shared/progress.js';
const identity = { type: 'series', contentId: 'tt1', videoId: 'tt1:1:2', season: 1, episode: 2 };
const callback = { url: 'https://sync.example.com/progress', bearerToken: 'secret' };
test('callback requires deployment-approved origin; caller cannot supply origin grants', () => {
  const reporter = createProgressReporter();
  assert.equal(reporter.start(callback, identity), false);
  const r = createProgressReporter({ allowedOrigins: ['https://sync.example.com'], uuid: () => 'id' });
  for (const url of ['http://sync.example.com/progress', 'https://user:pass@sync.example.com', 'https://sync.example.com/#secret', 'https://other.example.com']) {
    assert.equal(r.start({ ...callback, url }, identity), false);
  }
  assert.equal(r.start(callback, identity), true);
});
test('sample lifecycle, heartbeat, final position and duplicate stop', async () => {
  const requests = []; let time = 0, id = 0;
  const r = createProgressReporter({ allowedOrigins: ['https://sync.example.com'], now: () => time, uuid: () => String(++id),
    fetcher: async (url, init) => { requests.push({url, ...init, body: JSON.parse(init.body)}); return { status: 204 }; } });
  r.start(callback, identity);
  r.observe('playing', 1000, 60000);time=1000;r.observe('playing',2000,60000);
  time=31000;r.observe('playing',32000,60000);r.observe('paused',33000,60000);
  r.observe('stopped',0,60000);r.finish();
  await new Promise((resolve)=>setImmediate(resolve));
  assert.deepEqual(requests.map(r=>r.body.event),['started','progress','paused','stopped']);
  assert.equal(requests.at(-1).body.positionMs,33000);
  assert.equal(requests[0].headers.Authorization,'Bearer secret');
  assert.equal(requests[0].redirect,'error');
  assert.equal(requests[0].credentials,'omit');
  assert.ok(!JSON.stringify(requests[0].body).includes('secret'));
  assert.equal(new Set(requests.map(r=>r.body.playbackId)).size,1);
});
