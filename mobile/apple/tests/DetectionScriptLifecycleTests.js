const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/DetectionScript.swift'), 'utf8').split('#"""')[1].split('"""#')[0];
function fixture(mainFrame = true, options = {}) {
  const messages = [], listeners = {};
  const location = { href: 'https://example.test/list', hostname: 'example.test' };
  const history = {
    pushState(_state, _title, url) { location.href = new URL(url, location.href).href; return 42; },
    replaceState(_state, _title, url) { location.href = new URL(url, location.href).href; },
  };
  function XHR() {}
  XHR.prototype.open = function () {};
  XHR.prototype.send = function () {};
  const window = {
    webkit: { messageHandlers: { playbridge: { postMessage: message => messages.push(message) } } },
    addEventListener(name, fn) { listeners[name] = fn; },
  };
  if (options.fetch) window.fetch = options.fetch;
  window.top = mainFrame ? window : {};
  const context = { window, location, history, navigator: { userAgent: 'fixture' }, URL,
    XMLHttpRequest: options.XMLHttpRequest || XHR,
    TextDecoder,
    document: { readyState: 'complete', querySelectorAll() { return options.elements || []; }, documentElement: {} },
    MutationObserver: class { observe() {} },
  };
  vm.runInNewContext(source, context);
  return { messages, listeners, history, location, window };
}
const page = fixture();
assert.equal(page.history.pushState({}, '', '/watch/1'), 42);
assert.equal(page.messages.length, 1);
assert.equal(page.messages[0].type, 'mediaLifecycle');
page.history.replaceState({}, '', '/watch/1');
assert.equal(page.messages.length, 1, 'Unchanged URLs must not create a new lifecycle');
page.location.href = 'https://example.test/watch/2';
page.listeners.popstate();
page.listeners.hashchange();
assert.equal(page.messages.length, 2, 'Duplicate navigation events must be coalesced');
const frame = fixture(false);
frame.history.pushState({}, '', '/ad');
assert.equal(frame.messages.length, 0, 'Iframe navigation must not advance the tab lifecycle');

async function subtitleChecks() {
  const track = fixture(true, { elements: [{ tagName: 'TRACK', src: 'https://subs.example/english.vtt' }] });
  assert.equal(track.messages.length, 1);
  assert.equal(track.messages[0].detectedBy, 'dom_track_element');

  function response(body, headers = {}) {
    return {
      url: 'https://subs.example/resource/42',
      headers: { get(name) { return headers[name.toLowerCase()] || null; } },
      clone() { return { text() { return Promise.resolve(body); } }; },
    };
  }

  const webvtt = fixture(true, {
    fetch() { return Promise.resolve(response('WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello', { 'content-type': 'text/plain' })); },
  });
  await webvtt.window.fetch('/resource/42');
  await Promise.resolve();
  assert.equal(webvtt.messages.length, 1);
  assert.equal(webvtt.messages[0].contentType, 'text/vtt');
  assert.equal(webvtt.messages[0].detectedBy, 'body_content_subtitle');

  const disposition = fixture(true, {
    fetch() {
      return Promise.resolve(response('subtitle payload unavailable', {
        'content-type': 'application/octet-stream',
        'content-disposition': 'attachment; filename="English.srt"',
      }));
    },
  });
  await disposition.window.fetch('/resource/42');
  const dispositionSubtitle = disposition.messages.find(message => message.detectedBy === 'subtitle_disposition');
  assert.equal(dispositionSubtitle.contentType, 'application/x-subrip');
  assert.equal(dispositionSubtitle.mediaKind, 'subtitle');

  function SubtitleXHR() {
    this.readyState = 0;
    this.responseType = '';
    this.responseText = '1\r\n00:00:01,250 --> 00:00:03,500\r\nHello';
    this.responseURL = 'https://subs.example/resource/43';
    this.listeners = {};
  }
  SubtitleXHR.prototype.open = function () {};
  SubtitleXHR.prototype.addEventListener = function (name, callback) { this.listeners[name] = callback; };
  SubtitleXHR.prototype.getResponseHeader = function (name) {
    return name.toLowerCase() === 'content-type' ? 'text/plain' : null;
  };
  SubtitleXHR.prototype.send = function () {
    this.readyState = 2;
    this.listeners.readystatechange();
    this.readyState = 4;
    this.listeners.loadend();
  };
  const originalSubtitleSend = SubtitleXHR.prototype.send;
  const srt = fixture(true, { XMLHttpRequest: SubtitleXHR });
  const xhr = new SubtitleXHR();
  xhr.open('GET', '/resource/43');
  xhr.send();
  assert.equal(srt.messages.length, 1);
  assert.equal(srt.messages[0].contentType, 'application/x-subrip');
  assert.equal(srt.messages[0].detectedBy, 'body_content_subtitle');

  class ArrayBufferSubtitleXHR extends SubtitleXHR {
    constructor() {
      super();
      this.responseType = 'arraybuffer';
      this.responseText = undefined;
      this.response = new TextEncoder().encode('WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello').buffer;
    }
    send() { originalSubtitleSend.call(this); }
  }
  const binary = fixture(true, { XMLHttpRequest: ArrayBufferSubtitleXHR });
  new ArrayBufferSubtitleXHR().send();
  assert.equal(binary.messages.length, 1);
  assert.equal(binary.messages[0].contentType, 'text/vtt');

  class OversizedSubtitleXHR extends ArrayBufferSubtitleXHR {
    constructor() {
      super();
      this.response = new Uint8Array(256 * 1024 + 1).buffer;
    }
    send() { originalSubtitleSend.call(this); }
  }
  const oversized = fixture(true, { XMLHttpRequest: OversizedSubtitleXHR });
  new OversizedSubtitleXHR().send();
  assert.equal(oversized.messages.length, 0, 'Oversized binary responses must not be decoded');

  class BlobSubtitleXHR extends SubtitleXHR {
    constructor() {
      super();
      this.responseType = 'blob';
      this.responseText = undefined;
      this.response = new Blob(['WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello']);
    }
    send() { originalSubtitleSend.call(this); }
  }
  const blob = fixture(true, { XMLHttpRequest: BlobSubtitleXHR });
  new BlobSubtitleXHR().send();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(blob.messages.length, 1);
  assert.equal(blob.messages[0].contentType, 'text/vtt');
}

subtitleChecks().then(() => {
  console.log('PASS: lifecycle isolation and subtitle detection routes');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
