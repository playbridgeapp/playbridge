import { DurableObject } from "cloudflare:workers";
import {
  bearer,
  errorResponse,
  randomHex,
  sameText,
  sha256Hex,
  type LobbyServerFrame,
} from "@playbridge-together/shared";
import type { Env } from "./config";

// One Lobby per pairing code. A receiver registers its X25519 public key and
// waits on a WebSocket; phones look the key up and drop sealed invites, which
// the lobby passes on without being able to open them.

export const LOBBY_TTL_MS = 10 * 60_000;
const MAX_PENDING = 5;

interface Meta {
  pk: string;
  tokenHash: string;
  expiresAt: number;
}

interface Pending {
  id: string;
  sealed: string;
}

export class Lobby extends DurableObject<Env> {
  /** Claims this code for a receiver. Returns null when the code is taken. */
  async register(pk: string): Promise<{ token: string; expiresAt: number } | null> {
    const existing = await this.ctx.storage.get<Meta>("meta");
    if (existing && Date.now() < existing.expiresAt) return null;
    await this.ctx.storage.deleteAll();
    const token = randomHex(32);
    const meta: Meta = { pk, tokenHash: await sha256Hex(token), expiresAt: Date.now() + LOBBY_TTL_MS };
    await this.ctx.storage.put("meta", meta);
    await this.ctx.storage.setAlarm(meta.expiresAt);
    return { token, expiresAt: meta.expiresAt };
  }

  async lookup(): Promise<{ pk: string; expiresAt: number } | null> {
    const meta = await this.live();
    return meta ? { pk: meta.pk, expiresAt: meta.expiresAt } : null;
  }

  /** Queues a sealed invite and pushes it to the waiting receiver. */
  async invite(sealed: string): Promise<"ok" | "gone" | "full"> {
    if (!(await this.live())) return "gone";
    const pending = (await this.ctx.storage.get<Pending[]>("pending")) ?? [];
    if (pending.length >= MAX_PENDING) return "full";
    const entry: Pending = { id: randomHex(8), sealed };
    pending.push(entry);
    await this.ctx.storage.put("pending", pending);
    for (const ws of this.ctx.getWebSockets()) send(ws, { t: "invite", id: entry.id, sealed });
    return "ok";
  }

  /** The receiver is done with this code (joined or cancelled). */
  async release(token: string): Promise<boolean> {
    if (!(await this.owns(token))) return false;
    for (const ws of this.ctx.getWebSockets()) closeQuietly(ws, 1000, "released");
    await this.ctx.storage.deleteAll();
    return true;
  }

  async fetch(request: Request): Promise<Response> {
    const token = bearer(request);
    if (!token || !(await this.owns(token))) return errorResponse("unauthorized", 401);
    for (const ws of this.ctx.getWebSockets()) closeQuietly(ws, 4004, "replaced");
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    for (const entry of (await this.ctx.storage.get<Pending[]>("pending")) ?? []) {
      send(pair[1], { t: "invite", id: entry.id, sealed: entry.sealed });
    }
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket): Promise<void> {
    // Receivers only listen; anything they send is ignored.
    void ws;
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    closeQuietly(ws, 1000, "closing");
  }

  async alarm(): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      send(ws, { t: "expired" });
      closeQuietly(ws, 4008, "code expired");
    }
    await this.ctx.storage.deleteAll();
  }

  private async live(): Promise<Meta | null> {
    const meta = await this.ctx.storage.get<Meta>("meta");
    return meta && Date.now() < meta.expiresAt ? meta : null;
  }

  private async owns(token: string): Promise<boolean> {
    const meta = await this.live();
    return !!meta && sameText(meta.tokenHash, await sha256Hex(token));
  }
}

function send(ws: WebSocket, frame: LobbyServerFrame): void {
  try {
    ws.send(JSON.stringify(frame));
  } catch {
    // Closed; nothing to deliver to.
  }
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}
