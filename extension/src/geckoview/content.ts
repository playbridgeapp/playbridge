/**
 * Phone GeckoView content script — DOM signals + page-world player probes.
 * Network detection and synthetic HLS live in the background (shared core).
 */

import browser from "./browser";
import { isSupportedDomImage } from "./detected-media-kind";

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
  } else if (message?.type === "detector_same_document_navigation") {
    scanAll();
  }
  return false;
});

type DomMediaAction =
  | "dom_video_found"
  | "dom_audio_found"
  | "dom_image_found";

function reportMediaSrc(
  action: DomMediaAction,
  src: string | null | undefined,
  contentType?: string,
  width?: number,
  height?: number,
): void {
  if (!src || src.startsWith("blob:") || src.startsWith("data:")) return;
  if (!src.startsWith("http")) return;
  browser.runtime
    .sendMessage({
      action,
      url: src,
      origin: window.location.href,
      contentType,
      width,
      height,
    })
    .catch(() => {});
}

function reportImageElement(image: HTMLImageElement): void {
  const src = image.currentSrc || image.src;
  if (!src || !isSupportedDomImage(src)) return;
  const width = image.naturalWidth || image.width || image.clientWidth;
  const height = image.naturalHeight || image.height || image.clientHeight;
  if (width < 64 || height < 64 || width * height < 16_384) return;
  reportMediaSrc("dom_image_found", src, undefined, width, height);
}

const waitingForImageLoad = new WeakSet<HTMLImageElement>();

function scanElement(el: Element): void {
  if (el instanceof HTMLVideoElement) {
    reportMediaSrc("dom_video_found", el.currentSrc || el.src);
    if (el.poster) {
      reportMediaSrc(
        "dom_image_found",
        el.poster,
        undefined,
        el.videoWidth || el.clientWidth,
        el.videoHeight || el.clientHeight,
      );
    }
    for (const source of Array.from(el.querySelectorAll("source"))) {
      reportMediaSrc("dom_video_found", source.src, source.type);
    }
    return;
  }
  if (el instanceof HTMLAudioElement) {
    reportMediaSrc("dom_audio_found", el.currentSrc || el.src);
    for (const source of Array.from(el.querySelectorAll("source"))) {
      reportMediaSrc("dom_audio_found", source.src, source.type);
    }
    return;
  }
  if (el instanceof HTMLSourceElement) {
    const parent = el.closest("audio, video");
    reportMediaSrc(
      parent instanceof HTMLAudioElement
        ? "dom_audio_found"
        : "dom_video_found",
      el.src,
      el.type,
    );
    return;
  }
  if (el instanceof HTMLImageElement) {
    reportImageElement(el);
    if (!el.complete && !waitingForImageLoad.has(el)) {
      waitingForImageLoad.add(el);
      el.addEventListener("load", () => reportImageElement(el), { once: true });
    }
  }
}

function scanAll(): void {
  document.querySelectorAll("video, audio, source, img").forEach(scanElement);
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
      el.querySelectorAll?.("video, audio, source, img").forEach(scanElement);
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
  attributeFilter: ["src", "srcset", "poster"],
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
