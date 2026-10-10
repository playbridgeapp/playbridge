// Native-messaging bridge to the PlayBridge desktop app (host `com.playbridge.host`).
//
// Replaces the old direct `ws://` connection to the TV. The desktop app holds the
// pinned `wss://` link and owns TV discovery/pairing/selection; the extension
// just hands it a URL + headers. Tokens therefore never cross the cleartext LAN
// from the browser, and the extension never touches the TV's self-signed cert.

import browser from "./browser";
export { formatDeviceProtocol } from "./core/device-protocol";

declare const __PB_DEBUG__: boolean;
const isDebug = typeof __PB_DEBUG__ !== "undefined" && Boolean(__PB_DEBUG__);

export interface BridgeDevice {
  id?: string;
  uuid: string;
  name: string;
  protocol?: string;
  connectable?: boolean;
  paired: boolean;
  saved?: boolean;
  online?: boolean;
  active?: boolean;
}

export type DeviceSelection =
  | { kind: "local" }
  | {
      kind: "tv";
      id: string;
      name: string;
      status: "connected" | "connecting" | "offline";
    };

export interface BridgeCapabilities {
  selectDevice?: number;
}

export interface BridgeResult {
  ok: boolean;
  target?: "tv" | "local";
  targetName?: string;
  error?: string;
  code?: string;
}

export interface BridgeState {
  desktopConnected: boolean; // host/app reachable
  tvState: string; // desktop SenderConnectionState name
  activeTv: string | null;
  target?: "tv" | "local";
  capabilities?: BridgeCapabilities;
  selection?: DeviceSelection;
  devices: BridgeDevice[];
}

type StateListener = (s: BridgeState) => void;

const HOST_NAME = "com.playbridge.host";
const BASE_RECONNECT_MS = 2_000;
const MAX_RECONNECT_MS = 30_000;
const WAKE_THROTTLE_MS = 500;

let port: ReturnType<typeof browser.runtime.connectNative> | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectAttempts = 0;
let lastWakeTime = 0;
const listeners = new Set<StateListener>();
// Bridge results arrive in order; correlate them to in-flight requests by FIFO.
// When a request times out, keep a null placeholder so late results are swallowed
// and do not corrupt the correlation for subsequent requests.
const pendingResults: Array<((res: BridgeResult) => void) | null> = [];
let pendingLaunchResolvers: Array<(res: BridgeResult) => void> = [];
let connectionResolvers: Array<(connected: boolean) => void> = [];

let state: BridgeState = {
  desktopConnected: false,
  tvState: "disconnected",
  activeTv: null,
  devices: [],
};

function emit(): void {
  for (const l of listeners) l(state);
}

export function getState(): BridgeState {
  return state;
}

export function onState(listener: StateListener): () => void {
  listeners.add(listener);
  listener(state);
  return () => listeners.delete(listener);
}

function resolveConnectionWaiters(success: boolean): void {
  const list = connectionResolvers;
  connectionResolvers = [];
  for (const resolve of list) {
    resolve(success);
  }
}

function resolveLaunchWaiters(result: BridgeResult): void {
  const list = pendingLaunchResolvers;
  pendingLaunchResolvers = [];
  for (const resolve of list) {
    resolve(result);
  }
}

export function connect(): void {
  if (port) return;
  try {
    port = browser.runtime.connectNative(HOST_NAME);
  } catch {
    setDisconnected();
    scheduleReconnect();
    resolveConnectionWaiters(false);
    return;
  }
  port.onMessage.addListener(onMessage);
  port.onDisconnect.addListener(() => {
    const err = browser.runtime.lastError;
    if (err) {
      console.error("[PB Bridge] Native host disconnected with error:", err.message);
    }
    port = null;
    failPending("disconnected");
    resolveLaunchWaiters({ ok: false, error: "disconnected" });
    setDisconnected();
    resolveConnectionWaiters(false);
    scheduleReconnect();
  });
  send({ cmd: "list_devices" });
}

function ensureConnected(): Promise<boolean> {
  if (port && state.desktopConnected) {
    return Promise.resolve(true);
  }

  // Reset retry counter to try an immediate connection
  reconnectAttempts = 0;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (!port) {
    connect();
  } else {
    // Port exists but desktop is not connected (stub is alive).
    // Send list_devices so the stub retries connection to the app.
    send({ cmd: "list_devices" });
  }

  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      const idx = connectionResolvers.indexOf(onConnectResult);
      if (idx !== -1) {
        connectionResolvers.splice(idx, 1);
      }
      resolve(false);
    }, 3000);

    function onConnectResult(success: boolean): void {
      clearTimeout(timer);
      resolve(success);
    }

    connectionResolvers.push(onConnectResult);
  });
}

export function wake(): void {
  const now = Date.now();
  if (now - lastWakeTime < WAKE_THROTTLE_MS) {
    return;
  }
  lastWakeTime = now;

  if (!port || !state.desktopConnected) {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    reconnectAttempts = 0;
    if (!port) {
      connect();
    } else {
      send({ cmd: "list_devices" });
    }
  }
}

export function refresh(): void {
  reconnectAttempts = 0;
  if (!port) {
    connect();
    return;
  }
  send({ cmd: "list_devices" });
}

export function selectDevice(id: string): Promise<BridgeResult> {
  return sendCommand({ cmd: "select_device", id }, 20_000);
}

export function selectLocal(): Promise<BridgeResult> {
  return sendCommand({ cmd: "select_local" }, 10_000);
}

export function refreshDevices(): Promise<BridgeResult> {
  return sendCommand({ cmd: "refresh_devices" }, 10_000);
}

export function showApp(pairId?: string): Promise<BridgeResult> {
  const payload: Record<string, unknown> = { cmd: "show_app" };
  if (pairId) {
    payload.pairId = pairId;
  }
  return sendCommand(payload, 10_000);
}

export function launchApp(): Promise<BridgeResult> {
  if (state.desktopConnected) {
    send({ cmd: "launch_app" });
    return Promise.resolve({ ok: true });
  }

  if (!port) {
    connect();
  }
  if (!port) {
    return Promise.resolve({ ok: false, error: "PlayBridge native host is not available" });
  }

  return new Promise((resolve) => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const onDone = (res: BridgeResult) => {
      if (timer) clearTimeout(timer);
      const idx = pendingLaunchResolvers.indexOf(onDone);
      if (idx !== -1) pendingLaunchResolvers.splice(idx, 1);
      resolve(res);
    };

    pendingLaunchResolvers.push(onDone);

    timer = setTimeout(() => {
      onDone({ ok: false, error: "launch timed out", code: "launch_timeout" });
    }, 25_000);

    if (!send({ cmd: "launch_app" })) {
      onDone({ ok: false, error: "bridge send failed" });
    }
  });
}

export function cast(
  url: string,
  headers?: Record<string, string>,
  title?: string,
  contentType?: string,
  /**
   * Companion demuxed audio media playlist (same live session).
   */
  audioUrl?: string | null,
  /**
   * Full synthetic multivariant playlist text (absolute child URIs). Desktop
   * should write this to a temp .m3u8 and open that — never a data: URL.
   */
  playlistBody?: string | null,
  destination?: "local" | null,
): Promise<BridgeResult> {
  if (isDebug) {
    console.debug("[PB Debug][native bridge] cast URL:", url);
    console.debug("[PB Debug][native bridge] cast headers:", headers ?? {});
  }
  return sendCommand(
    {
      cmd: "cast",
      url,
      headers: headers ?? {},
      title: title ?? "",
      ...(contentType ? { contentType } : {}),
      ...(audioUrl ? { audioUrl } : {}),
      ...(playlistBody ? { playlistBody } : {}),
      ...(destination ? { destination } : {}),
    },
    30_000,
  );
}

export function control(action: string): Promise<BridgeResult> {
  return sendCommand({ cmd: "control", action }, 10_000);
}

async function sendCommand(
  payload: Record<string, unknown>,
  timeoutMs: number = 10_000,
  requireDesktopConnected: boolean = true,
): Promise<BridgeResult> {
  if (requireDesktopConnected) {
    const connected = await ensureConnected();
    if (!connected) {
      return { ok: false, error: "PlayBridge desktop is not running", code: "app_not_running" };
    }
  } else {
    if (!port) {
      connect();
    }
    if (!port) {
      return { ok: false, error: "PlayBridge native host is not available" };
    }
  }

  return new Promise((resolve) => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const cb = (res: BridgeResult) => {
      if (timer) clearTimeout(timer);
      resolve(res);
    };
    pendingResults.push(cb);
    if (!send(payload)) {
      const idx = pendingResults.indexOf(cb);
      if (idx !== -1) pendingResults.splice(idx, 1);
      resolve({ ok: false, error: "bridge send failed" });
      return;
    }
    if (timeoutMs > 0) {
      timer = setTimeout(() => {
        const idx = pendingResults.indexOf(cb);
        if (idx !== -1) {
          // Keep the slot in pendingResults as a null placeholder so the app's late
          // response is swallowed and does not shift onto subsequent requests.
          pendingResults[idx] = null;
          resolve({ ok: false, error: "Command timed out", code: "timeout" });
        }
      }, timeoutMs);
    }
  });
}

function onMessage(raw: unknown): void {
  const msg = raw as Record<string, unknown>;
  if (!msg || typeof msg !== "object") return;
  switch (msg.type) {
    case "hello":
      state = { ...state, desktopConnected: true };
      reconnectAttempts = 0;
      emit();
      resolveConnectionWaiters(true);
      resolveLaunchWaiters({ ok: true });
      break;
    case "state": {
      let selection: DeviceSelection | undefined = undefined;
      if (msg.selection && typeof msg.selection === "object") {
        const sel = msg.selection as Record<string, unknown>;
        if (sel.kind === "local") {
          selection = { kind: "local" };
        } else if (sel.kind === "tv") {
          selection = {
            kind: "tv",
            id: String(sel.id ?? ""),
            name: String(sel.name ?? ""),
            status: (sel.status as "connected" | "connecting" | "offline") ?? "offline",
          };
        }
      }
      const target =
        msg.target === "tv" || msg.target === "local" ? msg.target : undefined;
      const capabilities =
        msg.capabilities && typeof msg.capabilities === "object"
          ? (msg.capabilities as BridgeCapabilities)
          : undefined;
      const devices: BridgeDevice[] = Array.isArray(msg.devices)
        ? (msg.devices as Array<Record<string, unknown>>).map((d) => ({
            id: typeof d.id === "string" ? d.id : (typeof d.uuid === "string" ? d.uuid : ""),
            uuid: typeof d.uuid === "string" ? d.uuid : (typeof d.id === "string" ? d.id : ""),
            name: typeof d.name === "string" ? d.name : "",
            paired: Boolean(d.paired),
            ...(typeof d.protocol === "string" ? { protocol: d.protocol } : {}),
            ...(typeof d.connectable === "boolean" ? { connectable: d.connectable } : {}),
            ...(typeof d.saved === "boolean" ? { saved: d.saved } : {}),
            ...(typeof d.online === "boolean" ? { online: d.online } : {}),
            ...(typeof d.active === "boolean" ? { active: d.active } : {}),
          }))
        : [];

      state = {
        desktopConnected: true,
        tvState: typeof msg.state === "string" ? msg.state : state.tvState,
        activeTv: (msg.activeTv as string | null) ?? null,
        ...(target ? { target } : {}),
        ...(capabilities ? { capabilities } : {}),
        ...(selection ? { selection } : {}),
        devices,
      };
      reconnectAttempts = 0;
      emit();
      resolveConnectionWaiters(true);
      resolveLaunchWaiters({ ok: true });
      break;
    }
    case "result": {
      const cb = pendingResults.shift();
      if (cb) {
        cb({
          ok: Boolean(msg.ok),
          target: msg.target === "tv" || msg.target === "local" ? msg.target : undefined,
          targetName: typeof msg.targetName === "string" ? msg.targetName : undefined,
          error: typeof msg.error === "string" ? msg.error : undefined,
          code: typeof msg.code === "string" ? msg.code : undefined,
        });
      }
      break;
    }
    case "error": {
      const errCode = typeof msg.code === "string" ? msg.code : undefined;
      const errMsg = typeof msg.error === "string" ? msg.error : "Unknown bridge error";
      if (errCode === "app_not_running") {
        state = {
          desktopConnected: false,
          tvState: "disconnected",
          activeTv: null,
          target: undefined,
          capabilities: undefined,
          selection: undefined,
          devices: [],
        };
        emit();
        failPending(errMsg, errCode);
        resolveConnectionWaiters(false);
      } else if (errCode === "launch_failed") {
        resolveLaunchWaiters({ ok: false, error: errMsg, code: errCode });
      } else {
        if (pendingResults.length > 0) {
          const cb = pendingResults.shift();
          if (cb) cb({ ok: false, error: errMsg, code: errCode });
        } else {
          setDisconnected();
        }
      }
      break;
    }
  }
}

function send(obj: Record<string, unknown>): boolean {
  if (!port) return false;
  try {
    port.postMessage(obj);
    return true;
  } catch {
    return false;
  }
}

function setDisconnected(): void {
  state = {
    desktopConnected: false,
    tvState: "disconnected",
    activeTv: null,
    target: undefined,
    capabilities: undefined,
    selection: undefined,
    devices: [],
  };
  emit();
}

function failPending(error: string, code?: string): void {
  while (pendingResults.length) {
    const cb = pendingResults.shift();
    if (cb) cb({ ok: false, error, code });
  }
}

function scheduleReconnect(): void {
  if (reconnectTimer) return;
  const delay = Math.min(MAX_RECONNECT_MS, BASE_RECONNECT_MS * Math.pow(2, reconnectAttempts));
  reconnectAttempts++;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

export function _setPortForTesting(fakePort: any): void {
  port = fakePort;
  if (fakePort) {
    if (fakePort.onMessage?.addListener) {
      fakePort.onMessage.addListener(onMessage);
    }
    if (fakePort.onDisconnect?.addListener) {
      fakePort.onDisconnect.addListener(() => {
        port = null;
        failPending("disconnected");
        resolveLaunchWaiters({ ok: false, error: "disconnected" });
        setDisconnected();
        resolveConnectionWaiters(false);
      });
    }
  }
}

export function _resetStateForTesting(): void {
  port = null;
  reconnectAttempts = 0;
  lastWakeTime = 0;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  pendingResults.length = 0;
  pendingLaunchResolvers.length = 0;
  connectionResolvers.length = 0;
  setDisconnected();
}
