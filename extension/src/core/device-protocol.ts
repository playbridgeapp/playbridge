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

interface ActiveDeviceRow {
  id?: string;
  uuid: string;
  name: string;
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
    return selection.kind === "tv" &&
      (device.active === true || (!!selection.id && selection.id === device.id));
  }
  return !!activeTv && activeTv === device.name;
}
