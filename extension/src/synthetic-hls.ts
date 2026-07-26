/**
 * Build a durable synthetic multivariant playlist from a captured exclusive
 * bootstrap master (or from observed session media playlists).
 *
 * Why: CDNs like mmcdn issue `llhls.m3u8?token=…` once. The browser receives a
 * real master with `session=` child URIs; replaying the token fails with
 * `session_duplicated`. The synthetic master rewrites those children to absolute
 * URLs so we can cast a single multivariant playlist without re-hitting the
 * bootstrap token.
 */

import type { AudioTrack, VideoQuality } from "./hls-parser";
import { hlsStreamGroupKey } from "./media-candidate";

export interface SyntheticMasterResult {
  /** Playlist text with absolute child URIs. */
  content: string;
  /**
   * data: URL for the synthetic playlist. Safe to cast as the primary media
   * URL; children remain absolute https URLs on the CDN.
   */
  dataUrl: string;
  /** Origin master URL the body was captured from (if any). */
  sourceMasterUrl: string;
  hlsGroupKey: string;
  videoUrls: string[];
  audioUrls: string[];
  hasSeparateAudio: boolean;
  qualities: VideoQuality[];
  audioTracks: AudioTrack[];
}

/**
 * HTTPS open URL for a synthetic master when the full playlist body is lost.
 * Prefer highest declared bandwidth (qualities are usually sorted desc).
 */
export function preferredSyntheticCastUrl(
  synthetic: SyntheticMasterResult,
): string | null {
  if (synthetic.qualities.length > 0) {
    const best = [...synthetic.qualities].sort(
      (a, b) => b.bandwidth - a.bandwidth,
    )[0];
    if (best?.url) return best.url;
  }
  return synthetic.videoUrls[0] ?? null;
}

const URI_ATTR_RE = /(URI\s*=\s*")([^"]*)(")/gi;

function toAbsolute(baseUrl: string, maybeRelative: string): string | null {
  try {
    return new URL(maybeRelative, baseUrl).toString();
  } catch {
    return null;
  }
}

/** data: URL for a small text playlist (UTF-8 percent-encoded). */
export function playlistToDataUrl(content: string): string {
  // Prefer percent-encoding over base64 so the payload stays readable in logs
  // and works in Chromium data: URL length budgets for ~2–4KB masters.
  return `data:application/vnd.apple.mpegurl;charset=utf-8,${encodeURIComponent(content)}`;
}

/**
 * Rewrite a captured master body so every resource URI is absolute against the
 * request URL the browser used (token master host/path).
 */
export function rewriteMasterBodyAbsolute(
  body: string,
  masterUrl: string,
): { content: string; videoUrls: string[]; audioUrls: string[] } {
  const lines = body.replace(/\r\n/g, "\n").split("\n");
  const out: string[] = [];
  const videoUrls: string[] = [];
  const audioUrls: string[] = [];
  let pendingStreamInf = false;

  for (const raw of lines) {
    const line = raw.trimEnd();
    const trimmed = line.trim();

    if (!trimmed) {
      out.push(line);
      continue;
    }

    if (trimmed.startsWith("#")) {
      // Rewrite URI="..." on EXT-X-MEDIA / EXT-X-I-FRAME-STREAM-INF / KEY / MAP
      if (URI_ATTR_RE.test(trimmed)) {
        URI_ATTR_RE.lastIndex = 0;
        const rewritten = trimmed.replace(URI_ATTR_RE, (_m, a, uri, c) => {
          const abs = toAbsolute(masterUrl, uri);
          if (!abs) return `${a}${uri}${c}`;
          if (/TYPE=AUDIO/i.test(trimmed)) audioUrls.push(abs);
          return `${a}${abs}${c}`;
        });
        out.push(rewritten);
        pendingStreamInf = /#EXT-X-STREAM-INF:/i.test(trimmed);
        continue;
      }
      pendingStreamInf = /#EXT-X-STREAM-INF:/i.test(trimmed);
      out.push(trimmed);
      continue;
    }

    // URI line (variant or segment)
    const abs = toAbsolute(masterUrl, trimmed);
    if (abs) {
      out.push(abs);
      if (pendingStreamInf || /_video_/i.test(abs) || /chunklist_\d+_video/i.test(abs)) {
        videoUrls.push(abs);
      }
      pendingStreamInf = false;
    } else {
      out.push(trimmed);
    }
  }

  // De-dupe while preserving order
  const uniq = (list: string[]) => [...new Set(list)];
  return {
    content: out.join("\n").trim() + "\n",
    videoUrls: uniq(videoUrls),
    audioUrls: uniq(audioUrls),
  };
}

/** Re-order videoUrls highest-bandwidth first when qualities are known. */
function orderVideoUrlsByBandwidth(
  videoUrls: string[],
  qualities: VideoQuality[],
): string[] {
  if (qualities.length === 0) return videoUrls;
  const bw = new Map(qualities.map((q) => [q.url, q.bandwidth]));
  return [...videoUrls].sort(
    (a, b) => (bw.get(b) ?? 0) - (bw.get(a) ?? 0),
  );
}

/**
 * Build a synthetic master from a captured multivariant body.
 * Returns null if the body is not a usable master.
 */
export function buildSyntheticFromMasterBody(
  body: string,
  masterUrl: string,
): SyntheticMasterResult | null {
  const text = body.trim();
  if (!text.startsWith("#EXTM3U")) return null;
  if (!/#EXT-X-STREAM-INF:/i.test(text)) return null;

  const { content, videoUrls, audioUrls } = rewriteMasterBodyAbsolute(
    text,
    masterUrl,
  );
  if (videoUrls.length === 0) return null;

  // Re-parse attributes for qualities metadata (best-effort).
  const qualities: VideoQuality[] = [];
  const audioTracks: AudioTrack[] = [];
  const lines = content.split("\n");
  let bw: number | null = null;
  let avg: number | null = null;
  let res: string | null = null;
  let codecs: string | null = null;
  let audioGroup: string | null = null;
  let fr: string | null = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
      const attrs = line.slice(line.indexOf(":") + 1);
      const groupId = attrs.match(/GROUP-ID="([^"]+)"/)?.[1];
      const name = attrs.match(/NAME="([^"]+)"/)?.[1];
      const uri = attrs.match(/URI="([^"]+)"/)?.[1] ?? null;
      if (groupId && name) {
        audioTracks.push({
          groupId,
          name,
          language: attrs.match(/LANGUAGE="([^"]+)"/)?.[1] ?? null,
          uri,
          isDefault: /DEFAULT=YES/.test(attrs),
          autoselect: /AUTOSELECT=YES/.test(attrs),
          channels: attrs.match(/CHANNELS="([^"]+)"/)?.[1] ?? null,
        });
      }
    } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
      const attrs = line.slice(line.indexOf(":") + 1);
      bw = parseInt(attrs.match(/BANDWIDTH=(\d+)/)?.[1] ?? "", 10) || null;
      avg = parseInt(attrs.match(/AVERAGE-BANDWIDTH=(\d+)/)?.[1] ?? "", 10) || null;
      res = attrs.match(/RESOLUTION=(\d+x\d+)/)?.[1] ?? null;
      codecs = attrs.match(/CODECS="([^"]+)"/)?.[1] ?? null;
      audioGroup = attrs.match(/AUDIO="([^"]+)"/)?.[1] ?? null;
      fr = attrs.match(/FRAME-RATE=([\d.]+)/)?.[1] ?? null;
    } else if (line && !line.startsWith("#") && bw != null) {
      qualities.push({
        resolution: res ? `${res.split("x")[1]}p` : "Unknown",
        bandwidth: bw,
        averageBandwidth: avg,
        url: line,
        codecs,
        audioGroupId: audioGroup,
        frameRate: fr,
      });
      bw = avg = null;
      res = codecs = audioGroup = fr = null;
    }
  }
  qualities.sort((a, b) => b.bandwidth - a.bandwidth);

  const hasSeparateAudio =
    audioUrls.length > 0 ||
    audioTracks.some((t) => typeof t.uri === "string" && t.uri.length > 0);

  return {
    content,
    dataUrl: playlistToDataUrl(content),
    sourceMasterUrl: masterUrl,
    hlsGroupKey: hlsStreamGroupKey(masterUrl),
    videoUrls: orderVideoUrlsByBandwidth(videoUrls, qualities),
    audioUrls,
    hasSeparateAudio,
    qualities,
    audioTracks,
  };
}

/**
 * Fallback when the master body was never captured (e.g. Chrome MV3): build a
 * minimal multivariant from observed session media playlists.
 */
export function buildSyntheticFromObservations(input: {
  groupKey: string;
  sourceMasterUrl?: string;
  videoUrls: string[];
  audioUrls: string[];
}): SyntheticMasterResult | null {
  const videos = [...new Set(input.videoUrls.filter(Boolean))];
  const audios = [...new Set(input.audioUrls.filter(Boolean))];
  if (videos.length === 0) return null;

  const lines: string[] = [
    "#EXTM3U",
    "#EXT-X-VERSION:6",
    "#EXT-X-INDEPENDENT-SEGMENTS",
  ];

  const audioGroupId = "audio";
  if (audios.length > 0) {
    audios.forEach((uri, index) => {
      const name = `Audio_${index + 1}`;
      const def = index === 0 ? "YES" : "NO";
      lines.push(
        `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="${audioGroupId}",NAME="${name}",DEFAULT=${def},AUTOSELECT=YES,URI="${uri}"`,
      );
    });
  }

  videos.forEach((uri, index) => {
    // Unknown bandwidth — use descending placeholders so order is stable.
    const bandwidth = Math.max(500_000, 5_000_000 - index * 400_000);
    if (audios.length > 0) {
      lines.push(
        `#EXT-X-STREAM-INF:BANDWIDTH=${bandwidth},CODECS="avc1.42E01E,mp4a.40.2",AUDIO="${audioGroupId}"`,
      );
    } else {
      lines.push(
        `#EXT-X-STREAM-INF:BANDWIDTH=${bandwidth},CODECS="avc1.42E01E"`,
      );
    }
    lines.push(uri);
  });

  const content = lines.join("\n") + "\n";
  const qualities: VideoQuality[] = videos.map((url, index) => ({
    resolution: "Unknown",
    bandwidth: Math.max(500_000, 5_000_000 - index * 400_000),
    averageBandwidth: null,
    url,
    codecs: audios.length > 0 ? "avc1.42E01E,mp4a.40.2" : "avc1.42E01E",
    audioGroupId: audios.length > 0 ? audioGroupId : null,
    frameRate: null,
  }));

  const audioTracks: AudioTrack[] = audios.map((uri, index) => ({
    groupId: audioGroupId,
    name: `Audio_${index + 1}`,
    language: null,
    uri,
    isDefault: index === 0,
    autoselect: true,
    channels: null,
  }));

  const source =
    input.sourceMasterUrl ??
    videos[0] ??
    `synthetic://${input.groupKey}`;

  return {
    content,
    dataUrl: playlistToDataUrl(content),
    sourceMasterUrl: source,
    hlsGroupKey: input.groupKey,
    videoUrls: videos,
    audioUrls: audios,
    hasSeparateAudio: audios.length > 0,
    qualities,
    audioTracks,
  };
}
