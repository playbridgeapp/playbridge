import browser from "./browser";
import { PAGE_CHANNEL_PRELUDE, injectPageScriptWithChannel, type PageChannel } from "./page-channel";

const MAX_REQUEST_BYTES = 16 * 1024;
const OPERATIONS = new Set(["status", "resolve", "manage", "cancel"]);

export function validPluginBridgeRequest(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const request = value as Record<string, unknown>;
  if (Object.keys(request).some((key) => !["requestId", "operation", "payload"].includes(key))) return false;
  if (typeof request.operation !== "string" || !OPERATIONS.has(request.operation)) return false;
  if (request.operation === "cancel") return Object.keys(request).length === 1;
  if (typeof request.requestId !== "string" || request.requestId.length < 1 || request.requestId.length > 128 || /[\x00-\x1f\x7f]/.test(request.requestId)) return false;
  if (!request.payload || typeof request.payload !== "object" || Array.isArray(request.payload)) return false;
  const payload = request.payload as Record<string, unknown>;
  if (request.operation !== "resolve") return Object.keys(payload).length === 0;
  if (Object.keys(payload).some((key) => !["repoUrl", "scraperIds", "tmdbId", "mediaType", "season", "episode"].includes(key))) return false;
  if (typeof payload.repoUrl !== "string" || payload.repoUrl.length > 2048 ||
      typeof payload.tmdbId !== "string" || !/^[1-9][0-9]{0,9}$/.test(payload.tmdbId) ||
      !["movie", "tv"].includes(String(payload.mediaType))) return false;
  let repo: URL;
  try { repo = new URL(payload.repoUrl); } catch { return false; }
  if (repo.protocol !== "https:" || repo.username || repo.password || repo.hash) return false;
  if (!Array.isArray(payload.scraperIds) || payload.scraperIds.length < 1 || payload.scraperIds.length > 32 ||
      payload.scraperIds.some((id) => typeof id !== "string" || !id.trim() || id.length > 128 || /[\x00-\x1f\x7f]/.test(id)) ||
      new Set(payload.scraperIds).size !== payload.scraperIds.length) return false;
  if (payload.mediaType === "movie") return payload.season == null && payload.episode == null;
  return Number.isInteger(payload.season) && Number(payload.season) >= 0 && Number(payload.season) <= 10000 &&
    Number.isInteger(payload.episode) && Number(payload.episode) >= 1 && Number(payload.episode) <= 10000;
}

// This extends the page's existing cast API; it never replaces window.playbridge.
// Requests and responses use the private channel from page-channel.ts.
export const PLUGIN_PAGE_BRIDGE_SCRIPT = `
(function() {
  var bridge = window.playbridge = window.playbridge || {};
  if (bridge.plugins) return;
  ${PAGE_CHANNEL_PRELUDE}
  var pending = Object.create(null);
  var pendingCount = 0;
  var sequence = 0;
  var PromiseCtor = Promise;
  var ErrorCtor = Error;
  var EventCtor = Event;
  var dispatchWindow = window.dispatchEvent.bind(window);
  var stringify = JSON.stringify;
  var encode = TextEncoder.prototype.encode.bind(new TextEncoder());
  var setTimer = setTimeout;
  var clearTimer = clearTimeout;
  var randomId = crypto.randomUUID ? crypto.randomUUID.bind(crypto) : null;
  bridge.capabilities = Object.assign({}, bridge.capabilities, { nativePlugins: 0 });
  function rejectAll(message, onlyResolve) {
    for (var id in pending) {
      var waiter = pending[id];
      if (onlyResolve && waiter.operation !== 'resolve') continue;
      delete pending[id];
      pendingCount--;
      clearTimer(waiter.timer);
      waiter.reject(new ErrorCtor(message));
    }
  }
  function invoke(operation, payload) {
    return new PromiseCtor(function(resolve, reject) {
      if (pendingCount >= 4) { reject(new ErrorCtor('Too many pending device plugin requests')); return; }
      var id = randomId ? randomId() : 'plugin-' + (++sequence);
      var json;
      try { json = stringify({ requestId: id, operation: operation, payload: payload || {} }); }
      catch (_) { reject(new ErrorCtor('Invalid device plugin request')); return; }
      if (encode(json).length > ${MAX_REQUEST_BYTES}) { reject(new ErrorCtor('Device plugin request too large')); return; }
      var timer = setTimer(function() {
        if (!pending[id]) return;
        delete pending[id];
        pendingCount--;
        reject(new ErrorCtor('Device plugin request timed out'));
      }, 65000);
      pending[id] = { operation: operation, resolve: resolve, reject: reject, timer: timer };
      pendingCount++;
      if (!channelSend(json)) {
        delete pending[id];
        pendingCount--;
        clearTimer(timer);
        reject(new ErrorCtor('Device plugins unavailable'));
      }
    });
  }
  channelReceive(function(message) {
    if (message.type === 'plugin_capabilities') {
      bridge.capabilities.nativePlugins = message.available === true ? 1 : 0;
      dispatchWindow(new EventCtor('PlayBridgePluginsReady'));
      return;
    }
    if (message.type === 'plugin_disconnected') {
      bridge.capabilities.nativePlugins = 0;
      rejectAll('Device plugins disconnected', false);
      return;
    }
    if (typeof message.requestId !== 'string') return;
    var waiter = pending[message.requestId];
    if (!waiter) return;
    delete pending[message.requestId];
    pendingCount--;
    clearTimer(waiter.timer);
    if (message.ok === true) {
      if (waiter.operation === 'status') bridge.capabilities.nativePlugins = message.data && message.data.available === true ? 1 : 0;
      waiter.resolve(message.data || {});
    } else {
      var error = new ErrorCtor(message.error || 'Device plugin request failed');
      error.code = message.error || 'native_plugins_unavailable';
      waiter.reject(error);
    }
  });
  bridge.plugins = {
    status: function() { return invoke('status', {}); },
    resolve: function(request) { return invoke('resolve', request); },
    manage: function() { return invoke('manage', {}); },
    cancel: function() {
      rejectAll('Device plugin resolution cancelled', true);
      channelSend(stringify({ operation: 'cancel' }));
    }
  };
})();
`;

export function installPluginBridge(): void {
  if (window.top !== window) return;
  let port: ReturnType<typeof browser.runtime.connectNative> | undefined;
  let closed = false;
  let capabilities: unknown;
  let page: PageChannel | null = null;
  function deliver(message: unknown): void {
    page?.post(message);
  }
  function handleRequest(raw: unknown): void {
    if (typeof raw !== "string" || new TextEncoder().encode(raw).length > MAX_REQUEST_BYTES) return;
    let request: Record<string, unknown>;
    try {
      const value: unknown = JSON.parse(raw);
      if (!validPluginBridgeRequest(value)) {
        const id = (value as { requestId?: unknown } | null)?.requestId;
        if (typeof id === "string" && id.length >= 1 && id.length <= 128 && !/[\x00-\x1f\x7f]/.test(id)) {
          deliver({ type: "plugin_response", requestId: id, ok: false, error: "invalid_request" });
        }
        return;
      }
      request = value;
    } catch { return; }
    if (request.operation === "manage" && navigator.userActivation?.isActive !== true) {
      deliver({ type: "plugin_response", requestId: request.requestId, ok: false, error: "user_gesture_required" });
      return;
    }
    if (!port || closed) {
      deliver({ type: "plugin_response", requestId: request.requestId, ok: false, error: "native_plugins_unavailable" });
      return;
    }
    try { port.postMessage(request); }
    catch { deliver({ type: "plugin_response", requestId: request.requestId, ok: false, error: "native_plugins_unavailable" }); }
  }
  function connect(): void {
    closed = false;
    capabilities = undefined;
    try {
    port = browser.runtime.connectNative("plugins");
    port.onMessage.addListener((message: unknown) => {
      if ((message as { type?: string })?.type === "plugin_capabilities") capabilities = message;
      deliver(message);
    });
    port.onDisconnect.addListener(() => { closed = true; deliver({ type: "plugin_disconnected" }); });
    } catch { closed = true; deliver({ type: "plugin_disconnected" }); }
  }
  connect();
  page = injectPageScriptWithChannel(PLUGIN_PAGE_BRIDGE_SCRIPT);
  page?.onMessage(handleRequest);
  if (capabilities) deliver(capabilities);
  window.addEventListener("hashchange", () => {
    if (!closed) { try { port?.postMessage({ operation: "cancel" }); } catch { /* Document closed. */ } }
  });
  window.addEventListener("pagehide", () => {
    closed = true;
    try { port?.disconnect(); } catch { /* Already disconnected. */ }
    deliver({ type: "plugin_disconnected" });
  });
  window.addEventListener("pageshow", (event: PageTransitionEvent) => {
    if (event.persisted && closed) connect();
  });
}
