import assert from "node:assert/strict";
import test from "node:test";

import {
  SHOW_VIDEO_CAST_OVERLAY_KEY,
  VIDEO_CAST_OVERLAY_DEFAULT_POSITION,
  VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY,
  getVideoCastOverlayPreferences,
  normalizeSiteOverrides,
  resetVideoCastOverlaySiteOverride,
  setVideoCastOverlaySiteOverride,
  siteKeyFromUrl,
  type ExtensionStorageArea,
} from "../src/settings";

function memoryStorage(initial: Record<string, unknown> = {}): {
  storage: ExtensionStorageArea;
  values: Record<string, unknown>;
} {
  const values = { ...initial };
  return {
    values,
    storage: {
      async get(keys) {
        const selected =
          keys == null ? Object.keys(values) : Array.isArray(keys) ? keys : [keys];
        return Object.fromEntries(
          selected
            .filter((key) => Object.prototype.hasOwnProperty.call(values, key))
            .map((key) => [key, values[key]]),
        );
      },
      async set(items) {
        Object.assign(values, items);
      },
    },
  };
}

test("site keys use the normalized hostname of web pages", () => {
  assert.equal(
    siteKeyFromUrl("https://WWW.Example.com:8443/watch"),
    "www.example.com",
  );
  assert.equal(siteKeyFromUrl("http://media.example/path"), "media.example");
  assert.equal(siteKeyFromUrl("moz-extension://example/settings.html"), null);
  assert.equal(siteKeyFromUrl("not a URL"), null);
});

test("overlay preferences default to globally enabled and top right", async () => {
  const { storage } = memoryStorage();
  assert.deepEqual(
    await getVideoCastOverlayPreferences(storage, "video.example"),
    {
      globalEnabled: true,
      siteEnabled: true,
      enabled: true,
      position: VIDEO_CAST_OVERLAY_DEFAULT_POSITION,
      siteKey: "video.example",
      hasSiteOverride: false,
    },
  );
});

test("the global switch remains the master switch for site overrides", async () => {
  const { storage } = memoryStorage({
    [SHOW_VIDEO_CAST_OVERLAY_KEY]: false,
    [VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY]: {
      "video.example": { enabled: true, position: "middle-left" },
    },
  });
  const preferences = await getVideoCastOverlayPreferences(
    storage,
    "video.example",
  );
  assert.equal(preferences.globalEnabled, false);
  assert.equal(preferences.siteEnabled, true);
  assert.equal(preferences.enabled, false);
  assert.equal(preferences.position, "middle-left");
});

test("an unavailable preference store fails closed", async () => {
  const preferences = await getVideoCastOverlayPreferences(
    {
      async get() {
        throw new Error("storage unavailable");
      },
    },
    "video.example",
  );
  assert.equal(preferences.globalEnabled, false);
  assert.equal(preferences.enabled, false);
});

test("site overrides can be saved and reset without changing global state", async () => {
  const { storage, values } = memoryStorage({
    [SHOW_VIDEO_CAST_OVERLAY_KEY]: true,
  });

  await setVideoCastOverlaySiteOverride(storage, "video.example", {
    enabled: false,
    position: "top-center",
  });
  const overridden = await getVideoCastOverlayPreferences(
    storage,
    "video.example",
  );
  assert.equal(overridden.enabled, false);
  assert.equal(overridden.position, "top-center");
  assert.equal(overridden.hasSiteOverride, true);

  await resetVideoCastOverlaySiteOverride(storage, "video.example");
  const reset = await getVideoCastOverlayPreferences(storage, "video.example");
  assert.equal(reset.enabled, true);
  assert.equal(reset.position, VIDEO_CAST_OVERLAY_DEFAULT_POSITION);
  assert.equal(reset.hasSiteOverride, false);
  assert.equal(values[SHOW_VIDEO_CAST_OVERLAY_KEY], true);
});

test("malformed and prototype-like site entries are ignored safely", () => {
  const raw = JSON.parse(
    '{"__proto__":{"enabled":true,"position":"top-left"},"good.example":{"enabled":true,"position":"middle-right"},"bad.example":{"enabled":"yes","position":"bottom-left"}}',
  );
  const normalized = normalizeSiteOverrides(raw);
  assert.equal(Object.getPrototypeOf(normalized), null);
  assert.deepEqual(normalized["good.example"], {
    enabled: true,
    position: "middle-right",
  });
  assert.equal(normalized["bad.example"], undefined);
  assert.equal(normalized.__proto__, undefined);
});

test("invalid site keys and positions cannot be persisted", async () => {
  const { storage } = memoryStorage();
  await assert.rejects(
    setVideoCastOverlaySiteOverride(storage, "__proto__", {
      enabled: true,
      position: "top-left",
    }),
    /Invalid site overlay preference/,
  );
  await assert.rejects(
    setVideoCastOverlaySiteOverride(storage, "video.example", {
      enabled: true,
      position: "bottom-left" as never,
    }),
    /Invalid site overlay preference/,
  );
  await assert.rejects(
    resetVideoCastOverlaySiteOverride(storage, "not a hostname/path"),
    /Invalid site overlay preference/,
  );
});
