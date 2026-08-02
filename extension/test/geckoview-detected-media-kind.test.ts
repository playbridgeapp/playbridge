import assert from "node:assert/strict";
import test from "node:test";

import {
  detectedMediaKind,
  inferredMediaContentType,
  isSupportedDomImage,
  shouldReportNetworkImage,
} from "../src/geckoview/detected-media-kind";

test("classifies video, standalone audio, images, and subtitles", () => {
  assert.equal(detectedMediaKind("https://cdn.example/movie.mp4"), "video");
  assert.equal(detectedMediaKind("https://cdn.example/song", "audio/flac"), "audio");
  assert.equal(detectedMediaKind("https://cdn.example/poster", "image/webp"), "image");
  assert.equal(detectedMediaKind("https://cdn.example/captions.vtt"), "subtitle");
});

test("classifies demuxed HLS audio before generic mpegurl video", () => {
  assert.equal(
    detectedMediaKind(
      "https://cdn.example/audio.m3u8",
      "application/vnd.apple.mpegurl",
      "audio_media",
    ),
    "audio",
  );
});

test("adaptive stream URL wins over a deceptive image MIME", () => {
  assert.equal(
    detectedMediaKind(
      "https://cdn.example/manifest/master.m3u8",
      "image/jpeg",
    ),
    "video",
  );
  assert.equal(
    shouldReportNetworkImage(
      "https://cdn.example/manifest/master.m3u8",
      "image/jpeg",
      64 * 1024,
    ),
    false,
  );
});

test("only accepts substantial, non-tracking images from network headers", () => {
  assert.equal(
    shouldReportNetworkImage(
      "https://cdn.example/poster.webp",
      "image/webp",
      64 * 1024,
    ),
    true,
  );
  assert.equal(
    shouldReportNetworkImage(
      "https://cdn.example/favicon.png",
      "image/png",
      64 * 1024,
    ),
    false,
  );
  assert.equal(
    shouldReportNetworkImage(
      "https://cdn.example/poster.webp",
      "image/webp",
      null,
    ),
    false,
  );
  assert.equal(
    shouldReportNetworkImage(
      "https://cdn.example/artwork.svg",
      "image/svg+xml",
      64 * 1024,
    ),
    false,
  );
});

test("DOM images can be extensionless but filter obvious tracking assets", () => {
  assert.equal(isSupportedDomImage("https://cdn.example/image/42"), true);
  assert.equal(isSupportedDomImage("https://cdn.example/tracking/pixel/42"), false);
});

test("infers useful cast content types for DOM media", () => {
  assert.equal(
    inferredMediaContentType("https://cdn.example/cover.jpg?token=x", "image"),
    "image/jpeg",
  );
  assert.equal(
    inferredMediaContentType("https://cdn.example/song.mp3", "audio"),
    "audio/mpeg",
  );
  assert.equal(
    inferredMediaContentType("https://cdn.example/image/42", "image"),
    "image/*",
  );
});
