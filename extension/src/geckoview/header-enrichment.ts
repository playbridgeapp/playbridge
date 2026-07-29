export interface HeaderCarrier {
  headers?: Record<string, string>;
}

/**
 * Attach newly captured replay headers to an existing detection.
 *
 * Returns true only for the first non-empty enrichment so callers can forward
 * the updated record across the GeckoView native-messaging boundary without
 * rebroadcasting every repeated media request.
 */
export function enrichReplayHeaders(
  target: HeaderCarrier,
  headers: Record<string, string> | null,
  alreadyCaptured: boolean,
): boolean {
  if (alreadyCaptured || !headers || Object.keys(headers).length === 0) {
    return false;
  }

  target.headers = headers;
  return true;
}
