import assert from "node:assert/strict";
import test from "node:test";

import {
  advanceNavigationGeneration,
  currentNavigationGeneration,
  isCurrentNavigationGeneration,
  MainFrameDetectionGate,
  responseBodyNavigationGeneration,
  shouldStageMainFrameDetection,
} from "../src/geckoview/detection-lifecycle";

test("advances independent navigation generations per GeckoView tab", () => {
  const generations = new Map<number, number>();

  assert.equal(advanceNavigationGeneration(generations, 4), 1);
  assert.equal(advanceNavigationGeneration(generations, 4), 2);
  assert.equal(advanceNavigationGeneration(generations, 9), 1);
  assert.equal(currentNavigationGeneration(generations, 4), 2);
  assert.equal(currentNavigationGeneration(generations, 9), 1);
});

test("rejects an asynchronous detection from the previous document", () => {
  const generations = new Map<number, number>();
  const firstDocument = advanceNavigationGeneration(generations, 7);
  const secondDocument = advanceNavigationGeneration(generations, 7);

  assert.equal(firstDocument, 1);
  assert.equal(secondDocument, 2);
  assert.equal(
    isCurrentNavigationGeneration(generations, 7, firstDocument),
    false,
  );
  assert.equal(
    isCurrentNavigationGeneration(generations, 7, secondDocument),
    true,
  );
});

test("uses generation zero until the first committed navigation", () => {
  const generations = new Map<number, number>();

  assert.equal(currentNavigationGeneration(generations, 12), 0);
  assert.equal(isCurrentNavigationGeneration(generations, 12, 0), true);
});

test("moves a matching main-document body into its committed generation", () => {
  assert.equal(
    responseBodyNavigationGeneration(
      4,
      5,
      "main_frame",
      "https://site.example/watch#loading",
      "https://site.example/watch#ready",
    ),
    5,
  );
});

test("does not move stale subresource bodies into a newer page", () => {
  assert.equal(
    responseBodyNavigationGeneration(
      4,
      5,
      "xmlhttprequest",
      "https://cdn.example/config.json",
      "https://site.example/next",
    ),
    4,
  );
});

test("holds a direct main-frame detection until its document commits", () => {
  const gate = new MainFrameDetectionGate<string>();

  gate.begin(7);
  gate.stage(7, "https://media.example/movie.mp4", "direct-mp4");

  assert.equal(gate.isNavigating(7), true);
  assert.deepEqual(
    gate.commit(7, "https://media.example/movie.mp4#playback"),
    ["direct-mp4"],
  );
  assert.equal(gate.isNavigating(7), false);
});

test("stages only uncommitted top-level response detections", () => {
  assert.equal(
    shouldStageMainFrameDetection(
      "main_frame",
      "https://media.example/movie.mp4",
      "https://site.example/watch",
      true,
    ),
    true,
  );
  assert.equal(
    shouldStageMainFrameDetection(
      "main_frame",
      "https://media.example/movie.mp4",
      "https://media.example/movie.mp4",
      false,
    ),
    false,
  );
  assert.equal(
    shouldStageMainFrameDetection(
      "xmlhttprequest",
      "https://media.example/movie.mp4",
      "https://site.example/watch",
      true,
    ),
    false,
  );
});

test("an aborted navigation drops staged media without affecting another tab", () => {
  const gate = new MainFrameDetectionGate<string>();

  gate.begin(4);
  gate.stage(4, "https://blocked.example/popup.mp4", "popup");
  gate.begin(9);
  gate.stage(9, "https://media.example/kept.mp4", "kept");
  gate.abort(4);

  assert.deepEqual(gate.commit(4, "https://blocked.example/popup.mp4"), []);
  assert.deepEqual(gate.commit(9, "https://media.example/kept.mp4"), ["kept"]);
});

test("a redirect commits only the matching final main-frame detection", () => {
  const gate = new MainFrameDetectionGate<string>();

  gate.begin(3);
  gate.stage(3, "https://media.example/redirect.mp4", "redirect-response");
  gate.stage(3, "https://cdn.example/final.mp4", "final-response");

  assert.deepEqual(
    gate.commit(3, "https://cdn.example/final.mp4"),
    ["final-response"],
  );
});

test("a superseding navigation drops candidates from the abandoned attempt", () => {
  const gate = new MainFrameDetectionGate<string>();

  gate.begin(5);
  gate.stage(5, "https://first.example/movie.mp4", "first");
  gate.begin(5);
  gate.stage(5, "https://second.example/movie.mp4", "second");

  assert.deepEqual(
    gate.commit(5, "https://second.example/movie.mp4"),
    ["second"],
  );
});
