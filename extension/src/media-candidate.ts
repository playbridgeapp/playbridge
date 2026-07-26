/**
 * Shared media-candidate helpers used by the background detector, content-script
 * overlay, and ranking logic.
 *
 * HLS lives in three shapes we must distinguish:
 * - master / multivariant (`#EXT-X-STREAM-INF`) — often a one-shot bootstrap
 * - video media playlists (often demuxed CMAF / LL-HLS) — live `session=` URLs
 * - audio media playlists (sibling of demuxed video; companion, not solo cast)
 *
 * Some live CDNs (mmcdn LL-HLS) mint an exclusive bootstrap master
 * (`llhls.m3u8?token=…`). The browser receives a real multivariant body once;
 * replaying that token yields `session_duplicated`. Durable cast targets are the
 * child media playlists that carry `session=…`.
 */

export type HlsRole =
  | "master"
  | "video_media"
  | "audio_media"
  | "media"
  | "not_hls";

export interface MediaCandidate {
  url: string;
  contentType: string;
  detectedBy: string;
  timestamp: number;
  frameId?: number;
  qualities?: unknown[];
  /**
   * True when the background holds non-empty replay headers for this URL.
   * Content scripts never receive the header values themselves.
   */
  hasHeaders?: boolean;
  /** Classified HLS role when known (URL heuristics and/or playlist body). */
  hlsRole?: HlsRole;
  /**
   * Stable identity for a playlist across LL-HLS blocking-reload query variants
   * (`_HLS_msn`, `_HLS_part`, …).
   */
  hlsIdentityKey?: string;
  /**
   * Groups a master with its demuxed video/audio media playlists (typically
   * origin + directory of the playlist path).
   */
  hlsGroupKey?: string;
  /** True when the master references at least one external audio media URI. */
  hasSeparateAudio?: boolean;
  /** Last network observation of this stream or a sibling in its HLS group. */
  lastSeen?: number;
  /**
   * Companion demuxed audio media playlist for this video (same HLS session).
   * Set on the castable video record; never a primary list row by itself.
   */
  audioUrl?: string;
  /**
   * True when `url` is a synthetic multivariant we built from a captured master
   * body (or observed session media). Preferred cast target for exclusive-bootstrap
   * live CDNs.
   */
  isSyntheticMaster?: boolean;
  /** Raw synthetic playlist text (debugging / desktop handoff). */
  syntheticPlaylist?: string;
}

/** data: application/vnd.apple.mpegurl … synthetic masters we generate. */
export function isSyntheticHlsDataUrl(url: string): boolean {
  if (!url.startsWith("data:")) return false;
  const head = url.slice(0, 80).toLowerCase();
  return (
    head.includes("mpegurl") ||
    head.includes("application/vnd.apple.mpegurl") ||
    head.includes("application/x-mpegurl")
  );
}

/**
 * Bootstrap masters authenticated only by `token=` (no `session=`) are often
 * single-use: a second client gets `session_duplicated` / claims_denied while
 * the browser's `session=` media playlists keep working.
 */
export function isExclusiveBootstrapMaster(url: string, role?: HlsRole): boolean {
  const effectiveRole = role && role !== "not_hls" ? role : classifyHlsUrl(url);
  if (effectiveRole !== "master") return false;
  try {
    const parsed = new URL(url);
    const hasToken = parsed.searchParams.has("token");
    const hasSession = parsed.searchParams.has("session");
    return hasToken && !hasSession;
  } catch {
    return /[?&]token=/i.test(url) && !/[?&]session=/i.test(url);
  }
}

/** Live LL-HLS media playlists carry the edge session id. */
export function playlistSessionId(url: string): string | null {
  try {
    return new URL(url).searchParams.get("session");
  } catch {
    const match = url.match(/[?&]session=([^&]+)/i);
    return match ? decodeURIComponent(match[1]) : null;
  }
}

function activityTimestamp(candidate: MediaCandidate): number {
  return Math.max(candidate.timestamp, candidate.lastSeen ?? 0);
}

/** Best video media playlist in a group (header-bearing + most recently seen). */
export function pickBestVideoMedia<T extends MediaCandidate>(
  pool: T[],
  groupKey: string,
): T | null {
  const videos = pool.filter((candidate) => {
    if (isBlobOrDataUrl(candidate.url)) return false;
    const role = effectiveHlsRole(candidate);
    if (role !== "video_media" && role !== "media") return false;
    return effectiveHlsGroupKey(candidate) === groupKey;
  });
  if (videos.length === 0) return null;
  const withHeaders = videos.filter((candidate) => candidate.hasHeaders === true);
  const ranked = [...(withHeaders.length > 0 ? withHeaders : videos)].sort(
    (left, right) => activityTimestamp(right) - activityTimestamp(left),
  );
  return ranked[0] ?? null;
}

/**
 * Companion audio for a video media playlist: same group, prefer matching
 * `session=`, then most recently observed header-bearing audio.
 */
export function pickCompanionAudio<T extends MediaCandidate>(
  video: T,
  pool: T[],
): T | null {
  const group = effectiveHlsGroupKey(video);
  if (!group) return null;
  const session = playlistSessionId(video.url);
  const audios = pool.filter((candidate) => {
    if (isBlobOrDataUrl(candidate.url)) return false;
    if (effectiveHlsRole(candidate) !== "audio_media") return false;
    return effectiveHlsGroupKey(candidate) === group;
  });
  if (audios.length === 0) return null;

  const sameSession = session
    ? audios.filter((candidate) => playlistSessionId(candidate.url) === session)
    : audios;
  const poolForPick = sameSession.length > 0 ? sameSession : audios;
  const withHeaders = poolForPick.filter((c) => c.hasHeaders === true);
  const ranked = [...(withHeaders.length > 0 ? withHeaders : poolForPick)].sort(
    (left, right) => activityTimestamp(right) - activityTimestamp(left),
  );
  return ranked[0] ?? null;
}

export interface CandidateRankingContext {
  /** Normalized DOM URLs, ordered from strongest to weakest signal. */
  domUrls: string[];
  /** The frame that requested candidates from the background. */
  senderFrameId?: number;
  /** Network resources observed by this frame's Performance timeline. */
  frameResourceUrls?: Iterable<string>;
  /** Prefer detections associated with the current media lifecycle. */
  preferredSince?: number;
}

const SEGMENT_OR_SUB_RE =
  /\.(?:vtt|srt|ts|m4s)(?:$|\?)|\/segment|frag(?:ment)?|\/chunks?\/|init[-_][^/]*\.mp4|seg[-_][^/]*\.mp4/i;

/** Query params used by LL-HLS blocking playlist reloads — not part of identity. */
const HLS_EPHEMERAL_QUERY = /^(?:_HLS_.*|hls_.*)$/i;

const AUDIO_ONLY_CODECS_RE = /^(?:\s*mp4a\.[0-9a-z.]+|,|\s)+$/i;
const VIDEO_CODEC_HINT_RE = /avc1|hvc1|hev1|vp09|vp0[89]|av01|dvh1|dvhe/i;

function comparableUrl(raw: string): string {
  const trimmed = raw.trim();
  try {
    return new URL(trimmed).href;
  } catch {
    return trimmed;
  }
}

/**
 * True for blob: and non-HLS data: URLs. Synthetic HLS data: masters are
 * castable and intentionally excluded from this check.
 */
export function isBlobOrDataUrl(url: string): boolean {
  if (url.startsWith("blob:")) return true;
  if (url.startsWith("data:")) return !isSyntheticHlsDataUrl(url);
  return false;
}

export function isHlsUrl(url: string, contentType = ""): boolean {
  const u = url.toLowerCase();
  const ct = contentType.toLowerCase();
  return (
    u.includes("m3u8") ||
    ct.includes("mpegurl") ||
    ct.includes("vnd.apple.mpegurl")
  );
}

/**
 * Stable key for an HLS playlist across LL-HLS blocking reloads.
 * Keeps auth/session params (`token`, `session`) but drops `_HLS_*`.
 */
export function hlsIdentityKey(url: string): string {
  try {
    const parsed = new URL(url);
    for (const key of [...parsed.searchParams.keys()]) {
      if (HLS_EPHEMERAL_QUERY.test(key)) parsed.searchParams.delete(key);
    }
    // Prefer a deterministic query order for map keys.
    parsed.searchParams.sort();
    return parsed.origin + parsed.pathname + parsed.search;
  } catch {
    return url.replace(/([?&])_HLS_[^&]*/gi, "$1").replace(/[?&]$/, "");
  }
}

/**
 * Groups a master with demuxed child playlists that share a directory
 * (e.g. …/streams/origin.user.id/llhls.m3u8 + chunklist_*_video|audio_*.m3u8).
 */
export function hlsStreamGroupKey(url: string): string {
  try {
    const parsed = new URL(url);
    const path = parsed.pathname;
    const slash = path.lastIndexOf("/");
    const dir = slash >= 0 ? path.slice(0, slash + 1) : path;
    return `${parsed.origin}${dir}`.toLowerCase();
  } catch {
    const base = url.split(/[?#]/)[0] ?? url;
    const slash = base.lastIndexOf("/");
    return (slash >= 0 ? base.slice(0, slash + 1) : base).toLowerCase();
  }
}

/**
 * URL-only HLS role. Body parsing can upgrade `media` → `master` / refine
 * video vs audio; URL heuristics catch demuxed CDN naming without a body.
 */
export function classifyHlsUrl(url: string): HlsRole {
  if (!isHlsUrl(url)) return "not_hls";

  const path = (url.split(/[?#]/)[0] ?? "").toLowerCase();
  const file = path.split("/").pop() ?? path;

  // Explicit demuxed naming used by several live CDNs (mmcdn LL-HLS, etc.).
  if (
    /(?:^|[^a-z])audio(?:[^a-z]|$)/i.test(file) ||
    /_audio_/i.test(file) ||
    /chunklist_\d+_audio_/i.test(file) ||
    /media[_-]?audio/i.test(file) ||
    /\/audio\//i.test(path)
  ) {
    return "audio_media";
  }
  if (
    /(?:^|[^a-z])video(?:[^a-z]|$)/i.test(file) ||
    /_video_/i.test(file) ||
    /chunklist_\d+_video_/i.test(file) ||
    /media[_-]?video/i.test(file) ||
    /\/video\//i.test(path)
  ) {
    return "video_media";
  }

  // chunklist_* / media_* names are media playlists (incl. mmcdn `*_llhls.m3u8`).
  if (file.startsWith("chunklist") || file.startsWith("media_")) {
    return "media";
  }

  // Common master / multivariant filenames (bare llhls.m3u8, index.m3u8, …).
  if (
    /^(?:index|master|playlist|manifest|ll?hls)(?:[_-].*)?\.m3u8$/i.test(file)
  ) {
    return "master";
  }

  // LL-HLS blocking reloads almost always target media playlists.
  if (/[?&]_HLS_(?:msn|part|skip)=/i.test(url)) return "media";

  return "media";
}

/** Prefer body-derived role when present; otherwise URL heuristics. */
export function effectiveHlsRole(candidate: MediaCandidate): HlsRole {
  if (candidate.hlsRole && candidate.hlsRole !== "not_hls") {
    return candidate.hlsRole;
  }
  if (isHls(candidate)) return classifyHlsUrl(candidate.url);
  return "not_hls";
}

export function effectiveHlsGroupKey(candidate: MediaCandidate): string | null {
  if (candidate.hlsGroupKey) return candidate.hlsGroupKey;
  if (isHls(candidate) || isHlsUrl(candidate.url, candidate.contentType)) {
    return hlsStreamGroupKey(candidate.url);
  }
  return null;
}

export function effectiveHlsIdentityKey(candidate: MediaCandidate): string {
  if (candidate.hlsIdentityKey) return candidate.hlsIdentityKey;
  if (isHlsUrl(candidate.url, candidate.contentType)) {
    return hlsIdentityKey(candidate.url);
  }
  return comparableUrl(candidate.url);
}

/**
 * Infer role from playlist body tags. Used after HlsParser fetch or Firefox
 * body sniff.
 */
export function classifyHlsPlaylistBody(
  content: string,
  urlHint = "",
): HlsRole {
  const text = content.trim();
  if (!text.startsWith("#EXTM3U")) {
    return urlHint ? classifyHlsUrl(urlHint) : "not_hls";
  }

  const hasStreamInf = /#EXT-X-STREAM-INF:/i.test(text);
  const hasIFrameStreamInf = /#EXT-X-I-FRAME-STREAM-INF:/i.test(text);
  const hasAudioMedia = /#EXT-X-MEDIA:TYPE=AUDIO/i.test(text);
  const hasVideoMedia = /#EXT-X-MEDIA:TYPE=VIDEO/i.test(text);
  const hasTargetDuration = /#EXT-X-TARGETDURATION:/i.test(text);
  const hasExtInf = /#EXTINF:/i.test(text);
  const hasPart = /#EXT-X-PART:/i.test(text);
  const hasMap = /#EXT-X-MAP:/i.test(text);

  if (hasStreamInf || hasIFrameStreamInf) return "master";

  // Multivariant that only declares media groups (rare) still is not a media
  // segment list — treat as master so we don't cast an empty shell.
  if (hasAudioMedia && hasVideoMedia && !hasExtInf && !hasPart) return "master";

  const urlRole = urlHint ? classifyHlsUrl(urlHint) : "media";
  if (urlRole === "audio_media" || urlRole === "video_media") return urlRole;

  // CODECS on media playlists are uncommon; still try to read audio-only.
  const codecsMatch = text.match(/CODECS="([^"]+)"/i);
  if (codecsMatch) {
    const codecs = codecsMatch[1];
    if (AUDIO_ONLY_CODECS_RE.test(codecs) && !VIDEO_CODEC_HINT_RE.test(codecs)) {
      return "audio_media";
    }
    if (VIDEO_CODEC_HINT_RE.test(codecs)) return "video_media";
  }

  if (hasTargetDuration || hasExtInf || hasPart || hasMap) return "media";
  return urlRole === "master" ? "master" : "media";
}

export function isExcludedMediaCandidate(
  url: string,
  detectedBy?: string,
  hlsRole?: HlsRole,
): boolean {
  if (detectedBy === "subtitle_extension") return true;
  if (SEGMENT_OR_SUB_RE.test(url)) return true;
  const path = url.toLowerCase().split(/[?#]/)[0] ?? "";
  if (path.endsWith(".vtt") || path.endsWith(".srt")) return true;

  const role = hlsRole && hlsRole !== "not_hls" ? hlsRole : classifyHlsUrl(url);
  // Demuxed audio media playlists are never castable on their own.
  if (role === "audio_media") return true;

  return false;
}

function isHls(candidate: MediaCandidate): boolean {
  return (
    isHlsUrl(candidate.url, candidate.contentType) ||
    isSyntheticHlsDataUrl(candidate.url) ||
    candidate.isSyntheticMaster === true ||
    (candidate.hlsRole != null && candidate.hlsRole !== "not_hls")
  );
}

function isDash(candidate: MediaCandidate): boolean {
  const url = candidate.url.toLowerCase();
  const contentType = candidate.contentType.toLowerCase();
  return url.includes(".mpd") || contentType.includes("dash");
}

function isDirectFile(url: string): boolean {
  const path = url.toLowerCase().split(/[?#]/)[0] ?? "";
  return [
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".flv",
    ".m4v",
    ".wmv",
    ".3gp",
  ].some((extension) => path.endsWith(extension));
}

function hlsRoleNetworkPriority(role: HlsRole): number {
  switch (role) {
    case "master":
      return 6;
    case "video_media":
      return 3;
    case "media":
      return 3;
    case "audio_media":
      return 0;
    default:
      return 0;
  }
}

function networkPriority(candidate: MediaCandidate): number {
  if (candidate.isSyntheticMaster || isSyntheticHlsDataUrl(candidate.url)) {
    return 10; // always prefer synthetic multivariant for exclusive live CDNs
  }
  if (isHls(candidate)) {
    const role = effectiveHlsRole(candidate);
    // Live session media outranks exclusive bootstrap masters (token-only).
    if (
      (role === "video_media" || role === "media") &&
      playlistSessionId(candidate.url)
    ) {
      return 7;
    }
    let priority = hlsRoleNetworkPriority(role);
    if (
      role === "master" &&
      isExclusiveBootstrapMaster(candidate.url, role)
    ) {
      // Still discoverable, but weaker than live session media / synthetic.
      priority = 4;
    } else if (
      role === "master" &&
      Array.isArray(candidate.qualities) &&
      candidate.qualities.length > 0
    ) {
      priority = 6;
    }
    return priority;
  }
  if (isDash(candidate)) return 2;
  if (isDirectFile(candidate.url)) return 1;
  return 0;
}

/**
 * Choose which detections appear as cast targets.
 *
 * - Synthetic masters (from captured body or observed session media) win.
 * - Exclusive bootstrap masters (`?token=` only) yield to live `session=` video
 *   media when no synthetic is available.
 * - Reusable masters hide demuxed children.
 * - Audio media is never a primary row.
 */
export function filterPrimaryCastCandidates<T extends MediaCandidate>(
  records: T[],
): T[] {
  const mastersByGroup = new Map<string, T>();
  const syntheticByGroup = new Map<string, T>();
  const liveVideoGroups = new Set<string>();

  for (const candidate of records) {
    if (!candidate.url || isBlobOrDataUrl(candidate.url)) continue;
    const role = effectiveHlsRole(candidate);
    const group =
      effectiveHlsGroupKey(candidate) ??
      (candidate.isSyntheticMaster ? candidate.hlsGroupKey : null);
    if (!group) continue;

    if (candidate.isSyntheticMaster || isSyntheticHlsDataUrl(candidate.url)) {
      const existing = syntheticByGroup.get(group);
      if (
        !existing ||
        activityTimestamp(candidate) >= activityTimestamp(existing)
      ) {
        syntheticByGroup.set(group, candidate);
      }
    }

    if (role === "master" && !candidate.isSyntheticMaster) {
      if (
        isExcludedMediaCandidate(
          candidate.url,
          candidate.detectedBy,
          candidate.hlsRole,
        )
      ) {
        continue;
      }
      const existing = mastersByGroup.get(group);
      if (
        !existing ||
        activityTimestamp(candidate) >= activityTimestamp(existing)
      ) {
        mastersByGroup.set(group, candidate);
      }
    }

    if (
      (role === "video_media" || role === "media") &&
      playlistSessionId(candidate.url)
    ) {
      liveVideoGroups.add(group);
    }
  }

  const exclusiveGroupsWithLiveVideo = new Set<string>();
  for (const [group, master] of mastersByGroup) {
    if (
      isExclusiveBootstrapMaster(master.url, effectiveHlsRole(master)) &&
      liveVideoGroups.has(group)
    ) {
      exclusiveGroupsWithLiveVideo.add(group);
    }
  }

  const bestLiveVideoByGroup = new Map<string, T>();
  for (const group of exclusiveGroupsWithLiveVideo) {
    if (syntheticByGroup.has(group)) continue;
    const best = pickBestVideoMedia(records, group);
    if (best) bestLiveVideoByGroup.set(group, best);
  }

  return records.filter((candidate) => {
    if (!candidate.url || isBlobOrDataUrl(candidate.url)) return false;
    if (
      isExcludedMediaCandidate(
        candidate.url,
        candidate.detectedBy,
        candidate.hlsRole,
      )
    ) {
      return false;
    }
    const role = effectiveHlsRole(candidate);
    if (role === "audio_media") return false;

    const group =
      effectiveHlsGroupKey(candidate) ??
      (candidate.isSyntheticMaster ? candidate.hlsGroupKey : null) ??
      null;

    // Synthetic master is the sole primary for its group (url may equal a
    // live video media URL — match by flag, not URL alone).
    if (group && syntheticByGroup.has(group)) {
      const synth = syntheticByGroup.get(group)!;
      return (
        candidate.isSyntheticMaster === true &&
        (candidate.hlsIdentityKey === synth.hlsIdentityKey ||
          candidate.url === synth.url)
      );
    }

    if (group && exclusiveGroupsWithLiveVideo.has(group)) {
      if (role === "master") return false;
      if (role === "video_media" || role === "media") {
        return bestLiveVideoByGroup.get(group)?.url === candidate.url;
      }
      return true;
    }

    if (role === "video_media" || role === "media") {
      if (group && mastersByGroup.has(group)) return false;
    }
    return true;
  });
}

export interface ResolveCastableHlsOptions {
  /**
   * When true, keep an explicit media-playlist choice (popup quality pick) if
   * the group's master is a normal (non-exclusive) muxed ladder.
   */
  allowMuxedMediaVariant?: boolean;
}

/**
 * Map a resolved cast candidate back onto a stored pool row.
 *
 * Synthetic masters often reuse a live video media URL as their open target so
 * mpv gets `https` + `playlistBody`. URL-only lookup would return the plain
 * video row and drop `syntheticPlaylist` / `isSyntheticMaster`.
 */
export function matchResolvedCastRecord<T extends MediaCandidate>(
  resolved: T,
  pool: T[],
): T {
  if (pool.length === 0) return resolved;

  if (resolved.isSyntheticMaster || isSyntheticHlsDataUrl(resolved.url)) {
    const group =
      effectiveHlsGroupKey(resolved) ?? resolved.hlsGroupKey ?? null;
    const bySynthIdentity = pool.find(
      (candidate) =>
        candidate.isSyntheticMaster === true &&
        ((resolved.hlsIdentityKey &&
          candidate.hlsIdentityKey === resolved.hlsIdentityKey) ||
          (group != null &&
            (effectiveHlsGroupKey(candidate) === group ||
              candidate.hlsGroupKey === group))),
    );
    if (bySynthIdentity) return bySynthIdentity;
  }

  const urlMatches = pool.filter((candidate) => candidate.url === resolved.url);
  if (urlMatches.length === 0) return resolved;
  // Prefer synthetic when URL collides with live video media.
  return (
    urlMatches.find((candidate) => candidate.isSyntheticMaster === true) ??
    urlMatches[0]!
  );
}

/**
 * Resolve what URL should actually be cast.
 *
 * Exclusive bootstrap masters → live video media with `session=`.
 * Reusable masters → master (player walks the multivariant).
 * Audio-only preference → sibling video when possible.
 */
export function resolveCastableHlsUrl<T extends MediaCandidate>(
  preferred: T,
  pool: T[],
  options: ResolveCastableHlsOptions = {},
): T {
  const role = effectiveHlsRole(preferred);
  const group =
    effectiveHlsGroupKey(preferred) ??
    (preferred.isSyntheticMaster ? preferred.hlsGroupKey ?? null : null);

  // Always prefer a synthetic multivariant for the group when present.
  if (group) {
    const synthetics = pool.filter(
      (candidate) =>
        (candidate.isSyntheticMaster || isSyntheticHlsDataUrl(candidate.url)) &&
        (effectiveHlsGroupKey(candidate) === group ||
          candidate.hlsGroupKey === group),
    );
    if (synthetics.length > 0) {
      synthetics.sort(
        (left, right) => activityTimestamp(right) - activityTimestamp(left),
      );
      return matchResolvedCastRecord(synthetics[0]!, pool);
    }
  }

  if (preferred.isSyntheticMaster || isSyntheticHlsDataUrl(preferred.url)) {
    return matchResolvedCastRecord(preferred, pool);
  }

  const masters = group
    ? pool.filter(
        (candidate) =>
          effectiveHlsRole(candidate) === "master" &&
          !candidate.isSyntheticMaster &&
          effectiveHlsGroupKey(candidate) === group &&
          !isBlobOrDataUrl(candidate.url),
      )
    : [];
  const master =
    masters.sort(
      (left, right) => activityTimestamp(right) - activityTimestamp(left),
    )[0] ?? null;

  // Preferred master that is exclusive bootstrap: hand off to live video media.
  if (role === "master" && isExclusiveBootstrapMaster(preferred.url, role)) {
    if (group) {
      const live = pickBestVideoMedia(pool, group);
      if (live) return live;
    }
    return preferred;
  }

  if (role === "not_hls") return preferred;

  if (!group) return preferred;

  if (masters.length === 0) {
    if (role === "audio_media") {
      return pickBestVideoMedia(pool, group) ?? preferred;
    }
    return preferred;
  }

  if (!master) return preferred;

  // Exclusive bootstrap master present + live video: never cast the token URL.
  if (isExclusiveBootstrapMaster(master.url, effectiveHlsRole(master))) {
    if (role === "video_media" || role === "media") return preferred;
    if (role === "audio_media") {
      return pickBestVideoMedia(pool, group) ?? preferred;
    }
    const live = pickBestVideoMedia(pool, group);
    return live ?? preferred;
  }

  // Explicit quality pick on a muxed (non-exclusive) ladder.
  if (
    options.allowMuxedMediaVariant &&
    (role === "video_media" || role === "media") &&
    master.hasSeparateAudio !== true
  ) {
    return preferred;
  }

  // Reusable master: cast master so the player can attach audio groups.
  if (role === "video_media" || role === "media" || role === "audio_media") {
    return master;
  }

  return preferred;
}

/** DOM-only registrations never carry replay headers captured from webRequest. */
export function isDomSourceDetection(detectedBy?: string): boolean {
  return detectedBy === "dom_source";
}

/**
 * Rank a frame's detected media without allowing a same-origin fallback from a
 * different frame to outrank stronger local evidence.
 *
 * Exact DOM URL matches are authoritative only when the detection came from the
 * network (webRequest). Pure `dom_source` records are matching signals so the
 * player can associate a CDN/auth URL that actually carries cookies/Referer.
 *
 * Demuxed exclusive-bootstrap HLS: prefer live `session=` video media over the
 * one-shot `token=` master. Reusable masters still outrank their children.
 */
export function rankMediaCandidate<T extends MediaCandidate>(
  records: T[],
  context: CandidateRankingContext,
): T | null {
  const playable = filterPrimaryCastCandidates(records);
  if (playable.length === 0) return null;

  const byUrl = new Map<string, T>();
  const byIdentity = new Map<string, T>();
  for (const candidate of playable) {
    byUrl.set(comparableUrl(candidate.url), candidate);
    if (isHls(candidate)) {
      byIdentity.set(effectiveHlsIdentityKey(candidate), candidate);
    }
  }

  for (const domUrl of context.domUrls) {
    if (!domUrl || isBlobOrDataUrl(domUrl)) continue;
    const exact =
      byUrl.get(comparableUrl(domUrl)) ??
      (isHlsUrl(domUrl) ? byIdentity.get(hlsIdentityKey(domUrl)) : undefined);
    // Only a network detection that still has replay headers is safe to treat
    // as authoritative. Progressive players often mirror a page-origin get_file
    // URL on <video src> while the real request hits a CDN with Cookie/Referer.
    if (
      exact &&
      !isDomSourceDetection(exact.detectedBy) &&
      exact.hasHeaders === true
    ) {
      return resolveCastableHlsUrl(exact, records);
    }
  }

  const observed = new Set(
    Array.from(context.frameResourceUrls ?? [], comparableUrl),
  );
  // Also treat HLS identity matches in the Performance timeline as local.
  const observedIdentities = new Set<string>();
  for (const raw of context.frameResourceUrls ?? []) {
    if (isHlsUrl(raw)) observedIdentities.add(hlsIdentityKey(raw));
  }

  // Media playlist polls in the timeline should keep their group's master "live".
  const observedGroups = new Set<string>();
  for (const raw of context.frameResourceUrls ?? []) {
    if (isHlsUrl(raw)) observedGroups.add(hlsStreamGroupKey(raw));
  }

  const senderFrameId = context.senderFrameId;
  const preferredSince = context.preferredSince;

  const isLocallyObserved = (candidate: T): boolean => {
    if (observed.has(comparableUrl(candidate.url))) return true;
    if (
      isHls(candidate) &&
      observedIdentities.has(effectiveHlsIdentityKey(candidate))
    ) {
      return true;
    }
    // Master is rarely re-fetched during LL-HLS; child polls imply the master.
    const group = effectiveHlsGroupKey(candidate);
    if (
      group &&
      effectiveHlsRole(candidate) === "master" &&
      observedGroups.has(group)
    ) {
      return true;
    }
    return false;
  };

  const scopePriority = (candidate: T): number => {
    const locallyObserved = isLocallyObserved(candidate);
    const exactFrame =
      typeof senderFrameId === "number" &&
      typeof candidate.frameId === "number" &&
      candidate.frameId === senderFrameId;

    if (locallyObserved && exactFrame) return 5;
    // Firefox/Fission can report a worker or MSE request against another frame;
    // local Performance evidence is stronger than that attribution.
    if (locallyObserved) return 4;
    if (exactFrame) return 3;
    if (typeof candidate.frameId !== "number") return 2;
    return 1;
  };

  const headerPriority = (candidate: T): number => {
    if (candidate.hasHeaders === true) return 2;
    if (
      candidate.hasHeaders === false ||
      isDomSourceDetection(candidate.detectedBy)
    ) {
      return 0;
    }
    // Unknown (records without the flag) rank between known-good and weak.
    return 1;
  };

  return (
    [...playable].sort((left, right) => {
      const leftHeaders = headerPriority(left);
      const rightHeaders = headerPriority(right);

      // Header-bearing streams must beat pure DOM / headerless hits even when the
      // latter has a better frame match. Progressive <video src> is same-frame and
      // headerless; the CDN request that actually needs Cookie/Referer is often
      // attributed to another frame or lacks Performance evidence.
      if (leftHeaders === 0 || rightHeaders === 0) {
        if (rightHeaders !== leftHeaders) return rightHeaders - leftHeaders;
      }

      const scope = scopePriority(right) - scopePriority(left);
      if (scope !== 0) return scope;

      if (rightHeaders !== leftHeaders) return rightHeaders - leftHeaders;

      // Same HLS family: master always beats demuxed media regardless of recency.
      const leftGroup = effectiveHlsGroupKey(left);
      const rightGroup = effectiveHlsGroupKey(right);
      if (leftGroup && leftGroup === rightGroup) {
        const roleDiff =
          hlsRoleNetworkPriority(effectiveHlsRole(right)) -
          hlsRoleNetworkPriority(effectiveHlsRole(left));
        if (roleDiff !== 0) return roleDiff;
      }

      let bothCurrent = false;
      if (typeof preferredSince === "number") {
        const rightIsCurrent = activityTimestamp(right) >= preferredSince ? 1 : 0;
        const leftIsCurrent = activityTimestamp(left) >= preferredSince ? 1 : 0;
        if (rightIsCurrent !== leftIsCurrent) {
          return rightIsCurrent - leftIsCurrent;
        }
        bothCurrent = rightIsCurrent === 1 && leftIsCurrent === 1;
      }

      // Inside the active player lifecycle, recency associates the URL the player
      // just requested (e.g. progressive after an HLS preload). When *both* sides
      // are HLS, apply format/role first so continuous LL-HLS media polls cannot
      // displace the master that was only fetched once at session start.
      if (bothCurrent) {
        if (isHls(left) && isHls(right)) {
          const hlsType = networkPriority(right) - networkPriority(left);
          if (hlsType !== 0) return hlsType;
        }
        const recency = activityTimestamp(right) - activityTimestamp(left);
        if (recency !== 0) return recency;
      }

      const type = networkPriority(right) - networkPriority(left);
      if (type !== 0) return type;
      return activityTimestamp(right) - activityTimestamp(left);
    })[0] ?? null
  );
}
