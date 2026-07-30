import assert from "node:assert/strict";
import test from "node:test";

import {
  attachBoundedResponseBodyScanner,
  scanResponseBodyForMedia,
  shouldInspectResponseBody,
  type ResponseBodyStreamFilter,
} from "../src/core/response-body-media";

test("recognizes extensionless HLS and DASH response bodies", () => {
  assert.equal(
    scanResponseBodyForMedia(
      "#EXTM3U\n#EXT-X-TARGETDURATION:6\n",
      "https://media.example/session",
    ).responseKind,
    "hls",
  );
  assert.equal(
    scanResponseBodyForMedia(
      '<?xml version="1.0"?><MPD type="static"></MPD>',
      "https://media.example/playback",
    ).responseKind,
    "dash",
  );
});

test("extracts contextual media URLs from nested JSON", () => {
  const body = JSON.stringify({
    data: {
      sources: [
        {
          file: "https://cdn.example/protected/playback?id=42",
          type: "application/dash+xml",
        },
        {
          url: "/streams/master.m3u8?token=abc",
        },
      ],
      poster: "https://cdn.example/poster.jpg",
      apiUrl: "https://api.example/next-page",
    },
  });

  assert.deepEqual(
    scanResponseBodyForMedia(
      body,
      "https://site.example/api/player",
      "application/json",
    ).embeddedCandidates,
    [
      {
        url: "https://cdn.example/protected/playback?id=42",
        contentType: "application/dash+xml",
      },
      {
        url: "https://site.example/streams/master.m3u8?token=abc",
        contentType: "application/vnd.apple.mpegurl",
      },
    ],
  );
});

test("extracts escaped assignments and media element sources from text", () => {
  const body = String.raw`
    const config = { streamUrl: "https:\/\/cdn.example\/live\/master.m3u8" };
    <source src="/video/movie.mp4">
    const docs = "https://site.example/help";
  `;

  assert.deepEqual(
    scanResponseBodyForMedia(
      body,
      "https://site.example/embed/player",
      "text/html",
    ).embeddedCandidates,
    [
      {
        url: "https://cdn.example/live/master.m3u8",
        contentType: "application/vnd.apple.mpegurl",
      },
      {
        url: "https://site.example/video/movie.mp4",
        contentType: "video/mp4",
      },
    ],
  );
});

test("limits embedded candidates and ignores unrelated URLs", () => {
  const body = JSON.stringify({
    docs: "https://site.example/help",
    poster: "https://site.example/poster.jpg",
    sources: [
      { file: "https://cdn.example/one" },
      { file: "https://cdn.example/two" },
      { file: "https://cdn.example/three" },
    ],
  });

  assert.deepEqual(
    scanResponseBodyForMedia(
      body,
      "https://site.example/api",
      "application/json",
      2,
    ).embeddedCandidates.map((candidate) => candidate.url),
    ["https://cdn.example/one", "https://cdn.example/two"],
  );
});

test("selects only eligible textual response types", () => {
  assert.equal(
    shouldInspectResponseBody("application/json; charset=utf-8", "xmlhttprequest"),
    true,
  );
  assert.equal(
    shouldInspectResponseBody("application/octet-stream", "xmlhttprequest"),
    true,
  );
  assert.equal(shouldInspectResponseBody("video/mp4", "media"), false);
  assert.equal(shouldInspectResponseBody("image/jpeg", "image"), false);
  assert.equal(shouldInspectResponseBody("text/css", "stylesheet"), false);
  assert.equal(shouldInspectResponseBody("", "script"), false);
});

test("tees all bytes while scanning only the bounded prefix", () => {
  const writes: ArrayBuffer[] = [];
  let disconnects = 0;
  let scanned = "";
  const filter: ResponseBodyStreamFilter = {
    ondata: null,
    onstop: null,
    onerror: null,
    write: (data) => writes.push(data),
    disconnect: () => {
      disconnects += 1;
    },
  };

  attachBoundedResponseBodyScanner(
    filter,
    (body) => {
      scanned = body;
    },
    5,
  );

  const encoder = new TextEncoder();
  filter.ondata?.({ data: encoder.encode("abc").buffer as ArrayBuffer });
  filter.ondata?.({ data: encoder.encode("defg").buffer as ArrayBuffer });
  filter.onstop?.();

  assert.equal(writes.length, 2);
  assert.equal(new TextDecoder().decode(writes[0]), "abc");
  assert.equal(new TextDecoder().decode(writes[1]), "defg");
  assert.equal(scanned, "abcde");
  assert.equal(disconnects, 1);
});
