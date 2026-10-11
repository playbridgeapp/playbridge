import assert from "node:assert/strict";
import test from "node:test";

if (!globalThis.chrome) {
  (globalThis as any).chrome = {
    runtime: {
      id: "test",
      connectNative: () => ({
        postMessage: () => {},
        onMessage: { addListener: () => {} },
        onDisconnect: { addListener: () => {} },
      }),
    },
  };
}

const bridge = await import("../src/native-bridge");

function nextTick(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

function createFakePort() {
  const sent: any[] = [];
  const messageListeners: Array<(msg: any) => void> = [];
  const disconnectListeners: Array<() => void> = [];
  return {
    postMessage(msg: any) {
      sent.push(msg);
    },
    onMessage: {
      addListener(fn: (msg: any) => void) {
        messageListeners.push(fn);
      },
    },
    onDisconnect: {
      addListener(fn: () => void) {
        disconnectListeners.push(fn);
      },
    },
    sent,
    receive(msg: any) {
      for (const fn of messageListeners) fn(msg);
    },
    disconnect() {
      for (const fn of disconnectListeners) fn();
    },
  };
}

test("native-bridge parses extended device selection, capabilities and device fields", () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Deliver state message with capabilities and device selection
  fakePort.receive({
    type: "state",
    state: "connected",
    target: "tv",
    capabilities: { selectDevice: 1 },
    selection: {
      kind: "tv",
      id: "device-1",
      name: "Living Room TV",
      status: "connected",
    },
    activeTv: "Living Room TV",
    devices: [
      {
        id: "device-1",
        uuid: "uuid-1",
        name: "Living Room TV",
        paired: true,
        online: true,
        active: true,
      },
      {
        id: "device-2",
        uuid: "uuid-2",
        name: "Bedroom TV",
        paired: false,
        online: true,
        active: false,
      },
    ],
  });

  const state = bridge.getState();
  assert.equal(state.desktopConnected, true);
  assert.equal(state.tvState, "connected");
  assert.equal(state.target, "tv");
  assert.deepEqual(state.capabilities, { selectDevice: 1 });
  assert.deepEqual(state.selection, {
    kind: "tv",
    id: "device-1",
    name: "Living Room TV",
    status: "connected",
  });
  assert.equal(state.activeTv, "Living Room TV");
  assert.equal(state.devices.length, 2);
  assert.equal(state.devices[0].id, "device-1");
  assert.equal(state.devices[0].uuid, "uuid-1");
  assert.equal(state.devices[0].active, true);
  assert.equal(state.devices[0].online, true);
  assert.equal(state.devices[1].paired, false);

  // Deliver state with local selection
  fakePort.receive({
    type: "state",
    state: "connected",
    target: "local",
    capabilities: { selectDevice: 1 },
    selection: { kind: "local" },
    activeTv: null,
    devices: [],
  });

  const localState = bridge.getState();
  assert.equal(localState.target, "local");
  assert.deepEqual(localState.selection, { kind: "local" });
  assert.equal(localState.activeTv, null);
});

test("native-bridge maintains backward compatibility with legacy desktop state without capabilities", () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Legacy desktop sends state without capabilities, target, or selection
  fakePort.receive({
    type: "state",
    state: "connected",
    activeTv: "Legacy TV",
    devices: [
      { uuid: "legacy-uuid", name: "Legacy TV", paired: true },
    ],
  });

  const state = bridge.getState();
  assert.equal(state.desktopConnected, true);
  assert.equal(state.tvState, "connected");
  assert.equal(state.activeTv, "Legacy TV");
  assert.equal(state.capabilities, undefined);
  assert.equal(state.target, undefined);
  assert.equal(state.selection, undefined);
  assert.equal(state.devices.length, 1);
  assert.equal(state.devices[0].uuid, "legacy-uuid");
  assert.equal(state.devices[0].id, "legacy-uuid"); // id falls back to uuid
});

test("selectDevice, selectLocal, refreshDevices, and showApp send expected wire messages and resolve results", async () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Put bridge in connected state
  fakePort.receive({ type: "hello" });

  // 1. selectDevice
  const pSelect = bridge.selectDevice("tv-100");
  await nextTick();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "select_device", id: "tv-100" });
  fakePort.receive({
    type: "result",
    ok: true,
    target: "tv",
    targetName: "LG OLED",
  });
  const rSelect = await pSelect;
  assert.deepEqual(rSelect, {
    ok: true,
    target: "tv",
    targetName: "LG OLED",
    error: undefined,
    code: undefined,
  });

  // 2. selectLocal
  const pLocal = bridge.selectLocal();
  await nextTick();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "select_local" });
  fakePort.receive({
    type: "result",
    ok: true,
    target: "local",
    targetName: "This computer",
  });
  const rLocal = await pLocal;
  assert.deepEqual(rLocal, {
    ok: true,
    target: "local",
    targetName: "This computer",
    error: undefined,
    code: undefined,
  });

  // 3. refreshDevices
  const pRefresh = bridge.refreshDevices();
  await nextTick();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "refresh_devices" });
  fakePort.receive({ type: "result", ok: true });
  const rRefresh = await pRefresh;
  assert.equal(rRefresh.ok, true);

  // 4. showApp with pairId
  const pShowPair = bridge.showApp("pair-device-5");
  await nextTick();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "show_app", pairId: "pair-device-5" });
  fakePort.receive({ type: "result", ok: true });
  const rShowPair = await pShowPair;
  assert.equal(rShowPair.ok, true);

  // 5. showApp without pairId
  const pShow = bridge.showApp();
  await nextTick();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "show_app" });
  fakePort.receive({ type: "result", ok: true });
  const rShow = await pShow;
  assert.equal(rShow.ok, true);
});

test("cast supports destination: 'local' and returns extended BridgeResult", async () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  fakePort.receive({ type: "hello" });

  const pCast = bridge.cast(
    "https://example.com/stream.m3u8",
    { Referer: "https://example.com/" },
    "Test Title",
    "application/x-mpegurl",
    null,
    null,
    "local",
  );
  await nextTick();

  const sentPayload = fakePort.sent.pop();
  assert.equal(sentPayload.cmd, "cast");
  assert.equal(sentPayload.url, "https://example.com/stream.m3u8");
  assert.equal(sentPayload.destination, "local");

  fakePort.receive({
    type: "result",
    ok: true,
    target: "local",
    targetName: "This computer",
  });
  const res = await pCast;
  assert.deepEqual(res, {
    ok: true,
    target: "local",
    targetName: "This computer",
    error: undefined,
    code: undefined,
  });
});

test("launchApp has no FIFO result: resolves on hello or state, ignores early app_not_running frame", async () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  assert.equal(bridge.getState().desktopConnected, false);

  // 1. Calling launchApp sends { cmd: 'launch_app' } without consuming pendingResults
  const pLaunch = bridge.launchApp();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "launch_app" });

  // 2. The stub sends { type: 'error', code: 'app_not_running' } right after connectNative.
  // This frame must NOT cancel or reject the pending launch!
  fakePort.receive({
    type: "error",
    error: "PlayBridge is not running",
    code: "app_not_running",
  });

  let launchSettled = false;
  pLaunch.then(() => { launchSettled = true; }, () => { launchSettled = true; });
  await nextTick();
  assert.equal(launchSettled, false);

  // 3. Desktop app connects and sends hello frame
  fakePort.receive({ type: "hello" });
  const res = await pLaunch;
  assert.equal(res.ok, true);
  assert.equal(bridge.getState().desktopConnected, true);

  // 4. Test launch failure frame
  bridge._resetStateForTesting();
  const fakePort2 = createFakePort();
  bridge._setPortForTesting(fakePort2);

  const pFail = bridge.launchApp();
  assert.deepEqual(fakePort2.sent.pop(), { cmd: "launch_app" });
  fakePort2.receive({
    type: "error",
    error: "Failed to launch PlayBridge: binary not found",
    code: "launch_failed",
  });
  const failRes = await pFail;
  assert.equal(failRes.ok, false);
  assert.equal(failRes.code, "launch_failed");
  assert.equal(failRes.error, "Failed to launch PlayBridge: binary not found");
});

test("launchApp does not consume FIFO pendingResults and times out after 25s", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Desktop is not connected
  assert.equal(bridge.getState().desktopConnected, false);

  // launchApp sends { cmd: 'launch_app' } without pushing to pendingResults
  const pLaunch = bridge.launchApp();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "launch_app" });

  // A stray result frame must NOT resolve launchApp (since launchApp doesn't use pendingResults)
  fakePort.receive({ type: "result", ok: true, target: "local" });

  let launchSettled = false;
  pLaunch.then(() => { launchSettled = true; });
  await nextTick();
  assert.equal(launchSettled, false);

  // Advance time by 25s to trigger launchApp timeout
  t.mock.timers.tick(25_000);
  const rLaunch = await pLaunch;
  assert.equal(rLaunch.ok, false);
  assert.equal(rLaunch.code, "launch_timeout");
});

test("ensureConnected sends list_devices when port exists but desktopConnected is false", async () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Set desktopConnected = false while port is still alive (stub is alive after app_not_running)
  fakePort.receive({
    type: "error",
    code: "app_not_running",
    error: "PlayBridge is not running",
  });
  assert.equal(bridge.getState().desktopConnected, false);
  fakePort.sent.length = 0;

  // Now an in-flight command triggers ensureConnected()
  const pSelect = bridge.selectLocal();
  await nextTick();

  // It should send list_devices to retry app connection over the existing port!
  assert.deepEqual(fakePort.sent.shift(), { cmd: "list_devices" });

  // Simulate app responding to list_devices with state
  fakePort.receive({
    type: "state",
    state: "connected",
    devices: [],
  });

  // Now ensureConnected succeeded and dispatched select_local
  await nextTick();
  assert.deepEqual(fakePort.sent.shift(), { cmd: "select_local" });

  fakePort.receive({ type: "result", ok: true, target: "local" });
  const res = await pSelect;
  assert.equal(res.ok, true);
  assert.equal(res.target, "local");
});

test("sendCommand keeps FIFO placeholder on timeout and swallows late result without corrupting next request", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  fakePort.receive({ type: "hello" });

  // 1. Send first command (selectDevice with 20s timeout)
  const p1 = bridge.selectDevice("tv-slow");
  await nextTick();
  assert.equal(fakePort.sent.length, 1);
  assert.deepEqual(fakePort.sent[0], { cmd: "select_device", id: "tv-slow" });

  // 2. Advance time by 20s so p1 times out
  t.mock.timers.tick(20_000);
  const r1 = await p1;
  assert.equal(r1.ok, false);
  assert.equal(r1.code, "timeout");

  // 3. Send second command (selectLocal)
  const p2 = bridge.selectLocal();
  await nextTick();
  assert.equal(fakePort.sent.length, 2);
  assert.deepEqual(fakePort.sent[1], { cmd: "select_local" });

  // 4. Now the late result for first command arrives
  fakePort.receive({ type: "result", ok: true, target: "tv", targetName: "Slow TV" });

  // p2 should NOT have resolved yet because late result for p1 was swallowed!
  let p2Resolved = false;
  p2.then(() => {
    p2Resolved = true;
  });
  await nextTick();
  assert.equal(p2Resolved, false);

  // 5. Now the result for second command arrives
  fakePort.receive({ type: "result", ok: true, target: "local", targetName: "This computer" });
  const r2 = await p2;
  assert.equal(r2.ok, true);
  assert.equal(r2.target, "local");
  assert.equal(r2.targetName, "This computer");
});

test("commands use raised timeouts: cast 30s, select_device 20s, others 10s", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  fakePort.receive({ type: "hello" });

  // select_device timeout is 20s
  const pSelect = bridge.selectDevice("tv-1");
  await nextTick();
  t.mock.timers.tick(19_999);
  let selectResolved = false;
  pSelect.then(() => { selectResolved = true; });
  await nextTick();
  assert.equal(selectResolved, false);
  t.mock.timers.tick(1);
  const rSelect = await pSelect;
  assert.equal(rSelect.ok, false);
  assert.equal(rSelect.code, "timeout");

  // cast timeout is 30s
  const pCast = bridge.cast("https://example.com/v.mp4");
  await nextTick();
  t.mock.timers.tick(29_999);
  let castResolved = false;
  pCast.then(() => { castResolved = true; });
  await nextTick();
  assert.equal(castResolved, false);
  t.mock.timers.tick(1);
  const rCast = await pCast;
  assert.equal(rCast.ok, false);
  assert.equal(rCast.code, "timeout");

  // selectLocal timeout is 10s
  const pLocal = bridge.selectLocal();
  await nextTick();
  t.mock.timers.tick(9_999);
  let localResolved = false;
  pLocal.then(() => { localResolved = true; });
  await nextTick();
  assert.equal(localResolved, false);
  t.mock.timers.tick(1);
  const rLocal = await pLocal;
  assert.equal(rLocal.ok, false);
  assert.equal(rLocal.code, "timeout");
});

test("app_not_running error updates state and fails in-flight request without tearing down port", async () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Set connected state first
  fakePort.receive({
    type: "state",
    state: "connected",
    activeTv: "Living Room TV",
    devices: [{ id: "1", uuid: "1", name: "Living Room TV", paired: true }],
  });
  assert.equal(bridge.getState().desktopConnected, true);

  // Start in-flight selectDevice command
  const pSelect = bridge.selectDevice("1");
  await nextTick();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "select_device", id: "1" });

  // Stub reports desktop closed
  fakePort.receive({
    type: "error",
    error: "PlayBridge is not running",
    code: "app_not_running",
  });

  const res = await pSelect;
  assert.equal(res.ok, false);
  assert.equal(res.code, "app_not_running");
  assert.equal(res.error, "PlayBridge is not running");

  // Verify desktopConnected is false
  assert.equal(bridge.getState().desktopConnected, false);

  // Port is still alive and can send launchApp
  const pLaunch = bridge.launchApp();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "launch_app" });
  fakePort.receive({ type: "hello" });
  assert.equal((await pLaunch).ok, true);
});

test("wake resets reconnect attempts and is throttled to 500ms", () => {
  bridge._resetStateForTesting();
  const fakePort = createFakePort();
  bridge._setPortForTesting(fakePort);

  // Initially desktopConnected is false
  assert.equal(bridge.getState().desktopConnected, false);

  bridge.wake();
  assert.deepEqual(fakePort.sent.pop(), { cmd: "list_devices" });

  // Immediately call wake again within 500ms -> should be throttled
  bridge.wake();
  assert.equal(fakePort.sent.length, 0);
});

test("content script interactive overlay handlers enforce isTrusted check on all click events", async () => {
  const fs = await import("node:fs");
  const path = await import("node:path");
  const contentSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/content.ts"),
    "utf-8",
  );

  // 1. Verify onChipClick begins with isTrusted check
  assert.match(
    contentSource,
    /function onChipClick\s*\([^)]*\)\s*:\s*void\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;/,
    "onChipClick must guard against untrusted synthetic events",
  );

  // 2. Verify onCastClick begins with isTrusted check
  assert.match(
    contentSource,
    /async function onCastClick\s*\([^)]*\)\s*:\s*Promise<void>\s*\{\s*[\s\S]*?if\s*\(!e\.isTrusted\)\s*return;/,
    "onCastClick must guard against untrusted synthetic events",
  );

  // 3. Verify localBtn click handler guards with isTrusted
  assert.match(
    contentSource,
    /localBtn\.addEventListener\("click",\s*\((?:e|\w+)\)\s*=>\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;/,
    "localBtn click listener must guard with !e.isTrusted",
  );

  // 4. Verify devBtn click handler guards with isTrusted
  const devBtnMatches = [
    ...contentSource.matchAll(
      /devBtn\.addEventListener\("click",\s*\((?:e|\w+)\)\s*=>\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;/g,
    ),
  ];
  assert.equal(
    devBtnMatches.length >= 2,
    true,
    "devBtn click listeners (paired and unpaired) must guard with !e.isTrusted",
  );

  // 5. Verify pairBtn click handler guards with isTrusted
  assert.match(
    contentSource,
    /pairBtn\.addEventListener\("click",\s*\((?:e|\w+)\)\s*=>\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;/,
    "pairBtn click listener must guard with !e.isTrusted",
  );

  // 6. Verify setStatusWithAction btn click handler guards with isTrusted
  assert.match(
    contentSource,
    /btn\.addEventListener\("click",\s*\((?:e|\w+)\)\s*=>\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;\s*e\.stopPropagation\(\);\s*e\.preventDefault\(\);\s*onActionClick\(\);/,
    "status action button must guard with !e.isTrusted",
  );
});

test("formatDeviceProtocol correctly maps known protocols, preserves raw unknown values, and omits absent values", () => {
  const { formatDeviceProtocol } = bridge;
  assert.equal(typeof formatDeviceProtocol, "function");

  // Known protocol mappings
  assert.equal(formatDeviceProtocol("playBridge"), "PlayBridge");
  assert.equal(formatDeviceProtocol("googleCast"), "Google Cast");
  assert.equal(formatDeviceProtocol("dlna"), "DLNA");
  assert.equal(formatDeviceProtocol("roku"), "Roku");
  assert.equal(formatDeviceProtocol("webBrowser"), "Web Browser");

  // Unknown protocols return raw value
  assert.equal(formatDeviceProtocol("airplay"), "airplay");
  assert.equal(formatDeviceProtocol("custom-proto"), "custom-proto");
  assert.equal(formatDeviceProtocol("DIAL"), "DIAL");

  // Absent values return null (omitted)
  assert.equal(formatDeviceProtocol(undefined), null);
  assert.equal(formatDeviceProtocol(null), null);
  assert.equal(formatDeviceProtocol(""), null);
  assert.equal(formatDeviceProtocol("   "), null);
});

test("protocolChips extracts unique protocols in canonical order", () => {
  const { protocolChips } = bridge;
  assert.equal(typeof protocolChips, "function");

  const devices = [
    { protocol: "roku" },
    { protocol: "googleCast" },
    { protocol: "dlna" },
    { protocol: "playBridge" },
    { protocol: "webBrowser" },
    { protocol: "airplay" },
    { protocol: "custom-proto" },
    { protocol: "googleCast" }, // duplicate
    { protocol: undefined },
    { protocol: "" },
    { protocol: "   " },
  ];

  const chips = protocolChips(devices);
  assert.deepEqual(chips, [
    "playBridge",
    "googleCast",
    "dlna",
    "roku",
    "webBrowser",
    "airplay",
    "custom-proto",
  ]);

  // When devices have no protocol or empty
  assert.deepEqual(protocolChips([]), []);
  assert.deepEqual(protocolChips([{ protocol: "" }, { protocol: undefined }]), []);
});

test("defaultProtocol determines the default selected protocol chip with proper priority", () => {
  const { defaultProtocol } = bridge;
  assert.equal(typeof defaultProtocol, "function");

  const devices = [
    { id: "tv-dlna", uuid: "1", name: "Living Room TV", protocol: "dlna", active: false },
    { id: "tv-cast", uuid: "2", name: "Living Room TV", protocol: "googleCast", active: false },
    { id: "tv-pb", uuid: "3", name: "Living Room TV", protocol: "playBridge", active: false },
    { id: "tv-roku", uuid: "4", name: "Bedroom Roku", protocol: "roku", active: false },
  ];

  // Priority 1: Remembered choice if still present
  assert.equal(
    defaultProtocol(devices, { kind: "tv", id: "tv-cast" }, "roku", "Living Room TV"),
    "roku",
  );
  assert.equal(
    defaultProtocol(devices, { kind: "tv", id: "tv-cast" }, "googleCast", "Living Room TV"),
    "googleCast",
  );

  // Priority 2: Active device protocol if remembered is not present/empty
  assert.equal(
    defaultProtocol(devices, { kind: "tv", id: "tv-dlna" }, null, "Living Room TV"),
    "dlna",
  );
  assert.equal(
    defaultProtocol(devices, { kind: "tv", id: "tv-dlna" }, "nonexistent", "Living Room TV"),
    "dlna",
  );

  // Priority 3: PlayBridge if active device is not present (or selection is local)
  assert.equal(
    defaultProtocol(devices, { kind: "local" }, null, "Living Room TV"),
    "playBridge",
  );
  assert.equal(
    defaultProtocol(devices, null, null, null),
    "playBridge",
  );

  // Priority 4: First chip if PlayBridge not present and no active device/remembered
  const noPbDevices = [
    { id: "tv-dlna", name: "TV", protocol: "dlna" },
    { id: "tv-roku", name: "TV", protocol: "roku" },
  ];
  assert.equal(
    defaultProtocol(noPbDevices, null, null, null),
    "dlna",
  );

  // Empty devices returns null
  assert.equal(defaultProtocol([], null, null, null), null);
  assert.equal(defaultProtocol([{ name: "Legacy" }], null, null, null), null);
});

test("filterByProtocol filters devices by protocol case-insensitively", () => {
  const { filterByProtocol } = bridge;
  assert.equal(typeof filterByProtocol, "function");

  const devices = [
    { name: "TV 1", protocol: "playBridge" },
    { name: "TV 2", protocol: "googleCast" },
    { name: "TV 3", protocol: "dlna" },
    { name: "TV 4", protocol: "PlayBridge" },
  ];

  assert.deepEqual(filterByProtocol(devices, "playBridge"), [
    { name: "TV 1", protocol: "playBridge" },
    { name: "TV 4", protocol: "PlayBridge" },
  ]);
  assert.deepEqual(filterByProtocol(devices, "googlecast"), [
    { name: "TV 2", protocol: "googleCast" },
  ]);
  // Null or empty protocol returns all devices
  assert.deepEqual(filterByProtocol(devices, null), devices);
  assert.deepEqual(filterByProtocol(devices, undefined), devices);
});

test("popup and content script render protocol chips row at the top and remove per-row protocol badges", async () => {
  const fs = await import("node:fs");
  const path = await import("node:path");

  // Check popup.ts
  const popupSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/ui/popup.ts"),
    "utf-8",
  );
  // Per-row badge must not be present
  assert.equal(
    popupSource.includes("badge-protocol"),
    false,
    "popup.ts must remove per-row badge-protocol",
  );
  // Protocol chips row must be present
  assert.match(
    popupSource,
    /chipsRow\.className = 'protocol-chips-row';/,
    "popup.ts must render protocol-chips-row",
  );
  assert.match(
    popupSource,
    /btn\.className = 'protocol-chip'/,
    "popup.ts must render protocol-chip buttons",
  );
  // Chips row must be appended before 'This computer' row in popup
  const popupChipsIdx = popupSource.indexOf("devicesList.appendChild(chipsRow);");
  const popupLocalIdx = popupSource.indexOf("devicesList.appendChild(localItem);");
  assert.equal(
    popupChipsIdx !== -1 && popupLocalIdx !== -1 && popupChipsIdx < popupLocalIdx,
    true,
    "popup.ts must append protocol chips row at the top above This computer",
  );

  // Check content.ts
  const contentSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/content.ts"),
    "utf-8",
  );
  // Per-row protocol badge must not be present
  assert.equal(
    contentSource.includes("picker-item-badge protocol"),
    false,
    "content.ts must remove per-row picker-item-badge protocol",
  );
  // Picker protocol chips row must be present
  assert.match(
    contentSource,
    /chipsRow\.className = "picker-protocol-chips";/,
    "content.ts must render picker-protocol-chips",
  );
  assert.match(
    contentSource,
    /chipBtn\.className = "protocol-chip"/,
    "content.ts must render protocol-chip buttons",
  );
  // Chips row must be appended before 'This computer' row in content picker
  const contentChipsIdx = contentSource.indexOf("devicePickerEl.appendChild(chipsRow);");
  const contentLocalIdx = contentSource.indexOf("devicePickerEl.appendChild(localBtn);");
  assert.equal(
    contentChipsIdx !== -1 && contentLocalIdx !== -1 && contentChipsIdx < contentLocalIdx,
    true,
    "content.ts must append protocol chips row at the top above This computer",
  );
  // Chip click handler must check e.isTrusted and must not call closePicker
  assert.match(
    contentSource,
    /chipBtn\.addEventListener\("click",\s*\((?:e|\w+)\)\s*=>\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;\s*e\.stopPropagation\(\);\s*e\.preventDefault\(\);[\s\S]*?renderPicker\(\);/,
    "content.ts chip click must guard with isTrusted and re-render picker without closing",
  );
});

test("picking a TV in popup or picker updates remembered protocol while picking This computer does not", async () => {
  const fs = await import("node:fs");
  const path = await import("node:path");

  const popupSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/ui/popup.ts"),
    "utf-8",
  );
  // In popup selectDevice click handler:
  assert.match(
    popupSource,
    /if\s*\(d\.protocol\)\s*\{\s*rememberedProtocol = d\.protocol;\s*try\s*\{\s*browser\.storage\.local\s*\.set\(\{\s*\[DEVICE_PICKER_PROTOCOL_STORAGE_KEY\]:\s*d\.protocol\s*\}\)/,
    "popup selectDevice must update rememberedProtocol in memory and storage",
  );

  const contentSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/content.ts"),
    "utf-8",
  );
  // In content selectDevice click handler:
  assert.match(
    contentSource,
    /if\s*\(device\.protocol\)\s*\{\s*rememberedPickerProtocol = device\.protocol;\s*try\s*\{\s*browser\.storage\.local\s*\.set\(\{\s*\[DEVICE_PICKER_PROTOCOL_STORAGE_KEY\]:\s*device\.protocol\s*\}\)/,
    "content selectDevice must update rememberedPickerProtocol in memory and storage",
  );
});


test("isActiveDevice marks only the selected protocol entry of a multi-protocol TV", () => {
  const { isActiveDevice } = bridge;
  const rows = [
    { id: "dlna:uuid:9180", uuid: "uuid:9180", name: "Master bedroom TV", active: false },
    { id: "googleCast:95d1", uuid: "95d1", name: "Master bedroom TV", active: false },
    { id: "playBridge:1b5e", uuid: "1b5e", name: "Master bedroom TV", active: true },
  ];
  const selection = { kind: "tv", id: "playBridge:1b5e", name: "Master bedroom TV" };
  assert.deepEqual(rows.map((d) => isActiveDevice(d, selection, "Master bedroom TV")), [false, false, true]);
  // This computer selected: no TV row is active even if a name matches activeTv.
  assert.deepEqual(rows.map((d) => isActiveDevice(d, { kind: "local" }, "Master bedroom TV")), [false, false, false]);
  // Legacy desktop (no selection): fall back to the activeTv name.
  assert.equal(isActiveDevice({ uuid: "x", name: "Legacy TV" }, undefined, "Legacy TV"), true);
  assert.equal(isActiveDevice({ uuid: "y", name: "Other" }, undefined, "Legacy TV"), false);
});

test("visibleDevices filters out offline non-active devices by default and keeps legacy devices", () => {
  const { visibleDevices } = bridge;
  assert.equal(typeof visibleDevices, "function");

  const devices = [
    { id: "tv-1", name: "Online TV", online: true, active: false },
    { id: "tv-2", name: "Offline Inactive TV", online: false, active: false },
    { id: "tv-3", name: "Offline Active TV", online: false, active: true },
    { id: "tv-4", name: "Legacy Desktop TV" }, // no online field
  ];

  const selection = { kind: "tv", id: "tv-3" };

  // By default (showOffline = false):
  // tv-2 is hidden because online === false and not active.
  // tv-3 is included because it is active.
  // tv-4 is included because online is undefined (legacy).
  const visibleCollapsed = visibleDevices(devices, selection, null, false);
  assert.deepEqual(
    visibleCollapsed.map((d: any) => d.id),
    ["tv-1", "tv-3", "tv-4"],
  );

  // When expanded (showOffline = true): all devices returned
  const visibleExpanded = visibleDevices(devices, selection, null, true);
  assert.deepEqual(
    visibleExpanded.map((d: any) => d.id),
    ["tv-1", "tv-2", "tv-3", "tv-4"],
  );
});

test("hiddenOfflineCount counts only offline non-active devices, filtered by protocol if provided", () => {
  const { hiddenOfflineCount } = bridge;
  assert.equal(typeof hiddenOfflineCount, "function");

  const devices = [
    { id: "tv-pb-1", name: "PB Online", protocol: "playBridge", online: true },
    { id: "tv-pb-2", name: "PB Offline", protocol: "playBridge", online: false },
    { id: "tv-dlna-1", name: "DLNA Offline Inactive", protocol: "dlna", online: false },
    { id: "tv-dlna-2", name: "DLNA Offline Active", protocol: "dlna", online: false },
    { id: "tv-roku-1", name: "Roku Legacy", protocol: "roku" }, // no online field
  ];

  const selection = { kind: "tv", id: "tv-dlna-2" };

  // For playBridge: 1 hidden offline device (tv-pb-2)
  assert.equal(hiddenOfflineCount(devices, selection, null, "playBridge"), 1);

  // For dlna: tv-dlna-2 is active so not hidden; tv-dlna-1 is hidden -> count 1
  assert.equal(hiddenOfflineCount(devices, selection, null, "dlna"), 1);

  // For roku: 0 hidden devices
  assert.equal(hiddenOfflineCount(devices, selection, null, "roku"), 0);

  // Across all devices (protocol null): tv-pb-2 and tv-dlna-1 -> count 2
  assert.equal(hiddenOfflineCount(devices, selection, null, null), 2);
});

test("protocol chips only include protocols with visible devices when offline devices are hidden", () => {
  const { visibleDevices, protocolChips, defaultProtocol } = bridge;

  const devices = [
    { id: "1", name: "Roku TV", protocol: "roku", online: true },
    { id: "2", name: "DLNA TV", protocol: "dlna", online: false }, // only device for DLNA is offline
    { id: "3", name: "PlayBridge TV", protocol: "playBridge", online: false }, // offline
    { id: "4", name: "Cast TV", protocol: "googleCast", online: true },
  ];

  // When collapsed: only roku and googleCast have online devices
  const visible = visibleDevices(devices, null, null, false);
  const chips = protocolChips(visible);
  // DLNA and PlayBridge must NOT have chips because their devices are offline
  assert.deepEqual(chips, ["googleCast", "roku"]);

  // If user remembered "dlna" or "playBridge", defaultProtocol falls back to visible chips
  assert.equal(defaultProtocol(visible, null, "dlna", null), "googleCast");
  assert.equal(defaultProtocol(visible, null, "playBridge", null), "googleCast");

  // When expanded: dlna and playBridge chips reappear
  const expanded = visibleDevices(devices, null, null, true);
  assert.deepEqual(protocolChips(expanded), ["playBridge", "googleCast", "dlna", "roku"]);
});

test("popup and content script mark active offline device as Offline and render toggle offline button", async () => {
  const fs = await import("node:fs");
  const path = await import("node:path");

  // Check popup.ts
  const popupSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/ui/popup.ts"),
    "utf-8",
  );
  // Active offline device is marked Offline
  assert.match(
    popupSource,
    /d\.online === false\s*\?\s*'badge-offline'\s*:\s*isTvActive\s*\?\s*'badge-active'\s*:\s*'badge-online'/,
    "popup.ts must mark active offline device with badge-offline",
  );
  // Toggle offline button
  assert.match(
    popupSource,
    /toggleBtn\.className = 'toggle-offline-btn';/,
    "popup.ts must render toggle-offline-btn",
  );
  assert.match(
    popupSource,
    /showOfflinePopup \? 'Hide offline' : `Show \$\{offlineCount\} offline`/,
    "popup.ts must show correct toggle offline label",
  );

  // Check content.ts
  const contentSource = fs.readFileSync(
    path.resolve(process.cwd(), "src/content.ts"),
    "utf-8",
  );
  // Active offline device is marked Offline
  assert.match(
    contentSource,
    /device\.online === false \? "Offline" : isTvActive \? "Active" : "Online"/,
    "content.ts must mark active offline device with Offline text",
  );
  // Toggle offline button in content.ts
  assert.match(
    contentSource,
    /toggleBtn\.className = "toggle-offline-btn";/,
    "content.ts must render toggle-offline-btn",
  );
  // Toggle click in content.ts must check e.isTrusted and must not close picker
  assert.match(
    contentSource,
    /toggleBtn\.addEventListener\("click",\s*\((?:e|\w+)\)\s*=>\s*\{\s*if\s*\(!e\.isTrusted\)\s*return;\s*e\.stopPropagation\(\);\s*e\.preventDefault\(\);\s*showOfflinePicker = !showOfflinePicker;\s*renderPicker\(\);/,
    "content.ts toggle offline click must check e.isTrusted and re-render without closing picker",
  );
});
