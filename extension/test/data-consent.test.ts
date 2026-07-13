import assert from "node:assert/strict";
import test from "node:test";

import {
  DATA_CONSENT_KEY,
  DATA_CONSENT_VERSION,
  dataConsentStatus,
  getDataConsentGranted,
  requiresLocalDataConsent,
  setDataConsentGranted,
  type ConsentStorageArea,
} from "../src/data-consent";

function memoryStorage(initial: Record<string, unknown> = {}): {
  storage: ConsentStorageArea;
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

test("local consent is required only for the Chrome MV3 target", () => {
  assert.equal(requiresLocalDataConsent(2), false);
  assert.equal(requiresLocalDataConsent(3), true);
});

test("Firefox uses built-in consent and is always locally enabled", async () => {
  const { storage } = memoryStorage();
  assert.equal(await getDataConsentGranted(storage, false), true);
  assert.deepEqual(dataConsentStatus(false, false), {
    required: false,
    granted: true,
    version: DATA_CONSENT_VERSION,
  });
});

test("Chrome fails closed until the current disclosure is accepted", async () => {
  const { storage } = memoryStorage();
  assert.equal(await getDataConsentGranted(storage, true), false);

  await setDataConsentGranted(storage, true);
  assert.equal(await getDataConsentGranted(storage, true), true);

  await setDataConsentGranted(storage, false);
  assert.equal(await getDataConsentGranted(storage, true), false);
});

test("an older disclosure version requires consent again", async () => {
  const { storage } = memoryStorage({ [DATA_CONSENT_KEY]: 0 });
  assert.equal(await getDataConsentGranted(storage, true), false);
});

test("storage failures fail closed for Chrome", async () => {
  assert.equal(
    await getDataConsentGranted(
      {
        async get() {
          throw new Error("unavailable");
        },
      },
      true,
    ),
    false,
  );
});
