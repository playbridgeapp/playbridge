interface VideoQuality {
  resolution: string;
  bandwidth: number;
  averageBandwidth: number | null;
  url: string;
  codecs: string | null;
  audioGroupId: string | null;
  frameRate: string | null;
}

interface AudioTrack {
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
}

export class HlsParser {
  static async parsePlaylist(masterPlaylistUrl: string): Promise<HlsPlaylist> {
    const empty: HlsPlaylist = { videoQualities: [], audioTracks: [], masterPlaylistUrl, segmentPrefixes: [] };
    try {
      const response = await fetch(masterPlaylistUrl);
      const content = await response.text();

      if (!content.startsWith("#EXTM3U")) return empty;

      const videoQualities: VideoQuality[] = [];
      const audioTracks: AudioTrack[] = [];
      const segmentPrefixes = new Set<string>();

      const lines = content.split("\n");
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
              try { uri = new URL(uriMatch[1], masterPlaylistUrl).toString(); } catch (_) {}
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
              const variantUrl = new URL(line, masterPlaylistUrl).toString();
              const label = currentResolution ? currentResolution.split("x")[1] + "p" : "Unknown";
              videoQualities.push({
                resolution: label,
                bandwidth: currentBandwidth,
                averageBandwidth: currentAverageBandwidth,
                url: variantUrl,
                codecs: currentCodecs,
                audioGroupId: currentAudioGroup,
                frameRate: currentFrameRate,
              });
            } catch (_) {}
            currentBandwidth = currentAverageBandwidth = currentResolution =
              currentCodecs = currentAudioGroup = currentFrameRate = null;
          } else {
            try {
              const segUrl = new URL(line, masterPlaylistUrl).toString();
              const prefix = segUrl.substring(0, segUrl.lastIndexOf("/") + 1);
              if (prefix.startsWith("http")) segmentPrefixes.add(prefix);
            } catch (_) {}
          }
        }
      }

      videoQualities.sort((a, b) => b.bandwidth - a.bandwidth);
      return { videoQualities, audioTracks, masterPlaylistUrl, segmentPrefixes: [...segmentPrefixes] };
    } catch (e) {
      console.error("[HlsParser] error:", e);
      return empty;
    }
  }
}
