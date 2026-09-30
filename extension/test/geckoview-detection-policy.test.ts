import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createContext, runInContext } from "node:vm";
import { TabDetectionPolicy, type DetectionPolicy } from "../src/geckoview/detection-policy";

const policy = (enabled: boolean, revision = 1, browserEnabled = true): DetectionPolicy => ({
  type: "detection_policy", enabled, revision, browserEnabled,
  bridgedAppOrigins: ["https://app.example"],
});
const event = () => {
  const listeners: Function[] = [];
  return { listeners, addListener: (listener: Function) => listeners.push(listener) };
};
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

function harness() {
  const messages: any[] = [];
  const nativeMessages: any[] = [];
  const filters: any[] = [];
  const policyMessages = event();
  const onMessage = event();
  const nativePort = {
    onMessage: event(), onDisconnect: event(),
    postMessage: (message: any) => {
      nativeMessages.push(message);
      for (const listener of nativePort.onMessage.listeners) {
        listener({ type: "linked_result", bridgeRequestId: message.bridgeRequestId, ok: true });
      }
    },
  };
  const browser = {
    runtime: {
      onMessage,
      connectNative: (name: string) => name === "detectorPolicy"
        ? { onMessage: policyMessages, onDisconnect: event() } : nativePort,
      sendMessage: async (message: any) => { messages.push(message); return true; },
      sendNativeMessage: async (_app: string, message: any) => {
        nativeMessages.push(message);
        return policy(false);
      },
    },
    tabs: { onRemoved: event(), sendMessage: async () => {} },
    webRequest: {
      onBeforeRequest: event(), onBeforeSendHeaders: event(), onHeadersReceived: event(),
      filterResponseData: () => {
        const filter = { write: () => {}, disconnected: false, disconnect() { this.disconnected = true; } };
        filters.push(filter);
        return filter;
      },
    },
    webNavigation: {
      onBeforeNavigate: event(), onCommitted: event(), onHistoryStateUpdated: event(),
      onReferenceFragmentUpdated: event(), onErrorOccurred: event(),
    },
  };
  const timers = new Map<number, Function>();
  let nextTimer = 0;
  const sandbox = {
    browser, URL, TextDecoder, TextEncoder, Uint8Array, ArrayBuffer, Map, Set, WeakSet,
    console: { log: () => {} },
    setTimeout: (callback: Function) => { timers.set(++nextTimer, callback); return nextTimer; },
    clearTimeout: (id: number) => timers.delete(id),
    setInterval: () => 1,
  };
  return { sandbox, browser, messages, nativeMessages, filters, policyMessages, onMessage, timers };
}

function script(name: string): string {
  return readFileSync(new URL(`./geckoview-runtime/${name}.js`, import.meta.url), "utf8");
}

test("unknown tabs and app subframes never inspect responses; a browser tab at the same origin still can", () => {
  const state = new TabDetectionPolicy();
  assert.equal(state.allowsRequest(1, "main_frame", "https://normal.example"), false);
  state.apply(policy(false));
  assert.equal(state.allowsRequest(1, "main_frame", "https://app.example"), false);
  assert.equal(state.allowsRequest(1, "script", "https://cdn.example/config.js"), false);
  assert.equal(state.allowsRequest(2, "main_frame", "https://normal.example"), true);
  state.apply(policy(false), 1);
  state.apply(policy(true), 2);
  assert.equal(state.allowsRequest(1, "xmlhttprequest", "https://normal.example/config.json"), false);
  assert.equal(state.allowsRequest(1, "sub_frame", "https://normal.example"), false);
  assert.equal(state.allowsRequest(2, "main_frame", "https://app.example"), true);
  assert.equal(state.allowsRequest(-1, "main_frame", "https://normal.example"), false);
  state.apply(policy(false, 2, false));
  assert.equal(state.allows(2), false);
  assert.equal(state.apply(policy(true, 1), 1), false);
  assert.equal(state.allows(1), false);
});

test("app response hooks and DOM messages stay idle while explicit casting still reaches Android", async () => {
  const h = harness();
  const context = createContext(h.sandbox);
  runInContext(script("background"), context);
  const runtimeMessage = h.onMessage.listeners[0];
  const sender = { tab: { id: 7, url: "https://app.example" }, frameId: 0 };
  await runtimeMessage({ action: "detector_policy", policy: policy(false) }, sender);
  h.browser.webNavigation.onCommitted.listeners[0]({ frameId: 0, tabId: 7, url: sender.tab.url });
  const headers = h.browser.webRequest.onHeadersReceived.listeners[0];
  for (const [type, url, contentType] of [
    ["main_frame", "https://app.example", "text/html"],
    ["xmlhttprequest", "https://cdn.example/config.json", "application/json"],
    ["media", "https://cdn.example/movie.m3u8", "application/vnd.apple.mpegurl"],
    ["image", "https://cdn.example/art.jpg", "image/jpeg"],
  ]) {
    headers({ tabId: 7, requestId: url, url, type, statusCode: 200,
      responseHeaders: [{ name: "content-type", value: contentType }] });
  }
  runtimeMessage({ action: "dom_video_found", url: "https://cdn.example/movie.mp4" }, sender);
  runtimeMessage({ action: "dom_image_found", url: "https://cdn.example/art.jpg" }, sender);
  assert.equal(h.filters.length, 0);
  assert.equal(h.nativeMessages.some(message => message.type === "video_detected"), false);
  runtimeMessage({ action: "page_cast_requested", payload: { url: "https://cdn.example/movie.mp4" } }, sender);
  await flush();
  assert.equal(h.nativeMessages.some(message => message.type === "cast"), true);
  const linked = await runtimeMessage({ action: "page_linked_cast", operation: "open",
    payload: { items: [{ id: "episode", url: "https://cdn.example/movie.mp4" }] } }, sender);
  assert.equal(linked.ok, true);
  assert.equal(h.nativeMessages.some(message => message.type === "linked_open"), true);
  // A second, normal tab still gets body inspection, even at the app's URL.
  await runtimeMessage({ action: "detector_policy", policy: policy(true) }, { ...sender, tab: { ...sender.tab, id: 8 } });
  headers({ tabId: 8, requestId: "normal", url: "https://cdn.example/config.json",
    type: "xmlhttprequest", statusCode: 200, responseHeaders: [{ name: "content-type", value: "application/json" }] });
  assert.equal(h.filters.length, 1);
  await runtimeMessage({ action: "detector_policy", policy: policy(false, 2, false) }, { ...sender, tab: { ...sender.tab, id: 8 } });
  assert.equal(h.filters[0].disconnected, true);
  h.filters[0].onstop();
  assert.equal(h.nativeMessages.some(message => message.type === "video_detected"), false);
});

test("another tab's policy update preserves a first main-frame scan and its replay headers", async () => {
  const h = harness();
  runInContext(script("background"), createContext(h.sandbox));
  const message = h.onMessage.listeners[0];
  const sender = (id: number) => ({ tab: { id, url: "https://normal.example" }, frameId: 0 });
  await message({ action: "detector_policy", policy: policy(false) }, sender(7));
  const request = { tabId: 9, requestId: "first-main", url: "https://normal.example",
    type: "main_frame", statusCode: 200 };
  h.browser.webRequest.onBeforeSendHeaders.listeners[0]({ ...request, method: "GET",
    requestHeaders: [{ name: "Referer", value: "https://normal.example/start" }] });
  await message({ action: "detector_policy", policy: policy(true) }, sender(8));
  h.browser.webRequest.onHeadersReceived.listeners[0]({ ...request,
    responseHeaders: [{ name: "content-type", value: "application/json" }] });
  assert.equal(h.filters.length, 1);
  await message({ action: "detector_policy", policy: policy(true) }, sender(10));
  assert.equal(h.filters[0].disconnected, false);
  h.browser.webNavigation.onCommitted.listeners[0]({ tabId: 9, frameId: 0, url: request.url });
  h.filters[0].ondata({ data: new TextEncoder().encode("#EXTM3U\n#EXTINF:10,\nhttps://cdn.example/segment.ts\n#EXT-X-ENDLIST\n").buffer });
  h.filters[0].onstop();
  await flush();
  const detected = h.nativeMessages.find(item => item.type === "video_detected");
  assert.equal(detected?.headers?.Referer, "https://normal.example/start");
});

test("a cancelled scanner cannot report a late body after detection is reenabled", async () => {
  const h = harness();
  runInContext(script("background"), createContext(h.sandbox));
  const message = h.onMessage.listeners[0];
  const sender = { tab: { id: 8, url: "https://normal.example" }, frameId: 0 };
  await message({ action: "detector_policy", policy: policy(true) }, sender);
  h.browser.webNavigation.onCommitted.listeners[0]({ tabId: 8, frameId: 0, url: sender.tab.url });
  h.browser.webRequest.onHeadersReceived.listeners[0]({ tabId: 8, requestId: "late", type: "xmlhttprequest",
    url: "https://cdn.example/config.json", statusCode: 200,
    responseHeaders: [{ name: "content-type", value: "application/json" }] });
  const filter = h.filters[0];
  filter.ondata({ data: new TextEncoder().encode('{"file":"https://cdn.example/movie.mp4"}').buffer });
  await message({ action: "detector_policy", policy: policy(false, 2, false) }, sender);
  assert.equal(filter.disconnected, true);
  await message({ action: "detector_policy", policy: policy(true, 3) }, sender);
  filter.onstop();
  await flush();
  assert.equal(h.nativeMessages.some(item => item.type === "video_detected"), false);
});

test("app content injects casting without DOM scans, observers, player timers or visibility overrides", async () => {
  const h = harness();
  const windowListeners = new Map<string, Function[]>();
  let scans = 0;
  let observers = 0;
  let disconnected = 0;
  const window: any = {
    location: { href: "https://app.example" },
    addEventListener: (type: string, fn: Function) => {
      windowListeners.set(type, [...(windowListeners.get(type) ?? []), fn]);
    },
    removeEventListener: (type: string, fn: Function) => {
      windowListeners.set(type, (windowListeners.get(type) ?? []).filter(listener => listener !== fn));
    },
    dispatchEvent: (event: any) => { for (const listener of windowListeners.get(event.type) ?? []) listener(event); },
  };
  window.top = window;
  const context = createContext({ ...h.sandbox, window,
    crypto: { randomUUID: () => "test-request" },
    Event: class { constructor(public type: string) {} },
    CustomEvent: class { detail: any; constructor(public type: string, options?: any) { this.detail = options?.detail; } },
    MutationObserver: class {
      constructor() { observers++; }
      observe() {}
      disconnect() { disconnected++; }
    },
  });
  const document: any = {
    readyState: "complete", hidden: true, visibilityState: "hidden",
    querySelectorAll: () => { scans++; return []; },
    addEventListener: () => {}, removeEventListener: () => {},
    createElement: () => ({ textContent: "", remove: () => {} }),
    documentElement: { appendChild: (element: any) => runInContext(element.textContent, context) },
  };
  context.document = document;
  runInContext(script("content"), context);
  await h.policyMessages.listeners[0](policy(false));
  await flush();
  assert.equal(scans, 0);
  assert.equal(observers, 0);
  assert.equal(h.timers.size, 0);
  assert.equal(document.hidden, true);
  assert.equal(document.visibilityState, "hidden");
  assert.equal(typeof window.playbridge.cast, "function");
  assert.equal(typeof window.playbridge.linkCast, "function");
  window.playbridge.cast({ url: "https://cdn.example/movie.mp4" });
  await flush();
  assert.equal(h.messages.some(message => message.action === "page_cast_requested"), true);
  await h.policyMessages.listeners[0](policy(true, 2));
  await flush();
  assert.equal(scans, 1);
  assert.equal(observers, 1);
  assert.equal(h.timers.size, 2);
  await h.policyMessages.listeners[0](policy(false, 3));
  await flush();
  assert.equal(disconnected, 1);
  assert.equal(h.timers.size, 0);
  // An older async enable reply cannot restart detection after the latest off.
  h.policyMessages.listeners[0](policy(true, 4));
  h.policyMessages.listeners[0](policy(false, 5));
  await flush();
  assert.equal(scans, 1);
  assert.equal(observers, 1);
});
