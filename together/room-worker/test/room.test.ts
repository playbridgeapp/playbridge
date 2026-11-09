import { SELF, env as testEnv, runDurableObjectAlarm } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { Env } from "../src/config";

const env = testEnv as unknown as Env;

const BASE = "https://relay.test";
const hex = (n: number) => n.toString(16).padStart(32, "0");

interface Client {
  ws: WebSocket;
  frames: any[];
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
  ws.addEventListener("message", (event) => {
    const frame = JSON.parse(event.data as string);
    const waiter = waiters.shift();
    if (waiter) waiter(frame);
    else frames.push(frame);
  });
  return {
    ws,
    frames,
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
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    await (await member(room, hex(2), "second")).next("welcome");
  });

  it("limits new rooms per IP", async () => {
    const ip = "203.0.113.9";
    for (let i = 0; i < 2; i++) await (await member(hex(100 + i), hex(1), "t", ip)).next("welcome");
    const third = await connect(hex(102), hex(1), "t", ip);
    expect((third as Response).status).toBe(429);
  });
});
