import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { PLUGIN_PAGE_BRIDGE_SCRIPT, validPluginBridgeRequest } from "../src/geckoview/plugin-bridge";
import { delivered, pageWorld } from "./geckoview-page-channel-fixture";

const payload = { repoUrl: "https://plugins.example/manifest.json", scraperIds: ["castle"], tmdbId: "60625", mediaType: "tv", season: 8, episode: 2 };

test("plugin bridge accepts identifiers only, with bounded provider and episode inputs", () => {
  const request = { operation: "resolve", requestId: "one", payload };
  assert.equal(validPluginBridgeRequest(request), true);
  for (const extra of [{ code: "fetch('http://localhost')" }, { headers: { Authorization: "secret" } }, { settings: {} }, { url: "https://attacker.example" }]) {
    assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, ...extra } }), false);
  }
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, scraperIds: Array.from({ length: 33 }, (_, i) => `p${i}`) } }), false);
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, scraperIds: ["castle", "castle"] } }), false);
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, episode: -1 } }), false);
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, episode: "2" } }), false);
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, tmdbId: "60625:8:2" } }), false);
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, repoUrl: "http://plugins.example/manifest.json" } }), false);
  assert.equal(validPluginBridgeRequest({ ...request, payload: { ...payload, repoUrl: "https://user:secret@plugins.example/manifest.json" } }), false);
  assert.equal(validPluginBridgeRequest({ operation: "status", requestId: "one", payload: {} }), true);
  assert.equal(validPluginBridgeRequest({ operation: "manage", requestId: "one", payload: { install: "https://plugins.example/manifest.json" } }), false);
  assert.equal(validPluginBridgeRequest({ operation: "fetch", requestId: "one", payload: {} }), false);
  assert.equal(validPluginBridgeRequest({ operation: "cancel" }), true);
  assert.equal(validPluginBridgeRequest({ operation: "cancel", requestId: "another-document" }), false);
});

function pageBridge(t: { after(fn: () => void): void }) {
  const cast = () => {};
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `request-${++sequence}` } });
  t.after(world.close);
  world.window.playbridge = { cast, capabilities: { linkedCast: 1 } };
  const channel = world.inject(PLUGIN_PAGE_BRIDGE_SCRIPT);
  assert.ok(channel);
  const requests: any[] = [];
  channel.onMessage((message) => { requests.push(JSON.parse(message as string)); });
  const deliver = async (message: unknown) => { channel.post(message); await delivered(); };
  const sent = (count: number) => delivered(() => requests.length >= count);
  return { window: world.window, run: world.run, requests, deliver, sent, cast };
}

test("page plugin API preserves cast and waits for native capability and correlated responses", async (t) => {
  const { window, requests, deliver, sent, cast } = pageBridge(t);
  assert.equal(window.playbridge.cast, cast);
  assert.equal(window.playbridge.capabilities.linkedCast, 1);
  assert.equal(window.playbridge.capabilities.nativePlugins, 0);
  const status = window.playbridge.plugins.status();
  await deliver({ type: "plugin_capabilities", available: true });
  await delivered(() => window.playbridge.capabilities.nativePlugins === 1);
  await deliver({ type: "plugin_response", requestId: "another-document", ok: true, data: {} });
  await sent(1);
  await deliver({ type: "plugin_response", requestId: requests[0].requestId, ok: true, data: { available: true, enabled: false, providers: [] } });
  assert.equal((await status).enabled, false);
  const resolve = window.playbridge.plugins.resolve(payload);
  await sent(2);
  assert.deepEqual(requests[1].payload, payload);
  await deliver({ type: "plugin_response", requestId: requests[1].requestId, ok: true, data: { streams: [{ url: "https://cdn.example/video.mp4" }], warnings: [] } });
  assert.equal((await resolve).streams[0].url, "https://cdn.example/video.mp4");
});

test("Play unavailability and disconnect reject safely, with bounded pending requests", async (t) => {
  const { window, requests, deliver, sent } = pageBridge(t);
  const status = window.playbridge.plugins.status();
  await sent(1);
  await deliver({ type: "plugin_response", requestId: requests[0].requestId, ok: true, data: { available: false, enabled: false, providers: [] } });
  assert.equal((await status).available, false);
  assert.equal(window.playbridge.capabilities.nativePlugins, 0);
  const pending = Array.from({ length: 4 }, () => window.playbridge.plugins.resolve(payload));
  const assertions = pending.map((promise) => assert.rejects(promise, /cancelled/));
  await assert.rejects(window.playbridge.plugins.resolve(payload), /Too many pending/);
  window.playbridge.plugins.cancel();
  await delivered(() => requests.at(-1)?.operation === "cancel");
  assert.deepEqual(requests.at(-1), { operation: "cancel" });
  await deliver({ type: "plugin_disconnected" });
  await Promise.all(assertions);
});

test("another script on the page cannot read plugin results or forge them", async (t) => {
  const { window, run, requests, deliver, sent } = pageBridge(t);
  run(`
    window.observed = [];
    for (const name of ["PlayBridgePluginsRequestJson", "message"]) {
      window.addEventListener(name, () => window.observed.push(name), true);
    }
  `);
  const resolve = window.playbridge.plugins.resolve(payload);
  await sent(1);
  run(`window.dispatchEvent(new CustomEvent("PlayBridgePluginsResponseJson", { detail: JSON.stringify({
    type: "plugin_response", requestId: "request-1", ok: true, data: { streams: [{ url: "https://attacker.example/x.mp4" }] } }) }));`);
  await deliver({ type: "plugin_response", requestId: requests[0].requestId, ok: true, data: { streams: [{ url: "https://cdn.example/video.mp4" }], warnings: [] } });
  assert.equal((await resolve).streams[0].url, "https://cdn.example/video.mp4");
  assert.equal(run("window.observed.length"), 0);
});

test("content transport requires a top frame and user gesture, cancels navigation and reconnects restored documents", async (t) => {
  const source = readFileSync(new URL("./geckoview-runtime/plugin-bridge.js", import.meta.url), "utf8");
  const window = Object.assign(new EventTarget(), { top: null as unknown, playbridge: {} as any });
  window.top = window;
  const activation = { isActive: false };
  const ports: any[] = [];
  const event = () => ({ listeners: [] as Function[], addListener(listener: Function) { this.listeners.push(listener); } });
  const channels: MessageChannel[] = [];
  class TrackedChannel extends MessageChannel { constructor() { super(); channels.push(this); } }
  t.after(() => channels.forEach((channel) => { channel.port1.close(); channel.port2.close(); }));
  const context = vm.createContext({
    window, navigator: { userActivation: activation }, TextEncoder, Event, CustomEvent, URL,
    MessageChannel: TrackedChannel, MessageEvent, MessagePort,
    setTimeout, clearTimeout, crypto: { randomUUID: () => `native-${Math.random()}` },
    browser: { runtime: { connectNative(name: string) {
      assert.equal(name, "plugins");
      const port = { messages: [] as any[], onMessage: event(), onDisconnect: event(),
        postMessage(message: unknown) { this.messages.push(message); }, disconnect() { this.onDisconnect.listeners.forEach((fn) => fn()); } };
      ports.push(port);
      return port;
    } } },
    document: { createElement: () => ({ textContent: "", remove() {} }), documentElement: {
      appendChild(script: { textContent: string }) { vm.runInContext(script.textContent, context); }
    } },
  });
  vm.runInContext(source, context);
  assert.equal(ports.length, 1);
  await assert.rejects(window.playbridge.plugins.manage(), /user_gesture_required/);
  assert.equal(ports[0].messages.length, 0);
  await assert.rejects(window.playbridge.plugins.resolve({ ...payload, code: "while(true){}" }), /invalid_request/);
  activation.isActive = true;
  const manage = window.playbridge.plugins.manage();
  await delivered(() => ports[0].messages.length > 0);
  const request = ports[0].messages.at(-1);
  ports[0].onMessage.listeners[0]({ type: "plugin_response", requestId: request.requestId, ok: true, data: { opened: true } });
  assert.equal((await manage).opened, true);
  window.dispatchEvent(new Event("hashchange"));
  assert.equal(ports[0].messages.at(-1).operation, "cancel");
  const resolution = window.playbridge.plugins.resolve(payload);
  const rejected = assert.rejects(resolution, /disconnected/);
  await delivered(() => ports[0].messages.at(-1)?.operation === "resolve");
  window.dispatchEvent(new Event("pagehide"));
  await rejected;
  await delivered();
  window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: true }));
  assert.equal(ports.length, 2);
  const status = window.playbridge.plugins.status();
  await delivered(() => ports[1].messages.length > 0);
  const statusRequest = ports[1].messages.at(-1);
  ports[1].onMessage.listeners[0]({ type: "plugin_response", requestId: statusRequest.requestId, ok: true, data: { available: true } });
  assert.equal((await status).available, true);
  // The same installation code cannot attach a native port from an iframe.
  window.top = {};
  vm.runInContext(source, context);
  assert.equal(ports.length, 2);
});
