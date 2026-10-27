import assert from "node:assert/strict";
import test from "node:test";
import {
  MAX_SUBTITLE_BYTES, readSubtitleBlob, SubtitleTextDeduper, validSubtitleText,
} from "../src/core/blob-subtitles";
import { installBlobSubtitleCapture } from "../src/geckoview/blob-subtitles";

const vtt = "\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n";
const srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n";
const settle = async () => { for (let i = 0; i < 15; i++) await new Promise(resolve => setTimeout(resolve, 0)); };

test("blob filter accepts VTT/SRT MIME and signature (including BOM)", async () => {
  for (const [text, type, format] of [
    [vtt, "", "vtt"], [srt, "text/plain", "srt"],
    ["captions", "text/vtt; charset=utf-8", "vtt"], ["captions", "application/x-subrip", "srt"],
  ]) {
    const result = await readSubtitleBlob(new Blob([text], { type }));
    assert.equal(result?.subtitleFormat, format);
    assert.equal(result?.subtitleText, text.replace(/^\uFEFF/, ""));
  }
});

test("blob filter rejects video/audio/image and MSE without reading them", async () => {
  const original = Blob.prototype.text;
  let reads = 0;
  Blob.prototype.text = function () { reads++; return original.call(this); };
  try {
    for (const type of ["video/mp4", "audio/mp4", "image/png"]) {
      assert.equal(await readSubtitleBlob(new Blob([vtt], { type })), null);
    }
    assert.equal(await readSubtitleBlob({ size: 10, type: "text/vtt", text() { throw new Error("MSE is not a Blob"); } } as any), null);
    assert.equal(reads, 0);
  } finally { Blob.prototype.text = original; }
  assert.equal(await readSubtitleBlob(new Blob(["unrelated text"])), null);
});

test("blob filter enforces the two MiB byte cap before reading and after decoding", async () => {
  assert.equal(await readSubtitleBlob(new Blob(["a".repeat(MAX_SUBTITLE_BYTES + 1)], { type: "text/vtt" })), null);
  assert.equal(validSubtitleText("WEBVTT\n" + "é".repeat(MAX_SUBTITLE_BYTES / 2)), null);
  assert.equal(validSubtitleText("WEBVTT\n" + "a".repeat(MAX_SUBTITLE_BYTES - 7))?.subtitleFormat, "vtt");
  assert.equal(await readSubtitleBlob(new Blob([])), null);
});

test("a disabled detector stops after the bounded header read", async () => {
  const original = Blob.prototype.text;
  let active = true, reads = 0;
  Blob.prototype.text = function () { reads++; active = false; return original.call(this); };
  try {
    assert.equal(await readSubtitleBlob(new Blob([vtt]), () => active), null);
    assert.equal(reads, 1);
  } finally { Blob.prototype.text = original; }
});

test("content hashes dedupe different blob URLs and allow one track-label enrichment", () => {
  const dedupe = new SubtitleTextDeduper();
  const subtitle = validSubtitleText(vtt)!;
  const first = dedupe.accept(subtitle)!;
  assert.match(first.url, /^playbridge-subtitle:[0-9a-f]{16}\.vtt$/);
  assert.equal(dedupe.accept(subtitle), null);
  assert.equal(dedupe.accept(subtitle, "English")?.url, first.url);
  assert.equal(dedupe.accept(subtitle, "English"), null);
  assert.equal(dedupe.accept(subtitle), null);
  assert.notEqual(dedupe.accept(validSubtitleText(srt)!)?.url, first.url);
});

function pageFixture() {
  const target: any = { createObjectURL(_blob: unknown) { return "blob:https://page.example/fixture"; } };
  const original = target.createObjectURL;
  const page = { URL: target, playbridge: { sentinel: true } };
  let reads = 0;
  let trackBlob = new Blob([srt]);
  const track: any = { src: "blob:https://page.example/fixture", kind: "captions", label: "English", srclang: "en" };
  const globals = globalThis as any;
  globals.window = { wrappedJSObject: page, fetch() { throw new Error("Extension-principal fetch must not be used"); } };
  globals.document = { querySelectorAll() { return [track]; } };
  globals.exportFunction = (fn: Function, scope: unknown) => { assert.equal(scope, page); return fn; };
  globals.content = { fetch: async () => ({
    headers: new Headers({ "content-type": trackBlob.type, "content-length": String(trackBlob.size) }),
    blob: async () => { reads++; return trackBlob; },
  }) };
  return { target, original, page, track, get reads() { return reads; },
    setBlob(blob: Blob) { trackBlob = blob; },
    cleanup() { for (const name of ["window", "document", "exportFunction", "content"]) delete globals[name]; } };
}

test("Firefox hook uses exportFunction, preserves return/receiver and leaves playbridge intact", async () => {
  const f = pageFixture(), messages: any[] = [];
  const api = f.page.playbridge;
  const capture = installBlobSubtitleCapture(message => messages.push(message));
  try {
    assert.notEqual(f.target.createObjectURL, f.original);
    assert.equal(f.target.createObjectURL(new Blob([vtt])), "blob:https://page.example/fixture");
    await settle();
    assert.equal(messages.length, 1);
    assert.equal(messages[0].subtitleLabel, "English");
    assert.equal(messages[0].subtitleText, vtt.replace(/^\uFEFF/, ""));
    assert.equal(f.page.playbridge, api);
    capture.stop();
    assert.equal(f.target.createObjectURL, f.original);
    f.target.createObjectURL(new Blob([srt]));
    await settle();
    assert.equal(messages.length, 1);
  } finally { capture.stop(); f.cleanup(); }
});

test("blob track fetch uses page principal, dedupes, and ignores late or oversized reads", async () => {
  const f = pageFixture(), messages: any[] = [];
  const capture = installBlobSubtitleCapture(message => messages.push(message));
  try {
    capture.track(f.track);
    await settle();
    capture.track(f.track);
    await settle();
    assert.equal(messages.length, 1);
    assert.equal(messages[0].subtitleFormat, "srt");
    f.setBlob(new Blob([vtt], { type: "video/mp4" }));
    const reads = f.reads;
    capture.track(f.track);
    await settle();
    assert.equal(f.reads, reads, "video must be rejected before response.blob()");
    f.setBlob(new Blob(["x".repeat(MAX_SUBTITLE_BYTES + 1)], { type: "text/vtt" }));
    capture.track(f.track);
    await settle();
    assert.equal(f.reads, reads);
    f.setBlob(new Blob([vtt]));
    capture.track(f.track);
    capture.stop();
    await settle();
    assert.equal(messages.length, 1);
  } finally { capture.stop(); f.cleanup(); }
});

test("hook preserves original exceptions and cannot throw a detector error into the page", async () => {
  const f = pageFixture();
  const sentinel = new Error("original failure");
  f.target.createObjectURL = function (this: unknown, blob: unknown) {
    assert.equal(this, f.target);
    if (!blob) throw sentinel;
    return "blob:https://page.example/fixture";
  };
  const capture = installBlobSubtitleCapture(() => { throw new Error("detector failure"); });
  try {
    assert.throws(() => f.target.createObjectURL(null), error => error === sentinel);
    assert.equal(f.target.createObjectURL(new Blob([vtt])), "blob:https://page.example/fixture");
    await settle();
  } finally { capture.stop(); f.cleanup(); }
});
