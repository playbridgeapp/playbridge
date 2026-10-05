const OPERATIONS = new Set([
  "open", "play", "replace", "append", "jump", "supply", "unlink", "ping",
  "destination", "choose_destination",
]);
const FIELDS = new Set(["pageRequestId", "operation", "sessionId", "payload"]);

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
    const { pageRequestId, operation, sessionId, payload } = request;
    if (!validPageRequestId(pageRequestId) ||
        typeof operation !== "string" || !OPERATIONS.has(operation)) return null;
    if (sessionId != null && !validPageRequestId(sessionId)) return null;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
    return {
      action: "page_linked_cast" as const,
      pageRequestId,
      operation,
      sessionId: sessionId ?? null,
      payload,
    };
  } catch {
    return null;
  }
}
