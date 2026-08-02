import assert from "node:assert/strict";
import test from "node:test";

import {
  advanceNavigationGeneration,
  currentNavigationGeneration,
  isCurrentNavigationGeneration,
  responseBodyNavigationGeneration,
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
