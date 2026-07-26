import assert from "node:assert/strict";
import test from "node:test";

import {
  buildSyntheticFromMasterBody,
  buildSyntheticFromObservations,
  chunklistLadderIndex,
  expandChunklistLadder,
  observationSyntheticImproves,
  playlistToDataUrl,
  preferredSyntheticCastUrl,
  rewriteMasterBodyAbsolute,
  sortMediaPlaylistsByQualityDesc,
} from "../src/core/synthetic-hls";

const MASTER =
  "https://edge2-atl.live.mmcdn.com/v1/edge/streams/origin.user.01ABC/llhls.m3u8?token=tok";

const BODY = `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac_128",NAME="Audio_1",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="/v1/edge/streams/origin.user.01ABC/chunklist_5_audio_99_llhls.m3u8?session=sess-1"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac_96",NAME="Audio_2",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="/v1/edge/streams/origin.user.01ABC/chunklist_4_audio_99_llhls.m3u8?session=sess-1"
#EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,FRAME-RATE=30.039,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio_aac_96"
/v1/edge/streams/origin.user.01ABC/chunklist_0_video_99_llhls.m3u8?session=sess-1
#EXT-X-STREAM-INF:BANDWIDTH=5128000,RESOLUTION=1920x1080,FRAME-RATE=30.039,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio_aac_128"
/v1/edge/streams/origin.user.01ABC/chunklist_3_video_99_llhls.m3u8?session=sess-1
`;

test("rewriteMasterBodyAbsolute resolves relative session URIs", () => {
  const { content, videoUrls, audioUrls } = rewriteMasterBodyAbsolute(
    BODY,
    MASTER,
  );
  assert.ok(content.includes("https://edge2-atl.live.mmcdn.com/v1/edge/streams/"));
  assert.ok(content.includes("session=sess-1"));
  assert.equal(videoUrls.length, 2);
  assert.equal(audioUrls.length, 2);
  assert.ok(videoUrls[0]!.startsWith("https://"));
  assert.ok(audioUrls[0]!.includes("chunklist_5_audio"));
  // Must not leave path-only URIs
  assert.doesNotMatch(content, /\n\/v1\/edge\//);
});

test("buildSyntheticFromMasterBody produces castable data URL multivariant", () => {
  const synth = buildSyntheticFromMasterBody(BODY, MASTER);
  assert.ok(synth);
  assert.equal(synth!.hasSeparateAudio, true);
  assert.equal(synth!.qualities.length, 2);
  assert.equal(synth!.qualities[0]!.resolution, "1080p"); // sorted by bandwidth
  // Fallback open URL is highest bandwidth (1080p), not body order (360p first).
  assert.ok(preferredSyntheticCastUrl(synth!)!.includes("chunklist_3_video"));
  assert.ok(synth!.videoUrls[0]!.includes("chunklist_3_video"));
  assert.ok(synth!.dataUrl.startsWith("data:application/vnd.apple.mpegurl"));
  assert.ok(synth!.content.includes("#EXT-X-STREAM-INF:"));
  assert.ok(synth!.content.includes("#EXT-X-MEDIA:TYPE=AUDIO"));
  // Decode data URL and ensure absolute children
  const encoded = synth!.dataUrl.split(",", 2)[1]!;
  const decoded = decodeURIComponent(encoded);
  assert.ok(decoded.includes("https://edge2-atl.live.mmcdn.com/"));
  assert.ok(decoded.includes("session=sess-1"));
});

test("buildSyntheticFromObservations builds minimal demuxed master", () => {
  const synth = buildSyntheticFromObservations({
    groupKey: "https://edge.example/streams/x/",
    videoUrls: [
      "https://edge.example/streams/x/chunklist_0_video_h_llhls.m3u8?session=s",
      "https://edge.example/streams/x/chunklist_1_video_h_llhls.m3u8?session=s",
    ],
    audioUrls: [
      "https://edge.example/streams/x/chunklist_5_audio_h_llhls.m3u8?session=s",
    ],
  });
  assert.ok(synth);
  assert.equal(synth!.hasSeparateAudio, true);
  assert.equal(synth!.videoUrls.length, 2);
  assert.equal(synth!.audioUrls.length, 1);
  // Highest chunklist index first (not insertion / recency order).
  assert.ok(synth!.videoUrls[0]!.includes("chunklist_1_video"));
  assert.equal(preferredSyntheticCastUrl(synth!)!.includes("chunklist_1_video"), true);
  assert.ok(synth!.content.includes('AUDIO="audio"'));
  assert.ok(synth!.dataUrl.startsWith(playlistToDataUrl("").slice(0, 40)));
});

test("chunklist ladder prefers higher index (1080p) over first-seen 360p", () => {
  const low =
    "https://edge.mmcdn.test/streams/u/chunklist_0_video_HASH_llhls.m3u8?session=s1";
  const high =
    "https://edge.mmcdn.test/streams/u/chunklist_4_video_HASH_llhls.m3u8?session=s1";
  assert.equal(chunklistLadderIndex(low), 0);
  assert.equal(chunklistLadderIndex(high), 4);
  assert.deepEqual(sortMediaPlaylistsByQualityDesc([low, high]), [high, low]);

  // Expand gaps 1–3 when 0 and 4 observed.
  const expanded = expandChunklistLadder([low, high]);
  assert.equal(expanded.length, 5);
  assert.ok(expanded.some((u) => u.includes("chunklist_2_video")));

  const synth = buildSyntheticFromObservations({
    groupKey: "https://edge.mmcdn.test/streams/u/",
    // Chrome-style: only the ABR-selected low rung first in the list.
    videoUrls: [low, high],
    audioUrls: [
      "https://edge.mmcdn.test/streams/u/chunklist_5_audio_HASH_llhls.m3u8?session=s1",
    ],
  });
  assert.ok(synth);
  assert.ok(preferredSyntheticCastUrl(synth!)!.includes("chunklist_4_video"));
  assert.ok(synth!.qualities[0]!.resolution === "1080p");
});

test("observationSyntheticImproves detects higher rung", () => {
  const low = buildSyntheticFromObservations({
    groupKey: "g",
    videoUrls: [
      "https://e/x/chunklist_0_video_h_llhls.m3u8?session=s",
    ],
    audioUrls: [],
  })!;
  const high = buildSyntheticFromObservations({
    groupKey: "g",
    videoUrls: [
      "https://e/x/chunklist_0_video_h_llhls.m3u8?session=s",
      "https://e/x/chunklist_3_video_h_llhls.m3u8?session=s",
    ],
    audioUrls: [],
  })!;
  assert.equal(
    observationSyntheticImproves(low.videoUrls, low.audioUrls, high),
    true,
  );
  assert.equal(
    observationSyntheticImproves(high.videoUrls, high.audioUrls, low),
    false,
  );
});

test("non-master body returns null", () => {
  const media = `#EXTM3U
#EXT-X-TARGETDURATION:2
#EXTINF:2.0,
seg.m4s
`;
  assert.equal(buildSyntheticFromMasterBody(media, MASTER), null);
});
