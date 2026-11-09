import { HEX_ID, PROTOCOL_VERSION, clientIp, errorResponse, json, limitsFor } from "@playbridge-together/shared";
import { roomLimits, type Env } from "./config";
import { invitePage, setupPage } from "./pages";

export { Room } from "./room";
export { Limits } from "@playbridge-together/shared";

const CONNECTS_PER_IP_PER_MINUTE = 60;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const limits = roomLimits(env);

    if (url.pathname === "/version") {
      return json({
        service: "playbridge-together-room",
        protocol: PROTOCOL_VERSION,
        shared: limits.shared,
        max_members: limits.maxMembers,
        room_lifetime_ms: limits.lifetimeMs,
      });
    }
    if (url.pathname === "/" || url.pathname === "/setup") {
      return html(setupPage(url.origin, limits));
    }

    const invite = url.pathname.match(/^\/j\/([0-9a-f]{32})$/);
    if (invite) return html(invitePage());

    const room = url.pathname.match(/^\/ws\/([0-9a-f]{32})$/)?.[1];
    if (room) {
      if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        return errorResponse("upgrade_required", 426);
      }
      const member = url.searchParams.get("member") ?? "";
      if (!HEX_ID.test(member)) return errorResponse("bad_member", 400);

      const ip = clientIp(request);
      if (!(await limitsFor(env.LIMITS, ip).hit("connect", CONNECTS_PER_IP_PER_MINUTE, 60_000))) {
        return errorResponse("rate_limited", 429);
      }
      const forwarded = new Request(request);
      forwarded.headers.set("x-pb-client-ip", ip);
      return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(forwarded);
    }

    return errorResponse("not_found", 404);
  },
} satisfies ExportedHandler<Env>;

function html(body: string): Response {
  return new Response(body, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'",
      "referrer-policy": "no-referrer",
    },
  });
}
