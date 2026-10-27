import { MAX_SUBTITLE_BYTES, readSubtitleBlob, SubtitleTextDeduper, type SubtitleText } from "../core/blob-subtitles";

declare const exportFunction: undefined | ((fn: Function, scope: object) => Function);
declare const content: Window & typeof globalThis;

export function installBlobSubtitleCapture(
  report: (subtitle: SubtitleText & { url: string; subtitleLabel: string }) => void,
): { track: (track: HTMLTrackElement) => void; stop: () => void } {
  let active = true;
  const deduper = new SubtitleTextDeduper();
  const pendingTracks = new Set<string>();
  let restore = () => {};

  function labelFor(url: string): string {
    for (const track of document.querySelectorAll<HTMLTrackElement>("track")) {
      if (track.src === url && (track.kind === "subtitles" || track.kind === "captions")) {
        return (track.label || track.srclang || "Subtitles").slice(0, 256);
      }
    }
    return "Subtitles";
  }

  async function capture(blob: Blob, url: string): Promise<void> {
    if (!active) return;
    const subtitle = await readSubtitleBlob(blob, () => active);
    if (!active || !subtitle) return;
    const identity = deduper.accept(subtitle, labelFor(url));
    if (identity) report({ ...subtitle, ...identity });
  }

  try {
    const page = (window as unknown as { wrappedJSObject?: Window & typeof globalThis }).wrappedJSObject;
    if (page?.URL && typeof exportFunction === "function") {
      const target = page.URL;
      const original = target.createObjectURL;
      const wrapped = exportFunction(function (this: unknown, blob: Blob): string {
        const url = Reflect.apply(original, this, arguments);
        try { void capture(blob, url).catch(() => {}); } catch { /* Detection must not affect the page. */ }
        return url;
      }, page) as typeof URL.createObjectURL;
      target.createObjectURL = wrapped;
      restore = () => { if (target.createObjectURL === wrapped) target.createObjectURL = original; };
    }
  } catch { /* A page can lock its URL constructor. Track scanning remains available. */ }

  return {
    track(track) {
      const url = track.src;
      if (!active || !url.startsWith("blob:") || pendingTracks.has(url)) return;
      pendingTracks.add(url);
      // Extension-principal fetch cannot resolve a page-local blob in Firefox MV2.
      try {
        const page = (window as unknown as { wrappedJSObject?: Window & typeof globalThis }).wrappedJSObject;
        const pageFetch = typeof content !== "undefined" ? content.fetch.bind(content) : page?.fetch.bind(page);
        if (!pageFetch) { pendingTracks.delete(url); return; }
        void pageFetch(url).then(response => {
          if (!active) return null;
          const type = response.headers.get("content-type") ?? "";
          const size = Number(response.headers.get("content-length"));
          if (/^(video|audio|image)\//i.test(type) || !Number.isFinite(size) || size <= 0 || size > MAX_SUBTITLE_BYTES) return null;
          return response.blob();
        }).then(blob => blob && capture(blob, url))
          .catch(() => {}).finally(() => pendingTracks.delete(url));
      } catch { pendingTracks.delete(url); }
    },
    stop() {
      active = false;
      pendingTracks.clear();
      try { restore(); } catch { /* The page may have replaced the hook. */ }
    },
  };
}
