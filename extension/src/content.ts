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
  SHOW_VIDEO_CAST_OVERLAY_DEFAULT,
  SHOW_VIDEO_CAST_OVERLAY_KEY,
  getShowVideoCastOverlay,
} from "./settings";

// ── Types ────────────────────────────────────────────────────────────────────

interface DetectedVideo {
  url: string;
  tabId: number;
  contentType: string;
  detectedBy: string;
  originUrl: string;
  timestamp: number;
  frameId?: number;
  subtitles?: string[];
  subtitlePreview?: string;
  qualities?: unknown[];
}

type OverlayState =
  | "idle"
  | "disabled-no-stream"
  | "casting"
  | "success"
  | "error";

// ── Constants ────────────────────────────────────────────────────────────────

const HOST_ID = "playbridge-cast-overlay-host";
const MIN_VIDEO_WIDTH = 200;
const MIN_VIDEO_HEIGHT = 112;
const MIN_VIDEO_AREA = MIN_VIDEO_WIDTH * MIN_VIDEO_HEIGHT;
const OVERLAY_INSET = 14;
const BUTTON_SIZE = 40;
const Z_INDEX = 2147483646;
const STATUS_RESET_MS = 2800;
const NO_STREAM_STATUS_MS = 2800;
const NO_STREAM_MSG = "No castable stream detected yet";
const RESOLVE_DEBOUNCE_MS = 120;
/** Fade the cast chrome after this idle period (player-control style). */
const AUTO_HIDE_MS = 3500;

const SEGMENT_OR_SUB_RE =
  /\.(?:vtt|srt|ts|m4s)(?:$|\?)|\/segment|frag(?:ment)?|\/chunks?\//i;

// ── State ────────────────────────────────────────────────────────────────────

let enabled = false;
let settingLoaded = false;
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

function isBlobOrData(url: string): boolean {
  return url.startsWith("blob:") || url.startsWith("data:");
}

function isExcludedMedia(url: string, detectedBy?: string): boolean {
  if (detectedBy === "subtitle_extension") return true;
  if (SEGMENT_OR_SUB_RE.test(url)) return true;
  const lower = url.toLowerCase();
  if (lower.endsWith(".vtt") || lower.endsWith(".srt")) return true;
  return false;
}

function isHls(url: string, contentType = ""): boolean {
  const u = url.toLowerCase();
  const ct = contentType.toLowerCase();
  return (
    u.includes("m3u8") ||
    ct.includes("mpegurl") ||
    ct.includes("vnd.apple.mpegurl")
  );
}

function isDash(url: string, contentType = ""): boolean {
  const u = url.toLowerCase();
  const ct = contentType.toLowerCase();
  return u.includes(".mpd") || ct.includes("dash");
}

function isDirectFile(url: string): boolean {
  const path = url.toLowerCase().split("?")[0] ?? "";
  return [".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".m4v", ".wmv", ".3gp"].some(
    (ext) => path.endsWith(ext),
  );
}

function networkPriority(v: DetectedVideo): number {
  // Parsed qualities are only populated for an HLS master playlist.
  if (isHls(v.url, v.contentType) && Array.isArray(v.qualities) && v.qualities.length > 0) return 4;
  if (isHls(v.url, v.contentType)) return 3;
  if (isDash(v.url, v.contentType)) return 2;
  if (isDirectFile(v.url)) return 1;
  return 0;
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
  const rect = video.getBoundingClientRect();
  if (rect.width < MIN_VIDEO_WIDTH || rect.height < MIN_VIDEO_HEIGHT) return false;
  if (rect.width * rect.height < MIN_VIDEO_AREA) return false;
  const area = visibleArea(video);
  // Reject players represented by only a tiny off-screen sliver.
  return area >= Math.min(MIN_VIDEO_AREA, rect.width * rect.height * 0.15);
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

/**
 * Deterministic ranking of detected network records for a video element.
 * Never returns blob:/data: URLs. Prefers exact DOM matches, then same-frame
 * HLS/DASH/direct, then newest plausible tab candidate.
 */
function rankCandidate(
  video: HTMLVideoElement,
  records: DetectedVideo[],
): DetectedVideo | null {
  const playable = records.filter(
    (r) =>
      r.url &&
      !isBlobOrData(r.url) &&
      !isExcludedMedia(r.url, r.detectedBy),
  );
  if (playable.length === 0) return null;

  const domUrls = domSourceUrls(video);
  const nonBlobDom = domUrls.filter((u) => !isBlobOrData(u));

  const byExact = (target: string | null | undefined): DetectedVideo | null => {
    if (!target || isBlobOrData(target)) return null;
    const n = normalizeUrl(target);
    if (!n) return null;
    return playable.find((r) => normalizeUrl(r.url) === n) ?? null;
  };

  // 1. Exact match currentSrc
  const current = byExact(video.currentSrc);
  if (current) return current;

  // 2. Exact match src or <source>
  for (const u of nonBlobDom) {
    const hit = byExact(u);
    if (hit) return hit;
  }

  // 3–4. Same-frame network records when frame metadata exists.
  // Background already prefers the sender frame when records have frameId;
  // if any returned records are frame-tagged, rank within that set first.
  const withFrame = playable.filter((r) => typeof r.frameId === "number");
  const pool = withFrame.length > 0 ? withFrame : playable;

  // Prefer HLS master > DASH > direct, then newest
  const sorted = [...pool].sort((a, b) => {
    const pr = networkPriority(b) - networkPriority(a);
    if (pr !== 0) return pr;
    return b.timestamp - a.timestamp;
  });

  if (sorted.length === 0) return null;

  // 5. If only tab-level records and many competing streams of different kinds
  // with no DOM signal, still pick the best-ranked (newest high-priority).
  // Ambiguous multi-player pages are a known limitation.
  return sorted[0] ?? null;
}

// ── Overlay UI ───────────────────────────────────────────────────────────────

/**
 * Original generic cast glyph: a display outline plus two local signal arcs.
 * It uses currentColor and no third-party assets or branding.
 */
const CAST_SVG = `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="23" height="23" fill="none" aria-hidden="true" focusable="false">
  <path d="M4 5.75A1.75 1.75 0 0 1 5.75 4h12.5A1.75 1.75 0 0 1 20 5.75v9.5A1.75 1.75 0 0 1 18.25 17H16"
        stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M4 15.5a4.5 4.5 0 0 1 4.5 4.5M4 11.5A8.5 8.5 0 0 1 12.5 20"
        stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
  <circle cx="4" cy="20" r="1.15" fill="currentColor"/>
</svg>`.trim();

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

  const shadow = host.attachShadow({ mode: "open" });
  injectOverlayStyles(shadow);

  const wrap = document.createElement("div");
  wrap.className = "wrap";

  const chrome = document.createElement("div");
  chrome.className = "chrome";

  const controls = document.createElement("div");
  controls.className = "controls";

  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "cast";
  btn.setAttribute("aria-label", "Cast this video with PlayBridge");
  btn.title = "Cast this video with PlayBridge";
  // Inline SVG via DOM parse for a single trusted constant string.
  const tpl = document.createElement("template");
  tpl.innerHTML = CAST_SVG;
  const svg = tpl.content.firstElementChild;
  if (svg) btn.appendChild(svg.cloneNode(true));

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
  if (castBtn) castBtn.tabIndex = -1;
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

async function fetchDetectedVideos(): Promise<DetectedVideo[]> {
  try {
    const res = (await browser.runtime.sendMessage({
      action: "getVideos",
      scope: "frame",
    })) as { videos?: DetectedVideo[] } | undefined;
    return Array.isArray(res?.videos) ? res!.videos! : [];
  } catch {
    return [];
  }
}

async function resolveAndRender(): Promise<void> {
  if (!enabled || !settingLoaded) return;

  const video = selectPrimaryVideo();
  if (video !== primaryVideo) {
    unbindVideoListeners(primaryVideo);
    primaryVideo = video;
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
  const records = await fetchDetectedVideos();
  if (!enabled) return;

  const candidate = rankCandidate(primaryVideo, records);
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
  e.preventDefault();
  e.stopPropagation();
  if (!enabled || castInFlight || !primaryVideo || !chromeVisible) return;

  castInFlight = true;
  showChrome({ restartTimer: false });
  setOverlayVisualState("casting", "Casting…");

  try {
    const records = await fetchDetectedVideos();
    const candidate = rankCandidate(primaryVideo, records);
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

function onMediaSourceChange(): void {
  scheduleResolve();
}

function bindVideoListeners(video: HTMLVideoElement | null): void {
  if (!video) return;
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
}

function applyEnabled(next: boolean): void {
  const was = enabled;
  enabled = next;
  if (!settingLoaded) return;
  if (enabled && !was) startOverlayFeature();
  else if (!enabled && was) stopOverlayFeature();
}

// ── Init ─────────────────────────────────────────────────────────────────────

function onStorageChanged(
  changes: Record<string, browser.Storage.StorageChange>,
  area: string,
): void {
  if (area !== "local") return;
  if (!(SHOW_VIDEO_CAST_OVERLAY_KEY in changes)) return;
  const change = changes[SHOW_VIDEO_CAST_OVERLAY_KEY];
  const value =
    typeof change.newValue === "boolean"
      ? change.newValue
      : SHOW_VIDEO_CAST_OVERLAY_DEFAULT;
  applyEnabled(value);
}

function onRuntimeMessage(message: unknown): void {
  if (!enabled || !settingLoaded) return;
  const msg = message as { type?: string };
  // New detection or bridge status — re-rank without high-frequency polling.
  if (
    msg?.type === "video_detected" ||
    msg?.type === "ws_status_update" ||
    msg?.type === "overlay_navigation"
  ) {
    scheduleResolve();
  }
}

async function init(): Promise<void> {
  // Avoid FOUC: do not create UI until the setting is known.
  try {
    enabled = await getShowVideoCastOverlay(browser.storage.local);
  } catch {
    enabled = SHOW_VIDEO_CAST_OVERLAY_DEFAULT;
  }
  settingLoaded = true;

  browser.storage.onChanged.addListener(onStorageChanged);
  browser.runtime.onMessage.addListener(onRuntimeMessage);

  if (enabled) startOverlayFeature();
}

void init();
