// SPDX-License-Identifier: MPL-2.0
// Run with: node --test mobile/apple/tests/BridgedAppStartupPaintTests.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const swift = fs.readFileSync(path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser/BridgedAppStartupTiming.swift'), 'utf8');
const source = swift.match(/static let paintScript = #"""\n([\s\S]*?)\n\s*"""#/)[1];

function fixture(paintTiming, readyState = 'loading') {
  const messages = [], frames = [], listeners = {};
  let observer;
  class PerformanceObserver {
    static supportedEntryTypes = paintTiming ? ['paint'] : [];
    constructor(callback) { this.callback = callback; observer = this; }
    observe() {}
    disconnect() { this.disconnected = true; }
  }
  vm.runInNewContext(source, {
    PerformanceObserver,
    window: { webkit: { messageHandlers: { startupPaint: { postMessage: value => messages.push(value) } } } },
    document: { readyState, addEventListener: (name, callback) => { listeners[name] = callback; } },
    requestAnimationFrame: callback => frames.push(callback)
  });
  return { messages, frames, listeners, get observer() { return observer; } };
}

test('paint timing reports FCP once, ignoring first-paint-only entries', () => {
  const f = fixture(true);
  const emit = names => f.observer.callback({ getEntries: () => names.map(name => ({ name })) });
  emit(['first-paint']);
  assert.deepEqual(f.messages, []);
  emit(['first-contentful-paint']); emit(['first-contentful-paint']);
  assert.deepEqual(f.messages, ['fcp']);
  assert.equal(f.observer.disconnected, true);
});

for (const state of ['loading', 'complete']) test(`unsupported Paint Timing labels a two-frame opportunity (${state})`, () => {
  const f = fixture(false, state);
  if (state === 'loading') {
    assert.equal(f.frames.length, 0);
    f.listeners.DOMContentLoaded();
  }
  assert.deepEqual(f.messages, []);
  f.frames.shift()();
  assert.deepEqual(f.messages, []);
  f.frames.shift()();
  assert.deepEqual(f.messages, ['frame']);
});
