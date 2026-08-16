import assert from "node:assert/strict";
import test from "node:test";

import {
  normalizeLinkedPageCastPayload,
  normalizeLinkedSupplyPayload,
  normalizePageCastPayload,
  pageCastRequestWithinLimit,
} from "../src/geckoview/page-cast";

test("normalizes a page bridge single-item request", () => {
  assert.deepEqual(
    normalizePageCastPayload({
      url: "https://media.example/video.mp4",
      title: "Video",
      metadata: { title: "Video", posterUrl: "https://media.example/poster.jpg" },
    }),
    {
      items: [{ url: "https://media.example/video.mp4", title: "Video", metadata: { title: "Video", posterUrl: "https://media.example/poster.jpg" } }],
      startIndex: 0,
      metadata: { title: "Video", posterUrl: "https://media.example/poster.jpg" },
    },
  );
});

test("normalizes a page bridge playlist and clamps its start index", () => {
  const request = normalizePageCastPayload({
    items: [
      { url: "https://media.example/one.mp4" },
      { url: "https://media.example/two.mp4" },
    ],
    startIndex: 9,
  });
  assert.equal(request?.items.length, 2);
  assert.equal(request?.startIndex, 1);
});

test("preserves an explicit local-network permission request", () => {
  const direct = normalizePageCastPayload({
    url: "http://media-box.local/video.mp4",
    localNetwork: true,
  });
  assert.equal(direct?.localNetwork, true);
  const linked = normalizeLinkedPageCastPayload({
    items: [{ id: "local-1", url: "http://192.168.1.10/live.m3u8" }],
    localNetwork: true,
  });
  assert.equal(linked?.localNetwork, true);
});

test("rejects unsupported page bridge URLs", () => {
  assert.equal(normalizePageCastPayload({ url: "javascript:alert(1)" }), undefined);
  assert.equal(normalizePageCastPayload([{ url: "blob:abc" }]), undefined);
  assert.equal(normalizePageCastPayload({ url: "https://user:pass@media.example/video.mp4" }), undefined);
});

test("normalizes linked items with safe replay data", () => {
  assert.deepEqual(
    normalizeLinkedPageCastPayload({
      items: [{
        id: "episode-1",
        url: "https://cdn.example/one.m3u8",
        contentType: "application/vnd.apple.mpegurl",
        headers: { Referer: "https://example.com/", Authorization: "Bearer token" },
        subtitles: ["https://cdn.example/one.vtt"],
      }],
      startIndex: 0,
    }),
    {
      items: [{
        id: "episode-1",
        url: "https://cdn.example/one.m3u8",
        contentType: "application/vnd.apple.mpegurl",
        headers: { Referer: "https://example.com/", Authorization: "Bearer token" },
        subtitles: ["https://cdn.example/one.vtt"],
      }],
      startIndex: 0,
    },
  );
});

test("binds provider headers to an explicit cross-origin subtitle resource", () => {
  const request = normalizeLinkedPageCastPayload({
    items: [{
      id: "episode-1",
      url: "https://video.provider.example/one.m3u8",
      headers: { Referer: "https://provider.example/watch" },
      subtitleResources: [{
        url: "https://subtitles.other-provider.example/one.vtt",
        headers: {
          Authorization: "Bearer subtitle-token",
          Referer: "https://other-provider.example/",
        },
        language: "en",
        label: "English",
      }],
    }],
  });
  assert.deepEqual(request?.items[0].subtitleResources, [{
    url: "https://subtitles.other-provider.example/one.vtt",
    headers: {
      Authorization: "Bearer subtitle-token",
      Referer: "https://other-provider.example/",
    },
    language: "en",
    label: "English",
  }]);
});

test("rejects duplicate linked IDs and unsafe headers", () => {
  assert.equal(normalizeLinkedPageCastPayload({
    items: [
      { id: "same", url: "https://cdn.example/one.mp4" },
      { id: "same", url: "https://cdn.example/two.mp4" },
    ],
  }), undefined);
  assert.equal(normalizeLinkedPageCastPayload({
    items: [{ id: "one", url: "https://cdn.example/one.mp4", headers: { Host: "internal" } }],
  }), undefined);
  assert.equal(normalizeLinkedPageCastPayload({
    items: [{ id: "one", url: "https://cdn.example/one.mp4", headers: null }],
  }), undefined);
  assert.equal(normalizeLinkedPageCastPayload({
    items: [{
      id: "one",
      url: "https://cdn.example/one.mp4",
      headers: { Authorization: "ok", authorization: "duplicate" },
    }],
  }), undefined);
  assert.equal(normalizeLinkedPageCastPayload({
    items: [{
      id: "one",
      url: "https://cdn.example/one.mp4",
      headers: { Authorization: "Bearer good\r\nX-Evil: yes" },
    }],
  }), undefined);
});

test("rejects oversized or structurally abusive bridge data", () => {
  assert.equal(normalizePageCastPayload({
    url: "https://media.example/video.mp4",
    metadata: { overview: "x".repeat(17 * 1024) },
  }), undefined);
  assert.equal(pageCastRequestWithinLimit({ value: "x".repeat(65 * 1024) }), false);
  assert.equal(normalizePageCastPayload({
    items: Array.from({ length: 51 }, (_, index) => ({
      url: `https://media.example/${index}.mp4`,
    })),
  }), undefined);
});

test("origin and referer headers may identify a cross-origin provider", () => {
  assert.notEqual(normalizePageCastPayload({
    url: "https://cdn.example/video.mp4",
    headers: { Referer: "https://provider.example/watch" },
  }), undefined);
  assert.equal(normalizePageCastPayload({
    url: "https://cdn.example/video.mp4",
    headers: { Referer: "not a URL" },
  }), undefined);
});

test("requires explicit end-of-list for an empty lazy response", () => {
  assert.equal(normalizeLinkedSupplyPayload({ requestId: "need-1", items: [] }), undefined);
  assert.deepEqual(
    normalizeLinkedSupplyPayload({ requestId: "need-1", items: [], endOfList: true }),
    { requestId: "need-1", items: [], endOfList: true },
  );
});
