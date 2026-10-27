const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/DetectionScript.swift'), 'utf8')
  .match(/static let source = #"""\n([\s\S]*?)\n\s*"""#/)[1];
const vtt = '\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n';
const srt = '1\n00:00:01,000 --> 00:00:02,000\nHello\n';
const settle = async () => { for (let i = 0; i < 15; i++) await new Promise(resolve => setTimeout(resolve, 0)); };
function fixture(options = {}, mainFrame = true) {
  const messages = [], tracks = [], observers = [], blobs = new Map();
  const window = new EventTarget();
  const original = function (blob) {
    if (!blob) throw new TypeError('Invalid blob');
    const url = 'blob:https://page.example/' + blobs.size;
    blobs.set(url, blob);
    return url;
  };
  const pageURL = { createObjectURL: original };
  Object.assign(window, { top: mainFrame ? window : {}, URL: pageURL,
    __playbridgeDetectionOptions: { ...options, visibilityOverrides: false, networkDetection: false },
    playbridge: { sentinel: true },
    fetch: async url => {
      const blob = blobs.get(url);
      return { headers: new Headers({ 'content-type': blob.type, 'content-length': String(blob.size) }), blob: async () => blob };
    },
    webkit: { messageHandlers: { playbridge: { postMessage(message) { messages.push(message); } } } },
  });
  const document = Object.assign(new EventTarget(), { readyState: 'complete', documentElement: {}, querySelectorAll: () => tracks });
  const context = { window, document, Blob, URL, TextDecoder, TextEncoder, Map, Set,
    history: { pushState() {}, replaceState() {} }, location: { hostname: 'page.example', href: 'https://page.example/watch' },
    navigator: { userAgent: 'FixtureBrowser/1' },
    MutationObserver: class { constructor(fn) { this.callback = fn; observers.push(this); } observe() {} disconnect() {} },
  };
  vm.runInNewContext(source, context);
  return { window, pageURL, original, messages, tracks, observers, blobs,
    detections: () => messages.filter(message => message.subtitleText),
    setOptions(value) { window.__playbridgeSetDetectionOptions({ ...value, visibilityOverrides: false, networkDetection: false }); } };
}

test('iOS captures VTT and SRT in all frames, dedupes content and preserves the page API', async () => {
  for (const mainFrame of [true, false]) {
    const f = fixture({}, mainFrame), api = f.window.playbridge;
    const url = f.pageURL.createObjectURL(new Blob([vtt]));
    f.tracks.push({ tagName: 'TRACK', kind: 'captions', src: url, label: 'English' });
    await settle();
    f.pageURL.createObjectURL(new Blob([vtt]));
    f.pageURL.createObjectURL(new Blob([srt], { type: 'application/x-subrip' }));
    f.pageURL.createObjectURL(new Blob([vtt], { type: 'video/mp4' }));
    f.pageURL.createObjectURL(new Blob(['unrelated text']));
    f.pageURL.createObjectURL(new Blob(['x'.repeat(2 * 1024 * 1024 + 1)], { type: 'text/vtt' }));
    f.pageURL.createObjectURL({}); // MediaSource-like non-Blob.
    await settle();
    assert.equal(f.detections().length, 2);
    assert.equal(f.detections()[0].subtitleLabel, 'English');
    assert.equal(f.detections()[0].subtitleFormat, 'vtt');
    assert.equal(f.detections()[1].subtitleFormat, 'srt');
    assert.match(f.detections()[0].url, /^playbridge-subtitle:/);
    assert.equal(f.window.playbridge, api);
  }
});

test('iOS only installs hooks while detection and subtitles are on; old wrappers stay inactive', async () => {
  const f = fixture({ enabled: false });
  assert.equal(f.pageURL.createObjectURL, f.original);
  f.setOptions({ subtitles: false });
  assert.equal(f.pageURL.createObjectURL, f.original);
  f.setOptions({});
  const wrapper = f.pageURL.createObjectURL;
  f.pageURL.createObjectURL(new Blob([vtt]));
  f.setOptions({ enabled: false });
  await settle();
  assert.equal(f.detections().length, 0);
  assert.equal(f.pageURL.createObjectURL, f.original);
  f.setOptions({});
  wrapper(new Blob([srt]));
  await settle();
  assert.equal(f.detections().length, 0);
  assert.throws(() => f.pageURL.createObjectURL(null), TypeError);
  f.window.dispatchEvent(new Event('pagehide'));
  assert.equal(f.pageURL.createObjectURL, f.original);
});

test('iOS scans blob tracks created before the hook with their language label', async () => {
  const f = fixture({ enabled: false });
  const url = f.original(new Blob([srt]));
  f.tracks.push({ tagName: 'TRACK', kind: 'subtitles', src: url, srclang: 'en' });
  f.setOptions({});
  await settle();
  assert.equal(f.detections().length, 1);
  assert.equal(f.detections()[0].subtitleText, srt);
  assert.equal(f.detections()[0].subtitleLabel, 'en');
  f.observers.at(-1).callback([{ addedNodes: [], type: 'attributes', target: { ...f.tracks[0], nodeType: 1 } }]);
  await settle();
  assert.equal(f.detections().length, 1);
});
