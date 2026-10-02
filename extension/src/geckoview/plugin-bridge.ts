import browser from "./browser";

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
export const PLUGIN_PAGE_BRIDGE_SCRIPT = `
(function() {
  var bridge = window.playbridge = window.playbridge || {};
  if (bridge.plugins) return;
  var pending = new Map();
  bridge.capabilities = Object.assign({}, bridge.capabilities, { nativePlugins: 0 });
  function invoke(operation, payload) {
    return new Promise(function(resolve, reject) {
      if (pending.size >= 4) { reject(new Error('Too many pending device plugin requests')); return; }
      var id = crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random();
      var json;
      try { json = JSON.stringify({ requestId: id, operation: operation, payload: payload || {} }); }
      catch (_) { reject(new Error('Invalid device plugin request')); return; }
      if (new TextEncoder().encode(json).length > ${MAX_REQUEST_BYTES}) { reject(new Error('Device plugin request too large')); return; }
      var timer = setTimeout(function() {
        pending.delete(id);
        reject(new Error('Device plugin request timed out'));
      }, 65000);
      pending.set(id, { operation: operation, resolve: resolve, reject: reject, timer: timer });
      window.dispatchEvent(new CustomEvent('PlayBridgePluginsRequestJson', { detail: json }));
    });
  }
  window.addEventListener('PlayBridgePluginsResponseJson', function(event) {
    var message;
    try { message = JSON.parse(event.detail); } catch (_) { return; }
    if (message.type === 'plugin_capabilities') {
      bridge.capabilities.nativePlugins = message.available === true ? 1 : 0;
      window.dispatchEvent(new Event('PlayBridgePluginsReady'));
      return;
    }
    if (message.type === 'plugin_disconnected') {
      bridge.capabilities.nativePlugins = 0;
      pending.forEach(function(waiter) { clearTimeout(waiter.timer); waiter.reject(new Error('Device plugins disconnected')); });
      pending.clear();
      return;
    }
    var waiter = pending.get(message.requestId);
    if (!waiter) return;
    pending.delete(message.requestId);
    clearTimeout(waiter.timer);
    if (message.ok === true) {
      if (waiter.operation === 'status') bridge.capabilities.nativePlugins = message.data && message.data.available === true ? 1 : 0;
      waiter.resolve(message.data || {});
    } else {
      var error = new Error(message.error || 'Device plugin request failed');
      error.code = message.error || 'native_plugins_unavailable';
      waiter.reject(error);
    }
  });
  bridge.plugins = {
    status: function() { return invoke('status', {}); },
    resolve: function(request) { return invoke('resolve', request); },
    manage: function() { return invoke('manage', {}); },
    cancel: function() {
      pending.forEach(function(waiter, id) {
        if (waiter.operation !== 'resolve') return;
        pending.delete(id);
        clearTimeout(waiter.timer);
        waiter.reject(new Error('Device plugin resolution cancelled'));
      });
      window.dispatchEvent(new CustomEvent('PlayBridgePluginsRequestJson', { detail: JSON.stringify({ operation: 'cancel' }) }));
    }
  };
})();
`;

export function installPluginBridge(): void {
  if (window.top !== window) return;
  let port: ReturnType<typeof browser.runtime.connectNative> | undefined;
  let closed = false;
  let capabilities: unknown;
  function deliver(message: unknown): void {
    window.dispatchEvent(new CustomEvent("PlayBridgePluginsResponseJson", { detail: JSON.stringify(message) }));
  }
  window.addEventListener("PlayBridgePluginsRequestJson", ((event: CustomEvent) => {
    if (window.top !== window || typeof event.detail !== "string" || new TextEncoder().encode(event.detail).length > MAX_REQUEST_BYTES) return;
    let request: Record<string, unknown>;
    try {
      const value: unknown = JSON.parse(event.detail);
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
  }) as EventListener);
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
  const script = document.createElement("script");
  script.textContent = PLUGIN_PAGE_BRIDGE_SCRIPT;
  (document.documentElement || document.head || document.body).appendChild(script);
  script.remove();
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
