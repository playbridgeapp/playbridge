import {
  BASE64URL,
  PROTOCOL_VERSION,
  bearer,
  clientIp,
  errorResponse,
  json,
  normalizeCode,
  randomCode,
  secondsUntilUtcMidnight,
  underLimit,
} from "@playbridge-together/shared";
import type { Env } from "./config";

export { Lobby } from "./lobby";

// Per-IP limits live in Workers rate-limit bindings (see wrangler.jsonc), which cost no
// Durable Object requests. They are generous on purpose: a code on its own gets nobody
// anything (a room member has to send an invite and the receiver has to accept it), and
// 30 symbols over 6 characters is about 7×10⁸ codes, far beyond what the daily request
// quota would let anyone try.

const MAX_SEALED_CHARS = 8 * 1024;
const X25519_PUBLIC_KEY_CHARS = 43; // 32 bytes, base64url without padding

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch (error) {
      // Most likely Cloudflare's daily Durable Object quota, which resets at 00:00 UTC.
      console.log("pairing unavailable", String(error));
      return errorResponse("pairing_unavailable", 503, secondsUntilUtcMidnight());
    }
  },
} satisfies ExportedHandler<Env>;

const RATE_LIMITED_SECS = 60;

async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const ip = clientIp(request);

  if (url.pathname === "/version" && request.method === "GET") {
    return json({ service: "playbridge-together-pairing", protocol: PROTOCOL_VERSION });
  }

  if (url.pathname === "/lobby" && request.method === "POST") {
    const body = await readJson(request);
    const pk = body?.pk;
    if (typeof pk !== "string" || pk.length !== X25519_PUBLIC_KEY_CHARS || !BASE64URL.test(pk)) {
      return errorResponse("bad_key", 400);
    }
    if (!(await underLimit(env.REGISTER_LIMIT, ip))) {
      return errorResponse("rate_limited", 429, RATE_LIMITED_SECS);
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
    if (!(await underLimit(env.LOOKUP_LIMIT, ip))) {
      return errorResponse("rate_limited", 429, RATE_LIMITED_SECS);
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
    if (!(await underLimit(env.INVITE_LIMIT, ip))) {
      return errorResponse("rate_limited", 429, RATE_LIMITED_SECS);
    }
    const result = await lobby(env, code).invite(sealed);
    if (result === "ok") return json({ ok: true }, 202);
    return result === "full" ? errorResponse("lobby_full", 409) : errorResponse("not_found", 404);
  }

  if (action === "/ws" && request.method === "GET") {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return errorResponse("upgrade_required", 426);
    }
    return env.LOBBIES.get(env.LOBBIES.idFromName(code)).fetch(request);
  }

  return errorResponse("not_found", 404);
}

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
