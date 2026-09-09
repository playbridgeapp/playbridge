const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/DetectionScript.swift'), 'utf8').split('#"""')[1].split('"""#')[0];
function fixture(mainFrame = true) {
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
  window.top = mainFrame ? window : {};
  const context = { window, location, history, navigator: { userAgent: 'fixture' }, URL,
    XMLHttpRequest: XHR,
    document: { readyState: 'complete', querySelectorAll() { return []; }, documentElement: {} },
    MutationObserver: class { observe() {} },
  };
  vm.runInNewContext(source, context);
  return { messages, listeners, history, location };
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
console.log('PASS: SPA push/replace/pop/hash events, return values and main-frame isolation');
