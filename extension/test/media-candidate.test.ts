import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyHlsPlaylistBody,
  classifyHlsUrl,
  detectionEvidencePriority,
  filterPrimaryCastCandidates,
  hlsIdentityKey,
  hlsStreamGroupKey,
  isExclusiveBootstrapMaster,
  isSyntheticHlsDataUrl,
  matchResolvedCastRecord,
  pickCompanionAudio,
  rankMediaCandidate,
  resolveCastableHlsUrl,
  type MediaCandidate,
} from "../src/core/media-candidate";
import {
  buildSyntheticFromMasterBody,
  preferredSyntheticCastUrl,
} from "../src/core/synthetic-hls";

function candidate(
  url: string,
  overrides: Partial<MediaCandidate> = {},
): MediaCandidate {
  return {
    url,
    contentType: "video/mp4",
    detectedBy: "content_type",
    timestamp: 1,
    ...overrides,
  };
}

const STREAM_DIR =
  "https://edge20-atl.live.mmcdn.com/v1/edge/streams/origin.user.01ABC/";
const MASTER = `${STREAM_DIR}llhls.m3u8?token=abc`;
const VIDEO_0 = `${STREAM_DIR}chunklist_0_video_123_llhls.m3u8?session=s1`;
const VIDEO_0_POLL = `${STREAM_DIR}chunklist_0_video_123_llhls.m3u8?session=s1&_HLS_msn=2104&_HLS_part=1`;
const VIDEO_1 = `${STREAM_DIR}chunklist_1_video_123_llhls.m3u8?session=s1`;
const AUDIO = `${STREAM_DIR}chunklist_5_audio_123_llhls.m3u8?session=s1`;
const AUDIO_POLL = `${STREAM_DIR}chunklist_5_audio_123_llhls.m3u8?session=s1&_HLS_msn=2105&_HLS_part=0`;

test("response-body evidence outranks URL guesses", () => {
  assert.ok(
    detectionEvidencePriority("body_content_m3u8") >
      detectionEvidencePriority("url_pattern_m3u8"),
  );
  assert.ok(
    detectionEvidencePriority("player_config") >
      detectionEvidencePriority("dom_source"),
  );
});

test("an exact DOM URL is authoritative", () => {
  const exact = candidate("https://media.example/main.mp4", {
    frameId: 2,
    hasHeaders: true,
  });
  const newerHls = candidate("https://media.example/preview.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    frameId: 2,
    timestamp: 50,
    hasHeaders: true,
  });

  assert.equal(
    rankMediaCandidate([newerHls, exact], {
      domUrls: [exact.url],
      senderFrameId: 2,
    }),
    exact,
  );
});

test("a pure DOM source does not outrank a header-bearing network stream", () => {
  // Sites often put a page-origin get_file URL on <video src> while the real
  // media request hits a CDN with Cookie/Referer — the cast icon must pick the
  // network record the popup would use.
  const domSrc = candidate("https://site.example/get_file/clip.mp4", {
    detectedBy: "dom_source",
    frameId: 0,
    timestamp: 200,
    hasHeaders: false,
  });
  const cdnStream = candidate(
    "https://cdn.example/remote_control.php?file=clip.mp4",
    {
      detectedBy: "content_type",
      frameId: 0,
      timestamp: 100,
      hasHeaders: true,
    },
  );

  assert.equal(
    rankMediaCandidate([domSrc, cdnStream], {
      domUrls: [domSrc.url],
      senderFrameId: 0,
      frameResourceUrls: [cdnStream.url],
    }),
    cdnStream,
  );
});

test("headerless network detections yield to a stream with replay headers", () => {
  const headerless = candidate("https://site.example/get_file/clip.mp4", {
    detectedBy: "url_extension",
    frameId: 0,
    timestamp: 150,
    hasHeaders: false,
  });
  const withHeaders = candidate("https://cdn.example/stream.mp4", {
    detectedBy: "content_type",
    frameId: 0,
    timestamp: 100,
    hasHeaders: true,
  });

  assert.equal(
    rankMediaCandidate([headerless, withHeaders], {
      domUrls: [headerless.url],
      senderFrameId: 0,
    }),
    withHeaders,
  );
});

test("header-bearing CDN stream beats same-frame headerless even with better scope", () => {
  // Progressive players: <video src> is same-frame + headerless; the real media
  // request is often attributed to another frameId under Fission.
  const domSrc = candidate("https://site.example/get_file/clip.mp4", {
    detectedBy: "dom_source",
    frameId: 0,
    timestamp: 200,
    hasHeaders: false,
  });
  const cdnStream = candidate(
    "https://nvs.cdn.example/remote_control.php?file=clip.mp4",
    {
      detectedBy: "content_type",
      frameId: 3,
      timestamp: 100,
      hasHeaders: true,
    },
  );

  assert.equal(
    rankMediaCandidate([domSrc, cdnStream], {
      domUrls: [domSrc.url],
      senderFrameId: 0,
    }),
    cdnStream,
  );
});

test("exact DOM match without hasHeaders true is not authoritative", () => {
  const exact = candidate("https://media.example/main.mp4", {
    frameId: 2,
    hasHeaders: false,
  });
  const newer = candidate("https://media.example/real.mp4", {
    frameId: 2,
    timestamp: 50,
    hasHeaders: true,
  });

  assert.equal(
    rankMediaCandidate([exact, newer], {
      domUrls: [exact.url],
      senderFrameId: 2,
    }),
    newer,
  );
});

test("an exact-frame stream outranks an unobserved same-origin fallback", () => {
  const main = candidate("https://media.example/main.mp4", { frameId: 0 });
  const iframeHls = candidate("https://media.example/iframe.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    frameId: 4,
    timestamp: 100,
  });

  assert.equal(
    rankMediaCandidate([iframeHls, main], {
      domUrls: [],
      senderFrameId: 0,
    }),
    main,
  );
});

test("frame-local resource evidence repairs a mismatched Fission frame id", () => {
  const unrelated = candidate("https://media.example/unrelated.mp4", {
    frameId: 3,
    timestamp: 20,
  });
  const mseHls = candidate("https://media.example/player.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    frameId: 0,
    timestamp: 10,
  });

  assert.equal(
    rankMediaCandidate([unrelated, mseHls], {
      domUrls: ["blob:https://site.example/player"],
      senderFrameId: 3,
      frameResourceUrls: [mseHls.url],
    }),
    mseHls,
  );
});

test("a current lifecycle candidate outranks an older master playlist", () => {
  const oldMaster = candidate("https://media.example/old-master.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    frameId: 1,
    timestamp: 10,
    qualities: [{}],
  });
  const currentDirect = candidate("https://media.example/current.mp4", {
    frameId: 1,
    timestamp: 100,
  });

  assert.equal(
    rankMediaCandidate([oldMaster, currentDirect], {
      domUrls: [],
      senderFrameId: 1,
      preferredSince: 90,
    }),
    currentDirect,
  );
});

test("the newest current-lifecycle request wins before format preference", () => {
  const earlierMaster = candidate("https://media.example/preloaded-master.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    frameId: 1,
    timestamp: 95,
    qualities: [{}],
  });
  const currentDirect = candidate("https://media.example/current.mp4", {
    frameId: 1,
    timestamp: 100,
  });

  assert.equal(
    rankMediaCandidate([earlierMaster, currentDirect], {
      domUrls: [],
      senderFrameId: 1,
      preferredSince: 90,
    }),
    currentDirect,
  );
});

test("segments and subtitles are never selected", () => {
  const segment = candidate("https://media.example/chunk-10.m4s", {
    frameId: 0,
    timestamp: 20,
  });
  const subtitle = candidate("https://media.example/captions.vtt", {
    detectedBy: "subtitle_extension",
    frameId: 0,
    timestamp: 30,
  });

  assert.equal(
    rankMediaCandidate([segment, subtitle], {
      domUrls: [],
      senderFrameId: 0,
    }),
    null,
  );
});

// ── Demuxed LL-HLS / live CDN ────────────────────────────────────────────────

test("classifyHlsUrl distinguishes master, video media, and audio media", () => {
  assert.equal(classifyHlsUrl(MASTER), "master");
  assert.equal(classifyHlsUrl(VIDEO_0), "video_media");
  assert.equal(classifyHlsUrl(VIDEO_0_POLL), "video_media");
  assert.equal(classifyHlsUrl(AUDIO), "audio_media");
  assert.equal(classifyHlsUrl(AUDIO_POLL), "audio_media");
  assert.equal(classifyHlsUrl("https://cdn.example/index.m3u8"), "master");
  assert.equal(classifyHlsUrl("https://cdn.example/master.m3u8"), "master");
});

test("hlsIdentityKey strips LL-HLS blocking-reload params but keeps session", () => {
  const a = hlsIdentityKey(VIDEO_0);
  const b = hlsIdentityKey(VIDEO_0_POLL);
  assert.equal(a, b);
  assert.match(a, /session=s1/);
  assert.doesNotMatch(a, /_HLS_/);
});

test("hlsStreamGroupKey groups master with demuxed children", () => {
  const g = hlsStreamGroupKey(MASTER);
  assert.equal(hlsStreamGroupKey(VIDEO_0), g);
  assert.equal(hlsStreamGroupKey(AUDIO), g);
  assert.equal(hlsStreamGroupKey(VIDEO_1), g);
});

test("audio media playlists are never primary cast candidates", () => {
  const audio = candidate(AUDIO, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "audio_media",
    hasHeaders: true,
    timestamp: 500,
  });
  const video = candidate(VIDEO_0, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: true,
    timestamp: 400,
  });
  assert.deepEqual(filterPrimaryCastCandidates([audio, video]), [video]);
  assert.equal(
    rankMediaCandidate([audio, video], { domUrls: [], senderFrameId: 0 }),
    video,
  );
});

test("exclusive bootstrap masters are detected by token without session", () => {
  assert.equal(isExclusiveBootstrapMaster(MASTER, "master"), true);
  assert.equal(isExclusiveBootstrapMaster(VIDEO_0, "video_media"), false);
  assert.equal(
    isExclusiveBootstrapMaster(
      "https://cdn.example/master.m3u8?session=abc",
      "master",
    ),
    false,
  );
});

test("synthetic master always wins over exclusive bootstrap and live media", () => {
  const body = `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="A",DEFAULT=YES,URI="${AUDIO}"
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO="a"
${VIDEO_0}
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="a"
${VIDEO_1}
`;
  const built = buildSyntheticFromMasterBody(body, MASTER);
  assert.ok(built);
  // Highest bandwidth first for fallback open URL.
  assert.equal(preferredSyntheticCastUrl(built!), VIDEO_1);
  assert.equal(built!.videoUrls[0], VIDEO_1);
  // Cast URL is a real https session media URL (mpv cannot open data:).
  const synth = candidate(built!.videoUrls[0]!, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    isSyntheticMaster: true,
    hlsGroupKey: built!.hlsGroupKey,
    hlsIdentityKey: `synthetic:${built!.hlsGroupKey}`,
    hasSeparateAudio: true,
    hasHeaders: true,
    timestamp: 50,
    syntheticPlaylist: built!.content,
    audioUrl: built!.audioUrls[0],
  });
  const master = candidate(MASTER, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    hasHeaders: true,
    timestamp: 10,
  });
  // Same URL as synth open target — must not win on identity collision.
  const video = candidate(VIDEO_1, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: true,
    timestamp: 999,
  });

  assert.equal(isSyntheticHlsDataUrl(built!.dataUrl), true);
  const primary = filterPrimaryCastCandidates([master, video, synth]);
  assert.equal(primary.length, 1);
  assert.equal(primary[0], synth);
  assert.equal(resolveCastableHlsUrl(master, [master, video, synth]), synth);
  assert.equal(resolveCastableHlsUrl(video, [master, video, synth]), synth);

  // URL-only re-find would pick `video` first; matchResolved keeps synthetic body.
  const matched = matchResolvedCastRecord(synth, [video, synth]);
  assert.equal(matched, synth);
  assert.equal(matched.syntheticPlaylist, built!.content);
});

test("matchResolvedCastRecord prefers synthetic when open URL collides", () => {
  const video = candidate(VIDEO_0, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: true,
    hlsGroupKey: hlsStreamGroupKey(VIDEO_0),
  });
  const synth = candidate(VIDEO_0, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    isSyntheticMaster: true,
    hlsGroupKey: hlsStreamGroupKey(VIDEO_0),
    hlsIdentityKey: `synthetic:${hlsStreamGroupKey(VIDEO_0)}`,
    syntheticPlaylist: "#EXTM3U\n",
    hasHeaders: true,
  });
  // Pool order: plain video first — URL find would drop the body.
  assert.equal(matchResolvedCastRecord(synth, [video, synth]), synth);
  assert.equal(
    matchResolvedCastRecord(
      { ...synth, url: VIDEO_0 },
      [video, synth],
    ).isSyntheticMaster,
    true,
  );
});

test("exclusive bootstrap master yields to live session video media", () => {
  // MASTER has ?token= only — replaying it returns session_duplicated.
  // Live chunklists with session= are what the browser is actually using.
  const master = candidate(MASTER, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    hasHeaders: true,
    timestamp: 10,
    qualities: [{ resolution: "720p", bandwidth: 1e6 }],
    hasSeparateAudio: true,
  });
  const video = candidate(VIDEO_0_POLL, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: true,
    timestamp: 999,
  });
  const audio = candidate(AUDIO_POLL, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "audio_media",
    hasHeaders: true,
    timestamp: 1000,
  });

  const primary = filterPrimaryCastCandidates([master, video, audio]);
  assert.equal(primary.length, 1);
  assert.equal(primary[0], video);

  assert.equal(
    rankMediaCandidate([master, video, audio], {
      domUrls: [],
      senderFrameId: 0,
      preferredSince: 1,
      frameResourceUrls: [VIDEO_0_POLL, AUDIO_POLL],
    }),
    video,
  );

  assert.equal(resolveCastableHlsUrl(master, [master, video, audio]), video);
  assert.equal(resolveCastableHlsUrl(video, [master, video, audio]), video);
  assert.equal(
    pickCompanionAudio(video, [master, video, audio])?.url,
    audio.url,
  );
});

test("reusable (non-exclusive) master still preferred over demuxed children", () => {
  const master = candidate("https://cdn.example/vod/master.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    hasHeaders: true,
    timestamp: 100,
    lastSeen: 100,
    qualities: [{}],
  });
  const video = candidate("https://cdn.example/vod/720p.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: true,
    timestamp: 500,
    lastSeen: 500,
  });

  const primary = filterPrimaryCastCandidates([master, video]);
  assert.equal(primary.length, 1);
  assert.equal(primary[0], master);
  assert.equal(resolveCastableHlsUrl(video, [master, video]), master);
  assert.equal(resolveCastableHlsUrl(master, [master, video]), master);
});

test("resolveCastableHlsUrl keeps muxed quality media when allowed", () => {
  const master = candidate("https://cdn.example/vod/master.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    hasHeaders: true,
    hasSeparateAudio: false,
  });
  const variant = candidate("https://cdn.example/vod/720p.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: false,
  });

  assert.equal(
    resolveCastableHlsUrl(variant, [master, variant], {
      allowMuxedMediaVariant: true,
    }),
    variant,
  );
  assert.equal(
    resolveCastableHlsUrl(variant, [master, variant], {
      allowMuxedMediaVariant: false,
    }),
    master,
  );
});

test("resolveCastableHlsUrl keeps live video for exclusive demuxed masters", () => {
  // Token-only master + session video: never upgrade back to the master token.
  const master = candidate(MASTER, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    hasHeaders: true,
    hasSeparateAudio: true,
  });
  const variant = candidate(VIDEO_0, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "video_media",
    hasHeaders: true,
  });

  assert.equal(
    resolveCastableHlsUrl(variant, [master, variant], {
      allowMuxedMediaVariant: true,
    }),
    variant,
  );
  assert.equal(
    resolveCastableHlsUrl(master, [master, variant]),
    variant,
  );
});

test("master stays selectable when only media playlists appear in Performance", () => {
  const master = candidate(MASTER, {
    contentType: "application/vnd.apple.mpegurl",
    hlsRole: "master",
    hasHeaders: true,
    timestamp: 10,
    frameId: 0,
    qualities: [{}],
  });
  const other = candidate("https://cdn.example/other/clip.mp4", {
    frameId: 0,
    timestamp: 5,
    hasHeaders: true,
  });

  const ranked = rankMediaCandidate([master, other], {
    domUrls: [],
    senderFrameId: 0,
    frameResourceUrls: [VIDEO_0_POLL, AUDIO_POLL],
  });
  assert.equal(ranked, master);
});

test("classifyHlsPlaylistBody detects master vs media from tags", () => {
  const masterBody = `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Default",DEFAULT=YES,URI="chunklist_5_audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO="a"
chunklist_0_video.m3u8
`;
  assert.equal(classifyHlsPlaylistBody(masterBody, MASTER), "master");

  const mediaBody = `#EXTM3U
#EXT-X-TARGETDURATION:2
#EXT-X-PART:DURATION=0.5,URI="part_0.m4s"
#EXTINF:2.0,
seg_0.m4s
`;
  assert.equal(
    classifyHlsPlaylistBody(mediaBody, VIDEO_0),
    "video_media",
  );
  assert.equal(
    classifyHlsPlaylistBody(mediaBody, AUDIO),
    "audio_media",
  );
});
