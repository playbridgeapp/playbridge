import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

// The popup script runs at import time and wires its controls to the DOM. These fakes record
// every click handler it registers and every runtime message it sends, so the test can drive
// the real popup code without a browser.

const sent: any[] = [];
const clickHandlers = new Map<string, Array<(event?: unknown) => unknown>>();
const elements = new Map<string, any>();

function fakeElement(id: string): any {
  const listeners: Record<string, Array<(event?: unknown) => unknown>> = {};
  const state: Record<string, any> = {
    id,
    value: "",
    textContent: "",
    innerHTML: "",
    hidden: false,
    disabled: false,
    checked: false,
    dataset: {},
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
    addEventListener(type: string, handler: (event?: unknown) => unknown) {
      (listeners[type] ??= []).push(handler);
      if (type === "click") {
        clickHandlers.set(id, [...(clickHandlers.get(id) ?? []), handler]);
      }
    },
    querySelectorAll: () => [],
  };
  // Any other DOM method the popup calls is a no-op that returns another fake element.
  return new Proxy(state, {
    get(target, prop) {
      if (prop in target || typeof prop === "symbol" || prop === "then") return target[prop as string];
      return () => fakeElement(String(prop));
    },
    set(target, prop, value) {
      target[prop as string] = value;
      return true;
    },
  });
}

(globalThis as any).document = {
  getElementById(id: string) {
    if (!elements.has(id)) elements.set(id, fakeElement(id));
    return elements.get(id);
  },
  querySelectorAll: () => [],
  querySelector: () => null,
  createElement: (tag: string) => fakeElement(tag),
};
// The web-extension polyfill requires a chrome runtime id before it uses the browser global.
(globalThis as any).chrome = { runtime: { id: "test" } };
(globalThis as any).window = { addEventListener() {} };
(globalThis as any).browser = {
  runtime: {
    id: "test",
    sendMessage(message: any) {
      sent.push(message);
      return Promise.resolve({ ok: true, success: true, targetName: "Test TV" });
    },
    onMessage: { addListener() {} },
  },
  storage: {
    local: { get: async () => ({}), set: async () => {} },
    onChanged: { addListener() {} },
  },
};

await import("../src/ui/popup");

test("popup offers no action that sends target 'browser'", async () => {
  // Fire every click handler the popup registered, so the test does not depend on any button id.
  for (const handlers of clickHandlers.values()) {
    for (const handler of handlers) {
      try {
        await handler();
      } catch {
        // Handlers that need device or tab state may reject. Only the messages matter here.
      }
    }
  }
  const browserTargets = sent.filter((message) => message?.target === "browser");
  assert.deepEqual(browserTargets, []);
  assert.equal(clickHandlers.has("open-current-tab-btn"), false);
  assert.equal(clickHandlers.has("open-url-browser-btn"), false);
});

test("popup markup no longer contains the browser-opening controls", () => {
  const html = fs.readFileSync(path.join("src", "ui", "popup.html"), "utf8");
  assert.equal(html.includes('id="open-current-tab-btn"'), false);
  assert.equal(html.includes('id="open-url-browser-btn"'), false);
  assert.equal(html.includes("Open in Browser"), false);
  assert.equal(html.includes("Open Current Tab on TV"), false);
  assert.equal(html.includes('id="open-url-player-btn"'), true);
});

test("Play in Player still sends the URL to the player target", async () => {
  sent.length = 0;
  elements.get("custom-url-input").value = "  https://example.com/video  ";
  await clickHandlers.get("open-url-player-btn")![0]!();
  assert.deepEqual(sent, [
    {
      action: "wsSendToTv",
      url: "https://example.com/video",
      target: "player",
    },
  ]);
});

test("Play in Player asks for a URL instead of sending an empty one", async () => {
  sent.length = 0;
  elements.get("custom-url-input").value = "   ";
  await clickHandlers.get("open-url-player-btn")![0]!();
  assert.deepEqual(sent, []);
});
