import vm from 'node:vm';
import { injectPageScriptWithChannel, type PageChannel } from '../src/geckoview/page-channel';

/** Real MessageChannels; ports are closed by `close()` so the test process can exit. */
export function pageWorld(globals: Record<string, unknown> = {}) {
  const channels: MessageChannel[] = [];
  class TrackedChannel extends MessageChannel {
    constructor() { super(); channels.push(this); }
  }
  const window: any = Object.assign(new EventTarget(), { playbridge: {} as any });
  // Page request timeouts run for up to minutes. Unref them so a test that leaves a
  // request unanswered doesn't keep the test process alive past its timeout.
  const pageSetTimeout = (callback: () => void, ms?: number) => {
    const timer = setTimeout(callback, ms);
    timer.unref?.();
    return timer;
  };
  const context = vm.createContext({
    window, EventTarget, Event, CustomEvent, MessageEvent, MessagePort, MessageChannel: TrackedChannel,
    TextEncoder, setTimeout: pageSetTimeout, clearTimeout, ...globals,
  });
  const run = (source: string) => vm.runInContext(source, context);
  const inject = (source: string): PageChannel | null => {
    const host = globalThis as any;
    const previous = { window: host.window, document: host.document };
    host.window = window;
    host.document = {
      createElement: () => ({ textContent: '', remove() {} }),
      documentElement: { appendChild: (script: { textContent: string }) => run(script.textContent) },
    };
    try { return injectPageScriptWithChannel(source); }
    finally { host.window = previous.window; host.document = previous.document; }
  };
  const close = () => channels.forEach((channel) => { channel.port1.close(); channel.port2.close(); });
  return { window, context, run, inject, close };
}

/**
 * Let MessagePort deliveries and promise continuations run. With a predicate, wait
 * until it holds (MessagePort delivery has no fixed latency); without one, give
 * in-flight messages a few milliseconds before checking that something did not happen.
 */
export async function delivered(predicate?: () => boolean): Promise<void> {
  if (!predicate) { await new Promise((resolve) => setTimeout(resolve, 10)); return; }
  for (let i = 0; i < 2000 && !predicate(); i++) await new Promise((resolve) => setTimeout(resolve, 1));
  if (!predicate()) throw new Error('expected page channel delivery did not happen');
}
