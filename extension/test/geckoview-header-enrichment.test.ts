import assert from "node:assert/strict";
import test from "node:test";

import { enrichReplayHeaders } from "../src/geckoview/header-enrichment";

test("enriches a headerless GeckoView detection once", () => {
  const detection: { headers?: Record<string, string> } = {};
  const headers = {
    Referer: "https://site.example/",
    "X-Playback-Token": "signed-value",
  };

  assert.equal(enrichReplayHeaders(detection, headers, false), true);
  assert.deepEqual(detection.headers, headers);
});

test("does not replace headers after the detection was already enriched", () => {
  const originalHeaders = { Referer: "https://site.example/" };
  const detection = { headers: originalHeaders };

  assert.equal(
    enrichReplayHeaders(
      detection,
      { Referer: "https://other.example/" },
      true,
    ),
    false,
  );
  assert.equal(detection.headers, originalHeaders);
});

test("ignores an empty header capture", () => {
  const detection: { headers?: Record<string, string> } = {};

  assert.equal(enrichReplayHeaders(detection, {}, false), false);
  assert.equal(detection.headers, undefined);
});
