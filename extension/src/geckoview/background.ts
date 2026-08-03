/**
 * Phone GeckoView video detector background.
 *
 * Shares pure detection / synthetic HLS logic with the Desktop store extension
 * via `../core/*`. Host-specific: native messaging to the Android app
 * (`browser.runtime.sendNativeMessage('browser', …)`).
 */

import browser from "./browser";
import {
  advanceNavigationGeneration,
  currentNavigationGeneration,
  isCurrentNavigationGeneration,
  MainFrameDetectionGate,
  responseBodyNavigationGeneration,
  shouldStageMainFrameDetection,
} from "./detection-lifecycle";
import {
  detectedMediaKind,
  inferredMediaContentType,
  shouldReportNetworkImage,
  type DetectedMediaKind,
} from "./detected-media-kind";
import { enrichReplayHeaders } from "./header-enrichment";
import { HlsParser } from "../core/hls-parser";
import {
  classifyHlsUrl,
  detectionEvidencePriority,
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
import {
  attachBoundedResponseBodyScanner,
  scanResponseBodyForMedia,
  shouldInspectResponseBody,
  type ResponseBodyStreamFilter,
} from "../core/response-body-media";

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
const AUDIO_EXTENSIONS = [
  ".mp3",
  ".m4a",
  ".aac",
  ".ogg",
  ".oga",
  ".opus",
  ".wav",
  ".flac",
  ".weba",
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
  navigationGeneration?: number;
  mediaKind?: DetectedMediaKind;
  width?: number;
  height?: number;
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
const tabNavigationGenerations = new Map<number, number>();
const mainFrameDetectionGate = new MainFrameDetectionGate<
  (navigationGeneration: number) => void
>();

// Identifies this background-script lifetime. Android uses the epoch together
// with each tab generation to reject late messages from an older detector.
const DETECTOR_EPOCH = Date.now();

const URL_TAB_TTL_MS = 60_000;
const HEADER_TTL_MS = 60_000;

function plog(...args: unknown[]): void {
  if (DEBUG) console.log("[VideoDetector BG]", ...args);
}

async function trySendToNative(message: Record<string, unknown>): Promise<boolean> {
  try {
    await browser.runtime.sendNativeMessage(NATIVE_APP_ID, message);
    return true;
  } catch (e) {
    plog("sendNativeMessage failed:", (e as Error)?.message);
    return false;
  }
}

let nativeReady = false;
let nativeSyncTimer: ReturnType<typeof setTimeout> | undefined;
let nativeReplayInProgress = false;

function scheduleNativeSync(delayMs = 1_000): void {
  if (nativeReady || nativeSyncTimer !== undefined) return;
  nativeSyncTimer = setTimeout(() => {
    nativeSyncTimer = undefined;
    void syncNativeConnection();
  }, delayMs);
}

function markNativeUnavailable(): void {
  nativeReady = false;
  scheduleNativeSync();
}

function sendToNative(message: Record<string, unknown>): void {
  void trySendToNative(message).then((delivered) => {
    if (!delivered) {
      markNativeUnavailable();
      return;
    }
    if (!nativeReady) {
      nativeReady = true;
      void replayCachedState();
    }
  });
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

function trimDetectedMedia(videos: VideoData[], kind: DetectedMediaKind): void {
  // Images are far more numerous on a typical page. Bound each category
  // independently so artwork cannot evict a playable stream from the cache.
  const limit = kind === "image" || kind === "audio" ? 30 : 50;
  let matching = videos.reduce(
    (count, video) => count + (video.mediaKind === kind ? 1 : 0),
    0,
  );
  while (matching > limit) {
    const oldest = videos.findIndex((video) => video.mediaKind === kind);
    if (oldest === -1) break;
    videos.splice(oldest, 1);
    matching -= 1;
  }
}

function clearTabDetectionState(tabId: number): void {
  tabVideos.delete(tabId);
  tabSeenUrls.delete(tabId);
  tabHeadersCaptured.delete(tabId);
}

function cleanupTab(tabId: number): void {
  mainFrameDetectionGate.abort(tabId);
  clearTabDetectionState(tabId);
  tabLastUrl.delete(tabId);
  tabNavigationGenerations.delete(tabId);
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

function nativeVideoForEmission(video: VideoData): VideoData {
  // Prefer synthetic cast URL + body when available for this group.
  let out = video;
  if (
    video.mediaKind !== "audio" &&
    video.mediaKind !== "image" &&
    !video.isSyntheticMaster &&
    video.hlsGroupKey
  ) {
    const synth = findSyntheticForGroup(video.tabId, video.hlsGroupKey);
    if (synth) out = synth;
  }
  return out;
}

function nativeVideoMessage(video: VideoData): Record<string, unknown> {
  const out = nativeVideoForEmission(video);
  return {
    type: "video_detected",
    url: out.url,
    tabId: out.tabId,
    contentType: out.contentType,
    detectedBy: out.detectedBy,
    originUrl: out.originUrl,
    timestamp: out.timestamp,
    lastSeen: out.lastSeen ?? out.timestamp,
    headers: out.headers ?? null,
    hlsRole: out.hlsRole,
    hlsGroupKey: out.hlsGroupKey,
    hasSeparateAudio: out.hasSeparateAudio ?? false,
    audioUrl: out.audioUrl ?? null,
    playlistBody: out.syntheticPlaylist ?? null,
    isSyntheticMaster: out.isSyntheticMaster ?? false,
    mediaKind:
      out.mediaKind ?? detectedMediaKind(out.url, out.contentType, out.hlsRole),
    width: out.width ?? null,
    height: out.height ?? null,
    detectorEpoch: DETECTOR_EPOCH,
    navigationGeneration:
      out.navigationGeneration ??
      currentNavigationGeneration(tabNavigationGenerations, out.tabId),
  };
}

function emitNativeVideo(video: VideoData): void {
  sendToNative(nativeVideoMessage(video));
}

async function replayCachedState(): Promise<void> {
  if (!nativeReady || nativeReplayInProgress) return;
  nativeReplayInProgress = true;
  let delivered = true;
  try {
    // Navigation first: Android can clear an older page before replayed media
    // arrives. The same generation on a later navigation message is a no-op.
    for (const [tabId, generation] of tabNavigationGenerations) {
      const url = tabLastUrl.get(tabId);
      if (!url) continue;
      delivered = await trySendToNative({
        type: "navigation",
        tabId,
        url,
        originUrl: url,
        transitionType: "native_replay",
        timestamp: Date.now(),
        detectorEpoch: DETECTOR_EPOCH,
        navigationGeneration: generation,
      });
      if (!delivered) return;
    }

    const replayed = new Set<string>();
    for (const [tabId, videos] of tabVideos) {
      const generation = currentNavigationGeneration(
        tabNavigationGenerations,
        tabId,
      );
      for (const video of videos) {
        if ((video.navigationGeneration ?? generation) !== generation) {
          continue;
        }
        const out = nativeVideoForEmission(video);
        const key = `${tabId}:${detectionKey(out.url)}`;
        if (replayed.has(key)) continue;
        replayed.add(key);
        delivered = await trySendToNative(nativeVideoMessage(out));
        if (!delivered) return;
      }
    }
  } finally {
    nativeReplayInProgress = false;
    if (!delivered) markNativeUnavailable();
  }
}

async function syncNativeConnection(): Promise<void> {
  if (nativeReady) return;
  const delivered = await trySendToNative({
    type: "detector_hello",
    detectorEpoch: DETECTOR_EPOCH,
    timestamp: Date.now(),
  });
  if (!delivered) {
    scheduleNativeSync();
    return;
  }
  nativeReady = true;
  await replayCachedState();
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
    mediaKind: "video",
    navigationGeneration: currentNavigationGeneration(
      tabNavigationGenerations,
      tabId,
    ),
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
    trimDetectedMedia(videos, "video");
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
  const navigationGeneration =
    video.navigationGeneration ??
    currentNavigationGeneration(tabNavigationGenerations, tabId);
  if (
    !isCurrentNavigationGeneration(
      tabNavigationGenerations,
      tabId,
      navigationGeneration,
    )
  ) {
    plog(
      "discarding stale detection",
      tabId,
      navigationGeneration,
      currentNavigationGeneration(tabNavigationGenerations, tabId),
    );
    return;
  }
  const annotated = annotateHlsFields({
    ...video,
    tabId,
    lastSeen: video.lastSeen ?? video.timestamp ?? Date.now(),
    navigationGeneration,
  });
  annotated.mediaKind =
    video.mediaKind ??
    detectedMediaKind(annotated.url, annotated.contentType, annotated.hlsRole) ??
    "video";
  const hasHeaders = !!(headers && Object.keys(headers).length > 0);
  const seenUrls = getTabSeenUrls(tabId);
  const headersCaptured = getTabHeadersCaptured(tabId);
  const videos = getTabVideos(tabId);
  const key = detectionKey(annotated.url);
  const existing = findVideoByIdentity(videos, annotated.url);

  if (seenUrls.has(key) && existing) {
    existing.lastSeen = Date.now();
    const evidenceEnriched =
      detectionEvidencePriority(annotated.detectedBy) >
      detectionEvidencePriority(existing.detectedBy);
    const hasSpecificContentType =
      annotated.contentType !== "unknown" &&
      !annotated.contentType.endsWith("/unknown");
    const metadataEnriched =
      (annotated.mediaKind != null && existing.mediaKind !== annotated.mediaKind) ||
      (annotated.width != null && existing.width !== annotated.width) ||
      (annotated.height != null && existing.height !== annotated.height) ||
      (hasSpecificContentType &&
        annotated.contentType !== existing.contentType);
    const headersEnriched = enrichReplayHeaders(
      existing,
      headers,
      headersCaptured.has(key),
    );
    if (headersEnriched) {
      headersCaptured.add(key);
    }
    if (annotated.hlsRole) {
      existing.hlsRole = annotated.hlsRole;
    }
    if (evidenceEnriched) existing.detectedBy = annotated.detectedBy;
    if (annotated.mediaKind) existing.mediaKind = annotated.mediaKind;
    if (annotated.width != null) existing.width = annotated.width;
    if (annotated.height != null) existing.height = annotated.height;
    if (hasSpecificContentType) {
      existing.contentType = annotated.contentType;
    }
    attachCompanionAudioToGroup(tabId, existing.hlsGroupKey);
    if (
      existing.hlsRole === "video_media" ||
      existing.hlsRole === "audio_media" ||
      existing.hlsRole === "media"
    ) {
      maybeSynthesizeFromObservations(tabId, existing.hlsGroupKey);
    }
    if (headersEnriched || metadataEnriched || evidenceEnriched) {
      emitNativeVideo(existing);
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
    trimDetectedMedia(videos, annotated.mediaKind ?? "video");
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
    // It remains a companion for video ranking, but the Audio tab also exposes
    // the playlist as an intentional audio-only cast target.
    emitNativeVideo(annotated);
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

function reportVideoForRequest(
  video: VideoData,
  tabId: number,
  headers: Record<string, string> | null,
  requestType: string,
  requestUrl: string,
  afterReport?: () => void,
): void {
  const committedUrl = tabLastUrl.get(tabId);
  const isUncommittedMainFrame = shouldStageMainFrameDetection(
    requestType,
    requestUrl,
    committedUrl,
    mainFrameDetectionGate.isNavigating(tabId),
  );
  if (isUncommittedMainFrame) {
    mainFrameDetectionGate.stage(tabId, requestUrl, (generation) => {
      reportVideo({ ...video, navigationGeneration: generation }, tabId, headers);
      afterReport?.();
    });
    return;
  }
  reportVideo(video, tabId, headers);
  afterReport?.();
}

function applyMasterBody(
  stored: VideoData,
  tabId: number,
  rawBody: string,
): void {
  if (
    !isCurrentNavigationGeneration(
      tabNavigationGenerations,
      tabId,
      stored.navigationGeneration ?? 0,
    )
  ) {
    return;
  }
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

browser.tabs.onRemoved.addListener((tabId: number) => {
  cleanupTab(tabId);
  sendToNative({
    type: "detector_tab_closed",
    tabId,
    detectorEpoch: DETECTOR_EPOCH,
    timestamp: Date.now(),
  });
});

function handleNavigation(
  tabId: number,
  url: string,
  transitionType = "",
): void {
  const previousUrl = tabLastUrl.get(tabId);
  const navigationGeneration = advanceNavigationGeneration(
    tabNavigationGenerations,
    tabId,
  );
  clearTabDetectionState(tabId);
  tabLastUrl.set(tabId, url);
  sendToNative({
    type: "navigation",
    tabId,
    url,
    originUrl: url,
    previousUrl: previousUrl ?? null,
    transitionType,
    timestamp: Date.now(),
    detectorEpoch: DETECTOR_EPOCH,
    navigationGeneration,
  });
  for (const pending of mainFrameDetectionGate.commit(tabId, url)) {
    pending(navigationGeneration);
  }
}

function handleSameDocumentNavigation(tabId: number, url: string): void {
  tabLastUrl.set(tabId, url);
  browser.tabs
    .sendMessage(tabId, { type: "detector_same_document_navigation" })
    .catch(() => {});
}

if (browser.webNavigation) {
  browser.webNavigation.onBeforeNavigate.addListener(
    (details: { frameId: number; tabId: number }) => {
      if (details.frameId !== 0) return;
      mainFrameDetectionGate.begin(details.tabId);
    },
  );
  browser.webNavigation.onCommitted.addListener(
    (details: { frameId: number; tabId: number; url: string; transitionType?: string }) => {
      if (details.frameId !== 0) return;
      handleNavigation(
        details.tabId,
        details.url,
        details.transitionType || "committed",
      );
    },
  );
  browser.webNavigation.onHistoryStateUpdated.addListener(
    (details: { frameId: number; tabId: number; url: string }) => {
      if (details.frameId !== 0) return;
      handleSameDocumentNavigation(details.tabId, details.url);
    },
  );
  browser.webNavigation.onReferenceFragmentUpdated?.addListener?.(
    (details: { frameId: number; tabId: number; url: string }) => {
      if (details.frameId !== 0) return;
      handleSameDocumentNavigation(details.tabId, details.url);
    },
  );
  browser.webNavigation.onErrorOccurred.addListener(
    (details: { frameId: number; tabId: number }) => {
      if (details.frameId !== 0) return;
      mainFrameDetectionGate.abort(details.tabId);
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
    const contentLengthValue = details.responseHeaders?.find(
      (h) => h.name.toLowerCase() === "content-length",
    )?.value;
    const parsedContentLength = contentLengthValue
      ? Number.parseInt(contentLengthValue, 10)
      : Number.NaN;
    const contentLength = Number.isFinite(parsedContentLength)
      ? parsedContentLength
      : null;
    const stored = requestHeadersMap.get(details.requestId);
    const tabId = resolveTabId(details.tabId, details.url);
    const navigationGeneration = currentNavigationGeneration(
      tabNavigationGenerations,
      tabId,
    );
    const urlFull = details.url.toLowerCase();
    const urlPath = urlFull.split("?")[0] ?? urlFull;
    const hasSubExt = SUBTITLE_EXTENSIONS.some((ext) => urlPath.endsWith(ext));
    const isSubtitleContentType =
      contentType.includes("text/vtt") ||
      contentType.includes("subrip") ||
      contentType.includes("application/x-subrip");

    // Drop HLS segments / fMP4 fragments early — but never subtitles. Desktop
    // filters only .ts/.m4s/segment/frag; GeckoView used to also match .vtt/.srt
    // via SEGMENT_OR_SUB_RE and silently never reported them to the phone.
    if (
      SEGMENT_OR_SUB_RE.test(details.url) &&
      !urlFull.includes("m3u8") &&
      !hasSubExt &&
      !isSubtitleContentType
    ) {
      requestHeadersMap.delete(details.requestId);
      return;
    }

    const isVideoContentType =
      contentType !== "" &&
      VIDEO_CONTENT_TYPES.some((t) => contentType.includes(t));
    const isM3u8Url = urlFull.includes("m3u8");
    const isMpdUrl = urlPath.endsWith(".mpd") || urlFull.includes(".mpd?");
    const hasVideoExt = VIDEO_EXTENSIONS.some((ext) => urlPath.endsWith(ext));
    const hasAudioExt = AUDIO_EXTENSIONS.some((ext) => urlPath.endsWith(ext));
    const isAudio = contentType.startsWith("audio/") || hasAudioExt;
    const isImage = shouldReportNetworkImage(
      details.url,
      contentType,
      contentLength,
    );
    const isDetectedMedia =
      isVideoContentType ||
      isM3u8Url ||
      isMpdUrl ||
      hasVideoExt ||
      hasSubExt ||
      isSubtitleContentType ||
      isAudio ||
      isImage;

    const frameId =
      typeof details.frameId === "number" && details.frameId >= 0
        ? details.frameId
        : undefined;

    if (isDetectedMedia) {
      let detectedBy = "unknown";
      if (isImage) detectedBy = "image_content_type";
      else if (isAudio) detectedBy = "audio_content_type";
      else if (isVideoContentType) detectedBy = "content_type";
      else if (isM3u8Url) detectedBy = "url_pattern_m3u8";
      else if (isMpdUrl) detectedBy = "url_pattern_mpd";
      else if (hasVideoExt) detectedBy = "url_extension";
      else if (hasSubExt) detectedBy = "subtitle_extension";

      const hlsRole = isHlsUrl(details.url, contentType)
        ? classifyHlsUrl(details.url)
        : ("not_hls" as HlsRole);

      const video: VideoData = {
        url: details.url,
        tabId,
        contentType: contentType || "unknown",
        detectedBy,
        originUrl: details.originUrl ?? "",
        timestamp: Date.now(),
        frameId,
        hlsRole,
        navigationGeneration,
        mediaKind:
          hlsRole === "audio_media"
            ? "audio"
            : detectedMediaKind(details.url, contentType, hlsRole) ?? "video",
      };
      reportVideoForRequest(
        video,
        tabId,
        stored?.headers ?? null,
        details.type,
        details.url,
      );

    }

    if (
      details.statusCode === 200 &&
      (isM3u8Url ||
        isMpdUrl ||
        shouldInspectResponseBody(contentType, details.type))
    ) {
      try {
        const filter = (
          browser.webRequest as unknown as {
            filterResponseData: (id: string) => ResponseBodyStreamFilter;
          }
        ).filterResponseData(details.requestId);
        attachBoundedResponseBodyScanner(filter, (body) => {
          const bodyNavigationGeneration = responseBodyNavigationGeneration(
            navigationGeneration,
            currentNavigationGeneration(tabNavigationGenerations, tabId),
            details.type,
            details.url,
            tabLastUrl.get(tabId),
          );
          const scan = scanResponseBodyForMedia(
            body,
            details.url,
            contentType,
          );

          if (scan.responseKind) {
            let hlsRole: HlsRole = "not_hls";
            let playlist:
              | ReturnType<typeof HlsParser.parsePlaylistContent>
              | undefined;
            if (scan.responseKind === "hls") {
              playlist = HlsParser.parsePlaylistContent(body, details.url);
              hlsRole =
                playlist.role === "not_hls" ? "media" : playlist.role;
            }
            const video: VideoData = {
              url: details.url,
              tabId,
              contentType:
                scan.responseKind === "hls"
                  ? "application/vnd.apple.mpegurl"
                  : "application/dash+xml",
              detectedBy:
                scan.responseKind === "hls"
                  ? "body_content_m3u8"
                  : "body_content_mpd",
              originUrl: details.originUrl ?? "",
              timestamp: Date.now(),
              frameId,
              hlsRole,
              navigationGeneration: bodyNavigationGeneration,
            };
            reportVideoForRequest(
              video,
              tabId,
              stored?.headers ?? null,
              details.type,
              details.url,
              () => {
                if (
                  playlist &&
                  (playlist.role === "master" ||
                    playlist.videoQualities.length > 0 ||
                    isExclusiveBootstrapMaster(details.url, hlsRole))
                ) {
                  const storedVideo = findVideoByIdentity(
                    getTabVideos(tabId),
                    details.url,
                  );
                  if (storedVideo) applyMasterBody(storedVideo, tabId, body);
                }
              },
            );
          }

          for (const candidate of scan.embeddedCandidates) {
            reportVideoForRequest(
              {
                url: candidate.url,
                tabId,
                contentType: candidate.contentType,
                detectedBy: "response_body_url",
                originUrl: details.originUrl ?? "",
                timestamp: Date.now(),
                frameId,
                hlsRole: isHlsUrl(candidate.url, candidate.contentType)
                  ? classifyHlsUrl(candidate.url)
                  : "not_hls",
                navigationGeneration: bodyNavigationGeneration,
              },
              tabId,
              null,
              details.type,
              details.url,
            );
          }
        });
      } catch (e) {
        plog("bounded response-body scan failed:", (e as Error)?.message);
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
    message: {
      action?: string;
      url?: string;
      origin?: string;
      contentType?: string;
      width?: number;
      height?: number;
    },
    sender: { tab?: { id?: number }; frameId?: number },
  ) => {
    if (
      message?.action !== "dom_video_found" &&
      message?.action !== "player_video_found" &&
      message?.action !== "dom_audio_found" &&
      message?.action !== "dom_image_found"
    ) {
      return false;
    }
    const tabId = sender.tab?.id;
    if (tabId == null || tabId < 0 || !message.url) return false;
    const mediaKind: DetectedMediaKind =
      message.action === "dom_audio_found"
        ? "audio"
        : message.action === "dom_image_found"
          ? "image"
          : "video";
    const contentType =
      message.contentType ||
      (mediaKind === "audio"
        ? inferredMediaContentType(message.url, "audio")
        : mediaKind === "image"
          ? inferredMediaContentType(message.url, "image")
          : message.url.toLowerCase().includes("m3u8")
            ? "application/vnd.apple.mpegurl"
            : inferredMediaContentType(message.url, "video"));
    reportVideo(
      {
        url: message.url,
        tabId,
        contentType,
        detectedBy:
          message.action === "player_video_found"
            ? "player_config"
            : mediaKind === "image"
              ? "dom_image"
              : mediaKind === "audio"
                ? "dom_audio"
                : "dom_source",
        originUrl: message.origin ?? "",
        timestamp: Date.now(),
        frameId: sender.frameId,
        mediaKind,
        width: message.width,
        height: message.height,
        navigationGeneration: currentNavigationGeneration(
          tabNavigationGenerations,
          tabId,
        ),
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
scheduleNativeSync(0);
