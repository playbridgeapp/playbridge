import { PAGE_PLAYBACK_BRIDGE_SCRIPT } from "./page-bridge";
/**
 * Phone GeckoView content script — DOM signals + page-world player probes.
 * Network detection and synthetic HLS live in the background (shared core).
 */

import browser from "./browser";
import { DETECTION_POLICY_PORT, detectionOptions, validDetectionPolicy } from "./detection-policy";
import { LINKED_PAGE_EVENTS, PAGE_API_PORT, normalizeLinkedPageRelay, validPageRequestId } from "./page-relay";
import { injectPageScriptWithChannel } from "./page-channel";
import { isSupportedDomImage } from "./detected-media-kind";
import { installPluginBridge } from "./plugin-bridge";

let detectionEnabled = false;
let options = detectionOptions();
let policyUpdate = 0;
let videoObserver: MutationObserver | undefined;

browser.runtime.onMessage.addListener((message: { type?: string }) => {
  if (message?.type === "detector_same_document_navigation" && options.navigationRescans) scanAll();
  return false;
});

type DomMediaAction =
  | "dom_video_found"
  | "dom_audio_found"
  | "dom_image_found"
  | "dom_subtitle_found";

function reportMediaSrc(
  action: DomMediaAction,
  src: string | null | undefined,
  contentType?: string,
  width?: number,
  height?: number,
): void {
  const allowed = action === "dom_image_found" ? options.images
    : action === "dom_audio_found" ? options.audio
    : action === "dom_subtitle_found" ? options.subtitles : options.videos;
  if (!allowed || !detectionEnabled || !src || src.startsWith("blob:") || src.startsWith("data:")) return;
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
  if (!detectionEnabled || !options.domScanning || !options.images) return;
  const src = image.currentSrc || image.src;
  if (!src || !isSupportedDomImage(src)) return;
  // Unloaded/lazy images have no intrinsic size yet. Reading rendered dimensions
  // here forces layout inside offscreen/content-visibility subtrees and reports
  // placeholder boxes as media. The load listener below will report real images.
  const width = image.naturalWidth;
  const height = image.naturalHeight;
  if (width < 64 || height < 64 || width * height < 16_384) return;
  reportMediaSrc("dom_image_found", src, undefined, width, height);
}

const waitingForImageLoad = new WeakSet<HTMLImageElement>();

function scanElement(el: Element): void {
  if (!detectionEnabled || !options.domScanning) return;
  if (el instanceof HTMLVideoElement) {
    if (options.videos) reportMediaSrc("dom_video_found", el.currentSrc || el.src);
    if (options.images && el.poster) {
      reportMediaSrc(
        "dom_image_found",
        el.poster,
        undefined,
        el.videoWidth || undefined,
        el.videoHeight || undefined,
      );
    }
    if (options.videos) for (const source of Array.from(el.querySelectorAll("source"))) {
      reportMediaSrc("dom_video_found", source.src, source.type);
    }
    return;
  }
  if (el instanceof HTMLAudioElement) {
    if (!options.audio) return;
    reportMediaSrc("dom_audio_found", el.currentSrc || el.src);
    for (const source of Array.from(el.querySelectorAll("source"))) {
      reportMediaSrc("dom_audio_found", source.src, source.type);
    }
    return;
  }
  if (el instanceof HTMLSourceElement) {
    const parent = el.closest("audio, video");
    if (parent instanceof HTMLAudioElement ? !options.audio : !options.videos) return;
    reportMediaSrc(
      parent instanceof HTMLAudioElement
        ? "dom_audio_found"
        : "dom_video_found",
      el.src,
      el.type,
    );
    return;
  }
  if (typeof HTMLTrackElement !== "undefined" && el instanceof HTMLTrackElement) {
    if (el.kind === "subtitles" || el.kind === "captions") reportMediaSrc("dom_subtitle_found", el.src);
    return;
  }
  if (el instanceof HTMLImageElement && options.images) {
    reportImageElement(el);
    if (!el.complete && !waitingForImageLoad.has(el)) {
      waitingForImageLoad.add(el);
      el.addEventListener("load", () => {
        waitingForImageLoad.delete(el);
        reportImageElement(el);
      }, { once: true });
    }
  }
}

function mediaSelector(): string {
  return [options.videos || options.images ? "video" : "", options.audio ? "audio" : "",
    options.videos || options.audio ? "source" : "", options.images ? "img" : "",
    options.subtitles ? "track" : ""].filter(Boolean).join(", ");
}

function scanAll(): void {
  if (!detectionEnabled || !options.domScanning) return;
  const selector = mediaSelector();
  if (selector) document.querySelectorAll(selector).forEach(scanElement);
}

function setDetectionEnabled(enabled: boolean): void {
  if (enabled === detectionEnabled) return;
  detectionEnabled = enabled;
  if (!enabled) {
    videoObserver?.disconnect();
    videoObserver = undefined;
    document.removeEventListener("DOMContentLoaded", startDocumentDetection);
    window.dispatchEvent(new Event("PlayBridgeStopDetection"));
    return;
  }
  if (options.domScanning && mediaSelector()) {
    videoObserver = new MutationObserver((mutations) => {
      if (!detectionEnabled) return;
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (node.nodeType !== 1) continue;
          const el = node as Element;
          scanElement(el);
          el.querySelectorAll?.(mediaSelector()).forEach(scanElement);
        }
        if (mutation.type === "attributes" && mutation.target.nodeType === 1) {
          scanElement(mutation.target as Element);
        }
      }
    });
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", startDocumentDetection, { once: true });
      observeDocument();
    } else {
      startDocumentDetection();
    }
  }
  injectPlayerProbe();
}

function observeDocument(): void {
  if (!detectionEnabled || !document.documentElement) return;
  videoObserver?.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["src", "srcset", "poster"],
  });
}

function startDocumentDetection(): void {
  observeDocument();
  scanAll();
}

// Native session ownership is authoritative even when an app and a browser tab
// have identical URLs. No scanning starts before the native policy arrives.
let policyRelayPort: any;
let latestNativePolicyEnabled = false;

function ensurePolicyRelayPort(): any {
  if (policyRelayPort) return policyRelayPort;
  const port = browser.runtime.connect({ name: DETECTION_POLICY_PORT });
  policyRelayPort = port;
  port.onMessage.addListener((message: { update?: number; ok?: boolean }) => {
    if (policyRelayPort === port && message?.update === policyUpdate) {
      setDetectionEnabled(message.ok === true && latestNativePolicyEnabled);
    }
  });
  port.onDisconnect.addListener(() => {
    if (policyRelayPort !== port) return;
    policyRelayPort = undefined;
    policyUpdate += 1;
    setDetectionEnabled(false);
  });
  return port;
}

try {
  const policyPort = browser.runtime.connectNative("detectorPolicy");
  policyPort.onMessage.addListener((policy: unknown) => {
    if (!validDetectionPolicy(policy)) return;
    const update = ++policyUpdate;
    const nextOptions = detectionOptions(policy.options);
    if (!policy.enabled || JSON.stringify(options) !== JSON.stringify(nextOptions)) setDetectionEnabled(false);
    options = nextOptions;
    latestNativePolicyEnabled = policy.enabled;
    if (window.top !== window) {
      setDetectionEnabled(policy.enabled);
      return;
    }
    // Only the isolated content script owns this port. Page events never write it.
    try { ensurePolicyRelayPort().postMessage({ update, policy }); }
    catch { setDetectionEnabled(false); }
  });
  policyPort.onDisconnect.addListener(() => {
    policyUpdate += 1;
    setDetectionEnabled(false);
  });
} catch {
  // Keep the casting API available while detection fails closed.
}

window.addEventListener("PlayBridgeMediaFound", ((event: CustomEvent) => {
  const url = event.detail && (event.detail as { url?: string }).url;
  if (!detectionEnabled || !options.playerProbes || !options.videos || !url || typeof url !== "string" || !url.startsWith("http")) return;
  browser.runtime
    .sendMessage({
      action: "player_video_found",
      url,
      origin: window.location.href,
    })
    .catch(() => {});
}) as EventListener);

// The page API reaches this isolated script only through its private channel, and
// the background only through this document's own runtime port. Nothing about a
// request, response or session event is visible to other scripts on the page.
function installPageApi(): void {
  if (window.top !== window) return;
  const injected = injectPageScriptWithChannel(PAGE_PLAYBACK_BRIDGE_SCRIPT);
  if (!injected) return;
  const page = injected;
  const outstanding = new Set<string>();
  let relay: any;
  const respond = (pageRequestId: string, response: unknown) => {
    outstanding.delete(pageRequestId);
    page.post({ channel: "linked", type: "response", pageRequestId, response });
  };
  function ensureRelay(): any {
    if (relay) return relay;
    const port = browser.runtime.connect({ name: PAGE_API_PORT });
    relay = port;
    port.onMessage.addListener((raw: unknown) => {
      if (relay !== port || !raw || typeof raw !== "object") return;
      const message = raw as Record<string, unknown>;
      if (message.type === "response" && validPageRequestId(message.pageRequestId) &&
          outstanding.has(message.pageRequestId)) {
        const response = message.response && typeof message.response === "object" ? message.response : {};
        respond(message.pageRequestId, response);
      } else if (message.type === "event" && validPageRequestId(message.sessionId) &&
          (LINKED_PAGE_EVENTS as readonly unknown[]).includes(message.event)) {
        const detail = message.detail && typeof message.detail === "object" ? message.detail : {};
        page.post({ channel: "linked", type: "event", sessionId: message.sessionId, event: message.event, detail });
      }
    });
    port.onDisconnect.addListener(() => {
      if (relay !== port) return;
      relay = undefined;
      for (const pageRequestId of [...outstanding]) {
        respond(pageRequestId, { ok: false, error: "native_unavailable" });
      }
    });
    return port;
  }
  function post(message: Record<string, unknown>, pageRequestId?: string): void {
    try {
      ensureRelay().postMessage(message);
    } catch {
      if (pageRequestId) respond(pageRequestId, { ok: false, error: "native_unavailable" });
    }
  }
  page.onMessage((message: unknown) => {
    if (window.top !== window || !message || typeof message !== "object") return;
    const channel = (message as { channel?: unknown }).channel;
    if (channel === "cast") {
      post({ type: "cast", payload: (message as { payload?: unknown }).payload });
      return;
    }
    if (channel !== "linked") return;
    const detail = normalizeLinkedPageRelay(message);
    if (!detail) {
      const pageRequestId = (message as { pageRequestId?: unknown }).pageRequestId;
      if (validPageRequestId(pageRequestId)) {
        page.post({ channel: "linked", type: "response", pageRequestId, response: { ok: false, error: "invalid_request" } });
      }
      return;
    }
    if (detail.operation === "choose_destination" && navigator.userActivation && !navigator.userActivation.isActive) {
      page.post({ channel: "linked", type: "response", pageRequestId: detail.pageRequestId,
        response: { ok: false, error: "user_gesture_required" } });
      return;
    }
    outstanding.add(detail.pageRequestId);
    post(detail, detail.pageRequestId);
  });
}

// The casting API is independent of automatic detection.
installPageApi();

// Only normal browsing pages with detection enabled receive player probes.
installPluginBridge();

function injectPlayerProbe(): void {
  if (window.top !== window || (!options.visibilityOverrides && !(options.playerProbes && options.videos))) return;
  const script = document.createElement("script");
  script.textContent = `(function() {
      var hiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
      var visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      if (${options.visibilityOverrides}) try {
        Object.defineProperty(document, 'hidden', { configurable: true, get: function() { return false; } });
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function() { return 'visible'; } });
      } catch (_) {}
      function report(url) {
        if (!url || typeof url !== 'string' || !url.startsWith('http')) return;
        window.dispatchEvent(new CustomEvent('PlayBridgeMediaFound', { detail: { url: url } }));
      }
      function probe() {
        try {
          if (window.jwplayer) {
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
      var timers = ${options.playerProbes && options.videos} ? [setTimeout(probe, 1500), setTimeout(probe, 4000)] : [];
      window.addEventListener('PlayBridgeStopDetection', function stop() {
        timers.forEach(clearTimeout);
        if (${options.visibilityOverrides}) try {
          if (hiddenDescriptor) Object.defineProperty(document, 'hidden', hiddenDescriptor);
          else delete document.hidden;
          if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
          else delete document.visibilityState;
        } catch (_) {}
        window.removeEventListener('PlayBridgeStopDetection', stop);
      }, { once: true });
  })();`;
  (document.documentElement || document.head || document.body).appendChild(script);
  script.remove();
}
