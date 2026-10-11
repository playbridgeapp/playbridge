/**
 * Formats a device protocol key for display on device rows.
 *
 * Mappings:
 * - playBridge -> "PlayBridge"
 * - googleCast -> "Google Cast"
 * - dlna -> "DLNA"
 * - roku -> "Roku"
 * - webBrowser -> "Web Browser"
 * - anything else -> raw value
 * - absent / empty -> null (omitted)
 */
export function formatDeviceProtocol(protocol?: string | null): string | null {
  if (!protocol || typeof protocol !== "string") {
    return null;
  }
  const trimmed = protocol.trim();
  if (!trimmed) {
    return null;
  }
  switch (trimmed) {
    case "playBridge":
      return "PlayBridge";
    case "googleCast":
      return "Google Cast";
    case "dlna":
      return "DLNA";
    case "roku":
      return "Roku";
    case "webBrowser":
      return "Web Browser";
    default:
      return trimmed;
  }
}

export interface ActiveDeviceRow {
  id?: string;
  uuid?: string;
  name?: string;
  active?: boolean;
}

/**
 * Whether a device row is the current destination. Apps with device selection
 * mark the exact protocol + id; one TV is often listed once per protocol under
 * the same name, so names only identify the active row for legacy apps.
 */
export function isActiveDevice(
  device: ActiveDeviceRow,
  selection: { kind: string; id?: string } | null | undefined,
  activeTv: string | null | undefined,
): boolean {
  if (selection) {
    return (
      selection.kind === "tv" &&
      (device.active === true || (!!selection.id && selection.id === device.id))
    );
  }
  return !!activeTv && activeTv === device.name;
}

export const DEVICE_PICKER_PROTOCOL_STORAGE_KEY = "devicePickerProtocol";

function protocolRank(p: string): number {
  switch (p.toLowerCase()) {
    case "playbridge":
      return 0;
    case "googlecast":
      return 1;
    case "dlna":
      return 2;
    case "roku":
      return 3;
    case "webbrowser":
      return 4;
    default:
      return 100;
  }
}

/**
 * Returns the unique protocol keys present across devices, ordered:
 * PlayBridge, Google Cast, DLNA, Roku, Web Browser, then others.
 */
export function protocolChips(
  devices: Array<{ protocol?: string }>,
): string[] {
  const seen = new Set<string>();
  const unique: string[] = [];

  for (const d of devices) {
    const p = d.protocol?.trim();
    if (p) {
      const lower = p.toLowerCase();
      if (!seen.has(lower)) {
        seen.add(lower);
        unique.push(p);
      }
    }
  }

  return unique.sort((a, b) => {
    const rankA = protocolRank(a);
    const rankB = protocolRank(b);
    if (rankA !== rankB) {
      return rankA - rankB;
    }
    const labelA = formatDeviceProtocol(a) ?? a;
    const labelB = formatDeviceProtocol(b) ?? b;
    return labelA.localeCompare(labelB);
  });
}

/**
 * Determines the default selected protocol chip:
 * 1. The user's last chosen chip if still present
 * 2. Otherwise the active device's protocol
 * 3. Otherwise PlayBridge if present
 * 4. Otherwise the first chip
 * Returns null if no chips exist.
 */
export function defaultProtocol(
  devices: Array<{
    protocol?: string;
    id?: string;
    uuid?: string;
    name?: string;
    active?: boolean;
  }>,
  selection?: { kind: string; id?: string } | null,
  remembered?: string | null,
  activeTv?: string | null,
): string | null {
  const chips = protocolChips(devices);
  if (chips.length === 0) {
    return null;
  }

  // 1. User's last chosen chip if that protocol is still present
  if (remembered) {
    const rememberedLower = remembered.trim().toLowerCase();
    const matched = chips.find((c) => c.toLowerCase() === rememberedLower);
    if (matched) {
      return matched;
    }
  }

  // 2. Active device's protocol
  const activeDevice = devices.find((d) =>
    isActiveDevice(
      { id: d.id || d.uuid, uuid: d.uuid ?? d.id ?? "", name: d.name ?? "", active: d.active },
      selection,
      activeTv,
    ),
  );
  if (activeDevice?.protocol) {
    const activeLower = activeDevice.protocol.trim().toLowerCase();
    const matched = chips.find((c) => c.toLowerCase() === activeLower);
    if (matched) {
      return matched;
    }
  }

  // 3. PlayBridge if present
  const pb = chips.find((c) => c.toLowerCase() === "playbridge");
  if (pb) {
    return pb;
  }

  // 4. First chip
  return chips[0];
}

/**
 * Filters the list of devices by the given protocol key (case-insensitive).
 * Returns all devices if protocol is not provided.
 */
export function filterByProtocol<T extends { protocol?: string }>(
  devices: T[],
  protocol?: string | null,
): T[] {
  if (!protocol) {
    return devices;
  }
  const target = protocol.trim().toLowerCase();
  return devices.filter((d) => d.protocol?.trim().toLowerCase() === target);
}
