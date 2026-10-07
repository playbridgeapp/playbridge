/**
 * Private page-world transport. Page scripts never talk to the isolated content
 * script through window events: any other script in the document can read and
 * forge those. Instead the injected script creates a MessageChannel and hands one
 * port to the content script during a synchronous boot event whose name is random
 * per injection. The event fires inside appendChild, before any page script can
 * listen, and the port is never reachable from window afterwards.
 */

export const PAGE_CHANNEL_BOOT = "__PLAYBRIDGE_PAGE_CHANNEL_BOOT__";

/**
 * Page-side prelude, placed at the top of an injected IIFE. It captures the
 * primordials it needs before page scripts run and defines:
 * - `channelSend(message)`: structured-clones `message` to the content script and
 *   returns false when it cannot be cloned;
 * - `channelReceive(handler)`: delivers parsed JSON messages from the content script.
 * A same-realm script can still wrap `window.playbridge` itself; this only removes
 * the passive and cross-caller channel.
 */
export const PAGE_CHANNEL_PRELUDE = `
  var channel = new MessageChannel();
  var channelPost = MessagePort.prototype.postMessage.bind(channel.port1);
  var parseJson = JSON.parse;
  var channelHandler = null;
  channel.port1.onmessage = function(event) {
    if (typeof event.data !== 'string' || !channelHandler) return;
    var message;
    try { message = parseJson(event.data); } catch (_) { return; }
    if (message && typeof message === 'object') channelHandler(message);
  };
  window.dispatchEvent(new MessageEvent(${PAGE_CHANNEL_BOOT}, { ports: [channel.port2] }));
  function channelSend(message) {
    try { channelPost(message); return true; } catch (_) { return false; }
  }
  function channelReceive(handler) { channelHandler = handler; }
`;

export interface PageChannel {
  /** Messages from the page are structured clones; validate every field. */
  onMessage(handler: (message: unknown) => void): void;
  /** Delivered to the page as JSON so page code never touches content-script objects. */
  post(message: unknown): void;
}

function bootEventName(): string {
  const random = globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
  return `playbridge-boot-${random}`;
}

/** Inject `source` (which must include PAGE_CHANNEL_PRELUDE) and return its private port. */
export function injectPageScriptWithChannel(source: string): PageChannel | null {
  const eventName = bootEventName();
  let port: MessagePort | null = null;
  const accept = (event: Event) => {
    event.stopImmediatePropagation();
    if (port) return;
    const candidate = (event as MessageEvent).ports?.[0];
    if (candidate) port = candidate;
  };
  window.addEventListener(eventName, accept, true);
  try {
    const script = document.createElement("script");
    script.textContent = source.split(PAGE_CHANNEL_BOOT).join(JSON.stringify(eventName));
    (document.documentElement || document.head || document.body).appendChild(script);
    script.remove();
  } finally {
    window.removeEventListener(eventName, accept, true);
  }
  const channel = port as MessagePort | null;
  if (!channel) return null;
  return {
    onMessage(handler) {
      channel.onmessage = (event: MessageEvent) => handler(event.data);
    },
    post(message) {
      try { channel.postMessage(JSON.stringify(message)); } catch { /* Document closed. */ }
    },
  };
}
