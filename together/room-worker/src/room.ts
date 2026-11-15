import { DurableObject } from "cloudflare:workers";
import {
  BASE64URL,
  Close,
  HEX_ID,
  bearer,
  errorResponse,
  parseFrame,
  secondsUntilUtcMidnight,
  sameText,
  sha256Hex,
  type RoomClientFrame,
  type RoomServerFrame,
} from "@playbridge-together/shared";
import { roomLimits, type Env, type RoomLimits } from "./config";
import { GATE_NAME, ROOM_RESERVE } from "./gate";

// One Room per party. It never sees plaintext: members exchange AES-GCM
// ciphertext, and the room only stamps senders, answers clock pings, keeps the
// latest encrypted state snapshot for late joiners, and enforces limits.
// Everything lives in storage or socket attachments so the object can hibernate.

const MAX_FRAME_BYTES = 64 * 1024;

/** An empty room gives its creator's slot back after this long; rejoining takes it again. */
export const SLOT_RELEASE_MS = 2 * 60_000;

/**
 * Messages per member: a burst of 60, then one every 2 seconds. A member normally sends a
 * few a minute (clock pings, position reports, controls).
 */
const BURST = 60;
const REFILL_PER_MS = 30 / 60_000;

/** How many messages a member sends before the room reports them to the daily budget. */
const REPORT_EVERY = 2_000;

interface Meta {
  tokenHash: string;
  createdAt: number;
  expiresAt: number;
  maxMembers: number;
  /** The creator's Limits object, which holds this room's slot. */
  owner?: string;
  /** Whether the room holds a slot now. Empty rooms give theirs back. */
  slot?: boolean;
  /** When the last member left; unset while anyone is connected. */
  emptySince?: number;
}

interface Snapshot {
  from: string;
  seq: number;
  ct: string;
}

interface Attachment {
  member: string;
  joinedAt: number;
  /** Message bucket. Kept on the socket so it survives hibernation. */
  tokens?: number;
  at?: number;
  /** Messages not yet reported to the daily budget. */
  used?: number;
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
    let created = false;

    if (!meta) {
      const ip = request.headers.get("x-pb-client-ip") ?? "unknown";
      const owner = this.env.LIMITS.idFromName(ip);
      const expiresAt = now + limits.lifetimeMs;
      const slot = await this.env.LIMITS.get(owner).openRoom(
        this.key(), expiresAt, limits.openRoomsPerIp, limits.roomsPerIpPerHour, true,
      );
      if (!slot.ok) return roomLimit(slot.retryAfterSecs);
      if (limits.dailyBudget > 0 && !(await this.gate().reserve(ROOM_RESERVE, limits.dailyBudget))) {
        await this.env.LIMITS.get(owner).closeRoom(this.key());
        console.log("refused room: daily budget spent");
        return errorResponse("relay_busy", 503, secondsUntilUtcMidnight());
      }
      meta = {
        tokenHash,
        createdAt: now,
        expiresAt,
        maxMembers: limits.maxMembers,
        owner: owner.toString(),
        slot: true,
      };
      await this.ctx.storage.put("meta", meta);
      created = true;
    } else if (!sameText(meta.tokenHash, tokenHash)) {
      return errorResponse("unauthorized", 401);
    }
    if (now >= meta.expiresAt) return errorResponse("expired", 410);

    const open = this.openSockets();
    const others = new Set(open.map(([, a]) => a.member).filter((m) => m !== member));
    if (others.size >= meta.maxMembers) return errorResponse("room_full", 403);

    // Coming back to a room that emptied: take its slot again if it gave it back.
    if (!created && (meta.slot === false || meta.emptySince !== undefined)) {
      if (meta.slot === false && meta.owner) {
        const slot = await this.owner(meta.owner).openRoom(
          this.key(), meta.expiresAt, limits.openRoomsPerIp, limits.roomsPerIpPerHour, false,
        );
        if (!slot.ok) return roomLimit(slot.retryAfterSecs);
        meta.slot = true;
      }
      delete meta.emptySince;
      await this.ctx.storage.put("meta", meta);
    }

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
    server.serializeAttachment({ member, joinedAt, tokens: BURST, at: now, used: 0 } satisfies Attachment);

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

    // A room's alarm only moves when it empties; one set earlier than expiry just re-arms.
    if (created) await this.ctx.storage.setAlarm(meta.expiresAt);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    const frame = parseFrame<RoomClientFrame>(raw, MAX_FRAME_BYTES);
    if (!attachment || !frame) return closeQuietly(ws, Close.BadMessage, "bad frame");
    if (!(await this.spend(ws, attachment))) {
      console.log("closed a member sending too fast");
      return closeQuietly(ws, Close.RateLimited, "too many messages");
    }

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
    const now = Date.now();
    if (!meta || now >= meta.expiresAt) {
      for (const [ws] of open) closeQuietly(ws, Close.Expired, "room expired");
      if (meta) await this.releaseSlot(meta);
      await this.ctx.storage.deleteAll();
      return;
    }
    if (open.length > 0) {
      await this.ctx.storage.setAlarm(meta.expiresAt);
      return;
    }
    const limits = roomLimits(this.env);
    const emptySince = meta.emptySince ?? now;
    if (now >= emptySince + limits.idleCloseMs) {
      await this.releaseSlot(meta);
      await this.ctx.storage.deleteAll();
      return;
    }
    if (meta.slot && now >= emptySince + SLOT_RELEASE_MS) {
      await this.releaseSlot(meta);
      meta.slot = false;
    }
    meta.emptySince = emptySince;
    await this.ctx.storage.put("meta", meta);
    await this.ctx.storage.setAlarm(this.emptyAlarm(meta, emptySince, limits));
  }

  private async memberGone(ws: WebSocket): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    const open = this.openSockets().filter(([socket]) => socket !== ws);
    if (attachment && !open.some(([, a]) => a.member === attachment.member)) {
      this.broadcast({ t: "left", member: attachment.member }, ws);
    }
    // The connect and this close, plus the messages not reported yet. A socket can get both
    // a close and an error; -1 marks it as counted.
    if (attachment && attachment.used !== -1) {
      try {
        ws.serializeAttachment({ ...attachment, used: -1 } satisfies Attachment);
      } catch {
        // Gone already; count it anyway.
      }
      await this.report(2 + Math.ceil((attachment.used ?? 0) / 20));
    }
    if (open.length === 0) {
      const meta = await this.ctx.storage.get<Meta>("meta");
      if (!meta || meta.emptySince !== undefined) return;
      meta.emptySince = Date.now();
      await this.ctx.storage.put("meta", meta);
      await this.ctx.storage.setAlarm(this.emptyAlarm(meta, meta.emptySince, roomLimits(this.env)));
    }
  }

  /** When an empty room next needs attention: giving its slot back, then closing. */
  private emptyAlarm(meta: Meta, emptySince: number, limits: RoomLimits): number {
    const next = meta.slot ? emptySince + Math.min(SLOT_RELEASE_MS, limits.idleCloseMs) : emptySince + limits.idleCloseMs;
    return Math.min(next, meta.expiresAt);
  }

  /** Takes one message from the member's bucket; false when the bucket is empty. */
  private async spend(ws: WebSocket, attachment: Attachment): Promise<boolean> {
    const now = Date.now();
    const tokens = Math.min(BURST, (attachment.tokens ?? BURST) + (now - (attachment.at ?? now)) * REFILL_PER_MS);
    if (tokens < 1) return false;
    let used = (attachment.used ?? 0) + 1;
    if (used >= REPORT_EVERY) {
      await this.report(used / 20);
      used = 0;
    }
    ws.serializeAttachment({ ...attachment, tokens: tokens - 1, at: now, used } satisfies Attachment);
    return true;
  }

  private async releaseSlot(meta: Meta): Promise<void> {
    if (!meta.slot || !meta.owner) return;
    try {
      await this.owner(meta.owner).closeRoom(this.key());
    } catch {
      // The slot frees itself when the room would have expired.
    }
  }

  /** Adds usage to the daily budget, when this relay has one. */
  private async report(units: number): Promise<void> {
    if (roomLimits(this.env).dailyBudget <= 0) return;
    try {
      await this.gate().report(units);
    } catch {
      // Best effort: the budget is a guard rail, not a meter.
    }
  }

  private key(): string {
    return this.ctx.id.toString();
  }

  private owner(id: string) {
    return this.env.LIMITS.get(this.env.LIMITS.idFromString(id));
  }

  private gate() {
    return this.env.GATE.get(this.env.GATE.idFromName(GATE_NAME));
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

function roomLimit(retryAfterSecs: number): Response {
  console.log("refused room: too many from this network");
  return errorResponse("room_limit", 429, retryAfterSecs);
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
