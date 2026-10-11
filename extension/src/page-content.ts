/**
 * Desktop top-frame content script for website window.playbridge API.
 * Injected at document_start in top frames only.
 */

import browser from "./browser";
import { DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT } from "./core/page-bridge";
import {
  injectPageScriptWithChannel,
  listenForPageChannelBoot,
  FIXED_PAGE_CHANNEL_BOOT,
  type PageChannel,
} from "./core/page-channel";
import {
  PAGE_API_PORT,
  normalizeLinkedPageRelay,
  validPageRequestId,
  LINKED_PAGE_EVENTS,
} from "./core/page-relay";

export function bindPageChannel(channel: PageChannel): void {
  const outstanding = new Map<string, ReturnType<typeof setTimeout> | null>();
  let relayPort: any = null;
  let disconnected = false;

  function failOutstanding(): void {
    for (const [pageRequestId, timer] of outstanding) {
      if (timer) clearTimeout(timer);
      channel?.post({
        channel: "linked",
        type: "response",
        pageRequestId,
        response: { ok: false, error: "unavailable" },
      });
    }
    outstanding.clear();
  }

  function handleDisconnect(): void {
    disconnected = true;
    relayPort = null;
    failOutstanding();
    channel?.post({
      channel: "linked",
      type: "capabilities",
      capabilities: { playback: 0 },
    });
  }

  function ensureRelay(): any {
    if (disconnected) return null;
    if (relayPort) return relayPort;

    try {
      relayPort = browser.runtime.connect({ name: PAGE_API_PORT });
      if (!relayPort) {
        handleDisconnect();
        return null;
      }
    } catch {
      handleDisconnect();
      return null;
    }


    relayPort.onMessage.addListener((raw: unknown) => {
      if (!raw || typeof raw !== "object") return;
      const msg = raw as Record<string, unknown>;

      if (msg.type === "capabilities" && msg.capabilities && typeof msg.capabilities === "object") {
        channel?.post({
          channel: "linked",
          type: "capabilities",
          capabilities: msg.capabilities,
        });
      } else if (msg.type === "response" && validPageRequestId(msg.pageRequestId)) {
        if (outstanding.has(msg.pageRequestId)) {
          const timer = outstanding.get(msg.pageRequestId);
          if (timer) clearTimeout(timer);
          outstanding.delete(msg.pageRequestId);
          const response =
            msg.response && typeof msg.response === "object"
              ? msg.response
              : { ok: false, error: "invalid_request" };
          channel?.post({
            channel: "linked",
            type: "response",
            pageRequestId: msg.pageRequestId,
            response,
          });
        }
      } else if (
        msg.type === "event" &&
        validPageRequestId(msg.sessionId) &&
        (LINKED_PAGE_EVENTS as readonly unknown[]).includes(msg.event)
      ) {
        const detail =
          msg.detail && typeof msg.detail === "object"
            ? msg.detail
            : {};
        channel?.post({
          channel: "linked",
          type: "event",
          sessionId: msg.sessionId,
          event: msg.event,
          detail,
        });
      }
    });

    relayPort.onDisconnect.addListener(() => {
      handleDisconnect();
    });

    return relayPort;
  }

  channel.onMessage((raw: unknown) => {
    if ((typeof window !== "undefined" && window.top !== window) || !raw || typeof raw !== "object") return;
    const msg = raw as Record<string, unknown>;

    // Read user gesture in isolated world (positive attestation required)
    const activation = navigator.userActivation?.isActive === true;

    if (msg.channel === "cast") {
      const port = ensureRelay();
      if (port) {
        try {
          port.postMessage({
            type: "cast",
            payload: msg.payload,
            userActivation: activation,
          });
        } catch {
          handleDisconnect();
        }
      }
      return;
    }

    if (msg.channel !== "linked") return;

    if (disconnected) {
      if (validPageRequestId(msg.pageRequestId)) {
        channel.post({
          channel: "linked",
          type: "response",
          pageRequestId: msg.pageRequestId,
          response: { ok: false, error: "unavailable" },
        });
      }
      return;
    }

    const detail = normalizeLinkedPageRelay(msg);
    if (!detail) {
      if (validPageRequestId(msg.pageRequestId)) {
        channel.post({
          channel: "linked",
          type: "response",
          pageRequestId: msg.pageRequestId,
          response: { ok: false, error: "invalid_request" },
        });
      }
      return;
    }

    if (detail.operation === "choose_destination" && !activation) {
      channel.post({
        channel: "linked",
        type: "response",
        pageRequestId: detail.pageRequestId,
        response: { ok: false, error: "user_gesture_required" },
      });
      return;
    }

    const port = ensureRelay();
    if (!port) {
      channel.post({
        channel: "linked",
        type: "response",
        pageRequestId: detail.pageRequestId,
        response: { ok: false, error: "unavailable" },
      });
      return;
    }

    const isLongTimeout = detail.operation === "play" || detail.operation === "open";
    let timer: ReturnType<typeof setTimeout> | null = null;
    if (!isLongTimeout) {
      timer = setTimeout(() => {
        if (outstanding.has(detail.pageRequestId)) {
          outstanding.delete(detail.pageRequestId);
          channel.post({
            channel: "linked",
            type: "response",
            pageRequestId: detail.pageRequestId,
            response: { ok: false, error: "unavailable" },
          });
        }
      }, 10_000);
      (timer as any)?.unref?.();
    }

    outstanding.set(detail.pageRequestId, timer);
    try {
      port.postMessage({
        ...detail,
        userActivation: activation,
      });
    } catch {
      if (timer) clearTimeout(timer);
      outstanding.delete(detail.pageRequestId);
      handleDisconnect();
      channel.post({
        channel: "linked",
        type: "response",
        pageRequestId: detail.pageRequestId,
        response: { ok: false, error: "unavailable" },
      });
    }
  });

  // Connect to background immediately
  ensureRelay();
}

function initPageContentBridge(): void {
  if (typeof window === "undefined" || window.top !== window || typeof window.addEventListener !== "function") {
    return;
  }

  const manifest =
    typeof browser !== "undefined" && browser.runtime?.getManifest
      ? browser.runtime.getManifest()
      : null;
  const isChromeMv3 = Boolean(manifest && manifest.manifest_version >= 3);

  if (isChromeMv3) {
    listenForPageChannelBoot(FIXED_PAGE_CHANNEL_BOOT, (channel) => {
      bindPageChannel(channel);
    });
  } else {
    const channel = injectPageScriptWithChannel(DESKTOP_PAGE_PLAYBACK_BRIDGE_SCRIPT);
    if (channel) {
      bindPageChannel(channel);
    }
  }
}

initPageContentBridge();

