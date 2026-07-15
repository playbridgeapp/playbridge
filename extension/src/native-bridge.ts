// Native-messaging bridge to the PlayBridge desktop app (host `com.playbridge.host`).
//
// Replaces the old direct `ws://` connection to the TV. The desktop app holds the
// pinned `wss://` link and owns TV discovery/pairing/selection; the extension
// just hands it a URL + headers. Tokens therefore never cross the cleartext LAN
// from the browser, and the extension never touches the TV's self-signed cert.

import browser from "./browser";

export interface BridgeDevice {
  uuid: string;
  name: string;
  paired: boolean;
}

export interface BridgeState {
  desktopConnected: boolean; // host/app reachable
  tvState: string; // desktop SenderConnectionState name
  activeTv: string | null;
  devices: BridgeDevice[];
}

type StateListener = (s: BridgeState) => void;

const HOST_NAME = "com.playbridge.host";
const BASE_RECONNECT_MS = 2_000;
const MAX_RECONNECT_MS = 30_000;

let port: ReturnType<typeof browser.runtime.connectNative> | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectAttempts = 0;
const listeners = new Set<StateListener>();
// Bridge results arrive in order; correlate them to in-flight requests by FIFO.
const pendingResults: Array<(ok: boolean, error?: string) => void> = [];
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
  connect();

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

export function refresh(): void {
  reconnectAttempts = 0;
  if (!port) {
    connect();
    return;
  }
  send({ cmd: "list_devices" });
}

export function cast(
  url: string,
  headers?: Record<string, string>,
  title?: string,
): Promise<{ ok: boolean; error?: string }> {
  return request({ cmd: "cast", url, headers: headers ?? {}, title: title ?? "" });
}

export function control(action: string): Promise<{ ok: boolean; error?: string }> {
  return request({ cmd: "control", action });
}

async function request(
  payload: Record<string, unknown>,
): Promise<{ ok: boolean; error?: string }> {
  const connected = await ensureConnected();
  if (!connected) {
    return { ok: false, error: "PlayBridge desktop is not running" };
  }
  return new Promise((resolve) => {
    pendingResults.push((ok, error) => resolve({ ok, error }));
    if (!send(payload)) {
      pendingResults.pop();
      resolve({ ok: false, error: "bridge send failed" });
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
      break;
    case "state":
      state = {
        desktopConnected: true,
        tvState: typeof msg.state === "string" ? msg.state : state.tvState,
        activeTv: (msg.activeTv as string | null) ?? null,
        devices: Array.isArray(msg.devices) ? (msg.devices as BridgeDevice[]) : [],
      };
      reconnectAttempts = 0;
      emit();
      resolveConnectionWaiters(true);
      break;
    case "result": {
      const cb = pendingResults.shift();
      if (cb) cb(Boolean(msg.ok), msg.error as string | undefined);
      break;
    }
    case "error":
      setDisconnected();
      break;
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
    devices: [],
  };
  emit();
}

function failPending(error: string): void {
  while (pendingResults.length) {
    const cb = pendingResults.shift();
    if (cb) cb(false, error);
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
