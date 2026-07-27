/**
 * Phone GeckoView content script — DOM signals + page-world player probes.
 * Network detection and synthetic HLS live in the background (shared core).
 */

import browser from "./browser";

// cloneInto is Firefox/GeckoView-only (not on TypeScript's DOM lib).
const cloneIntoFn = (globalThis as { cloneInto?: (obj: unknown, scope: Window) => unknown })
  .cloneInto;

browser.runtime.onMessage.addListener((message: { type?: string }) => {
  if (message?.type === "bridge_feedback") {
    const detail =
      typeof cloneIntoFn === "function"
        ? cloneIntoFn(message, window)
        : message;
    window.dispatchEvent(new CustomEvent("PlayBridgeFeedback", { detail }));
  }
  return false;
});

function reportVideoSrc(src: string | null | undefined): void {
  if (!src || src.startsWith("blob:") || src.startsWith("data:")) return;
  if (!src.startsWith("http")) return;
  browser.runtime
    .sendMessage({
      action: "dom_video_found",
      url: src,
      origin: window.location.href,
    })
    .catch(() => {});
}

function scanElement(el: Element): void {
  if (el.tagName === "VIDEO" || el.tagName === "SOURCE") {
    reportVideoSrc((el as HTMLVideoElement | HTMLSourceElement).src);
  }
}

function scanAll(): void {
  document.querySelectorAll("video, source").forEach(scanElement);
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", scanAll);
} else {
  scanAll();
}

const videoObserver = new MutationObserver((mutations) => {
  for (const mutation of mutations) {
    for (const node of mutation.addedNodes) {
      if (node.nodeType !== 1) continue;
      const el = node as Element;
      scanElement(el);
      el.querySelectorAll?.("video, source").forEach(scanElement);
    }
    if (mutation.type === "attributes" && mutation.target.nodeType === 1) {
      scanElement(mutation.target as Element);
    }
  }
});

videoObserver.observe(document.documentElement, {
  childList: true,
  subtree: true,
  attributes: true,
  attributeFilter: ["src"],
});

window.addEventListener("PlayBridgeMediaFound", ((event: CustomEvent) => {
  const url = event.detail && (event.detail as { url?: string }).url;
  if (!url || typeof url !== "string" || !url.startsWith("http")) return;
  browser.runtime
    .sendMessage({
      action: "player_video_found",
      url,
      origin: window.location.href,
    })
    .catch(() => {});
}) as EventListener);

// Page-world bridge + light player config probe (same idea as legacy phone detector).
(function injectBridge() {
  const bridgeScript = document.createElement("script");
  bridgeScript.textContent = `
    (function() {
      if (window.playbridge_injected) return;
      window.playbridge_injected = true;
      window.playbridge = {
        cast: function(payload) {
          window.dispatchEvent(new CustomEvent('PlayBridgeCast', { detail: payload }));
        }
      };
      try {
        Object.defineProperty(document, 'hidden', { get: function() { return false; } });
        Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
      } catch (e) {}
      function report(url) {
        if (!url || typeof url !== 'string' || !url.startsWith('http')) return;
        window.dispatchEvent(new CustomEvent('PlayBridgeMediaFound', { detail: { url: url } }));
      }
      function probe() {
        try {
          if (window.jwplayer) {
            var players = typeof window.jwplayer === 'function' ? [] : [];
            // best-effort: many pages expose jwplayer().getPlaylist
            try {
              var jw = window.jwplayer();
              var pl = jw && jw.getPlaylist && jw.getPlaylist();
              if (Array.isArray(pl)) {
                pl.forEach(function(item) {
                  if (item && item.file) report(item.file);
                  if (item && item.sources) item.sources.forEach(function(s) { if (s && s.file) report(s.file); });
                });
              }
            } catch (e) {}
          }
        } catch (e) {}
      }
      setTimeout(probe, 1500);
      setTimeout(probe, 4000);
    })();
  `;
  (document.documentElement || document.head || document.body).appendChild(
    bridgeScript,
  );
  bridgeScript.remove();
})();
