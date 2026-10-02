// Run with: node --test mobile/apple/tests/DetectionScriptTests.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/DetectionScript.swift'), 'utf8')
  .match(/static let source = #"""\n([\s\S]*?)\n\s*"""#/)[1];

function browser(options = {}) {
  const window = new EventTarget();
  const document = new EventTarget();
  const messages = [], queries = [], observers = [];
  const image = Object.assign(new EventTarget(), {
    tagName: 'IMG', src: 'https://media.test/poster.jpg', width: 400, height: 400, complete: false,
  });
  let imageListeners = 0;
  const add = image.addEventListener.bind(image), remove = image.removeEventListener.bind(image);
  image.addEventListener = (...args) => { imageListeners++; add(...args); };
  image.removeEventListener = (...args) => { imageListeners--; remove(...args); };
  const elements = [image,
    { tagName: 'VIDEO', src: 'https://media.test/movie.mp4' },
    { tagName: 'AUDIO', src: 'https://media.test/sound.mp3' },
    { tagName: 'TRACK', src: 'https://media.test/subtitles.vtt' }];
  Object.assign(document, {
    readyState: 'complete', documentElement: {},
    querySelectorAll(selector) {
      queries.push(selector);
      const tags = selector.split(',').map(t => t.trim().toUpperCase());
      return elements.filter(el => tags.includes(el.tagName));
    },
  });
  let clones = 0;
  let responseBody = 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhello';
  const originalFetch = async () => ({
    url: 'https://media.test/captions',
    headers: { get(name) { return name === 'content-type' ? 'text/plain' : null; } },
    clone() {
      clones++;
      return { text: async () => responseBody };
    },
  });
  class XHR {
    open() {}
    send() {}
  }
  const originalOpen = XHR.prototype.open;
  const originalHistory = { pushState() {}, replaceState() {} };
  const history = { ...originalHistory };
  const location = { href: 'https://site.test/home', hostname: 'site.test' };
  Object.assign(window, {
    top: window, fetch: originalFetch,
    __playbridgeDetectionOptions: options,
    webkit: { messageHandlers: { playbridge: { postMessage(message) { messages.push(JSON.parse(JSON.stringify(message))); } } } },
  });
  class MutationObserver {
    constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
    observe() {}
    disconnect() { this.disconnected = true; }
  }
  const context = vm.createContext({ window, document, history, location, navigator: { userAgent: 'fixture' },
    XMLHttpRequest: XHR, MutationObserver, URL, TextDecoder, Set, WeakSet });
  vm.runInContext(source, context);
  return { window, document, history, location, messages, queries, observers, image, XHR, originalFetch, originalOpen, originalHistory,
    options(value) { window.__playbridgeSetDetectionOptions(value); },
    get clones() { return clones; }, get imageListeners() { return imageListeners; },
  };
}

test('disabled detector installs no media hooks, observers or image listeners', () => {
  const b = browser({ enabled: false });
  assert.equal(b.window.fetch, b.originalFetch);
  assert.equal(b.XHR.prototype.open, b.originalOpen);
  assert.equal(b.observers.length, 0);
  assert.equal(b.imageListeners, 0);
  assert.deepEqual(b.messages.map(m => m.type), ['detectionPolicyRequest']);
});

test('disabling images removes their scan targets and pending load listeners while other media remain', () => {
  const b = browser();
  assert.equal(b.imageListeners, 1);
  b.messages.length = 0;
  const oldObserver = b.observers.at(-1);
  b.options({ images: false });
  assert.equal(oldObserver.disconnected, true);
  assert.equal(b.imageListeners, 0);
  assert.equal(b.queries.at(-1).includes('img'), false);
  assert.deepEqual(b.messages.filter(m => m.type === 'video').map(m => m.mediaKind).sort(), ['audio', 'subtitle', 'video']);
  b.image.dispatchEvent(new Event('load'));
  assert.equal(b.messages.some(m => m.mediaKind === 'image'), false);
  b.options({ images: true });
  assert.equal(b.messages.some(m => m.mediaKind === 'image'), true);
});

test('each media type switch prevents its DOM reports', () => {
  for (const [key, kind] of [['videos', 'video'], ['images', 'image'], ['audio', 'audio'], ['subtitles', 'subtitle']]) {
    const b = browser({ [key]: false });
    assert.equal(b.messages.some(m => m.mediaKind === kind), false, key);
  }
});

test('network detection can be removed without disabling DOM discovery', () => {
  const b = browser();
  b.options({ networkDetection: false });
  assert.equal(b.window.fetch, b.originalFetch);
  assert.equal(b.XHR.prototype.open, b.originalOpen);
  assert.equal(b.messages.some(m => m.mediaKind === 'video'), true);
});

test('disabling response or subtitle scanning avoids response cloning', async () => {
  for (const options of [{ responseScanning: false }, { subtitles: false }]) {
    const b = browser(options);
    await b.window.fetch('https://media.test/captions');
    assert.equal(b.clones, 0);
  }
  const b = browser();
  await b.window.fetch('https://media.test/captions');
  await Promise.resolve();
  assert.equal(b.clones, 1);
  assert.equal(b.messages.some(m => m.detectedBy === 'body_content_subtitle'), true);
});

test('page rescans can be disabled while preserving media lifecycle updates', () => {
  const b = browser({ navigationRescans: false });
  const before = b.queries.length;
  b.location.href = 'https://site.test/search';
  b.history.pushState();
  assert.equal(b.queries.length, before);
  assert.equal(b.messages.some(m => m.type === 'mediaLifecycle'), true);
});

test('master disable restores hooks and visibility state and asks for policy on BFCache return', () => {
  const b = browser();
  b.options({ enabled: false });
  assert.equal(b.window.fetch, b.originalFetch);
  assert.equal(b.XHR.prototype.open, b.originalOpen);
  assert.equal(b.history.pushState, b.originalHistory.pushState);
  assert.equal(Object.getOwnPropertyDescriptor(b.document, 'visibilityState'), undefined);
  assert.equal(b.imageListeners, 0);
  assert.equal(b.observers.every(o => o.disconnected), true);
  b.window.dispatchEvent(new Event('pageshow'));
  assert.equal(b.messages.at(-1).type, 'detectionPolicyRequest');
});
