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

// The injected page-world bridge deliberately crosses into this isolated
// content script through a DOM event. The background owns validation before it
// reaches Android native messaging.
window.addEventListener("PlayBridgeCast", ((event: CustomEvent) => {
  if (window.top !== window) return;
  browser.runtime
    .sendMessage({
      action: "page_cast_requested",
      payload: event.detail,
      origin: window.location.href,
    })
    .catch(() => {});
}) as EventListener);

window.addEventListener("PlayBridgeLinkedRequest", ((event: CustomEvent) => {
  if (window.top !== window) return;
  const detail = event.detail;
  browser.runtime
    .sendMessage({ action: "page_linked_cast", ...detail })
    .then((response: unknown) => {
      window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson", {
        detail: JSON.stringify({ pageRequestId: detail?.pageRequestId, response }),
      }));
    })
    .catch((error: Error) => {
      window.dispatchEvent(new CustomEvent("PlayBridgeLinkedResponseJson", {
        detail: JSON.stringify({
          pageRequestId: detail?.pageRequestId,
          response: { ok: false, error: "native_unavailable", message: error?.message },
        }),
      }));
    });
}) as EventListener);

browser.runtime.onMessage.addListener((message: { type?: string; event?: unknown }) => {
  if (window.top !== window || message?.type !== "linked_cast_event") return;
  // CustomEvent object details created in the extension's isolated world are not
  // reliably readable by Firefox page scripts. A string crosses that boundary
  // safely; the injected page bridge parses it into a page-owned object.
  window.dispatchEvent(new CustomEvent("PlayBridgeLinkedEventJson", {
    detail: JSON.stringify(message.event ?? {}),
  }));
});

// Page-world bridge + light player config probe (same idea as legacy phone detector).
(function injectBridge() {
  if (window.top !== window) return;
  const bridgeScript = document.createElement("script");
  bridgeScript.textContent = `
    (function() {
      if (window.playbridge_injected_version === 4) return;
      window.playbridge_injected = true;
      window.playbridge_injected_version = 4;
      var pending = new Map();
      var sessions = new Map();
      function request(operation, sessionId, payload) {
        var pageRequestId = (crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random());
        return new Promise(function(resolve, reject) {
          if (pending.size >= 32) {
            var limitError = new Error('Too many pending linked cast requests');
            limitError.code = 'resource_limit';
            reject(limitError);
            return;
          }
          var timeout = setTimeout(function() {
            pending.delete(pageRequestId);
            var timeoutError = new Error('Linked cast request timed out');
            timeoutError.code = 'timeout';
            reject(timeoutError);
          }, operation === 'open' ? 660000 : 45000);
          pending.set(pageRequestId, { resolve: resolve, reject: reject, timeout: timeout });
          window.dispatchEvent(new CustomEvent('PlayBridgeLinkedRequest', {
            detail: { pageRequestId: pageRequestId, operation: operation, sessionId: sessionId || null, payload: payload || {} }
          }));
        });
      }
      window.addEventListener('PlayBridgeLinkedResponseJson', function(event) {
        var detail;
        try { detail = JSON.parse(event.detail || '{}'); }
        catch (_) { return; }
        var waiter = pending.get(detail.pageRequestId);
        if (!waiter) return;
        pending.delete(detail.pageRequestId);
        clearTimeout(waiter.timeout);
        var response = detail.response || {};
        if (response.ok) waiter.resolve(response);
        else {
          var error = new Error(response.message || response.error || 'Linked cast failed');
          error.code = response.error || 'linked_cast_failed';
          waiter.reject(error);
        }
      });
      window.addEventListener('PlayBridgeLinkedEventJson', function(event) {
        var detail;
        try { detail = JSON.parse(event.detail || '{}'); }
        catch (_) { return; }
        var session = sessions.get(detail.sessionId);
        if (!session) return;
        session.dispatchEvent(new CustomEvent(detail.event || 'statechange', { detail: detail.detail || {} }));
        if (detail.event === 'ended') sessions.delete(detail.sessionId);
      });
      function LinkedCastSession(sessionId) {
        var target = new EventTarget();
        target.sessionId = sessionId;
        target.replace = function(items, startIndex, metadata) {
          return request('replace', sessionId, { items: items, startIndex: startIndex || 0, metadata: metadata });
        };
        target.append = function(items, options) {
          return request('append', sessionId, {
            items: items,
            localNetwork: !!(options && options.localNetwork)
          });
        };
        target.jump = function(index) { return request('jump', sessionId, { index: index }); };
        target.provideItems = function(requestId, result) {
          return request('supply', sessionId, {
            requestId: requestId,
            items: (result && result.items) || [],
            endOfList: !!(result && result.endOfList),
            localNetwork: !!(result && result.localNetwork)
          });
        };
        target.unlink = function() { return request('unlink', sessionId, {}); };
        return target;
      }
      window.playbridge = window.playbridge || {};
      window.playbridge.cast = function(payload) {
        window.dispatchEvent(new CustomEvent('PlayBridgeCast', { detail: payload }));
      };
      window.playbridge.capabilities = Object.assign({}, window.playbridge.capabilities, {
        linkedCast: 1,
        explicitHeaders: 1,
        localNetworkPermission: 1
      });
      window.playbridge.linkCast = function(payload) {
        return request('open', null, payload).then(function(response) {
          var session = LinkedCastSession(response.sessionId);
          sessions.set(response.sessionId, session);
          return session;
        });
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
