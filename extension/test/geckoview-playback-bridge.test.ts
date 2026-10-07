import assert from 'node:assert/strict';
import test from 'node:test';
import { PAGE_PLAYBACK_BRIDGE_SCRIPT } from '../src/geckoview/page-bridge';
import { delivered, pageWorld } from './geckoview-page-channel-fixture';

function bridge(t: { after(fn: () => void): void }, beforeInject?: (world: ReturnType<typeof pageWorld>) => void) {
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `page-${++sequence}` } });
  t.after(world.close);
  beforeInject?.(world);
  const channel = world.inject(PAGE_PLAYBACK_BRIDGE_SCRIPT);
  assert.ok(channel, 'page script must hand its private port to the content script');
  const messages: any[] = [];
  channel.onMessage((message) => messages.push(message));
  const sent = (count: number) => delivered(() => messages.length >= count);
  const reply = (message: any, response: unknown) =>
    channel.post({ channel: 'linked', type: 'response', pageRequestId: message.pageRequestId, response });
  const event = (sessionId: string, event: string, detail: unknown) =>
    channel.post({ channel: 'linked', type: 'event', sessionId, event, detail });
  return { ...world, channel, messages, sent, reply, event };
}

test('destination selection uses native state and preserves explicit local selection', async (t) => {
  const { window, messages, reply, sent } = bridge(t);
  assert.equal(window.playbridge.capabilities.playback, 1);
  const destination = { id: 'this-device', name: 'This device', kind: 'local', connected: true };
  const status = window.playbridge.getPlaybackDestination();
  await sent(1);
  assert.equal(messages[0].channel, 'linked');
  assert.equal(messages[0].operation, 'destination');
  reply(messages[0], { ok: true, destination });
  assert.deepEqual(JSON.parse(JSON.stringify((await status).destination)), destination);
  const choice = window.playbridge.choosePlaybackDestination({ destinationId: 'this-device' });
  await sent(2);
  assert.equal(messages[1].operation, 'choose_destination');
  assert.equal(messages[1].payload.destinationId, 'this-device');
  reply(messages[1], { ok: true, destination });
  await choice;
});

test('play binds queue and progress only after the correlated native launch succeeds', async (t) => {
  const { window, messages, reply, event, sent } = bridge(t);
  const payload = { destinationId: 'native:living-room', items: [{ id: 's1e4', url: 'https://media.example/4.mp4', startPositionMs: 1000 }] };
  const opening = window.playbridge.play(payload);
  await sent(1);
  assert.equal(messages[0].operation, 'play');
  assert.deepEqual(messages[0].payload, payload);
  reply({ pageRequestId: 'wrong-document' }, { ok: true, sessionId: 'wrong' });
  reply(messages[0], { ok: true, sessionId: 'native-session' });
  const session = await opening;
  assert.equal(session.sessionId, 'native-session');
  await sent(2);
  assert.equal(messages[1].operation, 'ping');
  assert.equal(messages[1].payload.ready, true);
  reply(messages[1], { ok: true });
  let progress: any;
  session.addEventListener('statechange', (e: CustomEvent) => { progress = e.detail; });
  event('another-session', 'statechange', { positionMs: 999 });
  await delivered();
  assert.equal(progress, undefined);
  event('native-session', 'statechange', { currentIndex: 0, items: [{ id: 's1e4' }], positionMs: 2000, durationMs: 3000 });
  await delivered(() => progress !== undefined);
  assert.equal(progress.items[0].id, 's1e4');
  const supply = session.provideItems('need-1', { items: [{ id: 's1e5', url: 'https://media.example/5.mp4' }], endOfList: false });
  await sent(3);
  assert.equal(messages[2].sessionId, 'native-session');
  assert.equal(messages[2].payload.requestId, 'need-1');
  reply(messages[2], { ok: true });
  await supply;
  const unlink = session.unlink();
  await sent(4);
  reply(messages[3], { ok: true });
  await unlink;
  event('native-session', 'ended', { reason: 'unlinked' });
  await delivered();
  progress = undefined;
  event('native-session', 'statechange', { positionMs: 5000 });
  await delivered();
  assert.equal(progress, undefined);
});

test('a changed or disconnected destination rejects play without creating a session', async (t) => {
  const { window, messages, reply, sent } = bridge(t);
  const opening = window.playbridge.play({ destinationId: 'tv', items: [] });
  const rejection = assert.rejects(opening, (error: any) => error.code === 'receiver_changed');
  await sent(1);
  reply(messages[0], { ok: false, error: 'receiver_changed' });
  await rejection;
  assert.equal(messages.length, 1);
});

test('legacy cast travels on the private channel', async (t) => {
  const { window, messages, sent } = bridge(t);
  window.playbridge.cast({ url: 'https://media.example/legacy.mp4' });
  await sent(1);
  assert.deepEqual(messages, [{ channel: 'cast', payload: { url: 'https://media.example/legacy.mp4' } }]);
});

test('session events outside the page allow-list are dropped', async (t) => {
  const { window, messages, reply, event, sent } = bridge(t);
  const opening = window.playbridge.linkCast({ items: [] });
  await sent(1);
  reply(messages[0], { ok: true, sessionId: 'native-session' });
  const session = await opening;
  const seen: string[] = [];
  for (const name of ['needitems', 'statechange', 'ended', 'error', 'message']) {
    session.addEventListener(name, () => seen.push(name));
  }
  event('native-session', 'error', {});
  event('native-session', 'message', {});
  event('native-session', 'needitems', { requestId: 'need-1' });
  await delivered(() => seen.length > 0);
  await delivered();
  assert.deepEqual(seen, ['needitems']);
});

test('another script on the page cannot read requests or forge responses and session events', async (t) => {
  const page = bridge(t, ({ run }) => {
    // A later page script cannot run before the bridge; this one is installed first to
    // show that nothing reaches window even for a listener that was already waiting.
    run(`
      window.observed = [];
      for (const name of ['PlayBridgeLinkedRequest', 'PlayBridgeCast', 'message']) {
        window.addEventListener(name, (event) => window.observed.push(name), true);
      }
    `);
  });
  const { window, run, messages, sent, reply, event } = page;
  // A later script patches the primordials the old bridge used. EventTarget and
  // MessagePort are shared with this test process, so the patch is undone afterwards.
  run(`
    window.patched = [];
    const set = Map.prototype.set;
    Map.prototype.set = function(key, value) { window.patched.push('Map.set'); return set.call(this, key, value); };
    const parse = JSON.parse;
    JSON.parse = function(text) { window.patched.push('JSON.parse'); return parse(text); };
    const dispatch = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function(e) { if (e.type === 'needitems') window.patched.push('dispatch:' + e.type); return dispatch.call(this, e); };
    const post = MessagePort.prototype.postMessage;
    // Page requests are objects; the content script's replies are JSON strings.
    MessagePort.prototype.postMessage = function(message) { if (typeof message !== 'string') window.patched.push('postMessage'); return post.call(this, message); };
    window.restore = () => { EventTarget.prototype.dispatchEvent = dispatch; MessagePort.prototype.postMessage = post; };
  `);
  t.after(() => run('window.restore()'));
  const opening = window.playbridge.linkCast({ items: [{ id: 'one', url: 'https://media.example/one.mp4', headers: { Authorization: 'Bearer site-secret' } }] });
  await sent(1);
  assert.equal(messages[0].payload.items[0].headers.Authorization, 'Bearer site-secret');

  // The advisory's forgery: answer the pending open with an attacker session.
  run(`
    window.dispatchEvent(new CustomEvent('PlayBridgeLinkedResponseJson', {
      detail: JSON.stringify({ pageRequestId: 'page-1', response: { ok: true, sessionId: 'attacker-session' } })
    }));
  `);
  // A second boot message cannot attach another port: the boot listener is gone.
  const forged = new MessageChannel();
  t.after(() => { forged.port1.close(); forged.port2.close(); });
  window.dispatchEvent(new MessageEvent('playbridge-boot-guess', { ports: [forged.port2] }));
  reply(messages[0], { ok: true, sessionId: 'native-session' });
  const session = await opening;
  assert.equal(session.sessionId, 'native-session');

  const demands: unknown[] = [];
  session.addEventListener('needitems', (e: CustomEvent) => demands.push(e.detail));
  run(`
    window.dispatchEvent(new CustomEvent('PlayBridgeLinkedEventJson', {
      detail: JSON.stringify({ sessionId: 'native-session', event: 'needitems', detail: { requestId: 'attacker' } })
    }));
    window.dispatchEvent(new CustomEvent('PlayBridgeLinkedEventJson', {
      detail: JSON.stringify({ sessionId: 'native-session', event: 'ended', detail: {} })
    }));
  `);
  event('native-session', 'needitems', { requestId: 'native-demand' });
  await delivered(() => demands.length > 0);
  await delivered();
  assert.deepEqual(JSON.parse(JSON.stringify(demands)), [{ requestId: 'native-demand' }]);
  assert.equal(run('window.observed.length'), 0);
  // Session events use the dispatchEvent captured at boot.
  assert.deepEqual(JSON.parse(run('JSON.stringify(window.patched)')), []);
});

test('the page API is not installed when the private channel cannot be created', (t) => {
  const world = pageWorld({ crypto: {} });
  t.after(world.close);
  world.run('window.playbridge_injected_version = 6;');
  assert.equal(world.inject(PAGE_PLAYBACK_BRIDGE_SCRIPT), null);
  assert.equal(world.window.playbridge.linkCast, undefined);
});
