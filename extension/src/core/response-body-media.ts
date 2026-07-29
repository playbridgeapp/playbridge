export const DEFAULT_RESPONSE_BODY_SCAN_LIMIT_BYTES = 256 * 1024;
export const DEFAULT_RESPONSE_BODY_CANDIDATE_LIMIT = 32;

export type ResponseBodyMediaKind = "hls" | "dash";

export interface ResponseBodyMediaCandidate {
  url: string;
  contentType: string;
}

export interface ResponseBodyMediaScan {
  responseKind: ResponseBodyMediaKind | null;
  embeddedCandidates: ResponseBodyMediaCandidate[];
}

export interface ResponseBodyStreamFilter {
  ondata: ((event: { data: ArrayBuffer }) => void) | null;
  onstop: (() => void) | null;
  onerror: (() => void) | null;
  write(data: ArrayBuffer): void;
  disconnect(): void;
}

const TEXTUAL_CONTENT_TYPE_HINTS = [
  "application/dash",
  "application/javascript",
  "application/json",
  "application/ld+json",
  "application/mpegurl",
  "application/vnd.apple.mpegurl",
  "application/x-javascript",
  "application/x-mpegurl",
  "application/xhtml",
  "application/xml",
  "text/",
  "+json",
  "+xml",
];

const FALLBACK_TEXT_REQUEST_TYPES = new Set([
  "main_frame",
  "sub_frame",
  "xmlhttprequest",
  "other",
]);

const MEDIA_URL_RE =
  /\.(?:m3u8|mpd|mp4|m4v|mkv|webm|mov|avi|flv|wmv|3gp|mpg|mpeg|ogv|ogg)(?:$|[?#])/i;
const MEDIA_QUERY_RE =
  /[?&](?:ext|extension|format|mime|type)=(?:application%2f)?(?:x-)?(?:mpegurl|vnd\.apple\.mpegurl|dash|mpd|m3u8|mp4|webm)(?:&|$)/i;
const STRONG_MEDIA_KEYS = new Set([
  "dash",
  "dashurl",
  "file",
  "hls",
  "hlsurl",
  "manifest",
  "manifesturl",
  "mediaurl",
  "playbackurl",
  "playlist",
  "playlisturl",
  "stream",
  "streamurl",
  "videourl",
]);
const MEDIA_CONTAINER_KEYS = new Set([
  "media",
  "playlist",
  "playlists",
  "source",
  "sources",
  "stream",
  "streams",
  "video",
  "videos",
]);
const MIME_METADATA_KEYS = new Set([
  "contenttype",
  "format",
  "mime",
  "mimetype",
  "type",
]);

function normalizeKey(key: string | undefined): string {
  return (key ?? "").replace(/[^a-z0-9]/gi, "").toLowerCase();
}

function isMediaMime(value: string | undefined): boolean {
  const lower = value?.toLowerCase() ?? "";
  return (
    lower.includes("video/") ||
    lower.includes("mpegurl") ||
    lower.includes("application/dash") ||
    lower === "hls" ||
    lower === "dash" ||
    lower === "mpd" ||
    lower === "m3u8"
  );
}

function inferContentType(url: string, hint?: string): string {
  const lowerHint = hint?.toLowerCase() ?? "";
  const lowerUrl = url.toLowerCase();
  if (
    lowerHint.includes("mpegurl") ||
    lowerHint === "hls" ||
    lowerHint === "m3u8" ||
    lowerUrl.includes(".m3u8") ||
    /[?&](?:ext|format|type)=m3u8(?:&|$)/i.test(lowerUrl)
  ) {
    return "application/vnd.apple.mpegurl";
  }
  if (
    lowerHint.includes("dash") ||
    lowerHint === "mpd" ||
    lowerUrl.includes(".mpd") ||
    /[?&](?:ext|format|type)=mpd(?:&|$)/i.test(lowerUrl)
  ) {
    return "application/dash+xml";
  }
  if (lowerHint.startsWith("video/")) return lowerHint;
  if (/\.webm(?:$|[?#])/i.test(lowerUrl)) return "video/webm";
  if (/\.(?:mp4|m4v)(?:$|[?#])/i.test(lowerUrl)) return "video/mp4";
  return "video/unknown";
}

function mediaKindFromBody(body: string): ResponseBodyMediaKind | null {
  const trimmed = body.replace(/^\uFEFF/, "").trimStart();
  if (trimmed.startsWith("#EXTM3U")) return "hls";
  const prefix = trimmed.slice(0, 2048);
  if (
    /^(?:<\?xml[^>]*>\s*)?(?:<!--[\s\S]*?-->\s*)*<(?:[\w.-]+:)?MPD(?:\s|>)/i.test(
      prefix,
    )
  ) {
    return "dash";
  }
  return null;
}

function decodeUrlEscapes(value: string): string {
  return value
    .replace(/\\u002f/gi, "/")
    .replace(/\\x2f/gi, "/")
    .replace(/\\\//g, "/")
    .replace(/&amp;/gi, "&")
    .trim();
}

function stripTrailingUrlPunctuation(value: string): string {
  return value.replace(/[),.;\]}]+$/g, "");
}

function normalizeCandidateUrl(
  value: string,
  responseUrl: string,
): string | null {
  const decoded = stripTrailingUrlPunctuation(decodeUrlEscapes(value));
  if (!decoded || decoded.startsWith("blob:") || decoded.startsWith("data:")) {
    return null;
  }
  const isUrlShaped =
    /^(?:https?:)?\/\//i.test(decoded) ||
    /^(?:\.{0,2}\/)/.test(decoded) ||
    decoded.includes("/") ||
    decoded.includes("?") ||
    looksLikeMediaUrl(decoded);
  if (!isUrlShaped) return null;
  try {
    const url = new URL(decoded, responseUrl);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    return url.toString();
  } catch {
    return null;
  }
}

function looksLikeMediaUrl(url: string): boolean {
  return MEDIA_URL_RE.test(url) || MEDIA_QUERY_RE.test(url);
}

function objectMimeHint(value: Record<string, unknown>): string | undefined {
  for (const key of ["contentType", "content_type", "mime", "mimeType", "type", "format"]) {
    const candidate = value[key];
    if (typeof candidate === "string" && isMediaMime(candidate)) {
      return candidate;
    }
  }
  return undefined;
}

/**
 * Return true when a response is safe and useful to inspect as bounded text.
 * Binary media, images, fonts, and stylesheets stay out of the response-filter
 * path. Textual JavaScript remains eligible because player configuration often
 * embeds media URLs there.
 */
export function shouldInspectResponseBody(
  contentType: string,
  requestType: string,
): boolean {
  const lower = contentType.toLowerCase();
  if (
    lower.startsWith("video/") ||
    lower.startsWith("audio/") ||
    lower.startsWith("image/") ||
    lower.includes("font") ||
    lower.includes("css")
  ) {
    return false;
  }
  if (TEXTUAL_CONTENT_TYPE_HINTS.some((hint) => lower.includes(hint))) {
    return true;
  }
  return (
    (!lower || lower.includes("octet-stream")) &&
    FALLBACK_TEXT_REQUEST_TYPES.has(requestType)
  );
}

/**
 * Inspect a bounded textual response for an extensionless HLS/DASH manifest
 * and contextual media URLs embedded in JSON, HTML, or JavaScript-like text.
 */
export function scanResponseBodyForMedia(
  body: string,
  responseUrl: string,
  contentType = "",
  candidateLimit = DEFAULT_RESPONSE_BODY_CANDIDATE_LIMIT,
): ResponseBodyMediaScan {
  const responseKind = mediaKindFromBody(body);
  if (responseKind) {
    return {
      responseKind,
      embeddedCandidates: [],
    };
  }
  const found = new Map<string, ResponseBodyMediaCandidate>();
  const responseAbsolute = normalizeCandidateUrl(responseUrl, responseUrl);

  const addCandidate = (
    raw: string,
    keyHint?: string,
    mimeHint?: string,
    parentKey?: string,
  ): void => {
    const url = normalizeCandidateUrl(raw, responseUrl);
    if (!url || url === responseAbsolute) return;
    const key = normalizeKey(keyHint);
    const parent = normalizeKey(parentKey);
    const contextual =
      STRONG_MEDIA_KEYS.has(key) ||
      MEDIA_CONTAINER_KEYS.has(parent) ||
      isMediaMime(mimeHint);
    if (!contextual && !looksLikeMediaUrl(url)) return;
    const inferredContentType = inferContentType(url, mimeHint ?? keyHint);
    const existing = found.get(url);
    if (existing) {
      if (
        existing.contentType === "video/unknown" &&
        inferredContentType !== "video/unknown"
      ) {
        found.set(url, {
          ...existing,
          contentType: inferredContentType,
        });
      }
      return;
    }
    if (found.size >= candidateLimit) return;
    found.set(url, {
      url,
      contentType: inferredContentType,
    });
  };

  const trimmed = body.replace(/^\uFEFF/, "").trim();
  if (
    contentType.toLowerCase().includes("json") ||
    trimmed.startsWith("{") ||
    trimmed.startsWith("[")
  ) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      const visit = (
        value: unknown,
        keyHint?: string,
        parentKey?: string,
        inheritedMime?: string,
      ): void => {
        if (found.size >= candidateLimit) return;
        if (typeof value === "string") {
          if (
            keyHint &&
            MIME_METADATA_KEYS.has(normalizeKey(keyHint)) &&
            isMediaMime(value)
          ) {
            return;
          }
          addCandidate(value, keyHint, inheritedMime, parentKey);
          return;
        }
        if (Array.isArray(value)) {
          for (const item of value) {
            visit(item, keyHint, parentKey, inheritedMime);
          }
          return;
        }
        if (!value || typeof value !== "object") return;
        const object = value as Record<string, unknown>;
        const mimeHint = objectMimeHint(object) ?? inheritedMime;
        for (const [key, child] of Object.entries(object)) {
          visit(child, key, keyHint, mimeHint);
        }
      };
      visit(parsed);
    } catch {
      // Fall through to bounded contextual text matching.
    }
  }

  const normalizedText = decodeUrlEscapes(body);
  const contextualAssignment =
    /(?:["']?)(file|stream(?:[_-]?url)?|playback[_-]?url|manifest(?:[_-]?url)?|playlist(?:[_-]?url)?|hls(?:[_-]?url)?|dash(?:[_-]?url)?|video[_-]?url|media[_-]?url)(?:["']?)\s*[:=]\s*["']([^"'\r\n]+)["']/gi;
  for (const match of normalizedText.matchAll(contextualAssignment)) {
    addCandidate(match[2], match[1]);
    if (found.size >= candidateLimit) break;
  }

  if (found.size < candidateLimit) {
    const mediaElementSource =
      /<(?:video|source)\b[^>]*?\b(?:src|data-src)\s*=\s*["']([^"']+)["']/gi;
    for (const match of normalizedText.matchAll(mediaElementSource)) {
      addCandidate(match[1], "file");
      if (found.size >= candidateLimit) break;
    }
  }

  if (found.size < candidateLimit) {
    const absoluteUrl = /https?:\/\/[^\s"'<>\\]+/gi;
    for (const match of normalizedText.matchAll(absoluteUrl)) {
      addCandidate(match[0]);
      if (found.size >= candidateLimit) break;
    }
  }

  return {
    responseKind,
    embeddedCandidates: [...found.values()],
  };
}

/**
 * Tee a response through unchanged while collecting only a bounded UTF-8
 * prefix for inspection. The callback runs once on EOF or when the byte cap is
 * reached; the rest of a capped response is handed back to the browser.
 */
export function attachBoundedResponseBodyScanner(
  filter: ResponseBodyStreamFilter,
  onBody: (body: string) => void,
  maxBytes = DEFAULT_RESPONSE_BODY_SCAN_LIMIT_BYTES,
): void {
  const decoder = new TextDecoder("utf-8");
  let text = "";
  let capturedBytes = 0;
  let finished = false;

  const finish = (): void => {
    if (finished) return;
    finished = true;
    text += decoder.decode();
    try {
      onBody(text);
    } finally {
      try {
        filter.disconnect();
      } catch {
        // The browser may already have closed the stream at EOF.
      }
    }
  };

  filter.ondata = (event) => {
    filter.write(event.data);
    if (finished) return;
    const chunk = new Uint8Array(event.data);
    const remaining = Math.max(0, maxBytes - capturedBytes);
    const take = Math.min(remaining, chunk.byteLength);
    if (take > 0) {
      text += decoder.decode(chunk.subarray(0, take), { stream: true });
      capturedBytes += take;
    }
    if (capturedBytes >= maxBytes) finish();
  };
  filter.onstop = finish;
  filter.onerror = () => {
    if (finished) return;
    finished = true;
    try {
      filter.disconnect();
    } catch {
      // Best effort: never keep a failed response filter attached.
    }
  };
}
