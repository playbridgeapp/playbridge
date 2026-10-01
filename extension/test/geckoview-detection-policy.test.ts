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

test("unknown tabs and declared main frames never inspect responses; ordinary origins still can", () => {
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
  assert.equal(state.allowsRequest(2, "main_frame", "https://app.example"), false);
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
  // A second, ordinary tab still gets body inspection.
  const normalSender = { ...sender, tab: { id: 8, url: "https://normal.example" } };
  await runtimeMessage({ action: "detector_policy", policy: policy(true) }, normalSender);
  headers({ tabId: 8, requestId: "normal", url: "https://cdn.example/config.json",
    type: "xmlhttprequest", statusCode: 200, responseHeaders: [{ name: "content-type", value: "application/json" }] });
  assert.equal(h.filters.length, 1);
  await runtimeMessage({ action: "detector_policy", policy: policy(false, 2, false) }, normalSender);
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
  await h.policyMessages.listeners[0]({ ...policy(true, 6), options: {
    domScanning: false, playerProbes: false, visibilityOverrides: false,
  } });
  await flush();
  assert.equal(scans, 1);
  assert.equal(observers, 1);
  assert.equal(h.timers.size, 0);
  assert.equal(document.hidden, true);
  assert.equal(document.visibilityState, "hidden");
  await h.policyMessages.listeners[0]({ ...policy(true, 7), options: {
    playerProbes: false, visibilityOverrides: false, navigationRescans: false,
  } });
  await flush();
  assert.equal(scans, 2);
  assert.equal(observers, 2);
  for (const listener of h.onMessage.listeners) listener({ type: "detector_same_document_navigation" });
  assert.equal(scans, 2);
  await h.policyMessages.listeners[0]({ ...policy(true, 8), options: {
    domScanning: false, visibilityOverrides: false,
  } });
  await flush();
  assert.equal(disconnected, 2);
  assert.equal(h.timers.size, 2);
  assert.equal(document.hidden, true);
  await h.policyMessages.listeners[0]({ ...policy(true, 9), options: {
    domScanning: false, playerProbes: false,
  } });
  await flush();
  assert.equal(h.timers.size, 0);
  assert.equal(document.hidden, false);
  await h.policyMessages.listeners[0](policy(false, 10));
  await flush();
  assert.equal(document.hidden, true);
  assert.equal(typeof window.playbridge.cast, "function");
  let imageReads = 0;
  context.HTMLVideoElement = class {};
  context.HTMLAudioElement = class {};
  context.HTMLSourceElement = class {};
  class ImageElement {
    get currentSrc() { imageReads++; return "https://cdn.example/poster.jpg"; }
    naturalWidth = 400;
    naturalHeight = 600;
    complete = true;
  }
  context.HTMLImageElement = ImageElement;
  const image = new ImageElement();
  document.querySelectorAll = (selector: string) => selector.includes("img") ? [image] : [];
  await h.policyMessages.listeners[0]({ ...policy(true, 11), options: {
    images: false, playerProbes: false, visibilityOverrides: false,
  } });
  await flush();
  assert.equal(imageReads, 0);
  assert.equal(h.messages.some(message => message.action === "dom_image_found"), false);
  await h.policyMessages.listeners[0]({ ...policy(true, 12), options: {
    playerProbes: false, visibilityOverrides: false,
  } });
  await flush();
  assert.equal(imageReads, 1);
  assert.equal(h.messages.some(message => message.action === "dom_image_found"), true);

  // Real catalog posters start unloaded. CSS-size reads must never force their
  // offscreen layout or report them before their intrinsic dimensions exist.
  let onImageLoad: Function | undefined;
  class LazyImageElement {
    currentSrc = "";
    src = "https://cdn.example/lazy-poster.jpg";
    naturalWidth = 0;
    naturalHeight = 0;
    complete = false;
    get width() { throw new Error("image.width forces layout"); }
    get height() { throw new Error("image.height forces layout"); }
    get clientWidth() { throw new Error("image.clientWidth forces layout"); }
    get clientHeight() { throw new Error("image.clientHeight forces layout"); }
    addEventListener(name: string, listener: Function) {
      assert.equal(name, "load");
      onImageLoad = listener;
    }
  }
  context.HTMLImageElement = LazyImageElement;
  const lazyImage = new LazyImageElement();
  document.querySelectorAll = (selector: string) => selector.includes("img") ? [lazyImage] : [];
  await h.policyMessages.listeners[0](policy(false, 13));
  await h.policyMessages.listeners[0]({ ...policy(true, 14), options: {
    playerProbes: false, visibilityOverrides: false,
  } });
  await flush();
  assert.equal(h.messages.some(message => message.url === lazyImage.src), false);
  assert.equal(typeof onImageLoad, "function");
  lazyImage.naturalWidth = 400;
  lazyImage.naturalHeight = 600;
  lazyImage.complete = true;
  onImageLoad!();
  await flush();
  const loaded = h.messages.find(message => message.url === lazyImage.src);
  assert.equal(loaded?.action, "dom_image_found");
  assert.equal(loaded?.width, 400);
  assert.equal(loaded?.height, 600);

  // A SPA can reuse the same image element for a different, unloaded poster.
  lazyImage.src = "https://cdn.example/replacement-poster.jpg";
  lazyImage.naturalWidth = 0;
  lazyImage.naturalHeight = 0;
  lazyImage.complete = false;
  onImageLoad = undefined;
  for (const listener of h.onMessage.listeners) listener({ type: "detector_same_document_navigation" });
  assert.equal(typeof onImageLoad, "function");
  assert.equal(h.messages.some(message => message.url === lazyImage.src), false);
  lazyImage.naturalWidth = 800;
  lazyImage.naturalHeight = 1200;
  lazyImage.complete = true;
  onImageLoad!();
  await flush();
  const replacement = h.messages.find(message => message.url === lazyImage.src);
  assert.equal(replacement?.action, "dom_image_found");
  assert.equal(replacement?.width, 800);
  assert.equal(replacement?.height, 1200);
});

test("a declared origin overrides the previous main document policy", () => {
  const tabs = new TabDetectionPolicy();
  tabs.apply(policy(true), 7);
  assert.equal(tabs.allowsRequest(7, "main_frame", "https://app.example/watch"), false);
  assert.equal(tabs.allowsRequest(7, "main_frame", "https://ordinary.example/watch"), true);
  tabs.apply(policy(false, 2), 7);
  assert.equal(tabs.allowsRequest(7, "sub_frame", "https://ordinary.example/embed"), false);
  assert.equal(tabs.allowsRequest(7, "xmlhttprequest", "https://cdn.example/stream.m3u8"), false);
  tabs.apply(policy(true, 3), 7);
  assert.equal(tabs.allowsRequest(7, "xmlhttprequest", "https://cdn.example/stream.m3u8"), true);
});


test("the bridged override is explicit and cannot bypass the master switch", () => {
  const tabs = new TabDetectionPolicy();
  tabs.apply({ ...policy(true), options: { detectInBridgedSites: true } }, 7);
  assert.equal(tabs.allowsRequest(7, "main_frame", "https://app.example/watch"), true);
  tabs.apply({ ...policy(false, 2, false), options: { detectInBridgedSites: true } }, 7);
  assert.equal(tabs.allowsRequest(7, "main_frame", "https://app.example/watch"), false);
});

test("media categories filter DOM and network reports and can be reenabled", async () => {
  const h = harness();
  runInContext(script("background"), createContext(h.sandbox));
  const message = h.onMessage.listeners[0];
  const sender = { tab: { id: 8, url: "https://normal.example" }, frameId: 0 };
  await message({ action: "detector_policy", policy: { ...policy(true), options: {
    images: false, audio: false, subtitles: false,
  } } }, sender);
  h.browser.webNavigation.onCommitted.listeners[0]({ tabId: 8, frameId: 0, url: sender.tab.url });
  const headers = h.browser.webRequest.onHeadersReceived.listeners[0];
  for (const [kind, url, contentType, action] of [
    ["video", "https://cdn.example/movie.mp4", "video/mp4", "dom_video_found"],
    ["image", "https://cdn.example/poster.jpg", "image/jpeg", "dom_image_found"],
    ["audio", "https://cdn.example/music.mp3", "audio/mpeg", "dom_audio_found"],
    ["subtitle", "https://cdn.example/captions.vtt", "text/vtt", "dom_subtitle_found"],
  ]) {
    headers({ tabId: 8, requestId: kind, type: "media", url, statusCode: 200,
      responseHeaders: [{ name: "content-type", value: contentType }, { name: "content-length", value: "200000" }] });
    message({ action, url }, sender);
  }
  await flush();
  assert.deepEqual(h.nativeMessages.filter(item => item.type === "video_detected").map(item => item.mediaKind), ["video", "video"]);
  await message({ action: "detector_policy", policy: { ...policy(true, 2), options: { videos: false } } }, sender);
  message({ action: "dom_image_found", url: "https://cdn.example/poster.jpg" }, sender);
  message({ action: "dom_audio_found", url: "https://cdn.example/music.mp3" }, sender);
  message({ action: "dom_subtitle_found", url: "https://cdn.example/captions.vtt" }, sender);
  message({ action: "dom_video_found", url: "https://cdn.example/other.mp4" }, sender);
  await flush();
  assert.deepEqual(h.nativeMessages.filter(item => item.type === "video_detected").slice(-3).map(item => item.mediaKind), ["image", "audio", "subtitle"]);
});

test("response scanning still reports embedded sources with network detection off", async () => {
  const h = harness();
  runInContext(script("background"), createContext(h.sandbox));
  const message = h.onMessage.listeners[0];
  const sender = { tab: { id: 8, url: "https://normal.example" }, frameId: 0 };
  await message({ action: "detector_policy", policy: { ...policy(true), options: { networkDetection: false } } }, sender);
  h.browser.webNavigation.onCommitted.listeners[0]({ tabId: 8, frameId: 0, url: sender.tab.url });
  h.browser.webRequest.onHeadersReceived.listeners[0]({ tabId: 8, requestId: "config", type: "xmlhttprequest",
    url: "https://cdn.example/config.json", statusCode: 200,
    responseHeaders: [{ name: "content-type", value: "application/json" }] });
  h.filters[0].ondata({ data: new TextEncoder().encode('{"file":"https://cdn.example/movie.mp4"}').buffer });
  h.filters[0].onstop();
  await flush();
  assert.ok(h.nativeMessages.some(item => item.type === "video_detected" && item.url === "https://cdn.example/movie.mp4"));
});

test("network detection and response scanning work independently and stop active scans", async () => {
  const h = harness();
  runInContext(script("background"), createContext(h.sandbox));
  const message = h.onMessage.listeners[0];
  const sender = { tab: { id: 8, url: "https://normal.example" }, frameId: 0 };
  await message({ action: "detector_policy", policy: { ...policy(true), options: {
    networkDetection: false, domScanning: false, playerProbes: false,
  } } }, sender);
  h.browser.webNavigation.onCommitted.listeners[0]({ tabId: 8, frameId: 0, url: sender.tab.url });
  const headers = h.browser.webRequest.onHeadersReceived.listeners[0];
  headers({ tabId: 8, requestId: "movie", type: "media", url: "https://cdn.example/movie.mp4", statusCode: 200,
    responseHeaders: [{ name: "content-type", value: "video/mp4" }] });
  message({ action: "dom_video_found", url: "https://cdn.example/movie.mp4" }, sender);
  message({ action: "player_video_found", url: "https://cdn.example/movie.mp4" }, sender);
  assert.equal(h.nativeMessages.some(item => item.type === "video_detected"), false);
  headers({ tabId: 8, requestId: "config", type: "xmlhttprequest", url: "https://cdn.example/config.json", statusCode: 200,
    responseHeaders: [{ name: "content-type", value: "application/json" }] });
  assert.equal(h.filters.length, 1);
  h.filters[0].ondata({ data: new TextEncoder().encode('{"file":"https://cdn.example/movie.mp4"}').buffer });
  await message({ action: "detector_policy", policy: { ...policy(true, 2), options: { responseScanning: false } } }, sender);
  assert.equal(h.filters[0].disconnected, true);
  h.filters[0].onstop();
  headers({ tabId: 8, requestId: "config2", type: "xmlhttprequest", url: "https://cdn.example/config.json", statusCode: 200,
    responseHeaders: [{ name: "content-type", value: "application/json" }] });
  assert.equal(h.filters.length, 1);
  headers({ tabId: 8, requestId: "movie2", type: "media", url: "https://cdn.example/movie.mp4", statusCode: 200,
    responseHeaders: [{ name: "content-type", value: "video/mp4" }] });
  await flush();
  const detected = h.nativeMessages.filter(item => item.type === "video_detected");
  assert.ok(detected.length > 0);
  assert.ok(detected.every(item => item.url === "https://cdn.example/movie.mp4" && item.detectedBy === "content_type"));
});
