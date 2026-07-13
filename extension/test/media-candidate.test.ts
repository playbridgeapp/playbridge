import assert from "node:assert/strict";
import test from "node:test";

import {
  rankMediaCandidate,
  type MediaCandidate,
} from "../src/media-candidate";

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
