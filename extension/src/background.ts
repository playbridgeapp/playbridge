import browser from "./browser";

import {
  DATA_CONSENT_KEY,
  DATA_CONSENT_VERSION,
  dataConsentStatus,
  getDataConsentGranted,
  requiresLocalDataConsent,
  setDataConsentGranted,
} from "./data-consent";
import { HlsParser } from "./hls-parser";
import {
  isBlobOrDataUrl,
  isDomSourceDetection,
  isExcludedMediaCandidate,
  rankMediaCandidate,
} from "./media-candidate";
import * as bridge from "./native-bridge";
import {
  getVideoCastOverlayPreferences,
  siteKeyFromUrl,
} from "./settings";

const DATA_CONSENT_REQUIRED = requiresLocalDataConsent(
  browser.runtime.getManifest().manifest_version,
);
let dataConsentGranted = !DATA_CONSENT_REQUIRED;
const dataConsentReady = getDataConsentGranted(
  browser.storage.local,
  DATA_CONSENT_REQUIRED,
).then((granted) => {
  dataConsentGranted = granted;
});

function canHandleMediaData(): boolean {
  return !DATA_CONSENT_REQUIRED || dataConsentGranted;
}

// Firefox-only: response-body filtering (used to sniff HLS playlist bodies).
// Absent in Chrome MV3, where detection degrades to header/URL signals only.
const FILTER_AVAILABLE =
  typeof (browser.webRequest as { filterResponseData?: unknown })
    .filterResponseData === "function";

// ==================== Debug logging ====================
// Flip PB_DEBUG to false to silence. Logs go to the extension's background
// console: about:debugging → This Firefox → PlayBridge Video Detector → Inspect.
const PB_DEBUG = false;
function plog(...args: unknown[]) {
  if (PB_DEBUG) console.log("[PB]", ...args);
}
const short = (u: string) => (u.length > 110 ? u.slice(0, 110) + "…" : u);

plog("background loaded. filterResponseData available:", FILTER_AVAILABLE);

// ==================== Video detection ====================

const VIDEO_CONTENT_TYPES = [
  "video/",
  "mpegurl",
  "application/dash",
  "application/x-mpegurl",
  "application/vnd.apple.mpegurl",
  "text/vtt",
  "application/x-subrip",
  ".vtt",
  ".srt",
];

interface VideoData {
  url: string;
  tabId: number;
  contentType: string;
  detectedBy: string;
  originUrl: string;
  timestamp: number;
  /** webRequest frame id when known; older session records may omit this. */
  frameId?: number;
  headers?: Record<string, string>;
  subtitles?: string[];
  subtitlePreview?: string;
  qualities?: unknown[];
}

interface StoredHeaders {
  headers: Record<string, string>;
  tabId: number;
  timestamp: number;
}

const tabVideos = new Map<number, VideoData[]>();
const tabSeenUrls = new Map<number, Set<string>>();
const tabHeadersCaptured = new Map<number, Set<string>>();
const requestHeadersMap = new Map<string, StoredHeaders>();

// Some requests (iframe/worker-initiated, esp. under Fission site isolation)
// surface in webRequest with tabId -1. Videos filed under -1 are invisible to
// the popup, which queries the real (active) tab. We recover the real tab two
// ways: (1) the SAME url usually also fires onBeforeRequest WITH a real tabId,
// so we keep a short-lived url→tab map; (2) fall back to the active tab. This
// mirrors the phone, which resolves a -1 request via originUrl / selected tab.
const urlToTab = new Map<string, { tabId: number; ts: number }>();
const URL_TAB_TTL_MS = 60_000;
let activeTabId = -1;

function rememberUrlTab(url: string, tabId: number) {
  if (tabId >= 0) urlToTab.set(url, { tabId, ts: Date.now() });
}
function resolveTabId(rawTabId: number, url: string): number {
  if (rawTabId >= 0) return rawTabId;
  const mapped = urlToTab.get(url);
  if (mapped && Date.now() - mapped.ts < URL_TAB_TTL_MS) return mapped.tabId;
  return activeTabId; // best-effort: the focused tab (may still be -1 if unknown)
}
function refreshActiveTab() {
  browser.tabs.query({ active: true, currentWindow: true })
    .then((tabs) => { if (tabs[0]?.id != null) activeTabId = tabs[0].id; })
    .catch(() => {});
}
refreshActiveTab();
browser.tabs.onActivated.addListener((info) => {
  activeTabId = info.tabId;
  updateBadge(info.tabId);
});
if (browser.windows?.onFocusChanged) {
  browser.windows.onFocusChanged.addListener(() => refreshActiveTab());
}

const CLEANUP_INTERVAL_MS = 30_000;
const HEADER_TTL_MS = 60_000;

setInterval(() => {
  const now = Date.now();
  for (const [id, data] of requestHeadersMap.entries()) {
    if (now - data.timestamp > HEADER_TTL_MS) requestHeadersMap.delete(id);
  }
  for (const [u, data] of urlToTab.entries()) {
    if (now - data.ts > URL_TAB_TTL_MS) urlToTab.delete(u);
  }
}, CLEANUP_INTERVAL_MS);

function getTabVideos(tabId: number): VideoData[] {
  if (!tabVideos.has(tabId)) tabVideos.set(tabId, []);
  return tabVideos.get(tabId)!;
}
function getTabSeenUrls(tabId: number): Set<string> {
  if (!tabSeenUrls.has(tabId)) tabSeenUrls.set(tabId, new Set());
  return tabSeenUrls.get(tabId)!;
}
function getTabHeadersCaptured(tabId: number): Set<string> {
  if (!tabHeadersCaptured.has(tabId)) tabHeadersCaptured.set(tabId, new Set());
  return tabHeadersCaptured.get(tabId)!;
}
function cleanupTab(tabId: number) {
  tabVideos.delete(tabId);
  tabSeenUrls.delete(tabId);
  tabHeadersCaptured.delete(tabId);
  updateBadge(tabId); // clears the count back to empty
  persistVideos(tabId); // drops the persisted copy too
}

// ── MV3 service-worker survival ──────────────────────────────────────────────
// Chrome suspends the background service worker after ~30s idle, wiping the
// in-memory Maps — so the popup would see no detections from a previous SW
// lifetime. Persist per-tab detections to storage.session (in-memory, cleared
// when the browser closes) and rehydrate on wake. Firefox MV2 uses a persistent
// background page and older Firefox lacks storage.session, so this no-ops there.
const sessionStore: any = (browser.storage as any)?.session;
const vkey = (tabId: number) => `pb_videos_${tabId}`;

async function clearPersistedDetections(): Promise<void> {
  if (!sessionStore) return;
  try {
    const all = await sessionStore.get(null);
    const keys = Object.keys(all ?? {}).filter((key) =>
      key.startsWith("pb_videos_"),
    );
    if (keys.length > 0) await sessionStore.remove(keys);
  } catch {
    /* session storage is best-effort */
  }
}

async function clearAllDetectionState(): Promise<void> {
  const tabIds = [...tabVideos.keys()];
  tabVideos.clear();
  tabSeenUrls.clear();
  tabHeadersCaptured.clear();
  requestHeadersMap.clear();
  urlToTab.clear();
  for (const tabId of tabIds) updateBadge(tabId);
  await clearPersistedDetections();
}

function persistVideos(tabId: number) {
  if (!sessionStore || tabId < 0) return;
  const v = tabVideos.get(tabId);
  try {
    if (v && v.length) sessionStore.set({ [vkey(tabId)]: v });
    else sessionStore.remove(vkey(tabId));
  } catch (_) {}
}

// Resolves once the in-memory Maps have been re-merged from storage.session.
// getVideos awaits this so a just-woken SW doesn't report an empty list.
const hydrated: Promise<void> = (async () => {
  await dataConsentReady;
  if (!canHandleMediaData()) {
    await clearPersistedDetections();
    return;
  }
  if (!sessionStore) return;
  try {
    const all = await sessionStore.get(null);
    for (const [k, v] of Object.entries(all ?? {})) {
      if (!k.startsWith("pb_videos_") || !Array.isArray(v)) continue;
      const tabId = Number(k.slice("pb_videos_".length));
      if (!Number.isFinite(tabId)) continue;
      const existing = getTabVideos(tabId);
      const seen = getTabSeenUrls(tabId);
      for (const item of v as VideoData[]) {
        if (!seen.has(item.url)) {
          existing.push(item);
          seen.add(item.url);
        }
      }
    }
  } catch (_) {}
})();

// ── Toolbar badge: count of detected streams on a tab ────────────────────────
// MV3 Chrome exposes browser.action; MV2 Firefox exposes browser.browserAction.
const actionApi: any =
  (browser as any).action ?? (browser as any).browserAction;
try {
  // Match the popup theme: --accent (#D0BCFF) with near-black text (--bg-primary).
  actionApi?.setBadgeBackgroundColor?.({ color: "#D0BCFF" });
  actionApi?.setBadgeTextColor?.({ color: "#1C1B1F" });
} catch (_) {}

function updateBadge(tabId: number) {
  if (!actionApi || tabId < 0) return;
  const n = tabVideos.get(tabId)?.length ?? 0;
  try {
    actionApi.setBadgeText({ tabId, text: n > 0 ? String(n) : "" }).catch(() => {});
  } catch (_) {}
}

browser.tabs.onRemoved.addListener((tabId) => cleanupTab(tabId));

function handleCommittedNavigation(details: { frameId: number; tabId: number; url: string }) {
  if (details.frameId !== 0) return;
  cleanupTab(details.tabId);
}

function handleHistoryStateUpdated(details: { frameId: number; tabId: number; url: string }) {
  if (details.frameId !== 0) return;
  // A same-document SPA navigation may keep the existing player alive without
  // issuing its media request again. Retain detections and let the content
  // script re-evaluate the current DOM instead of requiring a page refresh.
  browser.tabs.sendMessage(details.tabId, { type: "overlay_navigation" }).catch(() => {});
}

if (browser.webNavigation) {
  browser.webNavigation.onCommitted.addListener(handleCommittedNavigation);
  browser.webNavigation.onHistoryStateUpdated.addListener(handleHistoryStateUpdated);
}

function notifyContentScript(video: VideoData, tabId: number, headers: Record<string, string> | null) {
  const hasHeaders = headers && Object.keys(headers).length > 0;
  const seenUrls = getTabSeenUrls(tabId);
  const headersCaptured = getTabHeadersCaptured(tabId);
  const videos = getTabVideos(tabId);
  const existing = videos.find((candidate) => candidate.url === video.url);
  const upgradesDomDetection =
    existing?.detectedBy === "dom_source" && video.detectedBy !== "dom_source";

  if (seenUrls.has(video.url)) {
    if (!upgradesDomDetection && (headersCaptured.has(video.url) || !hasHeaders)) return;
  }

  seenUrls.add(video.url);
  if (seenUrls.size > 500) seenUrls.delete(seenUrls.keys().next().value!);

  if (hasHeaders) {
    video.headers = headers!;
    headersCaptured.add(video.url);
    if (headersCaptured.size > 500) headersCaptured.delete(headersCaptured.keys().next().value!);
  }

  const idx = videos.findIndex((v) => v.url === video.url);
  if (idx !== -1) videos[idx] = { ...videos[idx], ...video };
  else {
    videos.push(video);
    if (videos.length > 50) videos.shift();
  }
  plog("  → reported to tab", tabId, "(tab now has", videos.length, "videos):", short(video.url));
  updateBadge(tabId);
  persistVideos(tabId);

  // Notify an open popup (if any) so it can refresh its list live.
  browser.runtime.sendMessage({ type: "video_detected" }).catch(() => {});
  // runtime.sendMessage is not a reliable way to target content scripts.
  // Wake every frame in the tab: Firefox/Fission can attribute MSE requests to
  // a different webRequest frame than the frame containing the <video>. The
  // notification carries no media data; getVideos performs the scoped lookup.
  if (tabId >= 0) {
    browser.tabs.sendMessage(tabId, { type: "video_detected" }).catch(() => {});
  }
}

function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, { ...init, signal: controller.signal }).finally(() => clearTimeout(timer));
}

// Merge enrichment (qualities / subtitle preview) into an already-reported video
// and rebroadcast. Bypasses the dedup guard in notifyContentScript, which would
// otherwise drop this follow-up because the URL was already seen.
function updateStoredVideo(video: VideoData, tabId: number) {
  const videos = getTabVideos(tabId);
  const idx = videos.findIndex((v) => v.url === video.url);
  if (idx !== -1) videos[idx] = { ...videos[idx], ...video };
  persistVideos(tabId);
  browser.runtime.sendMessage({ type: "video_detected" }).catch(() => {});
  if (tabId >= 0) {
    browser.tabs.sendMessage(tabId, { type: "video_detected" }).catch(() => {});
  }
}

function processAndNotifyVideo(videoData: VideoData, tabId: number, headers: Record<string, string> | null) {
  // Report the video IMMEDIATELY. Detection must never be gated on a follow-up
  // fetch (playlist parse / subtitle preview): origins that stall requests
  // missing Referer/Origin would hang that fetch forever and the video would
  // never be reported. Enrichment is sent as a non-blocking follow-up update.
  // This mirrors the phone, which reports on webRequest and parses lazily after.
  notifyContentScript(videoData, tabId, headers);

  if (videoData.url.toLowerCase().includes("m3u8")) {
    HlsParser.parsePlaylist(videoData.url, headers ?? undefined)
      .then((playlist) => {
        if (playlist.videoQualities.length > 0) {
          videoData.qualities = playlist.videoQualities;
          updateStoredVideo(videoData, tabId);
        }
      })
      .catch(() => {});
  } else if (
    videoData.detectedBy === "subtitle_extension" ||
    videoData.url.endsWith(".srt") ||
    videoData.url.endsWith(".vtt")
  ) {
    fetchWithTimeout(videoData.url, { method: "GET", headers: { Range: "bytes=0-1500" } }, 8000)
      .then((r) => r.text())
      .then((text) => {
        const lines = text.split(/\r?\n/);
        const validLines: string[] = [];
        for (const line of lines) {
          const l = line.trim();
          if (!l || l === "WEBVTT" || l.startsWith("Kind:") || l.startsWith("Language:")) continue;
          if (/^\d+$/.test(l) || l.includes("-->")) continue;
          const clean = l.replace(/<[^>]+>/g, "").trim();
          if (clean) validLines.push(clean);
          if (validLines.length >= 6) break;
        }
        if (validLines.length > 0) {
          videoData.subtitlePreview = validLines.join(" • ") + "...";
          updateStoredVideo(videoData, tabId);
        }
      })
      .catch(() => {});
  }
}

// ==================== PlayBridge desktop bridge ====================
// Casting goes through the desktop app via the native-messaging host: the app
// holds the pinned wss link to the TV and owns TV discovery/pairing/selection.
// The extension just hands it a URL + the request headers it captured.

function bridgeStatusLabel(): "disconnected" | "connecting" | "connected" {
  const s = bridge.getState();
  if (!s.desktopConnected) return "disconnected";
  return s.activeTv ? "connected" : "connecting";
}

function broadcastStatus() {
  const s = bridge.getState();
  const msg = {
    type: "ws_status_update",
    status: bridgeStatusLabel(),
    activeTv: s.activeTv,
    devices: s.devices,
  };
  browser.runtime.sendMessage(msg).catch(() => {});
  browser.tabs.query({}).then((tabs) => {
    for (const tab of tabs) {
      if (tab.id) browser.tabs.sendMessage(tab.id, msg).catch(() => {});
    }
  });
}

bridge.onState(() => broadcastStatus());

function broadcastDataConsent(): void {
  const status = dataConsentStatus(
    DATA_CONSENT_REQUIRED,
    dataConsentGranted,
  );
  const message = { type: "data_consent_changed", status };
  browser.runtime.sendMessage(message).catch(() => {});
  browser.tabs.query({}).then((tabs) => {
    for (const tab of tabs) {
      if (tab.id != null) {
        browser.tabs.sendMessage(tab.id, message).catch(() => {});
      }
    }
  });
}

async function applyDataConsentState(granted: boolean): Promise<void> {
  const next = !DATA_CONSENT_REQUIRED || granted;
  if (next === dataConsentGranted) {
    if (!next) await clearAllDetectionState();
    return;
  }
  dataConsentGranted = next;
  if (!next) await clearAllDetectionState();
  broadcastDataConsent();
}

async function openDataConsentPage(): Promise<void> {
  if (!DATA_CONSENT_REQUIRED) return;
  const url = browser.runtime.getURL("ui/consent.html");
  try {
    const tabs = await browser.tabs.query({});
    const existing = tabs.find((tab) => tab.url === url);
    if (existing?.id != null) {
      await browser.tabs.update(existing.id, { active: true });
      if (existing.windowId != null) {
        await browser.windows.update(existing.windowId, { focused: true });
      }
      return;
    }
  } catch {
    /* create the tab below */
  }
  await browser.tabs.create({ url });
}

function isTrustedExtensionPage(
  sender: browser.Runtime.MessageSender,
): boolean {
  const extensionRoot = browser.runtime.getURL("");
  return (
    typeof sender.url === "string" &&
    sender.url.startsWith(extensionRoot)
  );
}

browser.storage.onChanged.addListener((changes, area) => {
  if (!DATA_CONSENT_REQUIRED || area !== "local") return;
  if (!(DATA_CONSENT_KEY in changes)) return;
  const granted =
    changes[DATA_CONSENT_KEY].newValue === DATA_CONSENT_VERSION;
  void applyDataConsentState(granted);
});

void dataConsentReady.then(() => broadcastDataConsent());

/**
 * Stream title for the TV: prefer the website/tab title, never the raw URL
 * basename unless the tab title is unavailable.
 */
async function resolveStreamTitle(
  tabId: number | undefined,
  fallbackUrl: string,
  preferred?: string | null,
): Promise<string> {
  const fromPreferred = preferred?.trim();
  if (fromPreferred) return fromPreferred;

  if (tabId != null && tabId >= 0) {
    try {
      const tab = await browser.tabs.get(tabId);
      const fromTab = tab?.title?.trim();
      if (fromTab) return fromTab;
    } catch {
      /* tab may already be closed */
    }
  }

  // Last resort only — better than an empty title on the TV.
  return fallbackUrl.split("/").pop() || fallbackUrl;
}

function castVideo(
  video: VideoData,
  titleHint?: string | null,
): Promise<{ ok: boolean; error?: string }> {
  return resolveStreamTitle(video.tabId, video.url, titleHint).then((title) =>
    bridge.cast(video.url, video.headers ?? {}, title, {
      detectedBy: video.detectedBy,
      contentType: video.contentType,
    }),
  );
}

function videoHasReplayHeaders(video: VideoData): boolean {
  return !!(video.headers && Object.keys(video.headers).length > 0);
}

function isPlayableCastRecord(video: VideoData): boolean {
  return (
    !!video.url &&
    !isBlobOrDataUrl(video.url) &&
    !isExcludedMediaCandidate(video.url, video.detectedBy)
  );
}

/**
 * Overlay cast may resolve a page-origin DOM/get_file URL that has no replay
 * headers, while the popup list includes a CDN request (Cookie/Referer) for the
 * same player. Prefer a header-bearing network record from the tab when the
 * content-script hint is weak.
 */
function resolveOverlayCastVideo(
  preferred: VideoData,
  tabRecords: VideoData[],
  sender: browser.Runtime.MessageSender,
): VideoData {
  if (
    videoHasReplayHeaders(preferred) &&
    !isDomSourceDetection(preferred.detectedBy)
  ) {
    return preferred;
  }

  const toCandidate = (video: VideoData) => ({
    url: video.url,
    contentType: video.contentType,
    detectedBy: video.detectedBy,
    timestamp: video.timestamp,
    frameId: video.frameId,
    qualities: video.qualities,
    hasHeaders: videoHasReplayHeaders(video),
  });

  const playable = tabRecords.filter(isPlayableCastRecord);
  const frameMatched = playable.filter((video) =>
    videoMatchesSender(video, sender),
  );
  // Prefer the sender's frame, but if every frame-local hit is headerless fall
  // back to the full tab (CDN requests are often attributed elsewhere).
  const frameHasHeaders = frameMatched.some(videoHasReplayHeaders);
  const pool = frameHasHeaders || frameMatched.length === 0 ? frameMatched : playable;
  const searchPool = pool.length > 0 ? pool : playable;

  const ranked = rankMediaCandidate(searchPool.map(toCandidate), {
    domUrls: [preferred.url],
    senderFrameId:
      typeof sender.frameId === "number" && sender.frameId >= 0
        ? sender.frameId
        : undefined,
    preferredSince: preferred.timestamp,
  });
  if (ranked) {
    const match = searchPool.find((video) => video.url === ranked.url);
    if (match && videoHasReplayHeaders(match)) return match;
  }

  const headerBearing = searchPool
    .filter(
      (video) =>
        videoHasReplayHeaders(video) &&
        !isDomSourceDetection(video.detectedBy),
    )
    .sort((left, right) => right.timestamp - left.timestamp);
  return headerBearing[0] ?? preferred;
}

// ==================== Request header capture ====================

browser.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    if (!canHandleMediaData()) return;
    if (details.method === "OPTIONS") return;
    const headers: Record<string, string> = {};
    const skip = ["host", "connection", "accept-encoding", "content-length", "upgrade-insecure-requests"];
    for (const h of details.requestHeaders ?? []) {
      if (!skip.includes(h.name.toLowerCase())) headers[h.name] = h.value ?? "";
    }
    if (Object.keys(headers).length > 0) {
      requestHeadersMap.set(details.requestId, { headers, tabId: details.tabId, timestamp: Date.now() });
    }
  },
  { urls: ["<all_urls>"] },
  // Firefox also exposes a `chrome` compatibility namespace, so checking for
  // globalThis.chrome misidentifies it as Chromium. filterResponseData is the
  // reliable capability distinction used by this extension's MV2 build.
  (FILTER_AVAILABLE
    ? ["requestHeaders"]
    : ["requestHeaders", "extraHeaders"]) as any,
);

// Visibility probe: log every request the extension can see whose URL looks
// stream-ish, BEFORE any filtering. If your m3u8 never appears here, the request
// isn't reaching the extension at all (e.g. a sandboxed/cross-origin iframe).
browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (!canHandleMediaData()) return;
    // Record the real tab for this URL so a later tabId:-1 sighting can be mapped back.
    rememberUrlTab(details.url, details.tabId);
    const u = details.url.toLowerCase();
    if (u.includes("m3u8") || u.includes(".mpd") || u.includes(".ts") || u.includes("video")) {
      plog("onBeforeRequest:", short(details.url),
        "| type:", details.type, "| frameId:", details.frameId, "| tabId:", details.tabId);
    }
  },
  { urls: ["<all_urls>"] },
);

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (!canHandleMediaData()) return;
    // Resolve a real tab for tabId:-1 sightings (iframe/worker-initiated requests)
    // so detections aren't filed under -1 where the popup can't see them.
    const tabId = resolveTabId(details.tabId, details.url);
    if (details.url.toLowerCase().includes("m3u8") || details.url.toLowerCase().includes(".mpd")) {
      plog("onHeadersReceived:", short(details.url),
        "| type:", details.type, "| frameId:", details.frameId,
        "| rawTabId:", details.tabId, "| resolvedTabId:", tabId, "| status:", details.statusCode);
    }
    const ctHeader = details.responseHeaders?.find((h) => h.name.toLowerCase() === "content-type");
    // Default to "" (not "unknown") and DON'T bail when the header is absent:
    // many HLS/live-stream origins omit Content-Type entirely, and the URL-pattern
    // + #EXTM3U body-sniff checks below must still run for those. (The phone
    // extension has the same comment — bailing here is the bug that made Firefox
    // miss headerless m3u8/mpd streams the phone detects.)
    const contentType = ctHeader?.value?.toLowerCase() ?? "";
    const stored = requestHeadersMap.get(details.requestId);

    const captured = getTabHeadersCaptured(tabId);
    const seen = getTabSeenUrls(tabId);
    if (seen.has(details.url) && (captured.has(details.url) || !stored)) {
      if (details.url.toLowerCase().includes("m3u8")) plog("  skip (already seen / no new headers):", short(details.url));
      if (stored) requestHeadersMap.delete(details.requestId);
      return;
    }

    const urlLower = details.url.toLowerCase().split("?")[0];
    const isSegment = [".ts", ".m4s"].some((ext) => urlLower.endsWith(ext)) ||
      urlLower.includes("/segment") || urlLower.includes("frag");
    if (isSegment) {
      if (details.url.toLowerCase().includes("m3u8")) plog("  skip (matched isSegment .ts/.m4s/segment/frag):", short(details.url));
      if (stored) requestHeadersMap.delete(details.requestId); return;
    }

    const isVideoContentType = VIDEO_CONTENT_TYPES.some((t) => contentType.includes(t));
    const isM3u8Url = details.url.toLowerCase().includes("m3u8");
    // DASH manifests are often served as text/xml or octet-stream, which the
    // content-type list misses — match the URL like we do for m3u8.
    const isMpdUrl = urlLower.endsWith(".mpd") || details.url.toLowerCase().includes(".mpd?");
    const hasVideoExt = [".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".m4v", ".wmv", ".3gp"]
      .some((ext) => urlLower.endsWith(ext));
    const hasSubExt = [".vtt", ".srt"].some((ext) => urlLower.endsWith(ext));
    const isVideoExtMatch = hasVideoExt && (
      contentType.includes("octet-stream") || contentType.includes("binary") || !contentType
    );

    let isVideo = false;
    let detectedBy = "unknown";
    if (isVideoContentType) { isVideo = true; detectedBy = "content_type"; }
    else if (isM3u8Url) { isVideo = true; detectedBy = "url_pattern_m3u8"; }
    else if (isMpdUrl) { isVideo = true; detectedBy = "url_pattern_mpd"; }
    else if (isVideoExtMatch || hasVideoExt) { isVideo = true; detectedBy = "url_extension"; }
    else if (hasSubExt) { isVideo = true; detectedBy = "subtitle_extension"; }

    // Prefer the request's frameId when the browser provided a real tab; for
    // tabId:-1 recoveries we still record frameId when present so content
    // scripts in that frame can associate detections later.
    const frameId =
      typeof details.frameId === "number" && details.frameId >= 0
        ? details.frameId
        : undefined;

    if (isVideo) {
      plog("  ✓ DETECTED:", detectedBy, "| ct:", contentType || "(none)", "| tabId:", tabId, "| frameId:", frameId, "|", short(details.url));
      processAndNotifyVideo(
        {
          url: details.url,
          tabId,
          contentType,
          detectedBy,
          originUrl: details.originUrl ?? "",
          timestamp: Date.now(),
          frameId,
        },
        tabId,
        stored?.headers ?? null,
      );
    } else {
      const skipTypes = ["image", "font", "stylesheet", "script"];
      const sniffable = details.statusCode === 200 && !skipTypes.includes(details.type) && FILTER_AVAILABLE;
      if (details.url.toLowerCase().includes("m3u8")) {
        plog("  not matched by ct/url; body-sniff", sniffable ? "ENABLED" : "SKIPPED",
          "(status:", details.statusCode, "type:", details.type, "filter:", FILTER_AVAILABLE + ")");
      }
      if (sniffable) {
        try {
          const filter = (browser.webRequest as any).filterResponseData(details.requestId);
          const decoder = new TextDecoder("utf-8");
          let checked = false;
          let acc = "";
          filter.ondata = (ev: any) => {
            filter.write(ev.data);
            if (checked) return;
            acc += decoder.decode(ev.data, { stream: true });
            if (acc.length >= 7) {
              checked = true;
              if (acc.trim().startsWith("#EXTM3U")) {
                plog("  ✓ DETECTED via body sniff (#EXTM3U):", short(details.url));
                processAndNotifyVideo(
                  {
                    url: details.url,
                    tabId,
                    contentType,
                    detectedBy: "body_content_m3u8",
                    originUrl: details.originUrl ?? "",
                    timestamp: Date.now(),
                    frameId,
                  },
                  tabId,
                  stored?.headers ?? null,
                );
              }
              filter.disconnect();
            }
          };
          filter.onstop = () => { try { filter.disconnect(); } catch (_) {} };
          filter.onerror = () => {};
        } catch (e) { plog("  filterResponseData threw:", (e as Error)?.message); }
      }
    }

    if (stored) requestHeadersMap.delete(details.requestId);
  },
  { urls: ["<all_urls>"] },
  // Blocking (needed for filterResponseData) is Firefox-only; Chrome MV3 observes.
  (FILTER_AVAILABLE ? ["responseHeaders", "blocking"] : ["responseHeaders"]) as any,
);

// ==================== Runtime message handler ====================

/** Strip sensitive fields before sending detection records to content scripts. */
function publicVideoView(
  v: VideoData,
): Omit<VideoData, "headers"> & { hasHeaders: boolean } {
  const { headers: _headers, ...rest } = v;
  return {
    ...rest,
    // Boolean only — never expose Cookie/Referer/Authorization values.
    hasHeaders: !!(_headers && Object.keys(_headers).length > 0),
  };
}

function domSourceContentType(url: string): string {
  const path = url.toLowerCase().split(/[?#]/)[0] ?? "";
  if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
  if (path.endsWith(".mpd")) return "application/dash+xml";
  if (path.endsWith(".webm")) return "video/webm";
  if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "video/mp4";
  return "video/unknown";
}

function validatedDomSourceUrls(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const result = new Set<string>();
  for (const item of value.slice(0, 16)) {
    if (typeof item !== "string" || item.length === 0 || item.length > 8192) continue;
    try {
      const parsed = new URL(item);
      if (!/^https?:$/.test(parsed.protocol)) continue;
      const path = parsed.pathname.toLowerCase();
      if (/\.(?:vtt|srt|ts|m4s)$/.test(path)) continue;
      result.add(parsed.href);
    } catch {
      /* ignore malformed page URLs */
    }
  }
  return [...result];
}

function hasSameWebOrigin(left: string | undefined, right: string | undefined): boolean {
  if (!left || !right) return false;
  try {
    const a = new URL(left);
    const b = new URL(right);
    if (!/^https?:$/.test(a.protocol) || !/^https?:$/.test(b.protocol)) return false;
    return a.origin === b.origin;
  } catch {
    return false;
  }
}

function videoMatchesSender(v: VideoData, sender: browser.Runtime.MessageSender): boolean {
  const frameId = sender.frameId;
  if (typeof frameId !== "number" || frameId < 0) return true;
  if (typeof v.frameId !== "number" || v.frameId === frameId) return true;
  // Firefox may attribute worker/MSE requests to the top frame despite the
  // player living in a same-origin child frame. Origin is a bounded fallback;
  // cross-origin records remain excluded.
  return hasSameWebOrigin(v.originUrl, sender.url);
}

function videosForSender(
  sender: browser.Runtime.MessageSender,
  options: { preferFrame?: boolean } = {},
): VideoData[] {
  const tabId = sender.tab?.id;
  if (tabId == null || tabId < 0) return [];
  const all = tabVideos.get(tabId) ?? [];
  if (!options.preferFrame) return all;
  const frameId = sender.frameId;
  if (typeof frameId !== "number" || frameId < 0) return all;
  // Exact-frame and legacy unframed records pass directly. Same-origin records
  // cover Firefox's MSE/Fission frame-attribution mismatch.
  return all.filter((v) => videoMatchesSender(v, sender));
}

const CONSENT_GATED_ACTIONS = new Set([
  "getCurrentTabUrl",
  "wsPlayOnTv",
  "castDetectedVideo",
  "wsSendToTv",
]);

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const msg = message as Record<string, unknown>;

  if (msg.action === "getDataConsent") {
    dataConsentReady.then(() =>
      sendResponse(
        dataConsentStatus(DATA_CONSENT_REQUIRED, dataConsentGranted),
      ),
    );
    return true;
  }

  if (msg.action === "setDataConsent") {
    if (!isTrustedExtensionPage(sender) || typeof msg.granted !== "boolean") {
      sendResponse({ success: false, reason: "Invalid consent request" });
      return true;
    }
    setDataConsentGranted(browser.storage.local, msg.granted)
      .then(() => applyDataConsentState(msg.granted as boolean))
      .then(() =>
        sendResponse({
          success: true,
          status: dataConsentStatus(
            DATA_CONSENT_REQUIRED,
            dataConsentGranted,
          ),
        }),
      )
      .catch(() =>
        sendResponse({ success: false, reason: "Could not save consent" }),
      );
    return true;
  }

  if (msg.action === "openDataConsent") {
    if (!isTrustedExtensionPage(sender)) {
      sendResponse({ opened: false });
      return true;
    }
    openDataConsentPage()
      .then(() => sendResponse({ opened: true }))
      .catch(() => sendResponse({ opened: false }));
    return true;
  }

  if (msg.action === "getOverlayPreferences") {
    const requestedTabId =
      typeof msg.tabId === "number" && msg.tabId >= 0 ? msg.tabId : undefined;
    const resolveTabUrl = async (): Promise<string | undefined> => {
      if (sender.tab?.url) return sender.tab.url;
      if (requestedTabId != null) {
        try {
          return (await browser.tabs.get(requestedTabId)).url;
        } catch {
          return undefined;
        }
      }
      try {
        const tabs = await browser.tabs.query({
          active: true,
          currentWindow: true,
        });
        return tabs[0]?.url;
      } catch {
        return undefined;
      }
    };

    dataConsentReady
      .then(async () => {
        if (!canHandleMediaData()) {
          const preferences = await getVideoCastOverlayPreferences(
            browser.storage.local,
            null,
          );
          return {
            ...preferences,
            enabled: false,
            siteKey: null,
            consentRequired: true,
          };
        }
        const url = await resolveTabUrl();
        return getVideoCastOverlayPreferences(
          browser.storage.local,
          siteKeyFromUrl(url),
        );
      })
      .then(sendResponse)
      .catch(() =>
        getVideoCastOverlayPreferences(browser.storage.local, null).then(
          (preferences) =>
            sendResponse({ ...preferences, enabled: false, siteKey: null }),
        ),
      );
    return true;
  }

  if (
    DATA_CONSENT_REQUIRED &&
    !canHandleMediaData() &&
    CONSENT_GATED_ACTIONS.has(String(msg.action))
  ) {
    sendResponse({
      success: false,
      reason: "Media access consent required",
      consentRequired: true,
    });
    return true;
  }

  if (msg.action === "getVideos") {
    // Content scripts must not choose arbitrary tab/frame IDs. Popup/extension
    // pages have no sender.tab and may pass an explicit tabId for the active tab.
    const fromContent = sender.tab?.id != null;
    const tabId = fromContent
      ? sender.tab!.id!
      : ((msg.tabId ?? sender.tab?.id) as number | undefined);
    // Await rehydration so a freshly-woken MV3 service worker doesn't reply with
    // an empty list before storage.session has been merged back in.
    hydrated.then(() => {
      if (!canHandleMediaData()) {
        sendResponse({ videos: [], count: 0, consentRequired: true });
        return;
      }
      let videos: VideoData[];
      if (fromContent) {
        videos = videosForSender(sender, { preferFrame: true });
        // Never expose captured auth headers to page-isolated content scripts.
        sendResponse({
          videos: videos.map(publicVideoView),
          count: videos.length,
          frameId: sender.frameId,
          tabId,
        });
      } else {
        videos = tabId != null ? (tabVideos.get(tabId) ?? []) : [];
        plog("getVideos query for tabId", tabId, "→", videos.length, "videos.",
          "All tabs with videos:", [...tabVideos.entries()].map(([t, v]) => `${t}:${v.length}`).join(", ") || "(none)");
        sendResponse({ videos, count: videos.length });
      }
    });
    return true; // async response
  }

  if (msg.action === "observeDomVideoSources") {
    if (!canHandleMediaData()) {
      sendResponse({ registered: 0, consentRequired: true });
      return true;
    }
    const tabId = sender.tab?.id;
    if (tabId == null || tabId < 0) {
      sendResponse({ registered: 0 });
      return true;
    }
    const frameId =
      typeof sender.frameId === "number" && sender.frameId >= 0
        ? sender.frameId
        : undefined;
    const urls = validatedDomSourceUrls(msg.urls);
    for (const url of urls) {
      notifyContentScript(
        {
          url,
          tabId,
          contentType: domSourceContentType(url),
          detectedBy: "dom_source",
          originUrl: sender.url ?? "",
          timestamp: Date.now(),
          frameId,
        },
        tabId,
        null,
      );
    }
    sendResponse({ registered: urls.length });
    return true;
  }

  if (msg.action === "clearVideos") {
    const tabId = (msg.tabId ?? sender.tab?.id) as number | undefined;
    if (tabId != null) {
      cleanupTab(tabId);
      sendResponse({ cleared: true });
    } else {
      clearAllDetectionState().then(() => sendResponse({ cleared: true }));
    }
    return true;
  }

  if (msg.action === "getCurrentTabUrl") {
    if (sender.tab?.url) { sendResponse({ url: sender.tab.url }); return true; }
    browser.tabs.query({ active: true, currentWindow: true }).then((tabs) =>
      sendResponse({ url: tabs[0]?.url ?? null })
    ).catch(() => sendResponse({ url: null }));
    return true;
  }

  if (msg.action === "wsGetStatus") {
    const s = bridge.getState();
    sendResponse({
      status: bridgeStatusLabel(),
      desktopConnected: s.desktopConnected,
      activeTv: s.activeTv,
      devices: s.devices,
    });
    return true;
  }

  if (msg.action === "wsConnect") {
    // TV selection/pairing is owned by the desktop app; just (re)connect the
    // bridge and pull fresh state.
    bridge.refresh();
    sendResponse({ connecting: true });
    return true;
  }

  if (msg.action === "wsDisconnect") {
    // Disconnecting from a TV is done in the desktop app; nothing to do here.
    sendResponse({ disconnected: true });
    return true;
  }

  if (msg.action === "wsPlayOnTv") {
    // Popup path: may pass a full video object (including quality-selected URL).
    // Content-script path: only accept a URL identifier; headers always come
    // from the stored detection record for the sender's tab.
    const fromContent = sender.tab?.id != null;
    if (fromContent) {
      const url = typeof msg.url === "string" ? msg.url : (msg.video as VideoData | undefined)?.url;
      if (!url || typeof url !== "string") {
        sendResponse({ success: false, reason: "Video not found" });
        return true;
      }
      const tabVideosList = getTabVideos(sender.tab!.id!);
      const stored = tabVideosList.find((v) => v.url === url);
      if (!stored) {
        sendResponse({ success: false, reason: "Video not found" });
        return true;
      }
      // Prefer the live tab title from sender (website title).
      const titleHint = sender.tab?.title;
      castVideo(stored, titleHint).then((r) =>
        sendResponse({ success: r.ok, reason: r.ok ? null : (r.error ?? "Not connected") }),
      );
      return true;
    }

    const tabId = (sender.tab?.id ?? msg.tabId) as number | undefined;
    const videos = tabId != null ? getTabVideos(tabId) : [];
    const video =
      (typeof msg.url === "string" ? videos.find((v) => v.url === msg.url) : undefined) ??
      (msg.video as VideoData | undefined);
    if (!video) {
      sendResponse({ success: false, reason: "Video not found" });
      return true;
    }
    // Prefer stored headers for the same URL when available (popup quality pick
    // may change the URL to a variant that still lacks a stored record).
    const stored =
      videos.find((v) => v.url === video.url) ??
      (video.tabId != null ? getTabVideos(video.tabId).find((v) => v.url === video.url) : undefined);
    const toCast: VideoData = stored
      ? { ...stored, ...video, headers: stored.headers ?? video.headers }
      : { ...video };
    if (msg.subtitleUrl) toCast.subtitles = [msg.subtitleUrl as string];

    // Popup messages have no sender.tab — use the video's tabId or the active tab
    // so the stream title is the website title, not a URL basename.
    const ensureTabId = async (): Promise<number | undefined> => {
      if (toCast.tabId != null && toCast.tabId >= 0) return toCast.tabId;
      if (tabId != null && tabId >= 0) {
        toCast.tabId = tabId;
        return tabId;
      }
      try {
        const tabs = await browser.tabs.query({ active: true, currentWindow: true });
        const id = tabs[0]?.id;
        if (id != null) toCast.tabId = id;
        return id;
      } catch {
        return undefined;
      }
    };

    ensureTabId()
      .then(() => castVideo(toCast))
      .then((r) =>
        sendResponse({ success: r.ok, reason: r.ok ? null : (r.error ?? "Not connected") }),
      );
    return true;
  }

  // Content-script cast: URL identifier only; tab/frame from sender; headers from store.
  if (msg.action === "castDetectedVideo") {
    const tabId = sender.tab?.id;
    if (tabId == null || tabId < 0) {
      sendResponse({ success: false, reason: "Invalid sender" });
      return true;
    }
    const url = msg.url;
    if (typeof url !== "string" || !url || url.startsWith("blob:") || url.startsWith("data:")) {
      sendResponse({ success: false, reason: "Invalid media URL" });
      return true;
    }
    const videos = getTabVideos(tabId);
    const stored =
      videos.find((v) => v.url === url && videoMatchesSender(v, sender)) ??
      videos.find((v) => v.url === url);
    if (!stored) {
      sendResponse({ success: false, reason: "Video not found" });
      return true;
    }
    // Content may point at a headerless DOM/get_file URL; upgrade to the
    // header-bearing network record the popup would cast for this player.
    const toCast = resolveOverlayCastVideo(stored, videos, sender);
    castVideo({ ...toCast, tabId }, sender.tab?.title).then((r) =>
      sendResponse({ success: r.ok, reason: r.ok ? null : (r.error ?? "Not connected") }),
    );
    return true;
  }

  if (msg.action === "wsSendToTv") {
    if (msg.target === "player") {
      const url = msg.url as string;
      const tabId = (sender.tab?.id ?? msg.tabId) as number | undefined;
      // Popup often has no sender.tab — resolve active tab for the website title.
      const resolveTabId = tabId != null && tabId >= 0
        ? Promise.resolve(tabId)
        : browser.tabs.query({ active: true, currentWindow: true })
            .then((tabs) => tabs[0]?.id)
            .catch(() => undefined);
      resolveTabId.then((resolvedTabId) =>
        resolveStreamTitle(resolvedTabId, url, sender.tab?.title).then((title) =>
          bridge.cast(url, {}, title).then((r) =>
            sendResponse({ success: r.ok, reason: r.ok ? null : (r.error ?? "Not connected") }),
          ),
        ),
      );
    } else {
      sendResponse({ success: false, reason: "Unsupported target" });
    }
    return true;
  }

  // Unhandled. The polyfill's onMessage listener type requires every path to
  // return `true` or a Promise (not `boolean`/void), so return true; nothing
  // here calls sendResponse, and the unused port is harmless.
  return true;
});

// ==================== Context menu ====================

// Firefox exposes this as `browser.menus` (with the "menus" permission); Chrome
// and the polyfill expose `browser.contextMenus`. Use whichever is present.
const menusApi: typeof browser.contextMenus =
  (browser as any).menus ?? browser.contextMenus;

function createContextMenus() {
  menusApi.create({ id: "playbridge-parent", title: "PlayBridge", contexts: ["all"] });
  menusApi.create({ id: "playbridge-play", parentId: "playbridge-parent", title: "Play on TV", contexts: ["link", "video", "audio"] });
  menusApi.create({ id: "playbridge-open", parentId: "playbridge-parent", title: "Open on TV", contexts: ["all"] });
}

// Create on install/update; removeAll first avoids duplicate-id errors when the
// MV3 service worker re-evaluates this file on wake.
browser.runtime.onInstalled.addListener(() => {
  menusApi.removeAll().then(createContextMenus).catch(() => {});
  void dataConsentReady.then(() => {
    if (DATA_CONSENT_REQUIRED && !dataConsentGranted) {
      return openDataConsentPage();
    }
  });
});

function handleContextMenuClick(
  info: browser.Menus.OnClickData,
  tab: browser.Tabs.Tab | undefined,
): void {
  if (!canHandleMediaData()) {
    browser.notifications.create("pb-consent-required", {
      type: "basic",
      iconUrl: "icon.png",
      title: "PlayBridge media access is off",
      message: "Review the media-data disclosure before casting.",
    });
    void openDataConsentPage();
    return;
  }
  const s = bridge.getState();
  if (!s.desktopConnected || !s.activeTv) {
    browser.notifications.create("pb-not-connected", {
      type: "basic",
      iconUrl: "icon.png",
      title: "PlayBridge",
      message: s.desktopConnected
        ? "No TV connected — open the PlayBridge app and pick a TV."
        : "PlayBridge desktop app is not running.",
    });
    return;
  }
  const url =
    info.menuItemId === "playbridge-play"
      ? (info.srcUrl ?? info.linkUrl)
      : (info.linkUrl ?? info.pageUrl ?? tab?.url);
  if (url) {
    const titleHint = tab?.title?.trim() || null;
    void resolveStreamTitle(tab?.id, url, titleHint).then((title) =>
      bridge.cast(url, {}, title),
    );
  }
}

menusApi.onClicked.addListener((info, tab) => {
  void dataConsentReady.then(() => handleContextMenuClick(info, tab));
});

// ==================== Startup ====================

// Connect to the desktop app on startup; the bridge auto-reconnects if the app
// isn't running yet.
bridge.connect();
