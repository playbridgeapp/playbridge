/**
 * Phone GeckoView video detector background.
 *
 * Shares pure detection / synthetic HLS logic with the Desktop store extension
 * via `../core/*`. Host-specific: native messaging to the Android app
 * (`browser.runtime.sendNativeMessage('browser', …)`).
 */

import browser from "./browser";
import { HlsParser } from "../core/hls-parser";
import {
  classifyHlsUrl,
  effectiveHlsRole,
  hlsIdentityKey,
  hlsStreamGroupKey,
  isExclusiveBootstrapMaster,
  isHlsUrl,
  pickCompanionAudio,
  playlistSessionId,
  type HlsRole,
  type MediaCandidate,
} from "../core/media-candidate";
import {
  buildSyntheticFromMasterBody,
  buildSyntheticFromObservations,
  observationSyntheticImproves,
  preferredSyntheticCastUrl,
  type SyntheticMasterResult,
} from "../core/synthetic-hls";

const NATIVE_APP_ID = "browser";
const DEBUG = false;

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
const VIDEO_EXTENSIONS = [
  ".mp4",
  ".mkv",
  ".webm",
  ".avi",
  ".mov",
  ".flv",
  ".m4v",
  ".wmv",
  ".3gp",
];
const SUBTITLE_EXTENSIONS = [".vtt", ".srt"];
const SEGMENT_OR_SUB_RE =
  /\.(?:vtt|srt|ts|m4s)(?:$|\?)|\/segment|frag(?:ment)?|\/chunks?\/|init[-_][^/]*\.mp4|seg[-_][^/]*\.mp4/i;

interface VideoData {
  url: string;
  tabId: number;
  contentType: string;
  detectedBy: string;
  originUrl: string;
  timestamp: number;
  headers?: Record<string, string>;
  hlsRole?: HlsRole;
  hlsIdentityKey?: string;
  hlsGroupKey?: string;
  hasSeparateAudio?: boolean;
  lastSeen?: number;
  audioUrl?: string;
  isSyntheticMaster?: boolean;
  syntheticPlaylist?: string;
  qualities?: unknown[];
  frameId?: number;
}

const tabVideos = new Map<number, VideoData[]>();
const tabSeenUrls = new Map<number, Set<string>>();
const tabHeadersCaptured = new Map<number, Set<string>>();
const requestHeadersMap = new Map<
  string,
  { headers: Record<string, string>; tabId: number; timestamp: number }
>();
const urlToTab = new Map<string, { tabId: number; ts: number }>();
const tabLastUrl = new Map<number, string>();

const URL_TAB_TTL_MS = 60_000;
const HEADER_TTL_MS = 60_000;

function plog(...args: unknown[]): void {
  if (DEBUG) console.log("[VideoDetector BG]", ...args);
}

function sendToNative(message: Record<string, unknown>): void {
  try {
    browser.runtime.sendNativeMessage(NATIVE_APP_ID, message).catch((e: Error) => {
      plog("sendNativeMessage failed:", e?.message);
    });
  } catch (e) {
    console.error("[VideoDetector BG] sendNativeMessage threw:", e);
  }
}

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

function cleanupTab(tabId: number): void {
  tabVideos.delete(tabId);
  tabSeenUrls.delete(tabId);
  tabHeadersCaptured.delete(tabId);
  tabLastUrl.delete(tabId);
}

function rememberUrlTab(url: string, tabId: number): void {
  if (typeof tabId === "number" && tabId >= 0) {
    urlToTab.set(url, { tabId, ts: Date.now() });
  }
}

function resolveTabId(rawTabId: number, url: string): number {
  if (typeof rawTabId === "number" && rawTabId >= 0) return rawTabId;
  const mapped = urlToTab.get(url);
  if (mapped && Date.now() - mapped.ts < URL_TAB_TTL_MS) return mapped.tabId;
  return rawTabId;
}

function detectionKey(url: string): string {
  return isHlsUrl(url) ? hlsIdentityKey(url) : url;
}

function annotateHlsFields(video: VideoData): VideoData {
  if (!isHlsUrl(video.url, video.contentType)) {
    return { ...video, hlsRole: video.hlsRole ?? "not_hls" };
  }
  const role =
    video.hlsRole && video.hlsRole !== "not_hls"
      ? video.hlsRole
      : classifyHlsUrl(video.url);
  return {
    ...video,
    hlsRole: role,
    hlsIdentityKey: video.hlsIdentityKey ?? hlsIdentityKey(video.url),
    hlsGroupKey: video.hlsGroupKey ?? hlsStreamGroupKey(video.url),
    lastSeen: video.lastSeen ?? video.timestamp,
  };
}

function toRankable(video: VideoData): MediaCandidate {
  return {
    url: video.url,
    contentType: video.contentType,
    detectedBy: video.detectedBy,
    timestamp: video.timestamp,
    frameId: video.frameId,
    qualities: video.qualities,
    hasHeaders: !!(video.headers && Object.keys(video.headers).length > 0),
    hlsRole: video.hlsRole,
    hlsIdentityKey: video.hlsIdentityKey,
    hlsGroupKey: video.hlsGroupKey,
    hasSeparateAudio: video.hasSeparateAudio,
    lastSeen: video.lastSeen,
    audioUrl: video.audioUrl,
    isSyntheticMaster: video.isSyntheticMaster,
    syntheticPlaylist: video.syntheticPlaylist,
  };
}

function findVideoByIdentity(
  videos: VideoData[],
  url: string,
): VideoData | undefined {
  const key = detectionKey(url);
  return videos.find(
    (c) =>
      detectionKey(c.url) === key ||
      c.hlsIdentityKey === key ||
      c.url === url,
  );
}

function findSyntheticForGroup(
  tabId: number,
  groupKey: string | undefined,
): VideoData | undefined {
  if (!groupKey) return undefined;
  return getTabVideos(tabId).find(
    (v) =>
      v.isSyntheticMaster &&
      (v.hlsGroupKey === groupKey || hlsStreamGroupKey(v.url) === groupKey),
  );
}

function attachCompanionAudioToGroup(
  tabId: number,
  groupKey: string | undefined,
): void {
  if (!groupKey) return;
  const members = getTabVideos(tabId).filter(
    (v) => (v.hlsGroupKey ?? hlsStreamGroupKey(v.url)) === groupKey,
  );
  const rankable = members.map(toRankable);
  for (const video of members) {
    const role = effectiveHlsRole(toRankable(video));
    if (role !== "video_media" && role !== "media") continue;
    const audio = pickCompanionAudio(toRankable(video), rankable);
    if (audio?.url && video.audioUrl !== audio.url) {
      video.audioUrl = audio.url;
      video.hasSeparateAudio = true;
    }
  }
}

function emitNativeVideo(video: VideoData): void {
  // Prefer synthetic cast URL + body when available for this group.
  let out = video;
  if (!video.isSyntheticMaster && video.hlsGroupKey) {
    const synth = findSyntheticForGroup(video.tabId, video.hlsGroupKey);
    if (synth) out = synth;
  }
  sendToNative({
    type: "video_detected",
    url: out.url,
    tabId: out.tabId,
    contentType: out.contentType,
    detectedBy: out.detectedBy,
    originUrl: out.originUrl,
    timestamp: out.timestamp,
    headers: out.headers ?? null,
    hlsRole: out.hlsRole,
    hlsGroupKey: out.hlsGroupKey,
    hasSeparateAudio: out.hasSeparateAudio ?? false,
    audioUrl: out.audioUrl ?? null,
    playlistBody: out.syntheticPlaylist ?? null,
    isSyntheticMaster: out.isSyntheticMaster ?? false,
  });
}

function installSyntheticMaster(
  tabId: number,
  synthetic: SyntheticMasterResult,
  base: {
    headers?: Record<string, string>;
    originUrl?: string;
    frameId?: number;
  },
): void {
  const now = Date.now();
  const existing = findSyntheticForGroup(tabId, synthetic.hlsGroupKey);
  const castUrl =
    preferredSyntheticCastUrl(synthetic) ??
    existing?.url ??
    synthetic.videoUrls[0];
  if (!castUrl) return;

  const record: VideoData = {
    url: castUrl,
    tabId,
    contentType: "application/vnd.apple.mpegurl",
    detectedBy: "synthetic_hls_master",
    originUrl: base.originUrl ?? existing?.originUrl ?? "",
    timestamp: existing?.timestamp ?? now,
    lastSeen: now,
    frameId: base.frameId ?? existing?.frameId,
    headers: base.headers ?? existing?.headers,
    hlsRole: "master",
    hlsGroupKey: synthetic.hlsGroupKey,
    hlsIdentityKey: `synthetic:${synthetic.hlsGroupKey}`,
    hasSeparateAudio: synthetic.hasSeparateAudio,
    qualities: synthetic.qualities,
    audioUrl: synthetic.audioUrls[0],
    isSyntheticMaster: true,
    syntheticPlaylist: synthetic.content,
  };

  const videos = getTabVideos(tabId);
  const idx = videos.findIndex(
    (v) => v.isSyntheticMaster && v.hlsGroupKey === synthetic.hlsGroupKey,
  );
  if (idx !== -1) {
    videos[idx] = {
      ...videos[idx],
      ...record,
      headers: record.headers ?? videos[idx].headers,
      timestamp: videos[idx].timestamp,
    };
  } else {
    videos.push(record);
    if (videos.length > 50) videos.shift();
  }
  getTabSeenUrls(tabId).add(record.hlsIdentityKey!);
  console.log(
    "[VideoDetector BG] synthetic master group",
    synthetic.hlsGroupKey.slice(-40),
    "videos:",
    synthetic.videoUrls.length,
    "audio:",
    synthetic.audioUrls.length,
  );
  emitNativeVideo(record);
}

function maybeSynthesizeFromObservations(
  tabId: number,
  groupKey: string | undefined,
): void {
  if (!groupKey) return;
  const members = getTabVideos(tabId).filter(
    (v) => (v.hlsGroupKey ?? hlsStreamGroupKey(v.url)) === groupKey,
  );
  const videos = members.filter((v) => {
    const role = effectiveHlsRole(toRankable(v));
    return (
      (role === "video_media" || role === "media") && playlistSessionId(v.url)
    );
  });
  const audios = members.filter(
    (v) => effectiveHlsRole(toRankable(v)) === "audio_media",
  );
  if (videos.length === 0) return;

  videos.sort(
    (a, b) => (b.lastSeen ?? b.timestamp) - (a.lastSeen ?? a.timestamp),
  );
  const session = playlistSessionId(videos[0]!.url);
  const sessionVideos = session
    ? videos.filter((v) => playlistSessionId(v.url) === session)
    : videos;
  const sessionAudios = session
    ? audios.filter((v) => playlistSessionId(v.url) === session)
    : audios;

  const master = members.find(
    (v) =>
      effectiveHlsRole(toRankable(v)) === "master" && !v.isSyntheticMaster,
  );

  // Gate: exclusive master present, or demuxed session A/V without reusable master.
  if (master) {
    if (
      !isExclusiveBootstrapMaster(
        master.url,
        effectiveHlsRole(toRankable(master)),
      )
    ) {
      return;
    }
  } else if (sessionAudios.length === 0) {
    return;
  }

  const synthetic = buildSyntheticFromObservations({
    groupKey,
    sourceMasterUrl: master?.url,
    videoUrls: sessionVideos.map((v) => v.url),
    audioUrls: sessionAudios.map((v) => v.url),
  });
  if (!synthetic) return;

  const existing = findSyntheticForGroup(tabId, groupKey);
  if (existing) {
    const existingVideos =
      existing.qualities
        ?.map((q) => (q as { url?: string }).url)
        .filter((u): u is string => typeof u === "string" && u.length > 0) ??
      (existing.url ? [existing.url] : []);
    const existingAudios = existing.audioUrl ? [existing.audioUrl] : [];
    if (
      !observationSyntheticImproves(existingVideos, existingAudios, synthetic)
    ) {
      return;
    }
  }

  const bestVideo =
    sessionVideos.find((v) => v.url === synthetic.videoUrls[0]) ??
    sessionVideos[0];
  installSyntheticMaster(tabId, synthetic, {
    headers: bestVideo?.headers ?? master?.headers,
    originUrl: bestVideo?.originUrl ?? master?.originUrl,
    frameId: bestVideo?.frameId ?? master?.frameId,
  });
}

function reportVideo(
  video: VideoData,
  tabId: number,
  headers: Record<string, string> | null,
): void {
  const annotated = annotateHlsFields({
    ...video,
    tabId,
    lastSeen: video.lastSeen ?? video.timestamp ?? Date.now(),
  });
  const hasHeaders = !!(headers && Object.keys(headers).length > 0);
  const seenUrls = getTabSeenUrls(tabId);
  const headersCaptured = getTabHeadersCaptured(tabId);
  const videos = getTabVideos(tabId);
  const key = detectionKey(annotated.url);
  const existing = findVideoByIdentity(videos, annotated.url);

  if (seenUrls.has(key) && existing) {
    existing.lastSeen = Date.now();
    if (hasHeaders && !headersCaptured.has(key)) {
      existing.headers = headers!;
      headersCaptured.add(key);
    }
    if (annotated.hlsRole) {
      existing.hlsRole = annotated.hlsRole;
    }
    attachCompanionAudioToGroup(tabId, existing.hlsGroupKey);
    if (
      existing.hlsRole === "video_media" ||
      existing.hlsRole === "audio_media" ||
      existing.hlsRole === "media"
    ) {
      maybeSynthesizeFromObservations(tabId, existing.hlsGroupKey);
    }
    return;
  }

  if (seenUrls.has(key) && headersCaptured.has(key)) return;

  seenUrls.add(key);
  if (hasHeaders) {
    annotated.headers = headers!;
    headersCaptured.add(key);
  }

  if (isHlsUrl(annotated.url)) {
    try {
      const stable = new URL(annotated.url);
      for (const param of [...stable.searchParams.keys()]) {
        if (/^(?:_HLS_.*|hls_.*)$/i.test(param)) {
          stable.searchParams.delete(param);
        }
      }
      annotated.url = stable.toString();
      annotated.hlsIdentityKey = hlsIdentityKey(annotated.url);
    } catch {
      /* keep */
    }
  }

  const idx = existing
    ? videos.indexOf(existing)
    : videos.findIndex((v) => detectionKey(v.url) === key);
  if (idx !== -1) {
    videos[idx] = { ...videos[idx], ...annotated, headers: annotated.headers ?? videos[idx].headers };
  } else {
    videos.push(annotated);
    if (videos.length > 50) videos.shift();
  }

  attachCompanionAudioToGroup(tabId, annotated.hlsGroupKey);
  if (
    annotated.hlsRole === "video_media" ||
    annotated.hlsRole === "audio_media" ||
    annotated.hlsRole === "media"
  ) {
    maybeSynthesizeFromObservations(tabId, annotated.hlsGroupKey);
  }

  // Don't emit raw exclusive token masters when synthetic or session video exists.
  if (
    annotated.hlsRole === "master" &&
    isExclusiveBootstrapMaster(annotated.url, annotated.hlsRole)
  ) {
    const synth = findSyntheticForGroup(tabId, annotated.hlsGroupKey);
    if (synth) {
      emitNativeVideo(synth);
      return;
    }
  }
  if (annotated.hlsRole === "audio_media") {
    // Companion only — attach and let video emit.
    return;
  }

  console.log(
    "[VideoDetector BG] VIDEO DETECTED tab=" +
      tabId +
      " role=" +
      annotated.hlsRole +
      " #" +
      videos.length +
      ": " +
      annotated.url.substring(0, 80),
  );
  emitNativeVideo(annotated);
}

function applyMasterBody(
  stored: VideoData,
  tabId: number,
  rawBody: string,
): void {
  try {
    const playlist = HlsParser.parsePlaylistContent(rawBody, stored.url);
    if (playlist.role === "master" || playlist.videoQualities.length > 0) {
      stored.hlsRole = "master";
      stored.qualities = playlist.videoQualities;
      if (playlist.hasSeparateAudio) stored.hasSeparateAudio = true;
    }
    if (
      isExclusiveBootstrapMaster(stored.url, stored.hlsRole ?? "master") &&
      rawBody.includes("#EXT-X-STREAM-INF")
    ) {
      const synthetic = buildSyntheticFromMasterBody(rawBody, stored.url);
      if (synthetic) {
        installSyntheticMaster(tabId, synthetic, {
          headers: stored.headers,
          originUrl: stored.originUrl,
          frameId: stored.frameId,
        });
        return;
      }
    }
    maybeSynthesizeFromObservations(tabId, stored.hlsGroupKey);
  } catch (e) {
    plog("master body parse failed:", (e as Error)?.message);
  }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

setInterval(() => {
  const now = Date.now();
  for (const [id, data] of requestHeadersMap) {
    if (now - data.timestamp > HEADER_TTL_MS) requestHeadersMap.delete(id);
  }
  for (const [url, data] of urlToTab) {
    if (now - data.ts > URL_TAB_TTL_MS) urlToTab.delete(url);
  }
}, 30_000);

browser.tabs.onRemoved.addListener((tabId: number) => cleanupTab(tabId));

function isSignificantNavigation(url1?: string, url2?: string): boolean {
  if (!url1 || !url2) return true;
  if (url1 === url2) return false;
  const noFrag1 = url1.split("#")[0];
  const noFrag2 = url2.split("#")[0];
  if (noFrag1 !== noFrag2) return true;
  const frag1 = url1.split("#")[1] || "";
  const frag2 = url2.split("#")[1] || "";
  if (frag1 !== frag2 && (frag1.includes("/") || frag2.includes("/"))) {
    return true;
  }
  return false;
}

function handleNavigation(
  tabId: number,
  url: string,
  transitionType = "",
  force = false,
): void {
  const lastUrl = tabLastUrl.get(tabId);
  if (!force && !isSignificantNavigation(lastUrl, url)) {
    tabLastUrl.set(tabId, url);
    return;
  }
  tabLastUrl.set(tabId, url);
  cleanupTab(tabId);
  sendToNative({
    type: "navigation",
    tabId,
    url,
    originUrl: url,
    transitionType,
    timestamp: Date.now(),
  });
}

if (browser.webNavigation) {
  browser.webNavigation.onCommitted.addListener(
    (details: { frameId: number; tabId: number; url: string; transitionType?: string }) => {
      if (details.frameId !== 0) return;
      handleNavigation(
        details.tabId,
        details.url,
        details.transitionType || "committed",
        true,
      );
    },
  );
  browser.webNavigation.onHistoryStateUpdated.addListener(
    (details: { frameId: number; tabId: number; url: string }) => {
      if (details.frameId !== 0) return;
      handleNavigation(details.tabId, details.url, "history_state_updated", false);
    },
  );
  browser.webNavigation.onReferenceFragmentUpdated?.addListener?.(
    (details: { frameId: number; tabId: number; url: string }) => {
      if (details.frameId !== 0) return;
      handleNavigation(
        details.tabId,
        details.url,
        "reference_fragment_updated",
        false,
      );
    },
  );
}

// ── webRequest ───────────────────────────────────────────────────────────────

browser.webRequest.onBeforeRequest.addListener(
  (details: { url: string; tabId: number }) => {
    rememberUrlTab(details.url, details.tabId);
  },
  { urls: ["<all_urls>"] },
);

browser.webRequest.onBeforeSendHeaders.addListener(
  (details: {
    method: string;
    requestId: string;
    tabId: number;
    requestHeaders?: { name: string; value?: string }[];
  }) => {
    if (details.method === "OPTIONS") return;
    const headers: Record<string, string> = {};
    const skip = [
      "host",
      "connection",
      "accept-encoding",
      "content-length",
      "upgrade-insecure-requests",
    ];
    for (const h of details.requestHeaders ?? []) {
      if (!skip.includes(h.name.toLowerCase())) {
        headers[h.name] = h.value ?? "";
      }
    }
    if (Object.keys(headers).length > 0) {
      requestHeadersMap.set(details.requestId, {
        headers,
        tabId: details.tabId,
        timestamp: Date.now(),
      });
    }
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders"],
);

browser.webRequest.onHeadersReceived.addListener(
  (details: {
    requestId: string;
    url: string;
    tabId: number;
    type: string;
    statusCode: number;
    originUrl?: string;
    frameId?: number;
    responseHeaders?: { name: string; value?: string }[];
  }) => {
    const ctHeader = details.responseHeaders?.find(
      (h) => h.name.toLowerCase() === "content-type",
    );
    const contentType = ctHeader?.value?.toLowerCase() ?? "";
    const stored = requestHeadersMap.get(details.requestId);
    const tabId = resolveTabId(details.tabId, details.url);
    const urlFull = details.url.toLowerCase();
    const urlPath = urlFull.split("?")[0] ?? urlFull;

    if (SEGMENT_OR_SUB_RE.test(details.url) && !urlFull.includes("m3u8")) {
      requestHeadersMap.delete(details.requestId);
      return;
    }

    const isVideoContentType =
      contentType !== "" &&
      VIDEO_CONTENT_TYPES.some((t) => contentType.includes(t));
    const isM3u8Url = urlFull.includes("m3u8");
    const isMpdUrl = urlPath.endsWith(".mpd") || urlFull.includes(".mpd?");
    const hasVideoExt = VIDEO_EXTENSIONS.some((ext) => urlPath.endsWith(ext));
    const hasSubExt = SUBTITLE_EXTENSIONS.some((ext) => urlPath.endsWith(ext));
    const isVideo =
      isVideoContentType || isM3u8Url || isMpdUrl || hasVideoExt || hasSubExt;

    const frameId =
      typeof details.frameId === "number" && details.frameId >= 0
        ? details.frameId
        : undefined;

    if (isVideo) {
      let detectedBy = "unknown";
      if (isVideoContentType) detectedBy = "content_type";
      else if (isM3u8Url) detectedBy = "url_pattern_m3u8";
      else if (isMpdUrl) detectedBy = "url_pattern_mpd";
      else if (hasVideoExt) detectedBy = "url_extension";
      else if (hasSubExt) detectedBy = "subtitle_extension";

      const hlsRole = isHlsUrl(details.url, contentType)
        ? classifyHlsUrl(details.url)
        : ("not_hls" as HlsRole);

      reportVideo(
        {
          url: details.url,
          tabId,
          contentType: contentType || "unknown",
          detectedBy,
          originUrl: details.originUrl ?? "",
          timestamp: Date.now(),
          frameId,
          hlsRole,
        },
        tabId,
        stored?.headers ?? null,
      );

      // Capture exclusive master body (GeckoView has filterResponseData).
      if (
        details.statusCode === 200 &&
        isExclusiveBootstrapMaster(details.url, hlsRole)
      ) {
        try {
          const filter = (
            browser.webRequest as {
              filterResponseData: (id: string) => {
                ondata: ((ev: { data: ArrayBuffer }) => void) | null;
                onstop: (() => void) | null;
                onerror: (() => void) | null;
                write: (data: ArrayBuffer) => void;
                disconnect: () => void;
              };
            }
          ).filterResponseData(details.requestId);
          const decoder = new TextDecoder("utf-8");
          let acc = "";
          filter.ondata = (ev) => {
            filter.write(ev.data);
            if (acc.length < 96_000) {
              acc += decoder.decode(ev.data, { stream: true });
            }
          };
          filter.onstop = () => {
            try {
              const text = acc.trim();
              if (
                text.startsWith("#EXTM3U") &&
                /#EXT-X-STREAM-INF:/i.test(text)
              ) {
                const storedVideo = findVideoByIdentity(
                  getTabVideos(tabId),
                  details.url,
                );
                if (storedVideo) applyMasterBody(storedVideo, tabId, text);
              }
            } catch (e) {
              plog("exclusive master parse failed:", (e as Error)?.message);
            }
            try {
              filter.disconnect();
            } catch {
              /* */
            }
          };
          filter.onerror = () => {};
        } catch (e) {
          plog("filterResponseData failed:", (e as Error)?.message);
        }
      }
    } else if (details.statusCode === 200) {
      const skipTypes = ["image", "font", "stylesheet", "script"];
      if (!skipTypes.includes(details.type)) {
        try {
          const filter = (
            browser.webRequest as {
              filterResponseData: (id: string) => {
                ondata: ((ev: { data: ArrayBuffer }) => void) | null;
                onstop: (() => void) | null;
                onerror: (() => void) | null;
                write: (data: ArrayBuffer) => void;
                disconnect: () => void;
              };
            }
          ).filterResponseData(details.requestId);
          const decoder = new TextDecoder("utf-8");
          let acc = "";
          let decided = false;
          filter.ondata = (ev) => {
            filter.write(ev.data);
            acc += decoder.decode(ev.data, { stream: true });
            if (!decided && acc.trim().startsWith("#EXTM3U") && acc.length >= 7) {
              decided = true;
              const role = classifyHlsUrl(details.url);
              reportVideo(
                {
                  url: details.url,
                  tabId,
                  contentType: contentType || "application/vnd.apple.mpegurl",
                  detectedBy: "body_content_m3u8",
                  originUrl: details.originUrl ?? "",
                  timestamp: Date.now(),
                  frameId,
                  hlsRole: role,
                },
                tabId,
                stored?.headers ?? null,
              );
              if (
                isExclusiveBootstrapMaster(details.url, role) ||
                /#EXT-X-STREAM-INF:/i.test(acc)
              ) {
                const storedVideo = findVideoByIdentity(
                  getTabVideos(tabId),
                  details.url,
                );
                if (storedVideo) applyMasterBody(storedVideo, tabId, acc);
              }
              try {
                filter.disconnect();
              } catch {
                /* */
              }
            }
          };
          filter.onstop = () => {
            try {
              filter.disconnect();
            } catch {
              /* */
            }
          };
          filter.onerror = () => {};
        } catch {
          /* filter unavailable */
        }
      }
    }

    if (stored) requestHeadersMap.delete(details.requestId);
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders", "blocking"],
);

// DOM / player messages from content script
browser.runtime.onMessage.addListener(
  (
    message: { action?: string; url?: string; origin?: string },
    sender: { tab?: { id?: number }; frameId?: number },
  ) => {
    if (
      message?.action !== "dom_video_found" &&
      message?.action !== "player_video_found"
    ) {
      return false;
    }
    const tabId = sender.tab?.id;
    if (tabId == null || tabId < 0 || !message.url) return false;
    reportVideo(
      {
        url: message.url,
        tabId,
        contentType: message.url.toLowerCase().includes("m3u8")
          ? "application/vnd.apple.mpegurl"
          : "video/unknown",
        detectedBy:
          message.action === "player_video_found"
            ? "player_config"
            : "dom_source",
        originUrl: message.origin ?? "",
        timestamp: Date.now(),
        frameId: sender.frameId,
      },
      tabId,
      null,
    );
    return false;
  },
);

console.log(
  "[VideoDetector BG] Starting (GeckoView + shared core, native messaging)...",
);
