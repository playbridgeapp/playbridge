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
