// Shared extension preference keys and validation. Safe to import from popup,
// background, and content entry points (no native-bridge dependencies).

/** Global master switch for the on-video cast overlay. */
export const SHOW_VIDEO_CAST_OVERLAY_KEY = "showVideoCastOverlay";

/** Hostname-keyed on/off and position overrides. */
export const VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY =
  "videoCastOverlaySiteOverrides";

export const SHOW_VIDEO_CAST_OVERLAY_DEFAULT = true;

export const VIDEO_CAST_OVERLAY_POSITIONS = [
  "top-left",
  "top-center",
  "top-right",
  "middle-left",
  "middle-right",
] as const;

export type VideoCastOverlayPosition =
  (typeof VIDEO_CAST_OVERLAY_POSITIONS)[number];

export const VIDEO_CAST_OVERLAY_DEFAULT_POSITION: VideoCastOverlayPosition =
  "top-right";

export interface VideoCastOverlaySiteOverride {
  enabled: boolean;
  position: VideoCastOverlayPosition;
}

export interface VideoCastOverlayPreferences {
  globalEnabled: boolean;
  siteEnabled: boolean;
  enabled: boolean;
  position: VideoCastOverlayPosition;
  siteKey: string | null;
  hasSiteOverride: boolean;
}

export interface ExtensionStorageArea {
  get: (keys: string | string[] | null) => Promise<Record<string, unknown>>;
  set: (items: Record<string, unknown>) => Promise<void>;
}

export const VIDEO_CAST_OVERLAY_STORAGE_KEYS = [
  SHOW_VIDEO_CAST_OVERLAY_KEY,
  VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY,
] as const;

const MAX_SITE_OVERRIDES = 500;
const RESERVED_SITE_KEYS = new Set(["__proto__", "constructor", "prototype"]);

export function isVideoCastOverlayPosition(
  value: unknown,
): value is VideoCastOverlayPosition {
  return (
    typeof value === "string" &&
    VIDEO_CAST_OVERLAY_POSITIONS.includes(value as VideoCastOverlayPosition)
  );
}

export function siteKeyFromUrl(
  rawUrl: string | undefined | null,
): string | null {
  if (!rawUrl) return null;
  try {
    const url = new URL(rawUrl);
    if (!/^https?:$/.test(url.protocol) || !url.hostname) return null;
    const hostname = url.hostname.toLowerCase();
    return RESERVED_SITE_KEYS.has(hostname) ? null : hostname;
  } catch {
    return null;
  }
}

function normalizedSiteOverride(
  value: unknown,
): VideoCastOverlaySiteOverride | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidate = value as Record<string, unknown>;
  if (
    typeof candidate.enabled !== "boolean" ||
    !isVideoCastOverlayPosition(candidate.position)
  ) {
    return null;
  }
  return {
    enabled: candidate.enabled,
    position: candidate.position,
  };
}

export function normalizeSiteOverrides(
  value: unknown,
): Record<string, VideoCastOverlaySiteOverride> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result = Object.create(null) as Record<
    string,
    VideoCastOverlaySiteOverride
  >;
  for (const [siteKey, rawOverride] of Object.entries(value).slice(
    0,
    MAX_SITE_OVERRIDES,
  )) {
    const normalizedKey = siteKeyFromUrl(`https://${siteKey}`);
    const override = normalizedSiteOverride(rawOverride);
    if (!normalizedKey || normalizedKey !== siteKey.toLowerCase() || !override) {
      continue;
    }
    result[normalizedKey] = override;
  }
  return result;
}

export async function getShowVideoCastOverlay(
  storage: Pick<ExtensionStorageArea, "get">,
): Promise<boolean> {
  try {
    const result = await storage.get(SHOW_VIDEO_CAST_OVERLAY_KEY);
    const value = result[SHOW_VIDEO_CAST_OVERLAY_KEY];
    return typeof value === "boolean"
      ? value
      : SHOW_VIDEO_CAST_OVERLAY_DEFAULT;
  } catch {
    return SHOW_VIDEO_CAST_OVERLAY_DEFAULT;
  }
}

export async function setShowVideoCastOverlay(
  storage: Pick<ExtensionStorageArea, "set">,
  enabled: boolean,
): Promise<void> {
  await storage.set({ [SHOW_VIDEO_CAST_OVERLAY_KEY]: enabled });
}

export async function getVideoCastOverlayPreferences(
  storage: Pick<ExtensionStorageArea, "get">,
  siteKey: string | null,
): Promise<VideoCastOverlayPreferences> {
  let values: Record<string, unknown> = {};
  let storageAvailable = true;
  try {
    values = await storage.get([...VIDEO_CAST_OVERLAY_STORAGE_KEYS]);
  } catch {
    storageAvailable = false;
  }

  // Missing values use the product default. An unavailable preference store
  // fails closed so a saved global/site disable cannot be bypassed.
  const globalEnabled = storageAvailable
    ? typeof values[SHOW_VIDEO_CAST_OVERLAY_KEY] === "boolean"
      ? (values[SHOW_VIDEO_CAST_OVERLAY_KEY] as boolean)
      : SHOW_VIDEO_CAST_OVERLAY_DEFAULT
    : false;
  const overrides = normalizeSiteOverrides(
    values[VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY],
  );
  const siteOverride = siteKey ? overrides[siteKey] : undefined;
  const siteEnabled = siteOverride?.enabled ?? true;

  return {
    globalEnabled,
    siteEnabled,
    enabled: globalEnabled && siteEnabled,
    position: siteOverride?.position ?? VIDEO_CAST_OVERLAY_DEFAULT_POSITION,
    siteKey,
    hasSiteOverride: !!siteOverride,
  };
}

export async function setVideoCastOverlaySiteOverride(
  storage: ExtensionStorageArea,
  siteKey: string,
  override: VideoCastOverlaySiteOverride,
): Promise<void> {
  const normalizedKey = siteKeyFromUrl(`https://${siteKey}`);
  const normalizedOverride = normalizedSiteOverride(override);
  if (
    !normalizedKey ||
    normalizedKey !== siteKey.toLowerCase() ||
    !normalizedOverride
  ) {
    throw new Error("Invalid site overlay preference");
  }

  const values = await storage.get(VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY);
  const overrides = normalizeSiteOverrides(
    values[VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY],
  );
  overrides[normalizedKey] = normalizedOverride;
  const entries = Object.entries(overrides);
  const bounded = Object.fromEntries(entries.slice(-MAX_SITE_OVERRIDES));
  await storage.set({ [VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY]: bounded });
}

export async function resetVideoCastOverlaySiteOverride(
  storage: ExtensionStorageArea,
  siteKey: string,
): Promise<void> {
  const normalizedKey = siteKeyFromUrl(`https://${siteKey}`);
  if (!normalizedKey || normalizedKey !== siteKey.toLowerCase()) {
    throw new Error("Invalid site overlay preference");
  }
  const values = await storage.get(VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY);
  const overrides = normalizeSiteOverrides(
    values[VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY],
  );
  delete overrides[normalizedKey];
  await storage.set({ [VIDEO_CAST_OVERLAY_SITE_OVERRIDES_KEY]: overrides });
}
