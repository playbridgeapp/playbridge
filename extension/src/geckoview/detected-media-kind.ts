export type DetectedMediaKind = "video" | "audio" | "image" | "subtitle";

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

const IMAGE_EXTENSIONS = [
  ".jpg",
  ".jpeg",
  ".png",
  ".webp",
  ".avif",
  ".gif",
  ".bmp",
  ".heic",
  ".heif",
];

const SUBTITLE_EXTENSIONS = [".vtt", ".srt"];
const IMAGE_NOISE_RE =
  /(?:^|[/_.-])(?:favicon|apple-touch-icon|sprite|spacer|pixel|beacon|analytics|tracking)(?:[/_.-]|$)/i;

function urlPath(url: string): string {
  return (url.toLowerCase().split(/[?#]/)[0] ?? "").trim();
}

function hasExtension(url: string, extensions: readonly string[]): boolean {
  const path = urlPath(url);
  return extensions.some((extension) => path.endsWith(extension));
}

export function detectedMediaKind(
  url: string,
  contentType = "",
  hlsRole?: string,
): DetectedMediaKind | null {
  const mime = contentType.toLowerCase();
  if (
    mime.includes("text/vtt") ||
    mime.includes("subrip") ||
    hasExtension(url, SUBTITLE_EXTENSIONS)
  ) {
    return "subtitle";
  }
  if (hlsRole === "audio_media") return "audio";
  // Adaptive stream URLs are authoritative here. Some hosts deliberately return
  // image/* for their manifests, so checking that MIME first would hide a real
  // HLS/DASH stream in the Images tab.
  if (
    mime.includes("mpegurl") ||
    mime.includes("application/dash") ||
    url.toLowerCase().includes("m3u8") ||
    hasExtension(url, [".mpd"])
  ) {
    return "video";
  }
  if (mime.startsWith("audio/") || hasExtension(url, AUDIO_EXTENSIONS)) {
    return "audio";
  }
  if (
    mime.startsWith("video/") ||
    hasExtension(url, VIDEO_EXTENSIONS)
  ) {
    return "video";
  }
  if (mime.startsWith("image/") || hasExtension(url, IMAGE_EXTENSIONS)) {
    return "image";
  }
  return null;
}

/**
 * Network image responses are deliberately conservative. Visible DOM images
 * are reported separately, so header-only discovery should not fill the sheet
 * with favicons, sprites, pixels, or tiny UI assets.
 */
export function shouldReportNetworkImage(
  url: string,
  contentType: string,
  contentLength: number | null,
): boolean {
  if (detectedMediaKind(url, contentType) !== "image") return false;
  if (contentType.toLowerCase().includes("svg")) return false;
  if (IMAGE_NOISE_RE.test(url)) return false;
  return contentLength != null && contentLength >= 16 * 1024;
}

export function isSupportedDomImage(url: string): boolean {
  return /^https?:/i.test(url) && !IMAGE_NOISE_RE.test(url);
}

export function inferredMediaContentType(
  url: string,
  kind: "video" | "audio" | "image",
): string {
  const path = urlPath(url);
  const lastDot = path.lastIndexOf(".");
  const extension = lastDot >= 0 ? path.slice(lastDot) : "";
  const known: Record<string, string> = {
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".aac": "audio/aac",
    ".ogg": "audio/ogg",
    ".oga": "audio/ogg",
    ".opus": "audio/ogg",
    ".wav": "audio/wav",
    ".flac": "audio/flac",
    ".weba": "audio/webm",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".avif": "image/avif",
    ".gif": "image/gif",
    ".bmp": "image/bmp",
    ".heic": "image/heic",
    ".heif": "image/heif",
  };
  return known[extension] ?? `${kind}/*`;
}
