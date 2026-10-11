import { normalizeOrigin } from "./page-origin";

export const PAGE_CONSENT_KEY = "pageConsent:v1";

export interface PageConsentV1 {
  origins: Record<
    string,
    {
      approved: boolean;
      approvedAt: number;
      allowedPrivateOrigins?: string[];
    }
  >;
}

export async function getConsentData(storage: {
  get: (key: string) => Promise<Record<string, unknown>>;
}): Promise<PageConsentV1> {
  const result = await storage.get(PAGE_CONSENT_KEY);
  const data = result[PAGE_CONSENT_KEY];
  if (data && typeof data === "object" && typeof (data as any).origins === "object") {
    return data as PageConsentV1;
  }
  return { origins: {} };
}

export async function isOriginApproved(
  storage: { get: (key: string) => Promise<Record<string, unknown>> },
  origin: string
): Promise<boolean> {
  const norm = normalizeOrigin(origin);
  if (!norm) return false;
  const data = await getConsentData(storage);
  return data.origins[norm]?.approved === true;
}

export async function approveOrigin(
  storage: {
    get: (key: string) => Promise<Record<string, unknown>>;
    set: (items: Record<string, unknown>) => Promise<void>;
  },
  origin: string
): Promise<void> {
  const norm = normalizeOrigin(origin);
  if (!norm) return;
  const data = await getConsentData(storage);
  data.origins[norm] = {
    ...data.origins[norm],
    approved: true,
    approvedAt: Date.now(),
  };
  await storage.set({ [PAGE_CONSENT_KEY]: data });
}

export async function revokeOrigin(
  storage: {
    get: (key: string) => Promise<Record<string, unknown>>;
    set: (items: Record<string, unknown>) => Promise<void>;
  },
  origin: string
): Promise<void> {
  const norm = normalizeOrigin(origin);
  if (!norm) return;
  const data = await getConsentData(storage);
  delete data.origins[norm];
  await storage.set({ [PAGE_CONSENT_KEY]: data });
}

export async function getApprovedOrigins(storage: {
  get: (key: string) => Promise<Record<string, unknown>>;
}): Promise<string[]> {
  const data = await getConsentData(storage);
  return Object.entries(data.origins)
    .filter(([_, entry]) => entry.approved)
    .map(([origin]) => origin);
}

export async function getApprovedPrivateOrigins(
  storage: { get: (key: string) => Promise<Record<string, unknown>> },
  origin: string
): Promise<string[]> {
  const norm = normalizeOrigin(origin);
  if (!norm) return [];
  const data = await getConsentData(storage);
  return data.origins[norm]?.allowedPrivateOrigins || [];
}

export async function approvePrivateOrigins(
  storage: {
    get: (key: string) => Promise<Record<string, unknown>>;
    set: (items: Record<string, unknown>) => Promise<void>;
  },
  origin: string,
  privateOrigins: string[]
): Promise<void> {
  const norm = normalizeOrigin(origin);
  if (!norm) return;
  const data = await getConsentData(storage);
  const existing = new Set(data.origins[norm]?.allowedPrivateOrigins || []);
  for (const po of privateOrigins) {
    const poNorm = normalizeOrigin(po);
    if (poNorm) existing.add(poNorm);
  }
  data.origins[norm] = {
    ...data.origins[norm],
    approved: data.origins[norm]?.approved ?? true,
    approvedAt: data.origins[norm]?.approvedAt ?? Date.now(),
    allowedPrivateOrigins: Array.from(existing),
  };
  await storage.set({ [PAGE_CONSENT_KEY]: data });
}
