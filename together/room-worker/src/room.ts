import { DurableObject } from "cloudflare:workers";
import {
  BASE64URL,
  Close,
  HEX_ID,
  bearer,
  errorResponse,
  limitsFor,
  parseFrame,
  sameText,
  sha256Hex,
  type RoomClientFrame,
  type RoomServerFrame,
} from "@playbridge-together/shared";
import { roomLimits, type Env } from "./config";

// One Room per party. It never sees plaintext: members exchange AES-GCM
// ciphertext, and the room only stamps senders, answers clock pings, keeps the
// latest encrypted state snapshot for late joiners, and enforces limits.
// Everything lives in storage or socket attachments so the object can hibernate.

const MAX_FRAME_BYTES = 64 * 1024;

interface Meta {
  tokenHash: string;
  createdAt: number;
  expiresAt: number;
  maxMembers: number;
}

interface Snapshot {
  from: string;
  seq: number;
  ct: string;
}

interface Attachment {
  member: string;
  joinedAt: number;
}

export class Room extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const member = url.searchParams.get("member") ?? "";
    const token = bearer(request);
    if (!HEX_ID.test(member) || !token) return errorResponse("unauthorized", 401);

    const limits = roomLimits(this.env);
    const now = Date.now();
    const tokenHash = await sha256Hex(token);
    let meta = await this.ctx.storage.get<Meta>("meta");

    if (!meta) {
      const ip = request.headers.get("x-pb-client-ip") ?? "unknown";
      const allowed = await limitsFor(this.env.LIMITS, ip).hit("room", limits.roomsPerIpPerDay, 86_400_000);
      if (!allowed) return errorResponse("rate_limited", 429);
      meta = {
        tokenHash,
        createdAt: now,
        expiresAt: now + limits.lifetimeMs,
        maxMembers: limits.maxMembers,
      };
      await this.ctx.storage.put("meta", meta);
    } else if (!sameText(meta.tokenHash, tokenHash)) {
      return errorResponse("unauthorized", 401);
    }
    if (now >= meta.expiresAt) return errorResponse("expired", 410);

    const open = this.openSockets();
    const others = new Set(open.map(([, a]) => a.member).filter((m) => m !== member));
    if (others.size >= meta.maxMembers) return errorResponse("room_full", 403);

    // A reconnect replaces the member's previous socket.
    let rejoined = false;
    for (const [ws, attachment] of open) {
      if (attachment.member === member) {
        rejoined = true;
        closeQuietly(ws, Close.Replaced, "replaced");
      }
    }

    const pair = new WebSocketPair();
    const server = pair[1];
    const joinedAt = open.find(([, a]) => a.member === member)?.[1].joinedAt ?? now;
    this.ctx.acceptWebSocket(server, [member]);
    server.serializeAttachment({ member, joinedAt } satisfies Attachment);

    const members = [...open.filter(([, a]) => a.member !== member), [server, { member, joinedAt }] as const]
      .sort(([, a], [, b]) => a.joinedAt - b.joinedAt || a.member.localeCompare(b.member))
      .map(([, a]) => a.member);
    const state = await this.ctx.storage.get<Snapshot>("state");
    send(server, {
      t: "welcome",
      member,
      members,
      room_time: Date.now(),
      expires_at: meta.expiresAt,
      max_members: meta.maxMembers,
      ...(state ? { state } : {}),
    });
    if (!rejoined) this.broadcast({ t: "joined", member }, server);

    await this.ctx.storage.setAlarm(meta.expiresAt);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    const frame = parseFrame<RoomClientFrame>(raw, MAX_FRAME_BYTES);
    if (!attachment || !frame) return closeQuietly(ws, Close.BadMessage, "bad frame");

    switch (frame.t) {
      case "ping":
        if (!Number.isFinite(frame.id) || !Number.isFinite(frame.t0)) break;
        return send(ws, { t: "pong", id: frame.id, t0: frame.t0, room_time: Date.now() });

      case "msg": {
        if (!validCiphertext(frame.seq, frame.ct)) break;
        const out: RoomServerFrame = { t: "msg", from: attachment.member, seq: frame.seq, ct: frame.ct };
        if (frame.to !== undefined) {
          if (!HEX_ID.test(frame.to)) break;
          for (const target of this.ctx.getWebSockets(frame.to)) send(target, out);
        } else {
          this.broadcast(out, ws);
        }
        return;
      }

      case "state": {
        if (!validCiphertext(frame.seq, frame.ct)) break;
        const snapshot: Snapshot = { from: attachment.member, seq: frame.seq, ct: frame.ct };
        await this.ctx.storage.put("state", snapshot);
        this.broadcast({ t: "state", ...snapshot }, ws);
        return;
      }
    }
    closeQuietly(ws, Close.BadMessage, "bad frame");
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    closeQuietly(ws, 1000, "closing");
    await this.memberGone(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.memberGone(ws);
  }

  async alarm(): Promise<void> {
    const meta = await this.ctx.storage.get<Meta>("meta");
    const open = this.openSockets();
    if (!meta || Date.now() >= meta.expiresAt) {
      for (const [ws] of open) closeQuietly(ws, Close.Expired, "room expired");
      await this.ctx.storage.deleteAll();
      return;
    }
    if (open.length === 0) {
      await this.ctx.storage.deleteAll();
      return;
    }
    await this.ctx.storage.setAlarm(meta.expiresAt);
  }

  private async memberGone(ws: WebSocket): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    const open = this.openSockets().filter(([socket]) => socket !== ws);
    if (attachment && !open.some(([, a]) => a.member === attachment.member)) {
      this.broadcast({ t: "left", member: attachment.member }, ws);
    }
    if (open.length === 0) {
      const meta = await this.ctx.storage.get<Meta>("meta");
      if (!meta) return;
      const idle = Date.now() + roomLimits(this.env).idleCloseMs;
      await this.ctx.storage.setAlarm(Math.min(idle, meta.expiresAt));
    }
  }

  private openSockets(): [WebSocket, Attachment][] {
    const result: [WebSocket, Attachment][] = [];
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      const attachment = ws.deserializeAttachment() as Attachment | null;
      if (attachment) result.push([ws, attachment]);
    }
    return result;
  }

  private broadcast(frame: RoomServerFrame, except?: WebSocket): void {
    for (const [ws] of this.openSockets()) if (ws !== except) send(ws, frame);
  }
}

function validCiphertext(seq: unknown, ct: unknown): boolean {
  return (
    typeof seq === "number" &&
    Number.isSafeInteger(seq) &&
    seq >= 0 &&
    typeof ct === "string" &&
    ct.length > 0 &&
    BASE64URL.test(ct)
  );
}

function send(ws: WebSocket, frame: RoomServerFrame): void {
  try {
    ws.send(JSON.stringify(frame));
  } catch {
    // The socket closed between listing and sending; its close event cleans up.
  }
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}
