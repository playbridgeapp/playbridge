/**
 * On-video cast overlay content script.
 *
 * Non-destructive: never wraps, reparents, pauses, or modifies the site's
 * <video>. Appends an extension-owned Shadow DOM host and positions a cast
 * control over the primary visible video in this frame.
 *
 * Media URLs always come from background network detection (with stored
 * headers). DOM `blob:` / `data:` sources are matching signals only.
 */

import browser from "./browser";
import {
  isBlobOrDataUrl,
  isDomSourceDetection,
  isExcludedMediaCandidate,
  rankMediaCandidate,
  type MediaCandidate,
} from "./media-candidate";
import {
  VIDEO_CAST_OVERLAY_DEFAULT_POSITION,
  VIDEO_CAST_OVERLAY_STORAGE_KEYS,
  isVideoCastOverlayPosition,
  type VideoCastOverlayPosition,
  type VideoCastOverlayPreferences,
} from "./settings";

// ── Types ────────────────────────────────────────────────────────────────────

interface DetectedVideo extends MediaCandidate {
  tabId: number;
  originUrl: string;
  subtitles?: string[];
  subtitlePreview?: string;
  hasHeaders?: boolean;
}

type OverlayState =
  | "idle"
  | "disabled-no-stream"
  | "casting"
  | "success"
  | "error";

// ── Constants ────────────────────────────────────────────────────────────────

const HOST_ID = "playbridge-cast-overlay-host";
const MIN_VIDEO_WIDTH = 320;
const MIN_VIDEO_HEIGHT = 180;
const MIN_VIDEO_AREA = MIN_VIDEO_WIDTH * MIN_VIDEO_HEIGHT;
const MIN_SUBFRAME_WIDTH = 400;
const MIN_SUBFRAME_HEIGHT = 225;
const MIN_MAIN_VIDEO_SCORE = 5;
const OVERLAY_INSET = 14;
const BUTTON_SIZE = 40;
const Z_INDEX = 2147483646;
const STATUS_RESET_MS = 2800;
const NO_STREAM_STATUS_MS = 2800;
const NO_STREAM_MSG = "No castable stream detected yet";
const RESOLVE_DEBOUNCE_MS = 120;
/** Fade the cast chrome after this idle period (player-control style). */
const AUTO_HIDE_MS = 3500;

// ── State ────────────────────────────────────────────────────────────────────

let enabled = false;
let settingLoaded = false;
let overlayPosition: VideoCastOverlayPosition =
  VIDEO_CAST_OVERLAY_DEFAULT_POSITION;
let preferenceRequestId = 0;
let primaryVideo: HTMLVideoElement | null = null;
let overlayHost: HTMLElement | null = null;
let shadowRoot: ShadowRoot | null = null;
let chromeEl: HTMLElement | null = null;
let castBtn: HTMLButtonElement | null = null;
let statusEl: HTMLElement | null = null;
let overlayState: OverlayState = "idle";
let castInFlight = false;
let statusResetTimer: ReturnType<typeof setTimeout> | null = null;
let resolveTimer: ReturnType<typeof setTimeout> | null = null;
let autoHideTimer: ReturnType<typeof setTimeout> | null = null;
let rafId: number | null = null;
let mutationObserver: MutationObserver | null = null;
let resizeObserver: ResizeObserver | null = null;
let lastCandidateUrl: string | null = null;
/**
 * Stable stream association for each DOM player. Network detections are scoped
 * to a frame, not an individual <video>, so a newer preview/ad request must not
 * replace the stream already selected for the primary player.
 */
let candidateBindings = new WeakMap<HTMLVideoElement, string>();
let candidatePreferredSince = new WeakMap<HTMLVideoElement, number>();
let playbackRefreshes = new WeakSet<HTMLVideoElement>();
let reportedDomUrls = new Set<string>();
let listenersAttached = false;
/** Cast chrome currently shown (not auto-hidden). */
let chromeVisible = true;
let lastPointerX = Number.NaN;
let lastPointerY = Number.NaN;

// ── URL helpers ──────────────────────────────────────────────────────────────

function normalizeUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  try {
    return new URL(trimmed, document.baseURI).href;
  } catch {
    return trimmed;
  }
}

// ── Video discovery (open shadow roots) ──────────────────────────────────────

function queryVideosIncludingShadows(
  root: Document | ShadowRoot | Element = document,
): HTMLVideoElement[] {
  const results: HTMLVideoElement[] = [];
  const visit = (node: Document | ShadowRoot | Element) => {
    let list: NodeListOf<Element> | Element[];
    try {
      list = node.querySelectorAll("video");
    } catch {
      return;
    }
    for (const el of Array.from(list)) {
      if (el instanceof HTMLVideoElement) results.push(el);
    }
    let all: NodeListOf<Element> | Element[];
    try {
      all = node.querySelectorAll("*");
    } catch {
      return;
    }
    for (const el of Array.from(all)) {
      const sr = (el as Element & { shadowRoot?: ShadowRoot | null }).shadowRoot;
      if (sr) visit(sr);
    }
  };
  visit(root);
  return results;
}

function visibleArea(el: Element): number {
  if (!(el as HTMLElement).isConnected) return 0;
  const style = window.getComputedStyle(el);
  if (
    style.display === "none" ||
    style.visibility === "hidden" ||
    style.opacity === "0" ||
    (el as HTMLElement).hidden
  ) {
    return 0;
  }
  const rect = el.getBoundingClientRect();
  if (rect.width < 1 || rect.height < 1) return 0;
  const vw = window.innerWidth || document.documentElement.clientWidth;
  const vh = window.innerHeight || document.documentElement.clientHeight;
  const left = Math.max(rect.left, 0);
  const top = Math.max(rect.top, 0);
  const right = Math.min(rect.right, vw);
  const bottom = Math.min(rect.bottom, vh);
  const w = Math.max(0, right - left);
  const h = Math.max(0, bottom - top);
  return w * h;
}

function isEligibleVideo(video: HTMLVideoElement): boolean {
  if (!video.isConnected) return false;
  // A video can be the largest element in a tiny advertisement/preview iframe.
  // Suppress overlays in frames too small to plausibly host the main player.
  if (
    window.top !== window &&
    (window.innerWidth < MIN_SUBFRAME_WIDTH ||
      window.innerHeight < MIN_SUBFRAME_HEIGHT)
  ) {
    return false;
  }
  const rect = video.getBoundingClientRect();
  if (rect.width < MIN_VIDEO_WIDTH || rect.height < MIN_VIDEO_HEIGHT) return false;
  if (rect.width * rect.height < MIN_VIDEO_AREA) return false;
  const area = visibleArea(video);
  // Reject players represented by only a tiny off-screen sliver.
  if (area < Math.min(MIN_VIDEO_AREA, rect.width * rect.height * 0.15)) {
    return false;
  }

  const viewportArea = Math.max(
    1,
    (window.innerWidth || document.documentElement.clientWidth) *
      (window.innerHeight || document.documentElement.clientHeight),
  );
  const viewportShare = area / viewportArea;
  let score = 0;

  // Size and prominence are the strongest signals and keep paused custom
  // players eligible even before metadata or playback state is available.
  score += rect.width >= 480 && rect.height >= 270 ? 3 : 1;
  if (viewportShare >= 0.12) score += 3;
  else if (viewportShare >= 0.06) score += 2;
  else if (viewportShare >= 0.035) score += 1;

  if (!video.paused && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
    score += 2;
  }
  if (video.controls) score += 1;
  if (!Number.isFinite(video.duration) || video.duration >= 60) score += 1;

  // Muted looping media and linked/card videos are common preview patterns.
  // These are penalties rather than hard exclusions so a genuinely large main
  // player can still qualify when a site uses those attributes.
  if (video.muted && video.loop && !video.controls) score -= 4;
  if (video.closest("a, button, [role='link']")) score -= 2;

  return score >= MIN_MAIN_VIDEO_SCORE;
}

/**
 * Primary video = largest visible eligible <video> in this frame.
 * Tiny previews, ads, and off-screen players are filtered out.
 */
function selectPrimaryVideo(): HTMLVideoElement | null {
  const videos = queryVideosIncludingShadows();
  let best: HTMLVideoElement | null = null;
  let bestArea = 0;
  for (const video of videos) {
    if (!isEligibleVideo(video)) continue;
    const area = visibleArea(video);
    if (area > bestArea) {
      bestArea = area;
      best = video;
    }
  }
  return best;
}

// ── Candidate ranking ────────────────────────────────────────────────────────

function domSourceUrls(video: HTMLVideoElement): string[] {
  const urls: string[] = [];
  const push = (raw: string | null | undefined) => {
    const n = normalizeUrl(raw);
    if (n) urls.push(n);
  };
  push(video.currentSrc);
  push(video.src);
  try {
    for (const source of Array.from(video.querySelectorAll("source"))) {
      push(source.getAttribute("src") ?? (source as HTMLSourceElement).src);
    }
  } catch {
    /* ignore */
  }
  return urls;
}

async function reportDomSources(video: HTMLVideoElement): Promise<void> {
  const urls = domSourceUrls(video).filter((url) => {
    if (isBlobOrDataUrl(url) || isExcludedMediaCandidate(url)) return false;
    try {
      return /^https?:$/.test(new URL(url).protocol);
    } catch {
      return false;
    }
  });
  const pending = [...new Set(urls)].filter((url) => !reportedDomUrls.has(url));
  if (pending.length === 0) return;

  for (const url of pending) reportedDomUrls.add(url);
  try {
    await browser.runtime.sendMessage({
      action: "observeDomVideoSources",
      urls: pending,
    });
  } catch {
    // A sleeping/restarting background should be retried on the next resolve.
    for (const url of pending) reportedDomUrls.delete(url);
  }
}

function frameResourceUrls(): string[] {
  try {
    return performance
      .getEntriesByType("resource")
      .map((entry) => normalizeUrl(entry.name))
      .filter((url): url is string => !!url);
  } catch {
    return [];
  }
}

/**
 * Resolve a candidate without allowing unrelated, later requests in the same
 * frame to steal an established player binding. Network-detected exact DOM
 * matches remain authoritative; pure `dom_source` matches are only signals so
 * protected CDN URLs (with captured headers) can win. A source lifecycle event
 * explicitly clears the binding before this function runs for a new stream.
 */
function resolveCandidate(
  video: HTMLVideoElement,
  records: DetectedVideo[],
  senderFrameId?: number,
): DetectedVideo | null {
  const playable = records.filter(
    (record) =>
      record.url &&
      !isBlobOrDataUrl(record.url) &&
      !isExcludedMediaCandidate(record.url, record.detectedBy),
  );

  const exactUrls = domSourceUrls(video).filter(
    (url) => !isBlobOrDataUrl(url),
  );
  for (const url of exactUrls) {
    const normalized = normalizeUrl(url);
    const exact = playable.find(
      (record) => normalizeUrl(record.url) === normalized,
    );
    // Authoritative only for webRequest detections that still carry headers.
    // DOM-only get_file/src URLs (or headerless detections) often fail on cast
    // while a CDN URL in the same frame has Cookie/Referer.
    if (
      exact &&
      !isDomSourceDetection(exact.detectedBy) &&
      exact.hasHeaders === true
    ) {
      candidateBindings.set(video, exact.url);
      return exact;
    }
  }

  const resources = frameResourceUrls();
  const preferredSince = candidatePreferredSince.get(video);
  const initial = rankMediaCandidate(playable, {
    domUrls: exactUrls,
    senderFrameId,
    frameResourceUrls: resources,
    preferredSince,
  });

  const boundUrl = candidateBindings.get(video);
  if (boundUrl) {
    const bound = playable.find((record) => record.url === boundUrl);
    if (bound) {
      if (initial && initial.url !== bound.url) {
        // Upgrade an early pure-DOM / headerless binding once a header-bearing
        // network stream appears for this player.
        const boundIsWeak =
          isDomSourceDetection(bound.detectedBy) || bound.hasHeaders !== true;
        const initialIsStronger =
          !isDomSourceDetection(initial.detectedBy) &&
          initial.hasHeaders === true;
        if (boundIsWeak && initialIsStronger) {
          candidateBindings.set(video, initial.url);
          return initial;
        }
        const observed = new Set(resources);
        const isCurrent = (record: DetectedVideo) =>
          observed.has(normalizeUrl(record.url) ?? record.url) ||
          (typeof preferredSince === "number" &&
            record.timestamp >= preferredSince);
        // Keep a stable association against later previews, but allow strong
        // evidence from the current media lifecycle to repair an early guess.
        if (isCurrent(initial) && !isCurrent(bound)) {
          candidateBindings.set(video, initial.url);
          return initial;
        }
      }
      return bound;
    }
    candidateBindings.delete(video);
  }

  if (initial) candidateBindings.set(video, initial.url);
  return initial;
}

// ── Overlay UI ───────────────────────────────────────────────────────────────

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";

/** Original generic cast glyph built without HTML parsing. */
function createCastIcon(): SVGSVGElement {
  const svg = document.createElementNS(SVG_NAMESPACE, "svg");
  for (const [name, value] of Object.entries({
    viewBox: "0 0 24 24",
    width: "23",
    height: "23",
    fill: "none",
    "aria-hidden": "true",
    focusable: "false",
  })) {
    svg.setAttribute(name, value);
  }

  const screen = document.createElementNS(SVG_NAMESPACE, "path");
  screen.setAttribute(
    "d",
    "M4 5.75A1.75 1.75 0 0 1 5.75 4h12.5A1.75 1.75 0 0 1 20 5.75v9.5A1.75 1.75 0 0 1 18.25 17H16",
  );
  screen.setAttribute("stroke", "currentColor");
  screen.setAttribute("stroke-width", "1.8");
  screen.setAttribute("stroke-linecap", "round");
  screen.setAttribute("stroke-linejoin", "round");

  const signals = document.createElementNS(SVG_NAMESPACE, "path");
  signals.setAttribute(
    "d",
    "M4 15.5a4.5 4.5 0 0 1 4.5 4.5M4 11.5A8.5 8.5 0 0 1 12.5 20",
  );
  signals.setAttribute("stroke", "currentColor");
  signals.setAttribute("stroke-width", "1.8");
  signals.setAttribute("stroke-linecap", "round");

  const dot = document.createElementNS(SVG_NAMESPACE, "circle");
  dot.setAttribute("cx", "4");
  dot.setAttribute("cy", "20");
  dot.setAttribute("r", "1.15");
  dot.setAttribute("fill", "currentColor");

  svg.append(screen, signals, dot);
  return svg;
}

function injectOverlayStyles(root: ShadowRoot): void {
  const style = document.createElement("style");
  style.textContent = `
    :host {
      all: initial;
      position: fixed;
      pointer-events: none;
      z-index: ${Z_INDEX};
      display: block;
    }
    .wrap {
      position: absolute;
      inset: 0;
      pointer-events: none;
    }
    .chrome {
      position: absolute;
      inset: 0;
      pointer-events: none;
      opacity: 1;
      transition: opacity 0.25s ease;
    }
    .chrome.is-hidden {
      opacity: 0;
    }
    .chrome.is-hidden .controls {
      pointer-events: none !important;
    }
    /* Isolated hit target for the cast control. */
    .controls {
      pointer-events: auto;
      position: absolute;
      top: ${OVERLAY_INSET - 4}px;
      right: ${OVERLAY_INSET - 2}px;
      width: ${BUTTON_SIZE + 8}px;
      height: ${BUTTON_SIZE + 8}px;
    }
    .chrome[data-position="top-left"] .controls {
      left: ${OVERLAY_INSET - 2}px;
      right: auto;
    }
    .chrome[data-position="top-center"] .controls {
      left: 50%;
      right: auto;
      transform: translateX(-50%);
    }
    .chrome[data-position="middle-left"] .controls {
      top: 50%;
      left: ${OVERLAY_INSET - 2}px;
      right: auto;
      transform: translateY(-50%);
    }
    .chrome[data-position="middle-right"] .controls {
      top: 50%;
      transform: translateY(-50%);
    }
    button.cast {
      pointer-events: auto;
      position: absolute;
      top: 4px;
      right: 4px;
      width: ${BUTTON_SIZE}px;
      height: ${BUTTON_SIZE}px;
      border: none;
      border-radius: 50%;
      background: rgba(28, 27, 31, 0.78);
      color: #D0BCFF;
      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0;
      transition: background 0.15s ease, transform 0.15s ease, color 0.15s ease, opacity 0.15s ease;
      backdrop-filter: blur(6px);
      -webkit-backdrop-filter: blur(6px);
    }
    button.cast:hover:not(:disabled) {
      background: rgba(44, 44, 50, 0.92);
      color: #E8DEF8;
      transform: scale(1.05);
    }
    button.cast:focus-visible {
      outline: 2px solid #D0BCFF;
      outline-offset: 2px;
    }
    button.cast:active:not(:disabled) {
      transform: scale(0.96);
    }
    button.cast:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }
    button.cast.state-casting {
      color: #E8DEF8;
    }
    button.cast.state-success {
      color: #A8E6CF;
    }
    button.cast.state-error,
    button.cast.state-disabled-no-stream {
      color: #F2B8B5;
    }
    .status {
      position: absolute;
      top: ${OVERLAY_INSET + BUTTON_SIZE + 6}px;
      right: ${OVERLAY_INSET + 4}px;
      max-width: min(220px, 70vw);
      padding: 6px 10px;
      border-radius: 8px;
      background: rgba(28, 27, 31, 0.88);
      color: #E6E1E5;
      font: 600 11px/1.35 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
      pointer-events: none;
      opacity: 0;
      transform: translateY(-4px);
      transition: opacity 0.2s ease, transform 0.2s ease;
      text-align: right;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
      /* Keep layout during fade-out so text doesn't vanish before opacity finishes. */
      visibility: hidden;
    }
    .status.show {
      opacity: 1;
      transform: translateY(0);
      visibility: visible;
    }
    .chrome[data-position="top-left"] .status {
      left: ${OVERLAY_INSET + 4}px;
      right: auto;
      text-align: left;
    }
    .chrome[data-position="top-center"] .status {
      left: 50%;
      right: auto;
      text-align: center;
      transform: translate(-50%, -4px);
    }
    .chrome[data-position="top-center"] .status.show {
      transform: translate(-50%, 0);
    }
    .chrome[data-position="middle-left"] .status,
    .chrome[data-position="middle-right"] .status {
      top: calc(50% + ${BUTTON_SIZE / 2 + 8}px);
    }
    .chrome[data-position="middle-left"] .status {
      left: ${OVERLAY_INSET + 4}px;
      right: auto;
      text-align: left;
    }
    @media (prefers-reduced-motion: reduce) {
      .chrome,
      button.cast,
      .status {
        transition: none;
      }
      button.cast:hover:not(:disabled),
      button.cast:active:not(:disabled) {
        transform: none;
      }
    }
  `;
  root.appendChild(style);
}

function ensureOverlay(): void {
  if (overlayHost && shadowRoot && castBtn && chromeEl) return;

  const host = document.createElement("div");
  host.id = HOST_ID;
  host.setAttribute("data-playbridge", "cast-overlay");

  const shadow = host.attachShadow({ mode: "closed" });
  injectOverlayStyles(shadow);

  const wrap = document.createElement("div");
  wrap.className = "wrap";

  const chrome = document.createElement("div");
  chrome.className = "chrome";
  chrome.dataset.position = overlayPosition;

  const controls = document.createElement("div");
  controls.className = "controls";

  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "cast";
  btn.setAttribute("aria-label", "Cast this video with PlayBridge");
  btn.title = "Cast this video with PlayBridge";
  btn.appendChild(createCastIcon());

  const status = document.createElement("div");
  status.className = "status";
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");

  controls.appendChild(btn);
  chrome.appendChild(controls);
  chrome.appendChild(status);
  wrap.appendChild(chrome);
  shadow.appendChild(wrap);

  btn.addEventListener("click", onCastClick, true);
  btn.addEventListener("mousedown", stopBubble, true);
  btn.addEventListener("mouseup", stopBubble, true);
  btn.addEventListener("pointerdown", stopBubble, true);
  btn.addEventListener("pointerenter", onCastPointerEnter);
  btn.addEventListener("pointerleave", onCastPointerLeave);
  controls.addEventListener("focusin", onChromeFocusIn);
  controls.addEventListener("focusout", onChromeFocusOut);

  overlayHost = host;
  shadowRoot = shadow;
  chromeEl = chrome;
  castBtn = btn;
  statusEl = status;
  attachOverlayHost();
  showChrome({ restartTimer: true });
}

function stopBubble(e: Event): void {
  e.stopPropagation();
}

function clearAutoHideTimer(): void {
  if (autoHideTimer) {
    clearTimeout(autoHideTimer);
    autoHideTimer = null;
  }
}

function shouldHoldChromeVisible(): boolean {
  return (
    castInFlight ||
    overlayState === "casting" ||
    overlayState === "success" ||
    overlayState === "error"
  );
}

function scheduleAutoHide(): void {
  clearAutoHideTimer();
  if (!enabled || !chromeVisible) return;
  if (shouldHoldChromeVisible()) return;
  autoHideTimer = setTimeout(() => {
    autoHideTimer = null;
    if (!enabled || shouldHoldChromeVisible()) return;
    hideChrome();
  }, AUTO_HIDE_MS);
}

function showChrome(opts: { restartTimer?: boolean } = {}): void {
  if (!enabled) return;
  chromeVisible = true;
  chromeEl?.classList.remove("is-hidden");
  if (castBtn) castBtn.tabIndex = 0;
  if (opts.restartTimer !== false) scheduleAutoHide();
}

function hideChrome(): void {
  chromeVisible = false;
  clearAutoHideTimer();
  chromeEl?.classList.add("is-hidden");
}

function onChromeFocusIn(): void {
  clearAutoHideTimer();
  showChrome({ restartTimer: false });
}

function onChromeFocusOut(): void {
  scheduleAutoHide();
}

function onVideoPointerActivity(event: PointerEvent): void {
  if (!enabled || !primaryVideo) return;
  const rect = primaryVideo.getBoundingClientRect();
  if (
    event.clientX < rect.left ||
    event.clientX > rect.right ||
    event.clientY < rect.top ||
    event.clientY > rect.bottom
  ) {
    return;
  }
  // Some fullscreen players emit repeated pointermove events without actual
  // movement. Only genuine coordinate changes should restart the idle timer.
  if (event.type === "pointermove" && event.clientX === lastPointerX && event.clientY === lastPointerY) return;
  lastPointerX = event.clientX;
  lastPointerY = event.clientY;
  showChrome({ restartTimer: true });
}

function attachOverlayHost(): void {
  if (!overlayHost) return;
  const fs = document.fullscreenElement;
  const parent =
    fs && fs !== overlayHost && !(overlayHost.contains(fs))
      ? fs
      : document.documentElement;
  if (overlayHost.parentElement !== parent) {
    try {
      parent.appendChild(overlayHost);
    } catch {
      try {
        document.documentElement.appendChild(overlayHost);
      } catch {
        /* restricted pages */
      }
    }
  }
}

function destroyOverlay(): void {
  if (statusResetTimer) {
    clearTimeout(statusResetTimer);
    statusResetTimer = null;
  }
  clearAutoHideTimer();
  if (castBtn) {
    castBtn.removeEventListener("click", onCastClick, true);
    castBtn.removeEventListener("mousedown", stopBubble, true);
    castBtn.removeEventListener("mouseup", stopBubble, true);
    castBtn.removeEventListener("pointerdown", stopBubble, true);
    castBtn.removeEventListener("pointerenter", onCastPointerEnter);
    castBtn.removeEventListener("pointerleave", onCastPointerLeave);
  }
  const controlsEl = chromeEl?.querySelector(".controls");
  if (controlsEl) {
    controlsEl.removeEventListener("focusin", onChromeFocusIn);
    controlsEl.removeEventListener("focusout", onChromeFocusOut);
  }
  overlayHost?.remove();
  overlayHost = null;
  shadowRoot = null;
  chromeEl = null;
  castBtn = null;
  statusEl = null;
  overlayState = "idle";
  castInFlight = false;
  chromeVisible = true;
  lastCandidateUrl = null;
}

const STATUS_FADE_MS = 200;

function clearStatusTimer(): void {
  if (statusResetTimer) {
    clearTimeout(statusResetTimer);
    statusResetTimer = null;
  }
}

/**
 * Fade the status toast out (remove .show). Clears text after the CSS transition
 * so the opacity animation can complete smoothly.
 */
function hideStatusMessage(): void {
  clearStatusTimer();
  if (!statusEl) return;
  statusEl.classList.remove("show");
  const el = statusEl;
  statusResetTimer = setTimeout(() => {
    statusResetTimer = null;
    // Only clear if still hidden (a new show may have started).
    if (statusEl === el && !el.classList.contains("show")) {
      el.textContent = "";
    }
  }, STATUS_FADE_MS);
}

function setStatusText(message: string): void {
  if (!statusEl) return;
  clearStatusTimer();
  statusEl.textContent = message;
  // Force reflow so re-showing after a quick leave restarts the fade-in.
  void statusEl.offsetWidth;
  statusEl.classList.add("show");
}

/** Toast while hovering the cast icon; also auto-fades if the pointer stays put. */
function showEphemeralStatus(message: string, durationMs = STATUS_RESET_MS): void {
  setStatusText(message);
  clearStatusTimer();
  statusResetTimer = setTimeout(() => {
    statusResetTimer = null;
    hideStatusMessage();
  }, durationMs);
}

/** No-stream hint only while the pointer is over the cast icon. */
function onCastPointerEnter(): void {
  if (!enabled) return;
  if (overlayState === "disabled-no-stream") {
    showEphemeralStatus(NO_STREAM_MSG, NO_STREAM_STATUS_MS);
  }
}

/** Hide the no-stream hint as soon as the pointer leaves the icon. */
function onCastPointerLeave(): void {
  if (overlayState === "disabled-no-stream") {
    hideStatusMessage();
  }
}

function setOverlayVisualState(state: OverlayState, message?: string): void {
  overlayState = state;
  if (!castBtn) return;
  castBtn.classList.remove(
    "state-casting",
    "state-success",
    "state-error",
    "state-disabled-no-stream",
  );
  const noStream = state === "disabled-no-stream";
  castBtn.disabled = noStream || state === "casting" || castInFlight;
  if (state === "casting") castBtn.classList.add("state-casting");
  if (state === "success") castBtn.classList.add("state-success");
  if (state === "error") castBtn.classList.add("state-error");
  if (noStream) castBtn.classList.add("state-disabled-no-stream");

  if (state === "casting") {
    clearStatusTimer();
    setStatusText(message || "Casting…");
    showChrome({ restartTimer: false });
    return;
  }

  if (state === "success" || state === "error") {
    showChrome({ restartTimer: false });
    setStatusText(message || (state === "success" ? "Casting to TV" : "Cast failed"));
    clearStatusTimer();
    statusResetTimer = setTimeout(() => {
      statusResetTimer = null;
      if (!enabled) return;
      hideStatusMessage();
      // Restore button state; no-stream toast only returns on icon hover.
      const next: OverlayState = lastCandidateUrl ? "idle" : "disabled-no-stream";
      setOverlayVisualState(next);
      scheduleAutoHide();
    }, STATUS_RESET_MS);
    return;
  }

  if (noStream) {
    // No auto toast — only show on cast-icon hover (onCastPointerEnter).
    return;
  }

  // idle
  hideStatusMessage();
}

function schedulePosition(): void {
  if (rafId != null) return;
  rafId = requestAnimationFrame(() => {
    rafId = null;
    positionOverlay();
  });
}

function positionOverlay(): void {
  if (!enabled || !overlayHost || !primaryVideo) {
    if (overlayHost) overlayHost.style.display = "none";
    return;
  }
  if (!primaryVideo.isConnected || !isEligibleVideo(primaryVideo)) {
    overlayHost.style.display = "none";
    return;
  }
  attachOverlayHost();
  const rect = primaryVideo.getBoundingClientRect();
  overlayHost.style.display = "block";
  overlayHost.style.top = `${Math.round(rect.top)}px`;
  overlayHost.style.left = `${Math.round(rect.left)}px`;
  overlayHost.style.width = `${Math.round(rect.width)}px`;
  overlayHost.style.height = `${Math.round(rect.height)}px`;
}

// ── Detection fetch + resolve ────────────────────────────────────────────────

interface DetectedVideosResponse {
  videos: DetectedVideo[];
  senderFrameId?: number;
}

async function fetchDetectedVideos(): Promise<DetectedVideosResponse> {
  try {
    const res = (await browser.runtime.sendMessage({
      action: "getVideos",
      scope: "frame",
    })) as { videos?: DetectedVideo[]; frameId?: number } | undefined;
    return {
      videos: Array.isArray(res?.videos) ? res.videos : [],
      senderFrameId:
        typeof res?.frameId === "number" ? res.frameId : undefined,
    };
  } catch {
    return { videos: [] };
  }
}

async function resolveAndRender(): Promise<void> {
  if (!enabled || !settingLoaded) return;

  const selectedVideo = selectPrimaryVideo();
  if (selectedVideo !== primaryVideo) {
    unbindVideoListeners(primaryVideo);
    primaryVideo = selectedVideo;
    bindVideoListeners(primaryVideo);
    observeVideoSize(primaryVideo);
    if (primaryVideo) {
      showChrome({ restartTimer: true });
    }
  }

  if (!primaryVideo) {
    destroyOverlay();
    return;
  }

  ensureOverlay();
  const activeVideo = primaryVideo;
  await reportDomSources(activeVideo);
  const response = await fetchDetectedVideos();
  if (!enabled || primaryVideo !== activeVideo || !activeVideo.isConnected) return;

  const candidate = resolveCandidate(
    activeVideo,
    response.videos,
    response.senderFrameId,
  );
  lastCandidateUrl = candidate?.url ?? null;

  if (!candidate) {
    setOverlayVisualState("disabled-no-stream");
  } else if (overlayState === "disabled-no-stream" || overlayState === "idle") {
    setOverlayVisualState("idle");
  }
  schedulePosition();
}

function scheduleResolve(): void {
  if (!enabled) return;
  if (resolveTimer) clearTimeout(resolveTimer);
  resolveTimer = setTimeout(() => {
    resolveTimer = null;
    void resolveAndRender();
  }, RESOLVE_DEBOUNCE_MS);
}

// ── Cast interaction ─────────────────────────────────────────────────────────

async function onCastClick(e: Event): Promise<void> {
  // The host lives in the page's shared DOM. Never allow synthetic page events
  // to invoke the privileged background casting path.
  if (!e.isTrusted) return;
  e.preventDefault();
  e.stopPropagation();
  if (!enabled || castInFlight || !primaryVideo || !chromeVisible) return;

  castInFlight = true;
  showChrome({ restartTimer: false });
  setOverlayVisualState("casting", "Casting…");

  try {
    const video = primaryVideo;
    const response = await fetchDetectedVideos();
    if (!enabled || primaryVideo !== video || !video.isConnected) {
      setOverlayVisualState("error", "Video changed; try again.");
      return;
    }
    const candidate = resolveCandidate(
      video,
      response.videos,
      response.senderFrameId,
    );
    lastCandidateUrl = candidate?.url ?? null;

    if (!candidate) {
      setOverlayVisualState("error", "No castable stream detected yet");
      return;
    }

    const status = (await browser.runtime.sendMessage({
      action: "wsGetStatus",
    })) as {
      status?: string;
      desktopConnected?: boolean;
    } | null;

    if (!status?.desktopConnected) {
      setOverlayVisualState("error", "Open the PlayBridge desktop app.");
      return;
    }
    if (status.status !== "connected") {
      setOverlayVisualState("error", "Connect a TV in PlayBridge.");
      return;
    }

    // Send only the URL identifier; background uses sender.tab + stored headers.
    const res = (await browser.runtime.sendMessage({
      action: "castDetectedVideo",
      url: candidate.url,
    })) as { success?: boolean; reason?: string | null } | null;

    if (res?.success) {
      setOverlayVisualState("success", "Casting to TV");
    } else {
      const reason = res?.reason || "Cast failed";
      if (reason === "Not connected" || reason === "Not connected to TV") {
        setOverlayVisualState("error", "Connect a TV in PlayBridge.");
      } else {
        setOverlayVisualState("error", reason);
      }
    }
  } catch {
    setOverlayVisualState("error", "Cast failed");
  } finally {
    castInFlight = false;
    if (castBtn && overlayState !== "disabled-no-stream") {
      // Re-enable unless still in a transitional disable state.
      if (overlayState === "idle" || overlayState === "success" || overlayState === "error") {
        castBtn.disabled = false;
      }
    }
  }
}

// ── Observers / listeners ────────────────────────────────────────────────────

function onDomMutated(records?: MutationRecord[]): void {
  if (
    records?.length &&
    overlayHost &&
    records.every(
      (record) =>
        record.target === overlayHost || overlayHost?.contains(record.target),
    )
  ) {
    return;
  }
  scheduleResolve();
}

function onPopState(): void {
  scheduleResolve();
}

function onViewportChange(): void {
  schedulePosition();
  scheduleResolve();
}

function onFullscreenChange(): void {
  attachOverlayHost();
  showChrome({ restartTimer: true });
  schedulePosition();
  scheduleResolve();
}

function onMediaSourceChange(event: Event): void {
  const video = event.currentTarget;
  if (video instanceof HTMLVideoElement) {
    const now = Date.now();
    if (event.type === "loadstart" || event.type === "emptied") {
      candidateBindings.delete(video);
      candidatePreferredSince.set(video, now - 5_000);
      playbackRefreshes.delete(video);
    } else if (event.type === "loadedmetadata") {
      // The network request belonging to this source should now be present.
      candidateBindings.delete(video);
      candidatePreferredSince.set(video, now - 30_000);
    } else if (event.type === "play" && !playbackRefreshes.has(video)) {
      // Content scripts can attach after loadstart/metadata. Refresh once at the
      // first play so an early ad/preload binding cannot remain authoritative.
      candidateBindings.delete(video);
      candidatePreferredSince.set(video, now - 30_000);
      playbackRefreshes.add(video);
    }
  }
  scheduleResolve();
}

function bindVideoListeners(video: HTMLVideoElement | null): void {
  if (!video) return;
  if (!candidatePreferredSince.has(video)) {
    candidatePreferredSince.set(video, Date.now() - 60_000);
  }
  video.addEventListener("loadedmetadata", onMediaSourceChange);
  video.addEventListener("emptied", onMediaSourceChange);
  video.addEventListener("loadstart", onMediaSourceChange);
  video.addEventListener("play", onMediaSourceChange);
  // Reveal chrome when the user interacts with the player area (after auto-hide).
  video.addEventListener("pointerenter", onVideoPointerActivity);
  video.addEventListener("pointermove", onVideoPointerActivity);
}

function unbindVideoListeners(video: HTMLVideoElement | null): void {
  if (!video) return;
  video.removeEventListener("loadedmetadata", onMediaSourceChange);
  video.removeEventListener("emptied", onMediaSourceChange);
  video.removeEventListener("loadstart", onMediaSourceChange);
  video.removeEventListener("play", onMediaSourceChange);
  video.removeEventListener("pointerenter", onVideoPointerActivity);
  video.removeEventListener("pointermove", onVideoPointerActivity);
}

function observeVideoSize(video: HTMLVideoElement | null): void {
  resizeObserver?.disconnect();
  if (!video || typeof ResizeObserver === "undefined") return;
  resizeObserver = new ResizeObserver(() => schedulePosition());
  try {
    resizeObserver.observe(video);
  } catch {
    /* ignore */
  }
}

function attachGlobalListeners(): void {
  if (listenersAttached) return;
  listenersAttached = true;

  window.addEventListener("resize", onViewportChange, { passive: true });
  window.addEventListener("scroll", onViewportChange, {
    capture: true,
    passive: true,
  });
  document.addEventListener("fullscreenchange", onFullscreenChange);
  document.addEventListener("webkitfullscreenchange", onFullscreenChange);
  // Capture at document level because site controls commonly sit above the
  // video and prevent pointer events from targeting the <video> itself.
  document.addEventListener("pointermove", onVideoPointerActivity, {
    capture: true,
    passive: true,
  });

  mutationObserver = new MutationObserver(onDomMutated);
  try {
    mutationObserver.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src", "style", "class", "hidden"],
    });
  } catch {
    /* ignore */
  }

  window.addEventListener("popstate", onPopState);
}

function detachGlobalListeners(): void {
  if (!listenersAttached) return;
  listenersAttached = false;

  window.removeEventListener("resize", onViewportChange);
  window.removeEventListener("scroll", onViewportChange, true);
  document.removeEventListener("fullscreenchange", onFullscreenChange);
  document.removeEventListener("webkitfullscreenchange", onFullscreenChange);
  document.removeEventListener("pointermove", onVideoPointerActivity, true);
  window.removeEventListener("popstate", onPopState);

  mutationObserver?.disconnect();
  mutationObserver = null;
  resizeObserver?.disconnect();
  resizeObserver = null;

  if (rafId != null) {
    cancelAnimationFrame(rafId);
    rafId = null;
  }
  if (resolveTimer) {
    clearTimeout(resolveTimer);
    resolveTimer = null;
  }
  clearAutoHideTimer();
}

// ── Enable / disable ─────────────────────────────────────────────────────────

function startOverlayFeature(): void {
  if (!settingLoaded || !enabled) return;
  attachGlobalListeners();
  void resolveAndRender();
}

function stopOverlayFeature(): void {
  unbindVideoListeners(primaryVideo);
  primaryVideo = null;
  lastPointerX = Number.NaN;
  lastPointerY = Number.NaN;
  detachGlobalListeners();
  destroyOverlay();
  lastCandidateUrl = null;
  candidateBindings = new WeakMap<HTMLVideoElement, string>();
  candidatePreferredSince = new WeakMap<HTMLVideoElement, number>();
  playbackRefreshes = new WeakSet<HTMLVideoElement>();
  reportedDomUrls = new Set<string>();
}

function applyEnabled(next: boolean): void {
  const was = enabled;
  enabled = next;
  if (!settingLoaded) return;
  if (enabled && !was) startOverlayFeature();
  else if (!enabled && was) stopOverlayFeature();
}

function applyOverlayPosition(next: VideoCastOverlayPosition): void {
  overlayPosition = next;
  if (chromeEl) chromeEl.dataset.position = next;
  schedulePosition();
}

async function refreshOverlayPreferences(): Promise<void> {
  const requestId = ++preferenceRequestId;
  let preferences: VideoCastOverlayPreferences | undefined;
  try {
    preferences = (await browser.runtime.sendMessage({
      action: "getOverlayPreferences",
    })) as VideoCastOverlayPreferences | undefined;
  } catch {
    /* handled as disabled below */
  }
  if (requestId !== preferenceRequestId) return;

  if (
    typeof preferences?.enabled !== "boolean" ||
    !isVideoCastOverlayPosition(preferences.position)
  ) {
    // A failed or malformed background response must not bypass a saved global
    // or site disable preference by falling back to an enabled overlay.
    settingLoaded = true;
    applyEnabled(false);
    return;
  }

  applyOverlayPosition(preferences.position);
  settingLoaded = true;
  applyEnabled(preferences.enabled);
}

// ── Init ─────────────────────────────────────────────────────────────────────

function onStorageChanged(
  changes: Record<string, browser.Storage.StorageChange>,
  area: string,
): void {
  if (area !== "local") return;
  if (!VIDEO_CAST_OVERLAY_STORAGE_KEYS.some((key) => key in changes)) return;
  void refreshOverlayPreferences();
}

function onRuntimeMessage(message: unknown): void {
  const msg = message as { type?: string };
  if (
    msg?.type === "overlay_navigation" ||
    msg?.type === "data_consent_changed"
  ) {
    void refreshOverlayPreferences();
    return;
  }
  if (!enabled || !settingLoaded) return;
  // New detection or bridge status — re-rank without high-frequency polling.
  if (
    msg?.type === "video_detected" ||
    msg?.type === "ws_status_update"
  ) {
    scheduleResolve();
  }
}

async function init(): Promise<void> {
  // Avoid FOUC: do not create UI until the setting is known.
  browser.storage.onChanged.addListener(onStorageChanged);
  browser.runtime.onMessage.addListener(onRuntimeMessage);
  await refreshOverlayPreferences();
}

void init();
