export const MAX_SUBTITLE_BYTES = 2 * 1024 * 1024;

export interface SubtitleText {
  subtitleText: string;
  subtitleFormat: "vtt" | "srt";
}

export function subtitleFormat(text: string, mime = ""): "vtt" | "srt" | null {
  const type = mime.toLowerCase().split(";", 1)[0].trim();
  if (/^(video|audio|image)\//.test(type)) return null;
  const start = text.replace(/^\uFEFF/, "").trimStart().slice(0, 8192);
  if (/^WEBVTT(?:[ \t]|\r?\n|$)/.test(start)) return "vtt";
  if (/^(?:\d+\s*\r?\n)?\d{1,2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{1,2}:\d{2}:\d{2},\d{3}/.test(start)) return "srt";
  return type === "text/vtt" ? "vtt" : type.includes("subrip") ? "srt" : null;
}

export function validSubtitleText(text: unknown, mime = ""): SubtitleText | null {
  if (typeof text !== "string" || !text || text.length > MAX_SUBTITLE_BYTES ||
      new TextEncoder().encode(text).length > MAX_SUBTITLE_BYTES) return null;
  const format = subtitleFormat(text, mime);
  return format ? { subtitleText: text, subtitleFormat: format } : null;
}

// Two independent 32-bit hashes keep page-local identities small without requiring
// crypto.subtle, which is unavailable on ordinary HTTP pages.
export function subtitleContentHash(text: string): string {
  let first = 0x811c9dc5;
  let second = 0x9e3779b9;
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index);
    first = Math.imul(first ^ code, 0x01000193);
    second = Math.imul(second ^ code, 0x85ebca6b);
  }
  return (first >>> 0).toString(16).padStart(8, "0") + (second >>> 0).toString(16).padStart(8, "0");
}

export async function readSubtitleBlob(blob: Blob, isActive = () => true): Promise<SubtitleText | null> {
  try {
    // Native Blob accessors reject MediaSource and avoid page-defined getters.
    const size = Object.getOwnPropertyDescriptor(Blob.prototype, "size")!.get!.call(blob) as number;
    const type = Object.getOwnPropertyDescriptor(Blob.prototype, "type")!.get!.call(blob) as string;
    if (!isActive() || !size || size > MAX_SUBTITLE_BYTES || /^(video|audio|image)\//i.test(type)) return null;
    const head = await Blob.prototype.slice.call(blob, 0, 8192).text();
    if (!isActive() || !subtitleFormat(head, type)) return null;
    return validSubtitleText(await Blob.prototype.text.call(blob), type);
  } catch {
    return null;
  }
}

export class SubtitleTextDeduper {
  private labels = new Map<string, string>();

  accept(subtitle: SubtitleText, label = "Subtitles"): { url: string; subtitleLabel: string } | null {
    const hash = subtitleContentHash(subtitle.subtitleText);
    const previous = this.labels.get(hash);
    // A track attached after createObjectURL can enrich the same detection's label.
    if (previous && (previous !== "Subtitles" || label === "Subtitles")) return null;
    this.labels.set(hash, label);
    return { url: `playbridge-subtitle:${hash}.${subtitle.subtitleFormat}`, subtitleLabel: label };
  }
}
