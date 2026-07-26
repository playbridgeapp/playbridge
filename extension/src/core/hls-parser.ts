import {
  classifyHlsPlaylistBody,
  type HlsRole,
} from "./media-candidate";

export interface VideoQuality {
  resolution: string;
  bandwidth: number;
  averageBandwidth: number | null;
  url: string;
  codecs: string | null;
  audioGroupId: string | null;
  frameRate: string | null;
}

export interface AudioTrack {
  groupId: string;
  name: string;
  language: string | null;
  uri: string | null;
  isDefault: boolean;
  autoselect: boolean;
  channels: string | null;
}

export interface HlsPlaylist {
  videoQualities: VideoQuality[];
  audioTracks: AudioTrack[];
  masterPlaylistUrl: string;
  segmentPrefixes: string[];
  /** Role inferred from playlist tags (+ URL hints). */
  role: HlsRole;
  /** True when at least one AUDIO media entry has its own URI (demuxed). */
  hasSeparateAudio: boolean;
}

function emptyPlaylist(masterPlaylistUrl: string): HlsPlaylist {
  return {
    videoQualities: [],
    audioTracks: [],
    masterPlaylistUrl,
    segmentPrefixes: [],
    role: "not_hls",
    hasSeparateAudio: false,
  };
}

export class HlsParser {
  /**
   * Parse a playlist string without fetching. Used by tests and any path that
   * already has body text (e.g. Firefox filterResponseData).
   */
  static parsePlaylistContent(
    content: string,
    playlistUrl: string,
  ): HlsPlaylist {
    const empty = emptyPlaylist(playlistUrl);
    const text = content.trim();
    if (!text.startsWith("#EXTM3U")) return empty;

    const videoQualities: VideoQuality[] = [];
    const audioTracks: AudioTrack[] = [];
    const segmentPrefixes = new Set<string>();

    const lines = text.split("\n");
    let currentBandwidth: number | null = null;
    let currentAverageBandwidth: number | null = null;
    let currentResolution: string | null = null;
    let currentCodecs: string | null = null;
    let currentAudioGroup: string | null = null;
    let currentFrameRate: string | null = null;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();

      if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
        const attrs = line.substring(line.indexOf(":") + 1);
        const groupIdMatch = attrs.match(/GROUP-ID="([^"]+)"/);
        const nameMatch = attrs.match(/NAME="([^"]+)"/);
        const languageMatch = attrs.match(/LANGUAGE="([^"]+)"/);
        const uriMatch = attrs.match(/URI="([^"]+)"/);
        const channelsMatch = attrs.match(/CHANNELS="([^"]+)"/);

        if (groupIdMatch && nameMatch) {
          let uri: string | null = null;
          if (uriMatch) {
            try {
              uri = new URL(uriMatch[1], playlistUrl).toString();
            } catch {
              /* ignore malformed relative URI */
            }
          }
          audioTracks.push({
            groupId: groupIdMatch[1],
            name: nameMatch[1],
            language: languageMatch?.[1] ?? null,
            uri,
            isDefault: /DEFAULT=YES/.test(attrs),
            autoselect: /AUTOSELECT=YES/.test(attrs),
            channels: channelsMatch?.[1] ?? null,
          });
        }
      } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
        const attrs = line.substring(line.indexOf(":") + 1);
        const bwMatch = attrs.match(/BANDWIDTH=(\d+)/);
        if (bwMatch) currentBandwidth = parseInt(bwMatch[1], 10);
        const avgBwMatch = attrs.match(/AVERAGE-BANDWIDTH=(\d+)/);
        if (avgBwMatch) currentAverageBandwidth = parseInt(avgBwMatch[1], 10);
        const resMatch = attrs.match(/RESOLUTION=(\d+x\d+)/);
        if (resMatch) currentResolution = resMatch[1];
        const codecsMatch = attrs.match(/CODECS="([^"]+)"/);
        if (codecsMatch) currentCodecs = codecsMatch[1];
        const audioMatch = attrs.match(/AUDIO="([^"]+)"/);
        if (audioMatch) currentAudioGroup = audioMatch[1];
        const frMatch = attrs.match(/FRAME-RATE=([\d.]+)/);
        if (frMatch) currentFrameRate = frMatch[1];
      } else if (!line.startsWith("#") && line.length > 0) {
        if (currentBandwidth !== null) {
          try {
            const variantUrl = new URL(line, playlistUrl).toString();
            const label = currentResolution
              ? currentResolution.split("x")[1] + "p"
              : "Unknown";
            videoQualities.push({
              resolution: label,
              bandwidth: currentBandwidth,
              averageBandwidth: currentAverageBandwidth,
              url: variantUrl,
              codecs: currentCodecs,
              audioGroupId: currentAudioGroup,
              frameRate: currentFrameRate,
            });
          } catch {
            /* ignore malformed variant URI */
          }
          currentBandwidth =
            currentAverageBandwidth =
            currentResolution =
            currentCodecs =
            currentAudioGroup =
            currentFrameRate =
              null;
        } else {
          try {
            const segUrl = new URL(line, playlistUrl).toString();
            const prefix = segUrl.substring(0, segUrl.lastIndexOf("/") + 1);
            if (prefix.startsWith("http")) segmentPrefixes.add(prefix);
          } catch {
            /* ignore malformed segment URI */
          }
        }
      }
    }

    videoQualities.sort((a, b) => b.bandwidth - a.bandwidth);

    const hasSeparateAudio = audioTracks.some(
      (track) => typeof track.uri === "string" && track.uri.length > 0,
    );

    // Prefer structural tags; fall back to URL naming for demuxed media lists.
    let role = classifyHlsPlaylistBody(text, playlistUrl);
    if (videoQualities.length > 0) role = "master";
    else if (role === "media" || role === "not_hls") {
      // Keep URL-refined video/audio when body only has segments.
      role = classifyHlsPlaylistBody(text, playlistUrl);
    }

    return {
      videoQualities,
      audioTracks,
      masterPlaylistUrl: playlistUrl,
      segmentPrefixes: [...segmentPrefixes],
      role,
      hasSeparateAudio,
    };
  }

  static async parsePlaylist(
    masterPlaylistUrl: string,
    headers?: Record<string, string>,
  ): Promise<HlsPlaylist> {
    const empty = emptyPlaylist(masterPlaylistUrl);
    // Enrichment is best-effort: time the fetch out so a stalling origin can
    // never wedge the caller. (fetch silently drops forbidden headers like
    // Referer/Origin/User-Agent, but passing what we have is harmless.)
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(masterPlaylistUrl, {
        headers,
        signal: controller.signal,
      });
      const content = await response.text();
      return HlsParser.parsePlaylistContent(content, masterPlaylistUrl);
    } catch (e) {
      console.error("[HlsParser] error:", e);
      return empty;
    } finally {
      clearTimeout(timer);
    }
  }
}
