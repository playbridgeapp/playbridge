// Run with: node --test mobile/apple/tests/PageCastScriptTests.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const swift = fs.readFileSync(path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/PageCastScript.swift'), 'utf8');
const source = swift.match(/static let source = #"""\n([\s\S]*?)\n\s*"""#/)[1];

class CustomEvent extends Event {
  constructor(name, options = {}) { super(name); this.detail = options.detail; }
}

function browser({ subframe = false, existing = {}, unavailable = false } = {}) {
  const window = new EventTarget();
  const messages = [];
  const timers = new Map();
  let time = 0;
  let timerId = 0;
  Object.assign(window, {
    playbridge: existing,
    webkit: { messageHandlers: { playbridge: { postMessage(message) {
      if (unavailable) throw new Error('disconnected');
      messages.push(JSON.parse(JSON.stringify(message)));
    } } } },
  });
  window.top = subframe ? {} : window;
  const context = vm.createContext({
    window, EventTarget, CustomEvent, TextEncoder, crypto: { randomUUID: () => 'document' },
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { fn, at: time + delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
  });
  vm.runInContext(source, context);
  return {
    window, messages, timers, api: window.playbridge,
    inject() { vm.runInContext(source, context); },
    receive(message) {
      if (typeof message === 'string') {
        try { message = JSON.stringify({ documentToken: 'document', ...JSON.parse(message) }); } catch (_) {}
      } else if (message) message = { documentToken: 'document', ...message };
      window.__playbridgePageCastReceive(message);
    },
    result(request, extra = {}) { this.receive({ requestId: request.requestId, ok: true, ...extra }); },
    async advance(ms) {
      const until = time + ms;
      for (;;) {
        const entry = [...timers].filter(([, timer]) => timer.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
        if (!entry) break;
        time = entry[1].at;
        timers.delete(entry[0]);
        entry[1].fn();
        await Promise.resolve();
        await Promise.resolve();
      }
      time = until;
    },
    async open(sessionId = 'session') {
      const promise = this.api.linkCast({ items: [{ id: 'one', url: 'https://media.example/video.mp4' }] });
      this.result(this.messages.at(-1), { sessionId });
      const session = await promise;
      await this.advance(0);
      this.result(this.messages.at(-1)); // ready ping
      await Promise.resolve();
      return session;
    },
  };
}

test('installs only in the main frame and preserves existing page API on reinjection', () => {
  const child = browser({ subframe: true });
  assert.equal(child.api.linkCast, undefined);
  assert.equal(child.window.__playbridgePageCastReceive, undefined);
  const existing = { custom: 'keep', capabilities: { other: 1 } };
  const b = browser({ existing });
  assert.equal(b.api, existing);
  assert.equal(b.api.custom, 'keep');
  assert.equal(b.api.capabilities.other, 1);
  assert.equal(b.api.capabilities.linkedCast, 1);
  assert.equal(b.api.capabilities.explicitHeaders, 1);
  assert.equal(b.api.capabilities.privateNetworkOriginPermission, 1);
  const link = b.api.linkCast;
  b.inject();
  assert.equal(b.api.linkCast, link);
});

test('one-off casting forwards complete JSON data without exposing rejecting promises', async () => {
  const b = browser();
  const payload = { items: [{ url: 'https://media.example/v.mp4', headers: { Origin: 'https://site.example' },
    subtitleResources: [{ url: 'https://media.example/en.vtt', language: 'en' }] }], startIndex: 0 };
  assert.equal(b.api.cast(payload), undefined);
  const sent = b.messages.at(-1);
  assert.equal(sent.operation, 'cast');
  assert.deepEqual(sent.payload, payload);
  b.receive({ requestId: sent.requestId, ok: false, error: 'permission_denied' });
  await Promise.resolve();
  assert.equal(b.timers.size, 0);
});

test('linked API matches Android operations and delivers demand after listeners attach', async () => {
  const b = browser();
  const opening = b.api.linkCast({ items: [] });
  const openRequest = b.messages.at(-1);
  assert.equal(openRequest.operation, 'open');
  b.result(openRequest, { sessionId: 'linked' });
  // Native may deliver an initial event immediately after its open response.
  b.receive({ sessionId: 'linked', event: 'needitems', detail: { requestId: 'demand-1' } });
  const session = await opening;
  assert.ok(session instanceof EventTarget);
  assert.equal(session.sessionId, 'linked');
  const demands = [];
  session.addEventListener('needitems', event => demands.push(event.detail));
  await b.advance(0);
  assert.deepEqual(demands, [{ requestId: 'demand-1' }]);
  assert.deepEqual(b.messages.at(-1).payload, { ready: true });
  b.result(b.messages.at(-1));
  const cases = [
    ['replace', () => session.replace([{ id: 'a' }], 2, { title: 'Series' }),
      { items: [{ id: 'a' }], startIndex: 2, metadata: { title: 'Series' } }],
    ['append', () => session.append([{ id: 'b' }], { privateNetworkOrigins: ['http://192.168.1.2'] }),
      { items: [{ id: 'b' }], privateNetworkOrigins: ['http://192.168.1.2'] }],
    ['jump', () => session.jump(3), { index: 3 }],
    ['supply', () => session.provideItems('demand-1', { items: [{ id: 'c' }], endOfList: true }),
      { requestId: 'demand-1', items: [{ id: 'c' }], endOfList: true, privateNetworkOrigins: [] }],
  ];
  for (const [operation, invoke, payload] of cases) {
    const promise = invoke();
    const sent = b.messages.at(-1);
    assert.equal(sent.operation, operation);
    assert.equal(sent.sessionId, 'linked');
    assert.deepEqual(sent.payload, payload);
    b.result(sent);
    assert.equal((await promise).ok, true);
  }
});

test('errors, malformed results, missing native bridge and request timeouts clean up', async () => {
  const b = browser();
  const denied = b.api.linkCast({});
  b.receive({ requestId: b.messages.at(-1).requestId, ok: false, error: 'permission_denied', message: 'Denied' });
  await assert.rejects(denied, { code: 'permission_denied', message: 'Denied' });
  const malformed = b.api.linkCast({});
  b.result(b.messages.at(-1));
  await assert.rejects(malformed, { code: 'invalid_response' });
  const offline = browser({ unavailable: true });
  await assert.rejects(offline.api.linkCast({}), { code: 'native_unavailable' });
  assert.equal(offline.timers.size, 0);
  const timeout = b.api.linkCast({});
  const expired = b.messages.at(-1);
  const rejection = assert.rejects(timeout, { code: 'timeout' });
  await b.advance(600000);
  await rejection;
  assert.equal(b.messages.at(-1).operation, 'cancel');
  assert.equal(b.messages.at(-1).payload.requestId, expired.requestId);
  assert.equal(b.timers.size, 0);
  b.result(expired, { sessionId: 'late' });
  assert.equal(b.timers.size, 0);
});

test('payload and pending limits are bounded and IDs do not collide', async () => {
  const b = browser();
  const cyclic = {}; cyclic.self = cyclic;
  await assert.rejects(b.api.linkCast(cyclic), { code: 'invalid_payload' });
  await assert.rejects(b.api.linkCast({ title: '字幕'.repeat(12000) }), { code: 'resource_limit' });
  const requests = Array.from({ length: 32 }, () => b.api.linkCast({}));
  assert.equal(new Set(b.messages.map(message => message.requestId)).size, 32);
  await assert.rejects(b.api.linkCast({}), { code: 'resource_limit' });
  const settled = Promise.allSettled(requests);
  b.window.dispatchEvent(new Event('pagehide'));
  assert.ok((await settled).every(result => result.status === 'rejected'));
  assert.equal(b.timers.size, 0);
});

test('unlink and remote-ended events stop heartbeats and reject commands on old sessions', async () => {
  const b = browser();
  const session = await b.open();
  const events = [];
  session.addEventListener('ended', event => events.push(event.detail));
  await b.advance(20000);
  assert.equal(b.messages.at(-1).operation, 'ping');
  b.result(b.messages.at(-1));
  const unlink = session.unlink();
  const unlinkRequest = b.messages.at(-1);
  // Some native implementations publish ended before replying to unlink.
  b.receive({ sessionId: session.sessionId, event: 'ended', detail: { reason: 'unlinked' } });
  await unlink;
  b.result(unlinkRequest);
  assert.equal(events.length, 1);
  await assert.rejects(session.jump(1), { code: 'session_ended' });
  assert.equal(b.timers.size, 0);
});

test('pagehide cancels open requests and sessions, with safe back/forward restoration', async () => {
  const b = browser();
  const session = await b.open();
  const opening = b.api.linkCast({});
  const openRequest = b.messages.at(-1);
  const rejected = assert.rejects(opening, { code: 'page_unavailable' });
  b.window.dispatchEvent(new Event('pagehide'));
  await rejected;
  assert.ok(b.messages.some(message => message.operation === 'cancel' && message.payload.requestId === openRequest.requestId));
  assert.ok(b.messages.some(message => message.operation === 'unlink' && message.sessionId === session.sessionId));
  assert.equal(b.timers.size, 0);
  await assert.rejects(b.api.linkCast({}), { code: 'page_unavailable' });
  const restored = new Event('pageshow'); restored.persisted = true;
  b.window.dispatchEvent(restored);
  await assert.rejects(session.jump(0), { code: 'session_ended' });
  const newSession = await b.open('new-session');
  assert.equal(newSession.sessionId, 'new-session');
});

test('same-document navigation retains sessions, unknown events are ignored, and receive accepts JSON', async () => {
  const b = browser();
  const session = await b.open();
  b.window.dispatchEvent(new Event('popstate'));
  b.window.dispatchEvent(new Event('hashchange'));
  const states = [];
  session.addEventListener('statechange', event => states.push(event.detail));
  b.receive('invalid JSON');
  b.receive(JSON.stringify({ sessionId: session.sessionId, event: 'statechange', detail: { index: 2 } }));
  b.receive({ sessionId: 'unknown', event: 'statechange', detail: { index: 3 } });
  b.receive({ sessionId: session.sessionId, event: 'unsupported', detail: {} });
  assert.deepEqual(JSON.parse(JSON.stringify(states)), [{ index: 2 }]);
  const jump = session.jump(1);
  b.result(b.messages.at(-1));
  await jump;
});

test('heartbeat failures end sessions and 30-second command timeout cancels native work', async () => {
  const b = browser();
  const session = await b.open();
  const ended = [];
  session.addEventListener('ended', event => ended.push(event.detail));
  const jump = session.jump(1);
  const jumpRequest = b.messages.at(-1);
  const rejected = assert.rejects(jump, { code: 'timeout' });
  await b.advance(30000);
  await rejected;
  assert.ok(b.messages.some(message => message.operation === 'cancel' && message.payload.requestId === jumpRequest.requestId));
  await b.advance(20000);
  assert.equal(ended.length, 1);
  assert.equal(ended[0].reason, 'timeout');
  assert.equal(b.timers.size, 0);
});

test('requests carry document identity and delayed messages from another document are ignored', async () => {
  const b = browser();
  const opening = b.api.linkCast({});
  const request = b.messages.at(-1);
  assert.equal(request.documentToken, 'document');
  b.receive({ documentToken: 'previous-document', requestId: request.requestId, ok: true, sessionId: 'stale' });
  assert.equal(b.timers.size, 1);
  b.result(request, { sessionId: 'current' });
  const session = await opening;
  await b.advance(0);
  b.result(b.messages.at(-1));
  const states = [];
  session.addEventListener('statechange', event => states.push(event.detail));
  b.receive({ documentToken: 'previous-document', sessionId: 'current', event: 'statechange', detail: {} });
  assert.equal(states.length, 0);
});
