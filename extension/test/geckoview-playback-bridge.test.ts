import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { PAGE_PLAYBACK_BRIDGE_SCRIPT } from '../src/geckoview/page-bridge';

class BridgeEvent extends Event {
  detail: any;
  constructor(type: string, options?: { detail?: unknown }) { super(type); this.detail = options?.detail; }
}
function bridge() {
  const window = Object.assign(new EventTarget(), { playbridge: {} as any });
  const messages: any[] = [];
  let sequence = 0;
  window.addEventListener('PlayBridgeLinkedRequest', (event) => messages.push((event as BridgeEvent).detail));
  vm.runInNewContext(PAGE_PLAYBACK_BRIDGE_SCRIPT, {
    window, EventTarget, CustomEvent: BridgeEvent, Map, Promise, Error, Date, Math,
    crypto: { randomUUID: () => `page-${++sequence}` }, setTimeout, clearTimeout,
  });
  function reply(message: any, response: unknown) {
    window.dispatchEvent(new BridgeEvent('PlayBridgeLinkedResponseJson', {
      detail: JSON.stringify({ pageRequestId: message.pageRequestId, response }),
    }));
  }
  return { window, messages, reply };
}

test('destination selection uses native state and preserves explicit local selection', async () => {
  const { window, messages, reply } = bridge();
  assert.equal(window.playbridge.capabilities.playback, 1);
  const destination = { id: 'this-device', name: 'This device', kind: 'local', connected: true };
  const status = window.playbridge.getPlaybackDestination();
  assert.equal(messages[0].operation, 'destination');
  reply(messages[0], { ok: true, destination });
  assert.deepEqual(JSON.parse(JSON.stringify((await status).destination)), destination);
  const choice = window.playbridge.choosePlaybackDestination({ destinationId: 'this-device' });
  assert.equal(messages[1].operation, 'choose_destination');
  assert.equal(messages[1].payload.destinationId, 'this-device');
  reply(messages[1], { ok: true, destination });
  await choice;
});

test('play binds queue and progress only after the correlated native launch succeeds', async () => {
  const { window, messages, reply } = bridge();
  const payload = { destinationId: 'native:living-room', items: [{ id: 's1e4', url: 'https://media.example/4.mp4', startPositionMs: 1000 }] };
  const opening = window.playbridge.play(payload);
  assert.equal(messages[0].operation, 'play');
  assert.equal(messages[0].payload, payload);
  reply({ pageRequestId: 'wrong-document' }, { ok: true, sessionId: 'wrong' });
  reply(messages[0], { ok: true, sessionId: 'native-session' });
  const session = await opening;
  assert.equal(session.sessionId, 'native-session');
  assert.equal(messages[1].operation, 'ping');
  assert.equal(messages[1].payload.ready, true);
  reply(messages[1], { ok: true });
  let progress: any;
  session.addEventListener('statechange', (event: BridgeEvent) => { progress = event.detail; });
  const event = (sessionId: string, event: string, detail: unknown) => window.dispatchEvent(new BridgeEvent('PlayBridgeLinkedEventJson', {
    detail: JSON.stringify({ sessionId, event, detail }),
  }));
  event('another-session', 'statechange', { positionMs: 999 });
  assert.equal(progress, undefined);
  event('native-session', 'statechange', { currentIndex: 0, items: [{ id: 's1e4' }], positionMs: 2000, durationMs: 3000 });
  assert.equal(progress.items[0].id, 's1e4');
  const supply = session.provideItems('need-1', { items: [{ id: 's1e5', url: 'https://media.example/5.mp4' }], endOfList: false });
  assert.equal(messages[2].sessionId, 'native-session');
  assert.equal(messages[2].payload.requestId, 'need-1');
  reply(messages[2], { ok: true });
  await supply;
  const unlink = session.unlink();
  reply(messages[3], { ok: true });
  await unlink;
  event('native-session', 'ended', { reason: 'unlinked' });
  progress = undefined;
  event('native-session', 'statechange', { positionMs: 5000 });
  assert.equal(progress, undefined);
});

test('a changed or disconnected destination rejects play without creating a session', async () => {
  const { window, messages, reply } = bridge();
  const opening = window.playbridge.play({ destinationId: 'tv', items: [] });
  const rejection = assert.rejects(opening, (error: any) => error.code === 'receiver_changed');
  reply(messages[0], { ok: false, error: 'receiver_changed' });
  await rejection;
  assert.equal(messages.length, 1);
});
