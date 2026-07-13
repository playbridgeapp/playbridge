// Chrome Web Store prominent-disclosure consent. Firefox uses its built-in
// manifest data-collection consent and therefore does not use this local gate.

export const DATA_CONSENT_KEY = "mediaDataConsentVersion";
export const DATA_CONSENT_VERSION = 1;

export interface ConsentStorageArea {
  get: (keys: string | string[] | null) => Promise<Record<string, unknown>>;
  set: (items: Record<string, unknown>) => Promise<void>;
}

export interface DataConsentStatus {
  required: boolean;
  granted: boolean;
  version: number;
}

export function requiresLocalDataConsent(manifestVersion: number): boolean {
  return manifestVersion >= 3;
}

export async function getDataConsentGranted(
  storage: Pick<ConsentStorageArea, "get">,
  required: boolean,
): Promise<boolean> {
  if (!required) return true;
  try {
    const values = await storage.get(DATA_CONSENT_KEY);
    return values[DATA_CONSENT_KEY] === DATA_CONSENT_VERSION;
  } catch {
    return false;
  }
}

export async function setDataConsentGranted(
  storage: Pick<ConsentStorageArea, "set">,
  granted: boolean,
): Promise<void> {
  await storage.set({
    [DATA_CONSENT_KEY]: granted ? DATA_CONSENT_VERSION : 0,
  });
}

export function dataConsentStatus(
  required: boolean,
  granted: boolean,
): DataConsentStatus {
  return {
    required,
    granted: !required || granted,
    version: DATA_CONSENT_VERSION,
  };
}
