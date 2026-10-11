import assert from "node:assert/strict";
import test, { mock } from "node:test";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

if (!globalThis.chrome) {
  (globalThis as any).chrome = {};
}
const chromeAny = (globalThis as any).chrome;
chromeAny.runtime = chromeAny.runtime || {};
chromeAny.runtime.id = chromeAny.runtime.id || "test";
chromeAny.runtime.getURL = chromeAny.runtime.getURL || ((path = "") => `chrome-extension://test/${path}`);
chromeAny.runtime.connectNative = chromeAny.runtime.connectNative || (() => ({
  postMessage: () => {},
  onMessage: { addListener: () => {} },
  onDisconnect: { addListener: () => {} },
}));
chromeAny.runtime.onConnect = chromeAny.runtime.onConnect || { addListener: () => {} };
const testStorageValues: Record<string, any> = {};
chromeAny.storage = chromeAny.storage || {
  local: {
    get: (keys: any, cb?: any) => {
      let res: Record<string, any> = {};
      if (typeof keys === "string") {
        res[keys] = testStorageValues[keys];
      } else if (Array.isArray(keys)) {
        for (const k of keys) res[k] = testStorageValues[k];
      } else if (keys && typeof keys === "object") {
        for (const k of Object.keys(keys)) res[k] = testStorageValues[k] ?? keys[k];
      } else {
        res = { ...testStorageValues };
      }
      if (typeof cb === "function") cb(res);
      return Promise.resolve(res);
    },
    set: (items: any, cb?: any) => {
      Object.assign(testStorageValues, items);
      if (typeof cb === "function") cb();
      return Promise.resolve();
    },
  },
};
chromeAny.windows = chromeAny.windows || {
  create: () => Promise.resolve({ id: 101 }),
  onRemoved: { addListener: () => {} },
};
chromeAny.tabs = chromeAny.tabs || {
  onRemoved: { addListener: () => {} },
};

const bridge = await import("../src/native-bridge");

import {
  getOrCreateInstallSecret,
  computeDestinationId,
  formatPlaybackDestination,
  destinationIdMatches,
  THIS_DEVICE_DESTINATION_ID,
  PAGE_DESTINATION_SECRET_KEY,
} from "../src/core/page-privacy";
import {
  approveOrigin,
  revokeOrigin,
  isOriginApproved,
  getApprovedOrigins,
  approvePrivateOrigins,
  getApprovedPrivateOrigins,
  PAGE_CONSENT_KEY,
} from "../src/core/page-consent";
import { normalizeOrigin, originFromUrl } from "../src/core/page-origin";
import {
  pageCastHasSenderOnlyFields,
  normalizeLinkedPageCastPayload,
} from "../src/core/page-cast";
import {
  DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT,
  initDesktopPageBridge,
} from "../src/core/page-bridge";
import {
  listenForPageChannelBoot,
  FIXED_PAGE_CHANNEL_BOOT,
  type PageChannel,
} from "../src/core/page-channel";
const { default: browser } = await import("../src/browser");
const { bindPageChannel } = await import("../src/page-content");
const {
  PageCoordinator,
  isExtensionPageSender,
  handlePrivilegedRuntimeMessage,
} = await import("../src/page-coordinator");
import { pageWorld, delivered } from "./geckoview-page-channel-fixture";

function memoryStorage(initial: Record<string, unknown> = {}) {
  const values = { ...initial };
  return {
    values,
    storage: {
      async get(key: string) {
        return { [key]: values[key] };
      },
      async set(items: Record<string, unknown>) {
        Object.assign(values, items);
      },
    },
  };
}

test("HMAC destination IDs: per-origin distinct, stable, base64url, and literal this-device for local", async () => {
  const { storage } = memoryStorage();
  const secret = await getOrCreateInstallSecret(storage);
  assert.equal(secret.length, 32);

  // Stored in storage.local as base64url string
  const storedSecret = (await storage.get(PAGE_DESTINATION_SECRET_KEY))[PAGE_DESTINATION_SECRET_KEY];
  assert.equal(typeof storedSecret, "string");
  assert.match(storedSecret, /^[A-Za-z0-9_-]+$/);

  const originA = "https://streams.playbridge.app";
  const originB = "https://other-site.com";
  const endpointKey = "playBridge:living-room-tv-uuid";

  const idA = await computeDestinationId(secret, originA, endpointKey);
  const idB = await computeDestinationId(secret, originB, endpointKey);

  // Distinct per origin
  assert.notEqual(idA, idB);
  // Base64url format
  assert.match(idA, /^[A-Za-z0-9_-]+$/);
  assert.match(idB, /^[A-Za-z0-9_-]+$/);

  // Stable
  const idA2 = await computeDestinationId(secret, originA, endpointKey);
  assert.equal(idA, idA2);

  // Literal this-device
  assert.equal(THIS_DEVICE_DESTINATION_ID, "this-device");
});

test("Destination consent gating: pre-consent null id/name, post-consent hmac id/name", async () => {
  const { storage } = memoryStorage();
  const secret = await getOrCreateInstallSecret(storage);
  const origin = "https://streams.playbridge.app";

  const tvState: bridge.BridgeState = {
    desktopConnected: true,
    tvState: "connected",
    activeTv: "tv-1",
    target: "tv",
    selection: {
      kind: "tv",
      id: "playBridge:tv-1",
      name: "Living Room TV",
      status: "connected",
    },
    devices: [
      {
        id: "playBridge:tv-1",
        uuid: "tv-1",
        name: "Living Room TV",
        protocol: "playBridge",
        paired: true,
        online: true,
      },
    ],
  };

  // Pre-consent: id and name are null, kind is native, connected is true
  const preConsent = await formatPlaybackDestination(tvState, origin, false, secret);
  assert.deepEqual(preConsent, {
    id: null,
    name: null,
    kind: "native",
    connected: true,
  });

  // Post-consent: id is computed HMAC, name is TV name
  const postConsent = await formatPlaybackDestination(tvState, origin, true, secret);
  const expectedId = await computeDestinationId(secret, origin, "playBridge:tv-1");
  assert.deepEqual(postConsent, {
    id: expectedId,
    name: "Living Room TV",
    kind: "native",
    connected: true,
  });

  // Local selection: literal this-device
  const localState: bridge.BridgeState = {
    ...tvState,
    selection: { kind: "local" },
  };
  const postConsentLocal = await formatPlaybackDestination(localState, origin, true, secret);
  assert.deepEqual(postConsentLocal, {
    id: "this-device",
    name: "This computer",
    kind: "local",
    connected: true,
  });
});

test("Website consent persistence, approval, revocation, and private origin grants", async () => {
  const { storage, values } = memoryStorage();
  const origin = "https://streams.playbridge.app";

  assert.equal(await isOriginApproved(storage, origin), false);

  await approveOrigin(storage, origin);
  assert.equal(await isOriginApproved(storage, origin), true);
  assert.deepEqual(await getApprovedOrigins(storage), [origin]);

  // Private origins
  await approvePrivateOrigins(storage, origin, ["http://192.168.1.50:8080", "http://10.0.0.2:9000"]);
  const priv = await getApprovedPrivateOrigins(storage, origin);
  assert.equal(priv.length, 2);
  assert.ok(priv.includes("http://192.168.1.50:8080"));
  assert.ok(priv.includes("http://10.0.0.2:9000"));

  // Check stored key
  assert.ok(values[PAGE_CONSENT_KEY]);

  // Revoke
  await revokeOrigin(storage, origin);
  assert.equal(await isOriginApproved(storage, origin), false);
  assert.deepEqual(await getApprovedOrigins(storage), []);
});

test("Destination ID matching and mismatch detection", async () => {
  const { storage } = memoryStorage();
  const secret = await getOrCreateInstallSecret(storage);
  const origin = "https://streams.playbridge.app";

  const tvSelection: bridge.DeviceSelection = {
    kind: "tv",
    id: "playBridge:uuid-tv",
    name: "Master TV",
    status: "connected",
  };
  const expectedId = await computeDestinationId(secret, origin, "playBridge:uuid-tv");

  assert.equal(await destinationIdMatches(expectedId, origin, tvSelection, secret), true);
  // Wrong origin
  assert.equal(await destinationIdMatches(expectedId, "https://wrong.com", tvSelection, secret), false);
  // Wrong ID
  assert.equal(await destinationIdMatches("arbitrary-id", origin, tvSelection, secret), false);
  // Literal this-device does not match TV selection
  assert.equal(await destinationIdMatches("this-device", origin, tvSelection, secret), false);

  // Local selection matches this-device
  const localSelection: bridge.DeviceSelection = { kind: "local" };
  assert.equal(await destinationIdMatches("this-device", origin, localSelection, secret), true);
  assert.equal(await destinationIdMatches(expectedId, origin, localSelection, secret), false);
});

test("progressWebhook rejection anywhere: envelope, payload, or items", () => {
  assert.equal(
    pageCastHasSenderOnlyFields({ progressWebhook: "https://hook.example/report" }),
    true,
  );
  assert.equal(
    pageCastHasSenderOnlyFields({ progressWebhook: null }),
    true,
  );
  assert.equal(
    pageCastHasSenderOnlyFields({ progressWebhook: false }),
    true,
  );
  assert.equal(
    pageCastHasSenderOnlyFields({
      items: [{ id: "1", url: "https://media.example/1.mp4", progressWebhook: "https://hook.example" }],
    }),
    true,
  );

  // Normalization returns undefined for payloads with progressWebhook
  assert.equal(
    normalizeLinkedPageCastPayload({
      items: [{ id: "1", url: "https://media.example/1.mp4" }],
      progressWebhook: "https://hook.example",
    }),
    undefined,
  );
  assert.equal(
    normalizeLinkedPageCastPayload({
      items: [{ id: "1", url: "https://media.example/1.mp4", progressWebhook: "https://hook.example" }],
    }),
    undefined,
  );
});

test("Desktop page bridge script defaults: playback 0, nativePlugins 0, localPlaybackOrientation absent", (t) => {
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `test-${++sequence}` } });
  t.after(world.close);

  const channel = world.inject(DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT);
  assert.ok(channel, "desktop page bridge must hand its private port to content script");

  // Capabilities
  assert.equal(world.window.playbridge.capabilities.playback, 0, "desktop playback must default to 0");
  assert.equal(world.window.playbridge.capabilities.nativePlugins, 0, "desktop nativePlugins must be 0");
  assert.equal(world.window.playbridge.capabilities.localPlaybackOrientation, undefined, "localPlaybackOrientation must be absent");
  assert.equal(world.window.playbridge.capabilities.linkedCast, 1);
});

test("Desktop page bridge capabilities update reactively over private channel", async (t) => {
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `test-${++sequence}` } });
  t.after(world.close);

  const channel = world.inject(DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT);
  assert.ok(channel);
  assert.equal(world.window.playbridge.capabilities.playback, 0);

  // Background confirms linkedPlayback -> sends capabilities update
  channel.post({
    channel: "linked",
    type: "capabilities",
    capabilities: { playback: 1 },
  });

  await delivered(() => world.window.playbridge.capabilities.playback === 1);
  assert.equal(world.window.playbridge.capabilities.playback, 1);

  // When app disconnects -> updates back to 0
  channel.post({
    channel: "linked",
    type: "capabilities",
    capabilities: { playback: 0 },
  });

  await delivered(() => world.window.playbridge.capabilities.playback === 0);
  assert.equal(world.window.playbridge.capabilities.playback, 0);
});

test("Wire correlation: linked_result routes by bridgeRequestId (not FIFO) and linked_event routes by sessionId", async (t) => {
  bridge._resetStateForTesting();
  t.after(() => bridge._resetStateForTesting());

  const sentMessages: any[] = [];
  const fakePort = {
    postMessage: (msg: any) => sentMessages.push(msg),
    onMessage: { addListener: (fn: any) => { fakePort._onMessage = fn; } },
    onDisconnect: { addListener: () => {} },
    _onMessage: null as any,
  };
  bridge._setPortForTesting(fakePort);

  // Send two linked commands out of order
  const p1 = bridge.sendLinkedCommand({ type: "linked_play", bridgeRequestId: "req-1" });
  const p2 = bridge.sendLinkedCommand({ type: "linked_play", bridgeRequestId: "req-2" });

  assert.equal(sentMessages.length, 2);
  assert.equal(sentMessages[0].bridgeRequestId, "req-1");
  assert.equal(sentMessages[1].bridgeRequestId, "req-2");

  // App responds to req-2 FIRST, then req-1 (non-FIFO)
  fakePort._onMessage({
    type: "linked_result",
    bridgeRequestId: "req-2",
    ok: true,
    sessionId: "session-2",
  });

  const res2 = await p2;
  assert.deepEqual(res2, {
    type: "linked_result",
    bridgeRequestId: "req-2",
    ok: true,
    sessionId: "session-2",
  });

  fakePort._onMessage({
    type: "linked_result",
    bridgeRequestId: "req-1",
    ok: true,
    sessionId: "session-1",
  });

  const res1 = await p1;
  assert.deepEqual(res1, {
    type: "linked_result",
    bridgeRequestId: "req-1",
    ok: true,
    sessionId: "session-1",
  });

  // Test linked_event routing
  const events: any[] = [];
  const unsub = bridge.onLinkedEvent((sessionId, event, detail) => {
    events.push({ sessionId, event, detail });
  });
  t.after(unsub);

  fakePort._onMessage({
    type: "linked_event",
    sessionId: "session-1",
    event: "statechange",
    detail: { state: "playing", positionMs: 5000 },
  });

  assert.equal(events.length, 1);
  assert.deepEqual(events[0], {
    sessionId: "session-1",
    event: "statechange",
    detail: { state: "playing", positionMs: 5000 },
  });
});

test("Desktop page bridge rejects getPlaybackDestination and play with unavailable when app lacks linkedPlayback", async (t) => {
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `test-${++sequence}` } });
  t.after(world.close);

  const channel = world.inject(DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT);
  assert.ok(channel);

  const messages: any[] = [];
  channel.onMessage((m) => messages.push(m));

  // getPlaybackDestination when capability is 0 and content script answers unavailable
  const destPromise = world.window.playbridge.getPlaybackDestination();
  await delivered(() => messages.length === 1);
  assert.equal(messages[0].channel, "linked");
  assert.equal(messages[0].operation, "destination");

  // Content script answers unavailable
  channel.post({
    channel: "linked",
    type: "response",
    pageRequestId: messages[0].pageRequestId,
    response: { ok: false, error: "unavailable" },
  });

  await assert.rejects(destPromise, (err: any) => err.code === "unavailable");

  // play when capability is 0 and content script answers unavailable
  const playPromise = world.window.playbridge.play({
    destinationId: "this-device",
    items: [{ id: "item-1", url: "https://media.example/1.mp4" }],
  });
  await delivered(() => messages.length === 2);
  assert.equal(messages[1].operation, "play");

  channel.post({
    channel: "linked",
    type: "response",
    pageRequestId: messages[1].pageRequestId,
    response: { ok: false, error: "unavailable" },
  });

  await assert.rejects(playPromise, (err: any) => err.code === "unavailable");
});

test("Navigation unlink: top frame navigation ends active session with navigation reason", async (t) => {
  bridge._resetStateForTesting();
  t.after(() => bridge._resetStateForTesting());

  const sentMessages: any[] = [];
  const fakePort = {
    postMessage: (msg: any) => sentMessages.push(msg),
    onMessage: { addListener: () => {} },
    onDisconnect: { addListener: () => {} },
  };
  bridge._setPortForTesting(fakePort);

  // Set fake bridge state with linkedPlayback: 1
  fakePort.postMessage({ cmd: "list_devices" });
  (fakePort as any).onMessage = { addListener: (fn: any) => fn({
    type: "state",
    capabilities: { linkedPlayback: 1 },
    selection: { kind: "local" },
    devices: [],
  }) };

  // Verify sendLinkedCommand for unlink sends reason 'navigation'
  const unlinkPromise = bridge.sendLinkedCommand({
    type: "linked_unlink",
    sessionId: "active-session-123",
    reason: "navigation",
  });

  assert.ok(sentMessages.some((m) => m.type === "linked_unlink" && m.reason === "navigation"));
});

test("Gesture attestation: positive activation required for choose_destination, linkCast consent, and legacy cast", async (t) => {
  bridge._resetStateForTesting();
  t.after(() => bridge._resetStateForTesting());

  const bridgeMessageListeners: Array<(msg: any) => void> = [];
  const fakeBridgePort = {
    postMessage: () => {},
    onMessage: { addListener: (fn: any) => bridgeMessageListeners.push(fn) },
    onDisconnect: { addListener: () => {} },
  };
  bridge._setPortForTesting(fakeBridgePort);
  for (const fn of bridgeMessageListeners) {
    fn({
      type: "state",
      desktopConnected: true,
      capabilities: { linkedPlayback: 1 },
      selection: { kind: "local" },
      devices: [],
    });
  }

  const coord = new PageCoordinator();
  t.after(() => coord.destroy());

  function createTestPort(origin: string) {
    const posted: any[] = [];
    const messageListeners: Array<(msg: any) => void> = [];
    const port = {
      name: "playbridge:page-api",
      sender: {
        frameId: 0,
        url: origin,
        tab: { id: 10, url: origin },
      },
      postMessage: (m: any) => posted.push(m),
      onMessage: { addListener: (fn: any) => messageListeners.push(fn) },
      onDisconnect: { addListener: () => {} },
      disconnect: () => {},
      receive(m: any) {
        for (const fn of messageListeners) fn(m);
      },
      posted,
    };
    (coord as any).handlePagePortConnect(port);
    return port;
  }

  const unapprovedOrigin = "https://unapproved.example.com";
  const port = createTestPort(unapprovedOrigin);

  // 1. choose_destination without destinationId
  for (const activation of [false, null, undefined]) {
    port.posted.length = 0;
    port.receive({
      type: "linked",
      pageRequestId: `choose-no-dest-${String(activation)}`,
      operation: "choose_destination",
      userActivation: activation,
    });
    await new Promise((r) => setImmediate(r));
    const resp = port.posted.find((m) => m.pageRequestId === `choose-no-dest-${String(activation)}`);
    assert.ok(resp, `Must respond to activation=${activation}`);
    assert.equal(resp.response.ok, false);
    assert.equal(resp.response.error, "user_gesture_required");
  }

  // 2. choose_destination with destinationId: "this-device"
  for (const activation of [false, null, undefined]) {
    port.posted.length = 0;
    port.receive({
      type: "linked",
      pageRequestId: `choose-this-dev-${String(activation)}`,
      operation: "choose_destination",
      payload: { destinationId: "this-device" },
      userActivation: activation,
    });
    await new Promise((r) => setImmediate(r));
    const resp = port.posted.find((m) => m.pageRequestId === `choose-this-dev-${String(activation)}`);
    assert.ok(resp, `Must respond to activation=${activation}`);
    assert.equal(resp.response.ok, false);
    assert.equal(resp.response.error, "user_gesture_required");
  }

  // 3. linkCast / open consent prompt for unapproved origin
  for (const activation of [false, null, undefined]) {
    port.posted.length = 0;
    port.receive({
      type: "linked",
      pageRequestId: `open-consent-${String(activation)}`,
      operation: "open",
      payload: {
        items: [{ id: "1", url: "https://media.example/1.mp4" }],
      },
      userActivation: activation,
    });
    await new Promise((r) => setImmediate(r));
    const resp = port.posted.find((m) => m.pageRequestId === `open-consent-${String(activation)}`);
    assert.ok(resp, `Must respond to activation=${activation}`);
    assert.equal(resp.response.ok, false);
    assert.equal(resp.response.error, "user_gesture_required");
  }

  // 4. Legacy cast for unapproved origin: drops silently without consent prompt when userActivation !== true
  let prompted = false;
  const originalPrompt = coord.promptConsent.bind(coord);
  coord.promptConsent = async () => {
    prompted = true;
    return false;
  };
  for (const activation of [false, null, undefined]) {
    prompted = false;
    port.receive({
      type: "cast",
      payload: {
        items: [{ id: "1", url: "https://media.example/1.mp4" }],
      },
      userActivation: activation,
    });
    await new Promise((r) => setImmediate(r));
    assert.equal(prompted, false, `Legacy cast must not prompt when userActivation is ${activation}`);
  }
  coord.promptConsent = originalPrompt;
});

test("Privileged runtime messages: only accepted from extension pages, and prompt response requires matching windowId", async (t) => {
  const coord = new PageCoordinator();
  t.after(() => coord.destroy());

  // 1. isExtensionPageSender validation
  assert.equal(isExtensionPageSender({ url: "https://untrusted.com/page" }), false);
  assert.equal(isExtensionPageSender({ url: "http://localhost:8080" }), false);
  assert.equal(isExtensionPageSender(undefined), false);
  assert.equal(isExtensionPageSender({ url: undefined }), false);
  assert.equal(isExtensionPageSender({ url: "chrome-extension://test/ui/popup.html" }), true);
  assert.equal(isExtensionPageSender({ url: "chrome-extension://test/ui/page-access.html" }), true);

  // 2. Untrusted sender rejected / ignored
  const webSender = { url: "https://untrusted.com/page", tab: { id: 1, windowId: 10 } };
  let responsePayload: any = null;
  const sendResponse = (val: any) => { responsePayload = val; };

  responsePayload = null;
  handlePrivilegedRuntimeMessage({ action: "getPageConsentOrigins" }, webSender, sendResponse, coord);
  await new Promise((r) => setImmediate(r));
  assert.equal(responsePayload, null, "Must ignore getPageConsentOrigins from web sender");

  responsePayload = null;
  handlePrivilegedRuntimeMessage({ action: "revokePageConsentOrigin", origin: "https://test.com" }, webSender, sendResponse, coord);
  await new Promise((r) => setImmediate(r));
  assert.equal(responsePayload, null, "Must ignore revokePageConsentOrigin from web sender");

  responsePayload = null;
  handlePrivilegedRuntimeMessage({ action: "pageAccessConsentResponse", origin: "https://test.com", allowed: true }, webSender, sendResponse, coord);
  await new Promise((r) => setImmediate(r));
  assert.equal(responsePayload, null, "Must ignore pageAccessConsentResponse from web sender");

  // 3. Trusted extension sender accepted for getPageConsentOrigins / revokePageConsentOrigin
  const extPopupSender = { url: "chrome-extension://test/ui/popup.html", tab: { id: 2, windowId: 20 } };

  responsePayload = null;
  handlePrivilegedRuntimeMessage({ action: "getPageConsentOrigins" }, extPopupSender, sendResponse, coord);
  await new Promise((r) => setImmediate(r));
  assert.ok(responsePayload && Array.isArray(responsePayload.origins));

  responsePayload = null;
  handlePrivilegedRuntimeMessage({ action: "revokePageConsentOrigin", origin: "https://revoke.example.com" }, extPopupSender, sendResponse, coord);
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(responsePayload, { ok: true });

  // 4. pageAccessConsentResponse must match pending prompt windowId
  const originToPrompt = "https://prompt-test.example.com";
  let promptResolvedValue: boolean | null = null;
  (coord as any).pendingConsentPrompts.set(originToPrompt, {
    resolve: (allowed: boolean) => { promptResolvedValue = allowed; },
    windowId: 777,
  });

  // Mismatched windowId from extension page
  const wrongWindowSender = {
    url: "chrome-extension://test/ui/page-access.html",
    tab: { id: 3, windowId: 888 },
  };
  responsePayload = null;
  handlePrivilegedRuntimeMessage(
    { action: "pageAccessConsentResponse", origin: originToPrompt, allowed: true },
    wrongWindowSender,
    sendResponse,
    coord,
  );
  await new Promise((r) => setImmediate(r));
  assert.equal(responsePayload, null, "Must ignore prompt response with mismatched windowId");
  assert.equal(promptResolvedValue, null);

  // Matching windowId from extension page
  const correctWindowSender = {
    url: "chrome-extension://test/ui/page-access.html",
    tab: { id: 3, windowId: 777 },
  };
  responsePayload = null;
  handlePrivilegedRuntimeMessage(
    { action: "pageAccessConsentResponse", origin: originToPrompt, allowed: true },
    correctWindowSender,
    sendResponse,
    coord,
  );
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(responsePayload, { ok: true });
  assert.equal(promptResolvedValue, true, "Must resolve pending prompt on matching windowId");
});

test("Desktop page bridge accepts ONLY playback (0/1) and linkedCast from capabilities message; ignores other keys and non-capabilities messages", async (t) => {
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `test-${++sequence}` } });
  t.after(world.close);

  const channel = world.inject(DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT);
  assert.ok(channel);
  assert.equal(world.window.playbridge.capabilities.playback, 0);
  assert.equal(world.window.playbridge.capabilities.linkedCast, 1);
  assert.equal(world.window.playbridge.capabilities.nativePlugins, 0);
  assert.equal(world.window.playbridge.capabilities.localPlaybackOrientation, undefined);

  // Send capabilities message with playback, linkedCast, and unauthorized keys
  channel.post({
    channel: "linked",
    type: "capabilities",
    capabilities: {
      playback: 1,
      linkedCast: 0,
      nativePlugins: 1,
      localPlaybackOrientation: 1,
      unauthorizedKey: true,
    },
  });

  await delivered(() => world.window.playbridge.capabilities.playback === 1);
  assert.equal(world.window.playbridge.capabilities.playback, 1);
  assert.equal(world.window.playbridge.capabilities.linkedCast, 0);
  assert.equal(world.window.playbridge.capabilities.nativePlugins, 0, "nativePlugins must not be updated by capabilities message");
  assert.equal(world.window.playbridge.capabilities.localPlaybackOrientation, undefined, "localPlaybackOrientation must not be set");
  assert.equal((world.window.playbridge.capabilities as any).unauthorizedKey, undefined);

  // Invalid playback values (e.g. 2, string) are ignored
  channel.post({
    channel: "linked",
    type: "capabilities",
    capabilities: {
      playback: 2,
    },
  });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(world.window.playbridge.capabilities.playback, 1, "invalid playback value must be ignored");

  // Non-capabilities message with capabilities payload must NOT change capabilities
  channel.post({
    channel: "linked",
    type: "response",
    pageRequestId: "req-ignore",
    response: { ok: true },
    capabilities: { playback: 0 },
  });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(world.window.playbridge.capabilities.playback, 1, "non-capabilities message must not change capabilities");
});

test("Chrome manifest registers page-main.js with world: MAIN at document_start after page-content.js", async () => {
  const manifestPath = path.resolve(process.cwd(), "manifests/chrome.json");
  const manifest = JSON.parse(await fs.promises.readFile(manifestPath, "utf8"));
  assert.equal(manifest.manifest_version, 3);
  const scripts = manifest.content_scripts;
  assert.ok(Array.isArray(scripts));

  const pageContentIdx = scripts.findIndex((entry: any) =>
    Array.isArray(entry.js) && entry.js.includes("page-content.js")
  );
  const pageMainIdx = scripts.findIndex((entry: any) =>
    Array.isArray(entry.js) && entry.js.includes("page-main.js")
  );

  assert.ok(pageContentIdx !== -1, "page-content.js must be registered");
  assert.ok(pageMainIdx !== -1, "page-main.js must be registered");
  assert.ok(
    pageContentIdx < pageMainIdx,
    "page-content.js must precede page-main.js in content_scripts array for correct execution order"
  );

  const pageMainEntry = scripts[pageMainIdx];
  assert.equal(pageMainEntry.world, "MAIN");
  assert.equal(pageMainEntry.run_at, "document_start");
  assert.equal(pageMainEntry.all_frames, false);
  assert.deepEqual(pageMainEntry.matches, ["<all_urls>"]);
});

test("pnpm build emits dist/chrome/page-main.js containing desktop bridge and does not emit it for firefox", async () => {
  await new Promise<void>((resolve, reject) => {
    const cp = spawn(process.execPath, [path.resolve(process.cwd(), "build.mjs")], {
      cwd: process.cwd(),
      stdio: "pipe",
    });
    cp.on("error", reject);
    cp.on("exit", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`build exited with code ${code}`));
    });
  });

  const chromePageMain = path.resolve(process.cwd(), "dist/chrome/page-main.js");
  assert.ok(fs.existsSync(chromePageMain), "dist/chrome/page-main.js must exist");
  const content = await fs.promises.readFile(chromePageMain, "utf8");
  assert.ok(content.includes("__PLAYBRIDGE_PAGE_MAIN_BOOT__"));
  assert.ok(content.includes("playbridge_injected"));
  assert.ok(content.includes("capabilities"));

  const firefoxPageMain = path.resolve(process.cwd(), "dist/firefox/page-main.js");
  assert.ok(!fs.existsSync(firefoxPageMain), "dist/firefox/page-main.js should not be generated");
});


test("Chrome MV3 FIXED_PAGE_CHANNEL_BOOT handshake: captures boot event, passes MessagePort, and ignores further events", async () => {
  assert.equal(FIXED_PAGE_CHANNEL_BOOT, "__PLAYBRIDGE_PAGE_MAIN_BOOT__");

  let receivedChannel: PageChannel | null = null;
  const targetWindow = new EventTarget();
  const host = globalThis as any;
  const prevWindow = host.window;
  host.window = targetWindow;

  try {
    listenForPageChannelBoot(FIXED_PAGE_CHANNEL_BOOT, (ch) => {
      receivedChannel = ch;
    });

    // 1. Fake event without ports should be ignored (not accept, listener remains active)
    let fakeStopped = false;
    const fakeEvent = new Event(FIXED_PAGE_CHANNEL_BOOT);
    fakeEvent.stopImmediatePropagation = () => { fakeStopped = true; };
    targetWindow.dispatchEvent(fakeEvent);
    assert.equal(receivedChannel, null);

    // 2. Legitimate boot event with channel.port2
    const channel = new MessageChannel();
    let bootStopped = false;
    const bootEvent = new MessageEvent(FIXED_PAGE_CHANNEL_BOOT, { ports: [channel.port2] });
    bootEvent.stopImmediatePropagation = () => { bootStopped = true; };
    targetWindow.dispatchEvent(bootEvent);

    assert.ok(receivedChannel !== null, "PageChannel should be created from port");
    assert.equal(bootStopped, true, "stopImmediatePropagation should be called on boot event");

    // 3. Subsequent event should NOT trigger callback again because listener was removed
    let secondChannel: PageChannel | null = null;
    const channel2 = new MessageChannel();
    const bootEvent2 = new MessageEvent(FIXED_PAGE_CHANNEL_BOOT, { ports: [channel2.port2] });
    targetWindow.dispatchEvent(bootEvent2);
    assert.equal(secondChannel, null);

    channel.port1.close();
    channel.port2.close();
    channel2.port1.close();
    channel2.port2.close();
  } finally {
    host.window = prevWindow;
  }
});

test("initDesktopPageBridge pairs with listenForPageChannelBoot to expose window.playbridge", async (t) => {
  let sequence = 0;
  const world = pageWorld({ crypto: { randomUUID: () => `test-${++sequence}` } });
  t.after(world.close);

  let capturedChannel: PageChannel | null = null;
  const host = globalThis as any;
  const prevWindow = host.window;
  host.window = world.window;

  try {
    listenForPageChannelBoot(FIXED_PAGE_CHANNEL_BOOT, (ch) => {
      capturedChannel = ch;
    });

    world.run(`(${initDesktopPageBridge.toString()})(${JSON.stringify(FIXED_PAGE_CHANNEL_BOOT)});`);

    assert.ok(capturedChannel !== null, "Handshake should have completed and provided a PageChannel");
    assert.ok(world.window.playbridge, "window.playbridge should be set up");
    assert.equal(world.window.playbridge.capabilities.playback, 0);
    assert.equal(world.window.playbridge.capabilities.linkedCast, 1);
  } finally {
    host.window = prevWindow;
  }
});

test("PageCoordinator handles Brave/Chrome missing tab: uses sender.url or documentId fallback and answers getPlaybackDestination", async (t) => {
  const coord = new PageCoordinator();
  t.after(() => coord.destroy());
  (coord as any).isLinkedPlaybackAvailable = () => true;

  let postedResponse: any = null;
  const messageListeners: Array<(msg: any) => void> = [];
  const bravePort = {
    name: "playbridge:page-api",
    sender: {
      frameId: 0,
      url: "https://streams.playbridge.app/watch",
      documentId: "doc-uuid-1234",
      // Notice: sender.tab is missing (like on Brave)
    },
    postMessage: (m: any) => { postedResponse = m; },
    onMessage: { addListener: (fn: any) => messageListeners.push(fn) },
    onDisconnect: { addListener: () => {} },
    disconnect: () => {},
  };
  (coord as any).handlePagePortConnect(bravePort);

  // Send destination query (getPlaybackDestination)
  for (const fn of messageListeners) {
    await fn({
      type: "linked",
      pageRequestId: "req-brave-1",
      operation: "destination",
    });
  }

  assert.ok(postedResponse !== null, "Brave port request must be answered");
  assert.equal(postedResponse.type, "response");
  assert.equal(postedResponse.pageRequestId, "req-brave-1");
  assert.equal(postedResponse.response?.ok, true);
});


test("PageCoordinator never returns silently for linked requests: replies unavailable on missing origin or tabId < 0, invalid_request on invalid pageRequestId, unavailable on thrown exception", async (t) => {
  const coord = new PageCoordinator();
  t.after(() => coord.destroy());

  const postedInvalid: any[] = [];
  const invalidOriginListeners: Array<(msg: any) => void> = [];
  const invalidOriginPort = {
    name: "playbridge:page-api",
    sender: {
      frameId: 0,
      url: "invalid-url-no-origin",
    },
    postMessage: (m: any) => postedInvalid.push(m),
    onMessage: { addListener: (fn: any) => invalidOriginListeners.push(fn) },
    onDisconnect: { addListener: () => {} },
    disconnect: () => {},
  };
  (coord as any).handlePagePortConnect(invalidOriginPort);

  // 1. Missing origin with valid pageRequestId -> replies unavailable
  postedInvalid.length = 0;
  for (const fn of invalidOriginListeners) {
    await fn({
      type: "linked",
      pageRequestId: "req-no-origin",
      operation: "destination",
    });
  }
  assert.equal(postedInvalid.length, 1);
  assert.deepEqual(postedInvalid[0], {
    type: "response",
    pageRequestId: "req-no-origin",
    response: { ok: false, error: "unavailable" },
  });

  // 2. Invalid pageRequestId when one is present -> replies invalid_request
  postedInvalid.length = 0;
  for (const fn of invalidOriginListeners) {
    await fn({
      type: "linked",
      pageRequestId: "",
      operation: "destination",
    });
  }
  assert.equal(postedInvalid.length, 1);
  assert.deepEqual(postedInvalid[0], {
    type: "response",
    pageRequestId: "",
    response: { ok: false, error: "invalid_request" },
  });

  // 3. Exception in handleLinkedRequest -> catches and replies unavailable
  const postedValid: any[] = [];
  const validPortListeners: Array<(msg: any) => void> = [];
  const validPort = {
    name: "playbridge:page-api",
    sender: {
      frameId: 0,
      url: "https://example.com",
      tab: { id: 99 },
    },
    postMessage: (m: any) => postedValid.push(m),
    onMessage: { addListener: (fn: any) => validPortListeners.push(fn) },
    onDisconnect: { addListener: () => {} },
    disconnect: () => {},
  };
  (coord as any).handlePagePortConnect(validPort);

  // Force handleLinkedRequest to throw
  const origHandle = (coord as any).handleLinkedRequest;
  (coord as any).handleLinkedRequest = async () => {
    throw new Error("Simulated unexpected crash");
  };

  postedValid.length = 0;
  for (const fn of validPortListeners) {
    await fn({
      type: "linked",
      pageRequestId: "req-crash-1",
      operation: "destination",
    });
  }
  (coord as any).handleLinkedRequest = origHandle;

  assert.equal(postedValid.length, 1);
  assert.deepEqual(postedValid[0], {
    type: "response",
    pageRequestId: "req-crash-1",
    response: { ok: false, error: "unavailable" },
  });
});

test("bindPageChannel times out non-play/open requests after 10s with unavailable, but keeps long timeouts for play/open", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  const origConnect = browser.runtime.connect;

  try {
    let portMessageListener: ((msg: any) => void) | null = null;
    const postedToPort: any[] = [];
    const fakePort = {
      onMessage: { addListener: (fn: any) => { portMessageListener = fn; } },
      onDisconnect: { addListener: () => {} },
      postMessage: (m: any) => postedToPort.push(m),
    };

    browser.runtime.connect = (() => fakePort) as any;

    const channelMessages: any[] = [];
    let channelHandler: ((msg: any) => void) | null = null;
    const channel: PageChannel = {
      post: (m: any) => channelMessages.push(m),
      onMessage: (fn: any) => { channelHandler = fn; },
      close: () => {},
    };

    bindPageChannel(channel);

    // 1. Send non-play/open request (destination)
    channelMessages.length = 0;
    channelHandler!({
      channel: "linked",
      pageRequestId: "req-dest-timeout",
      operation: "destination",
      sessionId: null,
      payload: {},
    });

    assert.equal(postedToPort.length, 1);
    assert.equal(channelMessages.length, 0);

    // Advance 5s: should NOT time out yet
    mock.timers.tick(5_000);
    assert.equal(channelMessages.length, 0);

    // Advance another 5s (total 10s): should time out with unavailable
    mock.timers.tick(5_000);
    assert.equal(channelMessages.length, 1);
    assert.deepEqual(channelMessages[0], {
      channel: "linked",
      type: "response",
      pageRequestId: "req-dest-timeout",
      response: { ok: false, error: "unavailable" },
    });

    // Late response from port should be swallowed
    portMessageListener!({
      type: "response",
      pageRequestId: "req-dest-timeout",
      response: { ok: true, destination: null },
    });
    assert.equal(channelMessages.length, 1, "late response must not produce a second reply");

    // 2. Play/open request does NOT time out after 10s
    channelMessages.length = 0;
    channelHandler!({
      channel: "linked",
      pageRequestId: "req-play-timeout",
      operation: "play",
      sessionId: null,
      payload: {
        destinationId: "this-device",
        items: [{ url: "https://example.com/v.mp4" }],
      },
    });
    mock.timers.tick(15_000);
    assert.equal(channelMessages.length, 0, "play request must keep long timeout and not time out at 10s");
  } finally {
    mock.timers.reset();
    browser.runtime.connect = origConnect;
  }
});

test("bindPageChannel: when runtime.connect throws or port disconnects, answers outstanding & new requests with unavailable and broadcasts capabilities playback 0", async () => {
  const origConnect = browser.runtime.connect;

  try {
    let disconnectListener: (() => void) | null = null;
    const fakePort = {
      onMessage: { addListener: () => {} },
      onDisconnect: { addListener: (fn: any) => { disconnectListener = fn; } },
      postMessage: () => {},
    };

    browser.runtime.connect = (() => fakePort) as any;

    const channelMessages: any[] = [];
    let channelHandler: ((msg: any) => void) | null = null;
    const channel: PageChannel = {
      post: (m: any) => channelMessages.push(m),
      onMessage: (fn: any) => { channelHandler = fn; },
      close: () => {},
    };

    bindPageChannel(channel);

    // Send a request while connected
    channelMessages.length = 0;
    channelHandler!({
      channel: "linked",
      pageRequestId: "req-in-flight",
      operation: "destination",
      sessionId: null,
      payload: {},
    });
    assert.equal(channelMessages.length, 0);

    // 1. Port disconnects (e.g. extension reloaded)
    disconnectListener!();

    // Must answer outstanding request with unavailable AND broadcast capabilities playback 0
    const outResponse = channelMessages.find(
      (m) => m.type === "response" && m.pageRequestId === "req-in-flight"
    );
    assert.deepEqual(outResponse, {
      channel: "linked",
      type: "response",
      pageRequestId: "req-in-flight",
      response: { ok: false, error: "unavailable" },
    });

    const capBroadcast = channelMessages.find((m) => m.type === "capabilities");
    assert.deepEqual(capBroadcast, {
      channel: "linked",
      type: "capabilities",
      capabilities: { playback: 0 },
    });

    // 2. New request after disconnect is answered with unavailable immediately
    channelMessages.length = 0;
    channelHandler!({
      channel: "linked",
      pageRequestId: "req-new-after-disconnect",
      operation: "destination",
      sessionId: null,
      payload: {},
    });

    assert.equal(channelMessages.length, 1);
    assert.deepEqual(channelMessages[0], {
      channel: "linked",
      type: "response",
      pageRequestId: "req-new-after-disconnect",
      response: { ok: false, error: "unavailable" },
    });

    // 3. When runtime.connect throws initially (Extension context invalidated)
    browser.runtime.connect = () => {
      throw new Error("Extension context invalidated.");
    };

    const channelMessages2: any[] = [];
    let channelHandler2: ((msg: any) => void) | null = null;
    const channel2: PageChannel = {
      post: (m: any) => channelMessages2.push(m),
      onMessage: (fn: any) => { channelHandler2 = fn; },
      close: () => {},
    };

    bindPageChannel(channel2);

    // Initial connect failure broadcasts capabilities playback 0
    const capInit = channelMessages2.find((m) => m.type === "capabilities");
    assert.deepEqual(capInit, {
      channel: "linked",
      type: "capabilities",
      capabilities: { playback: 0 },
    });

    // New request also gets unavailable
    channelMessages2.length = 0;
    channelHandler2!({
      channel: "linked",
      pageRequestId: "req-throw-connect",
      operation: "destination",
      sessionId: null,
      payload: {},
    });
    assert.equal(channelMessages2.length, 1);
    assert.deepEqual(channelMessages2[0], {
      channel: "linked",
      type: "response",
      pageRequestId: "req-throw-connect",
      response: { ok: false, error: "unavailable" },
    });
  } finally {
    browser.runtime.connect = origConnect;
  }
});

test("originFromUrl extracts canonical origin from URLs with paths, queries, and hash routes", () => {
  // Hash route (like streams.playbridge.app/#/movie/tt32261958)
  assert.equal(
    originFromUrl("https://streams.playbridge.app/#/movie/tt32261958"),
    "https://streams.playbridge.app"
  );

  // Path with query parameters
  assert.equal(
    originFromUrl("https://streams.playbridge.app/watch?v=123&t=45"),
    "https://streams.playbridge.app"
  );

  // Path + query + hash combined
  assert.equal(
    originFromUrl("https://streams.playbridge.app/player/item?id=abc#t=10s"),
    "https://streams.playbridge.app"
  );

  // Bare origin (with or without trailing slash)
  assert.equal(
    originFromUrl("https://streams.playbridge.app"),
    "https://streams.playbridge.app"
  );
  assert.equal(
    originFromUrl("https://streams.playbridge.app/"),
    "https://streams.playbridge.app"
  );

  // Custom port
  assert.equal(
    originFromUrl("http://localhost:8080/app#/dashboard"),
    "http://localhost:8080"
  );

  // Default port stripped
  assert.equal(
    originFromUrl("http://localhost:80/app"),
    "http://localhost"
  );
  assert.equal(
    originFromUrl("https://localhost:443/app"),
    "https://localhost"
  );

  // Rejections: credentials, invalid protocols, malformed
  assert.equal(originFromUrl("https://user:pass@example.com/movie"), null);
  assert.equal(originFromUrl("javascript:alert(1)"), null);
  assert.equal(originFromUrl("file:///home/user/index.html"), null);
  assert.equal(originFromUrl("data:text/html,test"), null);
  assert.equal(originFromUrl(""), null);
  assert.equal(originFromUrl(null), null);
  assert.equal(originFromUrl(undefined), null);
  assert.equal(originFromUrl("not a valid url"), null);
});

test("normalizeOrigin stays strict for stored/explicit origin strings and rejects URLs with path, query, or hash", () => {
  assert.equal(
    normalizeOrigin("https://streams.playbridge.app"),
    "https://streams.playbridge.app"
  );
  assert.equal(
    normalizeOrigin("https://streams.playbridge.app/"),
    "https://streams.playbridge.app"
  );

  // Strictly rejected when path, query, or hash present
  assert.equal(
    normalizeOrigin("https://streams.playbridge.app/#/movie/tt32261958"),
    null
  );
  assert.equal(
    normalizeOrigin("https://streams.playbridge.app/watch"),
    null
  );
  assert.equal(
    normalizeOrigin("https://streams.playbridge.app/?query=1"),
    null
  );
});

test("PageCoordinator handles sender URLs with paths, queries, and hash routes without dropping requests", async (t) => {
  const coord = new PageCoordinator();
  t.after(() => coord.destroy());
  (coord as any).isLinkedPlaybackAvailable = () => true;

  // 1. Hash route URL (e.g. streams.playbridge.app/#/movie/tt32261958)
  let postedHash: any = null;
  const hashListeners: Array<(msg: any) => void> = [];
  const hashPort = {
    name: "playbridge:page-api",
    sender: {
      frameId: 0,
      url: "https://streams.playbridge.app/#/movie/tt32261958",
      documentId: "doc-hash-1",
    },
    postMessage: (m: any) => { postedHash = m; },
    onMessage: { addListener: (fn: any) => hashListeners.push(fn) },
    onDisconnect: { addListener: () => {} },
    disconnect: () => {},
  };
  (coord as any).handlePagePortConnect(hashPort);

  for (const fn of hashListeners) {
    await fn({
      type: "linked",
      pageRequestId: "req-hash-1",
      operation: "destination",
    });
  }
  assert.ok(postedHash !== null, "Hash-route page request must not be dropped");
  assert.equal(postedHash.pageRequestId, "req-hash-1");
  assert.equal(postedHash.response?.ok, true);

  // 2. Path and query URL (e.g. example.com/watch?v=movie123&autoplay=1)
  let postedQuery: any = null;
  const queryListeners: Array<(msg: any) => void> = [];
  const queryPort = {
    name: "playbridge:page-api",
    sender: {
      frameId: 0,
      url: "https://example.com/watch?v=movie123&autoplay=1",
      documentId: "doc-query-1",
    },
    postMessage: (m: any) => { postedQuery = m; },
    onMessage: { addListener: (fn: any) => queryListeners.push(fn) },
    onDisconnect: { addListener: () => {} },
    disconnect: () => {},
  };
  (coord as any).handlePagePortConnect(queryPort);

  for (const fn of queryListeners) {
    await fn({
      type: "linked",
      pageRequestId: "req-query-1",
      operation: "destination",
    });
  }
  assert.ok(postedQuery !== null, "Path-and-query page request must not be dropped");
  assert.equal(postedQuery.pageRequestId, "req-query-1");
  assert.equal(postedQuery.response?.ok, true);
});




test("every runtime action sent by extension UI and content scripts has a background handler", () => {
  const read = (file: string) => fs.readFileSync(path.join(process.cwd(), file), "utf8");
  const handled = new Set(
    [...read("src/background.ts").matchAll(/msg\.action === "([A-Za-z]+)"/g)].map((m) => m[1]),
  );
  for (const file of ["src/ui/page-access.ts", "src/ui/popup.ts", "src/content.ts"]) {
    const sent = [...read(file).matchAll(/action: ["']([A-Za-z]+)["']/g)].map((m) => m[1]);
    for (const action of sent) {
      assert.ok(handled.has(action), `${file} sends "${action}" but background.ts has no handler`);
    }
  }
});
