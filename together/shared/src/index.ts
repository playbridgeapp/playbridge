// Wire contract shared by the room and pairing Workers. The Rust client in
// cast/together mirrors these shapes; bump PROTOCOL_VERSION on any breaking
// change to either side.

export const PROTOCOL_VERSION = 1;

/** Plain-text keep-alive handled by the room Durable Object without waking it from hibernation. */
export const KEEPALIVE_PING = "ping";
export const KEEPALIVE_PONG = "pong";

/** WebSocket close codes sent by the Workers. */
export const Close = {
  Unauthorized: 4001,
  RoomFull: 4003,
  Replaced: 4004,
  Expired: 4008,
  Silent: 4009,
  RateLimited: 4029,
  BadMessage: 4400,
} as const;

/** Frames a member sends to the room. */
export type RoomClientFrame =
  | { t: "ping"; id: number; t0: number }
  | { t: "msg"; to?: string; seq: number; ct: string }
  | { t: "state"; seq: number; ct: string };

/** Frames the room sends to a member. */
export type RoomServerFrame =
  | {
      t: "welcome";
      member: string;
      members: string[];
      room_time: number;
      expires_at: number;
      max_members: number;
      state?: { from: string; seq: number; ct: string };
      keepalive_secs?: number;
    }
  | { t: "pong"; id: number; t0: number; room_time: number }
  | { t: "msg"; from: string; seq: number; ct: string }
  | { t: "state"; from: string; seq: number; ct: string }
  | { t: "joined"; member: string }
  | { t: "left"; member: string }
  | { t: "error"; code: string };

/** Frames the pairing Worker pushes to a waiting receiver. */
export type LobbyServerFrame =
  | { t: "invite"; id: string; sealed: string }
  | { t: "expired" };

/** Opaque IDs: room and member IDs are 32 lowercase hex characters. */
export const HEX_ID = /^[0-9a-f]{32}$/;

/**
 * Room IDs that start with this carry their own expiry: the next 8 hex characters are the
 * minute (Unix time) the party ends. The relay refuses such a room from then on, so an old
 * invite link cannot start a new room. Mirrors `RoomId::dated` in the Rust client.
 */
export const DATED_ROOM_PREFIX = "e71da7";

/** When a dated room ends, in milliseconds; null for a room ID without an expiry. */
export function roomExpiry(room: string): number | null {
  if (!HEX_ID.test(room) || !room.startsWith(DATED_ROOM_PREFIX)) return null;
  return parseInt(room.slice(6, 14), 16) * 60_000;
}

/** Base64url without padding, as produced by the Rust client. */
export const BASE64URL = /^[A-Za-z0-9_-]+$/;

/** Pairing codes: 6 characters from an alphabet without look-alikes. */
export const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CODE_LENGTH = 6;

export function json(body: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", ...headers },
  });
}

/**
 * A refusal the apps can act on: `{error, retry_after}` plus a `Retry-After` header when the
 * caller should wait. 429 `rate_limited` means slow down and retry; 429 `room_limit` and 503
 * `relay_busy` / `pairing_unavailable` mean stop and tell the person.
 */
export function errorResponse(code: string, status: number, retryAfterSecs?: number): Response {
  if (retryAfterSecs === undefined) return json({ error: code }, status);
  const wait = Math.max(1, Math.ceil(retryAfterSecs));
  return json({ error: code, retry_after: wait }, status, { "retry-after": String(wait) });
}

/** Seconds until the next 00:00 UTC, when Cloudflare's daily quotas reset. */
export function secondsUntilUtcMidnight(now = Date.now()): number {
  const midnight = new Date(now);
  midnight.setUTCHours(24, 0, 0, 0);
  return Math.ceil((midnight.getTime() - now) / 1000);
}

/**
 * Asks a Workers rate-limit binding whether `key` may go on. Allows the call when the
 * binding is missing or fails: these limits only slow abuse, and failing closed would lock
 * everyone out on an outage.
 */
export async function underLimit(limiter: RateLimit | undefined, key: string): Promise<boolean> {
  if (!limiter) return true;
  try {
    return (await limiter.limit({ key })).success;
  } catch {
    return true;
  }
}

export function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? "unknown";
}

export function bearer(request: Request): string | null {
  const value = request.headers.get("authorization");
  if (!value?.startsWith("Bearer ")) return null;
  const token = value.slice("Bearer ".length).trim();
  return BASE64URL.test(token) ? token : null;
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time comparison of two equal-length strings. */
export function sameText(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function randomHex(bytes: number): string {
  const buf = crypto.getRandomValues(new Uint8Array(bytes));
  return [...buf].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function randomCode(): string {
  // 30 symbols; rejection sampling keeps the distribution uniform.
  let code = "";
  while (code.length < CODE_LENGTH) {
    const byte = crypto.getRandomValues(new Uint8Array(1))[0] ?? 255;
    if (byte < 240) code += CODE_ALPHABET[byte % CODE_ALPHABET.length];
  }
  return code;
}

export function normalizeCode(raw: string): string | null {
  const code = raw.toUpperCase().replace(/[\s-]/g, "");
  if (code.length !== CODE_LENGTH) return null;
  for (const ch of code) if (!CODE_ALPHABET.includes(ch)) return null;
  return code;
}

export function parseFrame<T>(raw: string | ArrayBuffer, maxBytes: number): T | null {
  if (typeof raw !== "string" || raw.length > maxBytes) return null;
  try {
    const value = JSON.parse(raw);
    return value && typeof value === "object" && typeof value.t === "string" ? (value as T) : null;
  } catch {
    return null;
  }
}

export { Limits, limitsFor, type LimitsRpc, type SlotResult } from "./limits";
