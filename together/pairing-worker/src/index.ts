import {
  BASE64URL,
  PROTOCOL_VERSION,
  bearer,
  clientIp,
  errorResponse,
  json,
  limitsFor,
  normalizeCode,
  randomCode,
} from "@playbridge-together/shared";
import type { Env } from "./config";

export { Lobby } from "./lobby";
export { Limits } from "@playbridge-together/shared";

// Per-IP budgets. Lookups are the guessing surface: 30 symbols over 6
// characters is about 7×10⁸ codes, so 30 lookups per 10 minutes per IP makes
// guessing a live code impractical.
const REGISTERS = { max: 20, windowMs: 3_600_000 };
const LOOKUPS = { max: 30, windowMs: 600_000 };
const INVITES = { max: 30, windowMs: 600_000 };

const MAX_SEALED_CHARS = 8 * 1024;
const X25519_PUBLIC_KEY_CHARS = 43; // 32 bytes, base64url without padding

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const ip = clientIp(request);
    const limits = limitsFor(env.LIMITS, ip);

    if (url.pathname === "/version" && request.method === "GET") {
      return json({ service: "playbridge-together-pairing", protocol: PROTOCOL_VERSION });
    }

    if (url.pathname === "/lobby" && request.method === "POST") {
      const body = await readJson(request);
      const pk = body?.pk;
      if (typeof pk !== "string" || pk.length !== X25519_PUBLIC_KEY_CHARS || !BASE64URL.test(pk)) {
        return errorResponse("bad_key", 400);
      }
      if (!(await limits.hit("register", REGISTERS.max, REGISTERS.windowMs))) {
        return errorResponse("rate_limited", 429);
      }
      for (let attempt = 0; attempt < 5; attempt++) {
        const code = randomCode();
        const claimed = await lobby(env, code).register(pk);
        if (claimed) return json({ code, token: claimed.token, expires_at: claimed.expiresAt }, 201);
      }
      return errorResponse("busy", 503);
    }

    const match = url.pathname.match(/^\/lobby\/([^/]+)(\/invite|\/ws)?$/);
    const code = match ? normalizeCode(decodeURIComponent(match[1] ?? "")) : null;
    if (!match || !code) return errorResponse("not_found", 404);
    const action = match[2];

    if (!action && request.method === "GET") {
      if (!(await limits.hit("lookup", LOOKUPS.max, LOOKUPS.windowMs))) {
        return errorResponse("rate_limited", 429);
      }
      const found = await lobby(env, code).lookup();
      return found ? json({ pk: found.pk, expires_at: found.expiresAt }) : errorResponse("not_found", 404);
    }

    if (!action && request.method === "DELETE") {
      const token = bearer(request);
      if (!token) return errorResponse("unauthorized", 401);
      return (await lobby(env, code).release(token))
        ? new Response(null, { status: 204 })
        : errorResponse("unauthorized", 401);
    }

    if (action === "/invite" && request.method === "POST") {
      const body = await readJson(request);
      const sealed = body?.sealed;
      if (typeof sealed !== "string" || sealed.length > MAX_SEALED_CHARS || !BASE64URL.test(sealed)) {
        return errorResponse("bad_invite", 400);
      }
      if (!(await limits.hit("invite", INVITES.max, INVITES.windowMs))) {
        return errorResponse("rate_limited", 429);
      }
      const result = await lobby(env, code).invite(sealed);
      if (result === "ok") return json({ ok: true }, 202);
      return result === "full" ? errorResponse("too_many_invites", 429) : errorResponse("not_found", 404);
    }

    if (action === "/ws" && request.method === "GET") {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        return errorResponse("upgrade_required", 426);
      }
      return env.LOBBIES.get(env.LOBBIES.idFromName(code)).fetch(request);
    }

    return errorResponse("not_found", 404);
  },
} satisfies ExportedHandler<Env>;

function lobby(env: Env, code: string) {
  return env.LOBBIES.get(env.LOBBIES.idFromName(code));
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  if (Number(request.headers.get("content-length") ?? 0) > 16 * 1024) return null;
  try {
    const value = await request.json();
    return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}
