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

function bindPageChannel(channel: PageChannel): void {
  const outstanding = new Set<string>();
  let relayPort: any = null;

  function ensureRelay(): any {
    if (relayPort) return relayPort;

    try {
      relayPort = browser.runtime.connect({ name: PAGE_API_PORT });
    } catch {
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
      relayPort = null;
      for (const pageRequestId of outstanding) {
        channel?.post({
          channel: "linked",
          type: "response",
          pageRequestId,
          response: { ok: false, error: "unavailable" },
        });
      }
      outstanding.clear();
      channel?.post({
        channel: "linked",
        type: "capabilities",
        capabilities: { playback: 0 },
      });
    });

    return relayPort;
  }

  channel.onMessage((raw: unknown) => {
    if (window.top !== window || !raw || typeof raw !== "object") return;
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
          // Port send failed
        }
      }
      return;
    }

    if (msg.channel !== "linked") return;

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

    outstanding.add(detail.pageRequestId);
    try {
      port.postMessage({
        ...detail,
        userActivation: activation,
      });
    } catch {
      outstanding.delete(detail.pageRequestId);
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
  if (typeof window === "undefined" || window.top !== window) {
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

