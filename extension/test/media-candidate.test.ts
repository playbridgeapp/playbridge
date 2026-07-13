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
  const exact = candidate("https://media.example/main.mp4", { frameId: 2 });
  const newerHls = candidate("https://media.example/preview.m3u8", {
    contentType: "application/vnd.apple.mpegurl",
    frameId: 2,
    timestamp: 50,
  });

  assert.equal(
    rankMediaCandidate([newerHls, exact], {
      domUrls: [exact.url],
      senderFrameId: 2,
    }),
    exact,
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
