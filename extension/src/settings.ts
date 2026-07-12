// Shared extension preference keys. Safe to import from popup and content
// entry points (no background/native dependencies).

/** When true, show the on-video cast overlay in content scripts. */
export const SHOW_VIDEO_CAST_OVERLAY_KEY = "showVideoCastOverlay";

/**
 * Default: enabled. The overlay is non-destructive and users can turn it off
 * from the Settings tab; opt-out matches "useful by default" for a cast tool.
 */
export const SHOW_VIDEO_CAST_OVERLAY_DEFAULT = true;

export async function getShowVideoCastOverlay(
  storage: {
    get: (keys: string | string[] | null) => Promise<Record<string, unknown>>;
  },
): Promise<boolean> {
  try {
    const result = await storage.get(SHOW_VIDEO_CAST_OVERLAY_KEY);
    const value = result[SHOW_VIDEO_CAST_OVERLAY_KEY];
    if (typeof value === "boolean") return value;
    return SHOW_VIDEO_CAST_OVERLAY_DEFAULT;
  } catch {
    return SHOW_VIDEO_CAST_OVERLAY_DEFAULT;
  }
}

export async function setShowVideoCastOverlay(
  storage: {
    set: (items: Record<string, unknown>) => Promise<void>;
  },
  enabled: boolean,
): Promise<void> {
  await storage.set({ [SHOW_VIDEO_CAST_OVERLAY_KEY]: enabled });
}
