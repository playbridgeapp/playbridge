const OPERATIONS = new Set([
  "open", "play", "replace", "append", "jump", "supply", "unlink", "ping",
  "destination", "choose_destination",
]);
const FIELDS = new Set(["channel", "pageRequestId", "operation", "sessionId", "payload"]);

/** Session events the page may observe. Native names outside this list are dropped. */
export const LINKED_PAGE_EVENTS = ["needitems", "statechange", "ended"] as const;

/** Per-document runtime port from the top-frame content script to the background. */
export const PAGE_API_PORT = "playbridge-page-api";

export function validPageRequestId(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 128 &&
    !/[\x00-\x1f\x7f]/.test(value);
}

/** Construct routing ourselves; page fields never become extension commands. */
export function normalizeLinkedPageRelay(value: unknown) {
  try {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const request = value as Record<string, unknown>;
    if (Object.keys(request).some(key => !FIELDS.has(key))) return null;
    const { channel, pageRequestId, operation, sessionId, payload } = request;
    if ((channel !== undefined && channel !== "linked") || !validPageRequestId(pageRequestId) ||
        typeof operation !== "string" || !OPERATIONS.has(operation)) return null;
    if (sessionId != null && !validPageRequestId(sessionId)) return null;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
    return {
      type: "linked" as const,
      pageRequestId,
      operation,
      sessionId: sessionId ?? null,
      payload,
    };
  } catch {
    return null;
  }
}
