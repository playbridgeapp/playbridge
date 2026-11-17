import { SELF, env as testEnv, runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { appleAppSiteAssociation, assetLinks, type Env } from "../src/config";
import { KEEPALIVE_SECS, LEGACY_SILENT_MS, SILENT_MS, isSilent } from "../src/room";

const env = testEnv as unknown as Env;

const BASE = "https://relay.test";
const CERT = Array.from({ length: 32 }, () => "AB").join(":");
const hex = (n: number) => n.toString(16).padStart(32, "0");

interface Client {
  ws: WebSocket;
  frames: any[];
  /** Keep-alive answers received; they are plain text, not frames. */
  pongs: number;
  next(type?: string): Promise<any>;
  send(frame: unknown): void;
}

async function connect(room: string, member: string, token = "tok", ip = `198.51.100.${parseInt(room.slice(-2), 16)}`): Promise<Client | Response> {
  const response = await SELF.fetch(`${BASE}/ws/${room}?member=${member}`, {
    headers: { upgrade: "websocket", authorization: `Bearer ${token}`, "cf-connecting-ip": ip },
  });
  if (!response.webSocket) return response;
  const ws = response.webSocket;
  ws.accept();
  const frames: any[] = [];
  const waiters: ((frame: any) => void)[] = [];
  const client = { pongs: 0 };
  ws.addEventListener("message", (event) => {
    if (event.data === "pong") {
      client.pongs++;
      return;
    }
    const frame = JSON.parse(event.data as string);
    const waiter = waiters.shift();
    if (waiter) waiter(frame);
    else frames.push(frame);
  });
  return {
    ws,
    frames,
    get pongs() {
      return client.pongs;
    },
    next(type) {
      const take = (): Promise<any> => {
        const queued = frames.shift();
        const p = queued ? Promise.resolve(queued) : new Promise((r) => waiters.push(r));
        return p.then((frame: any) => (type && frame.t !== type ? take() : frame));
      };
      return withTimeout(take());
    },
    send(frame) {
      ws.send(JSON.stringify(frame));
    },
  };
}

async function member(room: string, id: string, token?: string, ip?: string): Promise<Client> {
  const result = await connect(room, id, token, ip);
  if (result instanceof Response) throw new Error(`connect failed: ${result.status} ${await result.text()}`);
  return result;
}

function withTimeout<T>(p: Promise<T>, ms = 2000): Promise<T> {
  return Promise.race([p, new Promise<T>((_, reject) => setTimeout(() => reject(new Error("timed out")), ms))]);
}

describe("room worker", () => {
  it("reports its version and limits", async () => {
    const body = await (await SELF.fetch(`${BASE}/version`)).json<any>();
    expect(body).toMatchObject({ service: "playbridge-together-room", protocol: 1, shared: false, max_members: 3 });
  });

  it("serves the setup and invite pages", async () => {
    const setup = await SELF.fetch(`${BASE}/setup`);
    expect(await setup.text()).toContain("https://relay.test");
    const invite = await SELF.fetch(`${BASE}/j/${hex(1)}`);
    expect(invite.status).toBe(200);
  });

  it("serves App Links only when configured", async () => {
    const res = await SELF.fetch(`${BASE}/.well-known/assetlinks.json`);
    expect(res.status).toBe(200);
    const [statement] = await res.json<any[]>();
    expect(statement.target).toEqual({
      namespace: "android_app",
      package_name: "com.playbridge.sender",
      sha256_cert_fingerprints: [CERT],
    });
    expect(assetLinks({ ANDROID_APP_PACKAGE: "com.playbridge.sender" } as Env)).toBeNull();
    expect(assetLinks({ ANDROID_APP_PACKAGE: "x", ANDROID_CERT_SHA256: "nope" } as Env)).toBeNull();
  });

  it("serves Apple App Site Association only when configured", async () => {
    const res = await SELF.fetch(`${BASE}/.well-known/apple-app-site-association`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("application/json");
    const body = await res.json<any>();
    expect(body).toEqual({
      applinks: {
        apps: [],
        details: [
          {
            appIDs: ["4VL6AT677Y.app.playbridge.phone"],
            paths: ["/j/*"],
            components: [{ "/": "/j/*" }],
          },
        ],
      },
    });
    expect(appleAppSiteAssociation({} as Env)).toBeNull();
    expect(appleAppSiteAssociation({ APPLE_APP_IDS: "invalid-id" } as Env)).toBeNull();
  });

  it("welcomes members in join order and routes messages", async () => {
    const room = hex(10);
    const a = await member(room, hex(1));
    const welcomeA = await a.next("welcome");
    expect(welcomeA.members).toEqual([hex(1)]);
    expect(typeof welcomeA.room_time).toBe("number");

    const b = await member(room, hex(2));
    expect((await b.next("welcome")).members).toEqual([hex(1), hex(2)]);
    expect(await a.next("joined")).toEqual({ t: "joined", member: hex(2) });

    b.send({ t: "msg", seq: 1, ct: "Y2lwaGVy" });
    expect(await a.next("msg")).toEqual({ t: "msg", from: hex(2), seq: 1, ct: "Y2lwaGVy" });

    const c = await member(room, hex(3));
    await c.next("welcome");
    a.send({ t: "msg", to: hex(3), seq: 2, ct: "ZGlyZWN0" });
    expect(await c.next("msg")).toMatchObject({ from: hex(1), seq: 2 });
    b.send({ t: "msg", seq: 3, ct: "bWFya2Vy" });
    // b's broadcast reaches a before anything else, so a never saw the directed message.
    expect(await a.next("msg")).toMatchObject({ from: hex(2), seq: 3 });
  });

  it("answers clock pings with room time", async () => {
    const a = await member(hex(11), hex(1));
    await a.next("welcome");
    a.send({ t: "ping", id: 7, t0: 123 });
    const pong = await a.next("pong");
    expect(pong).toMatchObject({ t: "pong", id: 7, t0: 123 });
    expect(pong.room_time).toBeGreaterThan(0);
  });

  it("rejects a wrong room token", async () => {
    const room = hex(12);
    await (await member(room, hex(1), "right")).next("welcome");
    const result = await connect(room, hex(2), "wrong");
    expect(result).toBeInstanceOf(Response);
    expect((result as Response).status).toBe(401);
  });

  it("refuses members beyond the limit but lets a member reconnect", async () => {
    const room = hex(13);
    for (const id of [1, 2, 3]) await (await member(room, hex(id))).next("welcome");
    const extra = await connect(room, hex(4));
    expect((extra as Response).status).toBe(403);
    const again = await member(room, hex(2));
    expect((await again.next("welcome")).members).toEqual([hex(1), hex(2), hex(3)]);
  });

  it("gives late joiners the latest state snapshot", async () => {
    const room = hex(14);
    const a = await member(room, hex(1));
    await a.next("welcome");
    a.send({ t: "state", seq: 4, ct: "c3RhdGU" });
    const b = await member(room, hex(2));
    expect((await b.next("welcome")).state).toEqual({ from: hex(1), seq: 4, ct: "c3RhdGU" });
  });

  it("closes members that send bad frames", async () => {
    const a = await member(hex(15), hex(1));
    await a.next("welcome");
    const closed = new Promise<number>((r) => a.ws.addEventListener("close", (e) => r(e.code)));
    a.ws.send("not json");
    expect(await withTimeout(closed)).toBe(4400);
  });

  it("forgets an empty room when its alarm fires", async () => {
    const room = hex(16);
    const a = await member(room, hex(1), "first");
    await a.next("welcome");
    const closed = new Promise((r) => a.ws.addEventListener("close", r));
    a.ws.close(1000, "bye");
    await withTimeout(closed);
    const stub = env.ROOMS.get(env.ROOMS.idFromName(room));
    // Let the close handler run before firing the alarm.
    await new Promise((r) => setTimeout(r, 50));
    await emptyFor(stub, 31 * 60_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    await (await member(room, hex(2), "second")).next("welcome");
  });

  it("limits open rooms per network and frees a slot soon after a room empties", async () => {
    const ip = "203.0.113.9";
    const first = await member(hex(100), hex(1), "t", ip);
    await first.next("welcome");
    await (await member(hex(101), hex(1), "t", ip)).next("welcome");

    const third = (await connect(hex(102), hex(1), "t", ip)) as Response;
    expect(third.status).toBe(429);
    expect(await third.json()).toMatchObject({ error: "room_limit" });
    expect(Number(third.headers.get("retry-after"))).toBeGreaterThan(0);

    // The first room empties: it keeps its slot for 2 minutes, then gives it back.
    const closed = new Promise((r) => first.ws.addEventListener("close", r));
    first.ws.close(1000, "bye");
    await withTimeout(closed);
    await new Promise((r) => setTimeout(r, 50));
    const stub = env.ROOMS.get(env.ROOMS.idFromName(hex(100)));
    expect(((await connect(hex(102), hex(1), "t", ip)) as Response).status).toBe(429);
    await emptyFor(stub, 3 * 60_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    await (await member(hex(102), hex(1), "t", ip)).next("welcome");

    // Coming back to the emptied room needs a slot again, and this network has none left.
    const back = (await connect(hex(100), hex(1), "t", ip)) as Response;
    expect(back.status).toBe(429);
    expect(await back.json()).toMatchObject({ error: "room_limit" });
  });

  it("lets members back into an empty room that still holds its slot", async () => {
    const ip = "203.0.113.10";
    const room = hex(110);
    const a = await member(room, hex(1), "t", ip);
    await a.next("welcome");
    const closed = new Promise((r) => a.ws.addEventListener("close", r));
    a.ws.close(1000, "bye");
    await withTimeout(closed);
    await new Promise((r) => setTimeout(r, 50));
    await (await member(hex(111), hex(1), "t", ip)).next("welcome");
    // Both slots are taken, but the empty room never gave its slot back.
    await (await member(room, hex(1), "t", ip)).next("welcome");
  });

  it("counts open rooms and new rooms per hour in the network's limits", async () => {
    const limits = env.LIMITS.get(env.LIMITS.idFromName("203.0.113.20"));
    const later = Date.now() + 3_600_000;
    expect(await limits.openRoom("a", later, 2, 3, true)).toEqual({ ok: true });
    expect(await limits.openRoom("a", later, 2, 3, true)).toEqual({ ok: true });
    expect(await limits.openRoom("b", Date.now() - 1, 2, 3, true)).toEqual({ ok: true });
    // b had already expired, so it holds no slot.
    expect(await limits.openRoom("c", later, 2, 3, true)).toEqual({ ok: true });
    const full = await limits.openRoom("d", later, 2, 3, true);
    expect(full.ok).toBe(false);
    await limits.closeRoom("a");
    // A slot is free, but this network started 3 rooms this hour.
    const hourly = await limits.openRoom("d", later, 2, 3, true);
    expect(hourly).toMatchObject({ ok: false });
    expect(hourly.ok === false && hourly.retryAfterSecs).toBeGreaterThan(3_500);
    // A room coming back after it emptied isn't a new room.
    expect(await limits.openRoom("a", later, 2, 3, false)).toEqual({ ok: true });
  });

  it("closes a member that sends too many messages", async () => {
    const a = await member(hex(120), hex(1));
    await a.next("welcome");
    const closed = new Promise<number>((r) => a.ws.addEventListener("close", (e) => r(e.code)));
    for (let i = 0; i < 70; i++) a.send({ t: "ping", id: i, t0: i });
    expect(await withTimeout(closed)).toBe(4029);
  });

  it("tells members how often to send keep-alives and answers them", async () => {
    const a = await member(hex(140), hex(1));
    expect((await a.next("welcome")).keepalive_secs).toBe(KEEPALIVE_SECS);
    a.ws.send("ping");
    a.send({ t: "ping", id: 7, t0: 1 });
    expect(await a.next("pong")).toMatchObject({ id: 7 });
    expect(a.pongs).toBe(1);
  });

  it("knows a silent member from a quiet one", () => {
    const now = 10_000_000;
    // Sends keep-alives: gone soon after they stop, whatever its last frame was.
    expect(isSilent(now, now - 10 * 60_000, now - 30_000)).toBe(false);
    expect(isSilent(now, now - 10 * 60_000, now - SILENT_MS - 1)).toBe(true);
    expect(isSilent(now, now - 1_000, now - SILENT_MS - 1)).toBe(false);
    // An older app: only its two-minute clock pings to go by.
    expect(isSilent(now, now - 3 * 60_000, null)).toBe(false);
    expect(isSilent(now, now - LEGACY_SILENT_MS - 1, null)).toBe(true);
  });

  it("drops a member that went silent when its alarm fires, and tells the others", async () => {
    const room = hex(141);
    const a = await member(room, hex(1));
    await a.next("welcome");
    const b = await member(room, hex(2));
    await b.next("welcome");
    await a.next("joined");
    const stub = env.ROOMS.get(env.ROOMS.idFromName(room));
    const closed = new Promise<number>((r) => b.ws.addEventListener("close", (e) => r(e.code)));

    // Quiet, but within what an older app's clock pings allow: both stay.
    await quietFor(stub, hex(2), 3 * 60_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    a.send({ t: "ping", id: 1, t0: 1 });
    expect(await a.next()).toMatchObject({ t: "pong", id: 1 });

    await quietFor(stub, hex(2), LEGACY_SILENT_MS + 1_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect(await a.next()).toEqual({ t: "left", member: hex(2) });
    expect(await withTimeout(closed)).toBe(4009);

    // The room still has a member, so it keeps checking; the dropped one may come back.
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    const again = await member(room, hex(2));
    expect((await again.next("welcome")).members).toEqual([hex(1), hex(2)]);
    expect(await a.next()).toEqual({ t: "joined", member: hex(2) });
  });

  it("keeps a member whose only sign of life is its keep-alives", async () => {
    const room = hex(142);
    const a = await member(room, hex(1));
    await a.next("welcome");
    const b = await member(room, hex(2));
    await b.next("welcome");
    await a.next("joined");
    b.ws.send("ping");
    b.send({ t: "ping", id: 1, t0: 1 });
    await b.next("pong");
    const stub = env.ROOMS.get(env.ROOMS.idFromName(room));
    await quietFor(stub, hex(2), LEGACY_SILENT_MS + 60_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    a.send({ t: "ping", id: 2, t0: 1 });
    expect(await a.next()).toMatchObject({ t: "pong", id: 2 });
  });

  it("notices a silent member as soon as anyone else says something", async () => {
    const room = hex(143);
    const a = await member(room, hex(1));
    await a.next("welcome");
    const b = await member(room, hex(2));
    await b.next("welcome");
    await a.next("joined");
    const stub = env.ROOMS.get(env.ROOMS.idFromName(room));
    await quietFor(stub, hex(2), LEGACY_SILENT_MS + 1_000);
    a.send({ t: "ping", id: 1, t0: 1 });
    expect(await a.next()).toEqual({ t: "left", member: hex(2) });
    expect(await a.next()).toMatchObject({ t: "pong", id: 1 });
  });

  it("marks the room empty when the last member is dropped for silence", async () => {
    const room = hex(144);
    const a = await member(room, hex(1), "first");
    await a.next("welcome");
    const stub = env.ROOMS.get(env.ROOMS.idFromName(room));
    await quietFor(stub, hex(1), LEGACY_SILENT_MS + 1_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    await emptyFor(stub, 31 * 60_000);
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    // Forgotten: a different token can claim the room id again.
    await (await member(room, hex(2), "second")).next("welcome");
  });

  it("refuses new rooms once the daily budget is spent, but keeps open ones going", async () => {
    const open = await member(hex(130), hex(1));
    await open.next("welcome");
    const gate = env.GATE.get(env.GATE.idFromName("daily"));
    await gate.report(1_000_000);

    const refused = (await connect(hex(131), hex(1))) as Response;
    expect(refused.status).toBe(503);
    expect(await refused.json()).toMatchObject({ error: "relay_busy" });
    expect(Number(refused.headers.get("retry-after"))).toBeLessThanOrEqual(86_400);

    // The refused room gave its network slot back.
    const limits = env.LIMITS.get(env.LIMITS.idFromName(`198.51.100.${0x31}`));
    expect(await limits.openRoom("x", Date.now() + 60_000, 1, 10, true)).toEqual({ ok: true });

    open.send({ t: "ping", id: 1, t0: 1 });
    expect(await open.next("pong")).toMatchObject({ id: 1 });
    await (await member(hex(130), hex(2))).next("welcome");
  });
});

/** Pretends `memberId` last sent a frame `ms` ago. */
async function quietFor(stub: DurableObjectStub, memberId: string, ms: number): Promise<void> {
  await runInDurableObject(stub, async (_instance, state) => {
    for (const ws of state.getWebSockets(memberId)) {
      const attachment = ws.deserializeAttachment() as any;
      ws.serializeAttachment({ ...attachment, at: Date.now() - ms });
    }
  });
}

/** Pretends the room has been empty for `ms`, so its next alarm acts on it. */
async function emptyFor(stub: DurableObjectStub, ms: number): Promise<void> {
  await runInDurableObject(stub, async (_instance, state) => {
    const meta = await state.storage.get<any>("meta");
    meta.emptySince -= ms;
    await state.storage.put("meta", meta);
  });
}
