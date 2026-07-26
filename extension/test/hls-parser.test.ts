import assert from "node:assert/strict";
import test from "node:test";

import { HlsParser } from "../src/core/hls-parser";

const MASTER_URL =
  "https://edge.example/streams/origin.user.id/llhls.m3u8?token=t";

test("parsePlaylistContent classifies demuxed multivariant masters", () => {
  const body = `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="chunklist_5_audio_99_llhls.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1200000,AVERAGE-BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio"
chunklist_0_video_99_llhls.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio"
chunklist_1_video_99_llhls.m3u8
`;

  const parsed = HlsParser.parsePlaylistContent(body, MASTER_URL);
  assert.equal(parsed.role, "master");
  assert.equal(parsed.hasSeparateAudio, true);
  assert.equal(parsed.videoQualities.length, 2);
  assert.equal(parsed.audioTracks.length, 1);
  assert.ok(parsed.audioTracks[0].uri?.includes("chunklist_5_audio"));
  // Higher bandwidth first.
  assert.equal(parsed.videoQualities[0].resolution, "1080p");
  assert.ok(parsed.videoQualities[0].url.includes("chunklist_1_video"));
});

test("parsePlaylistContent classifies media playlists with parts", () => {
  const videoUrl =
    "https://edge.example/streams/origin.user.id/chunklist_0_video_99_llhls.m3u8?session=s";
  const body = `#EXTM3U
#EXT-X-VERSION:9
#EXT-X-TARGETDURATION:2
#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.5
#EXT-X-PART:DURATION=0.5,URI="part_0_100_0_video_99_llhls.m4s"
#EXTINF:1.0,
seg_0_100_video_99_llhls.m4s
`;

  const parsed = HlsParser.parsePlaylistContent(body, videoUrl);
  assert.equal(parsed.role, "video_media");
  assert.equal(parsed.hasSeparateAudio, false);
  assert.equal(parsed.videoQualities.length, 0);
  assert.ok(parsed.segmentPrefixes.length >= 1);
});

test("parsePlaylistContent classifies audio media by URL when body is segments only", () => {
  const audioUrl =
    "https://edge.example/streams/origin.user.id/chunklist_5_audio_99_llhls.m3u8";
  const body = `#EXTM3U
#EXT-X-TARGETDURATION:2
#EXTINF:2.0,
seg_5_100_audio_99_llhls.m4s
`;

  const parsed = HlsParser.parsePlaylistContent(body, audioUrl);
  assert.equal(parsed.role, "audio_media");
});

test("muxed master without external audio URI is not separate-audio", () => {
  const body = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2"
mid.m3u8
`;
  const parsed = HlsParser.parsePlaylistContent(
    body,
    "https://cdn.example/vod/master.m3u8",
  );
  assert.equal(parsed.role, "master");
  assert.equal(parsed.hasSeparateAudio, false);
  assert.equal(parsed.videoQualities.length, 1);
});
