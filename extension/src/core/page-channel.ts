/**
 * Private page-world transport. Page scripts never talk to the isolated content
 * script through window events: any other script in the document can read and
 * forge those. Instead the injected script creates a MessageChannel and hands one
 * port to the content script during a synchronous boot event whose name is random
 * per injection. The event fires inside appendChild, before any page script can
 * listen, and the port is never reachable from window afterwards.
 */

export const PAGE_CHANNEL_BOOT = "__PLAYBRIDGE_PAGE_CHANNEL_BOOT__";
export const FIXED_PAGE_CHANNEL_BOOT = "__PLAYBRIDGE_PAGE_MAIN_BOOT__";

export interface PageChannel {
  /** Messages from the page are structured clones; validate every field. */
  onMessage(handler: (message: unknown) => void): void;
  /** Delivered to the page as JSON so page code never touches content-script objects. */
  post(message: unknown): void;
}

export function createPageChannel(port: MessagePort): PageChannel {
  return {
    onMessage(handler) {
      port.onmessage = (event: MessageEvent) => handler(event.data);
    },
    post(message) {
      try {
        port.postMessage(JSON.stringify(message));
      } catch {
        /* Document closed. */
      }
    },
  };
}

/**
 * Page-side prelude, placed at the top of an injected IIFE or static MAIN-world script.
 * It captures primordials before page scripts run, creates a MessageChannel, and
 * dispatches the boot event with port2.
 */
export function createPageChannelPrelude(bootEvent: string = PAGE_CHANNEL_BOOT): string {
  return `
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
  window.dispatchEvent(new MessageEvent(${bootEvent}, { ports: [channel.port2] }));
  function channelSend(message) {
    try { channelPost(message); return true; } catch (_) { return false; }
  }
  function channelReceive(handler) { channelHandler = handler; }
`;
}

export const PAGE_CHANNEL_PRELUDE = createPageChannelPrelude(PAGE_CHANNEL_BOOT);

function bootEventName(): string {
  const random = globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
  return `playbridge-boot-${random}`;
}

/**
 * Register a primordial capture listener on window for the boot event name.
 * Accepts exactly one MessageEvent port, calls stopImmediatePropagation,
 * and removes the listener.
 */
export function listenForPageChannelBoot(
  eventName: string,
  onChannel: (channel: PageChannel) => void,
): () => void {
  const accept = (event: Event) => {
    event.stopImmediatePropagation();
    window.removeEventListener(eventName, accept, true);
    const candidate = (event as MessageEvent).ports?.[0];
    if (candidate) {
      onChannel(createPageChannel(candidate));
    }
  };
  window.addEventListener(eventName, accept, true);
  return () => {
    window.removeEventListener(eventName, accept, true);
  };
}

/** Inject `source` (which must include PAGE_CHANNEL_PRELUDE) and return its private port. */
export function injectPageScriptWithChannel(source: string): PageChannel | null {
  const eventName = bootEventName();
  let channel: PageChannel | null = null;
  const cleanup = listenForPageChannelBoot(eventName, (ch) => {
    channel = ch;
  });
  try {
    const script = document.createElement("script");
    script.textContent = source.split(PAGE_CHANNEL_BOOT).join(JSON.stringify(eventName));
    (document.documentElement || document.head || document.body).appendChild(script);
    script.remove();
  } finally {
    cleanup();
  }
  return channel;
}
