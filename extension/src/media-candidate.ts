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
  /\.(?:vtt|srt|ts|m4s)(?:$|\?)|\/segment|frag(?:ment)?|\/chunks?\//i;

function comparableUrl(raw: string): string {
  const trimmed = raw.trim();
  try {
    return new URL(trimmed).href;
  } catch {
    return trimmed;
  }
}

export function isBlobOrDataUrl(url: string): boolean {
  return url.startsWith("blob:") || url.startsWith("data:");
}

export function isExcludedMediaCandidate(
  url: string,
  detectedBy?: string,
): boolean {
  if (detectedBy === "subtitle_extension") return true;
  if (SEGMENT_OR_SUB_RE.test(url)) return true;
  const path = url.toLowerCase().split(/[?#]/)[0] ?? "";
  return path.endsWith(".vtt") || path.endsWith(".srt");
}

function isHls(candidate: MediaCandidate): boolean {
  const url = candidate.url.toLowerCase();
  const contentType = candidate.contentType.toLowerCase();
  return (
    url.includes("m3u8") ||
    contentType.includes("mpegurl") ||
    contentType.includes("vnd.apple.mpegurl")
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

function networkPriority(candidate: MediaCandidate): number {
  if (
    isHls(candidate) &&
    Array.isArray(candidate.qualities) &&
    candidate.qualities.length > 0
  ) {
    return 4;
  }
  if (isHls(candidate)) return 3;
  if (isDash(candidate)) return 2;
  if (isDirectFile(candidate.url)) return 1;
  return 0;
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
 */
export function rankMediaCandidate<T extends MediaCandidate>(
  records: T[],
  context: CandidateRankingContext,
): T | null {
  const playable = records.filter(
    (candidate) =>
      candidate.url &&
      !isBlobOrDataUrl(candidate.url) &&
      !isExcludedMediaCandidate(candidate.url, candidate.detectedBy),
  );
  if (playable.length === 0) return null;

  const byUrl = new Map<string, T>();
  for (const candidate of playable) {
    byUrl.set(comparableUrl(candidate.url), candidate);
  }

  for (const domUrl of context.domUrls) {
    if (!domUrl || isBlobOrDataUrl(domUrl)) continue;
    const exact = byUrl.get(comparableUrl(domUrl));
    // Only a network detection that still has replay headers is safe to treat
    // as authoritative. Progressive players often mirror a page-origin get_file
    // URL on <video src> while the real request hits a CDN with Cookie/Referer.
    if (
      exact &&
      !isDomSourceDetection(exact.detectedBy) &&
      exact.hasHeaders === true
    ) {
      return exact;
    }
  }

  const observed = new Set(
    Array.from(context.frameResourceUrls ?? [], comparableUrl),
  );
  const senderFrameId = context.senderFrameId;
  const preferredSince = context.preferredSince;

  const scopePriority = (candidate: T): number => {
    const locallyObserved = observed.has(comparableUrl(candidate.url));
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

  return [...playable].sort((left, right) => {
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

    let bothCurrent = false;
    if (typeof preferredSince === "number") {
      const rightIsCurrent = right.timestamp >= preferredSince ? 1 : 0;
      const leftIsCurrent = left.timestamp >= preferredSince ? 1 : 0;
      if (rightIsCurrent !== leftIsCurrent) {
        return rightIsCurrent - leftIsCurrent;
      }
      bothCurrent = rightIsCurrent === 1 && leftIsCurrent === 1;
    }

    // Inside the active player lifecycle, recency is a stronger association
    // signal than format. This prevents an older preload/master from displacing
    // the URL the current player just requested.
    if (bothCurrent && right.timestamp !== left.timestamp) {
      return right.timestamp - left.timestamp;
    }

    const type = networkPriority(right) - networkPriority(left);
    if (type !== 0) return type;
    return right.timestamp - left.timestamp;
  })[0] ?? null;
}
