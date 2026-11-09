import { SELF, env as testEnv, runDurableObjectAlarm } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { Env } from "../src/config";

const env = testEnv as unknown as Env;

const BASE = "https://pair.test";
const PK = "A".repeat(43);
let ipCounter = 0;
const freshIp = () => `192.0.2.${++ipCounter}`;

async function call(path: string, init: RequestInit & { ip?: string } = {}) {
  const headers = new Headers(init.headers);
  headers.set("cf-connecting-ip", init.ip ?? "198.51.100.7");
  return SELF.fetch(`${BASE}${path}`, { ...init, headers });
}

async function register(ip = freshIp()) {
  const response = await call("/lobby", { method: "POST", body: JSON.stringify({ pk: PK }), ip });
  expect(response.status).toBe(201);
  return response.json<{ code: string; token: string; expires_at: number }>();
}

async function wait(code: string, token: string) {
  const response = await call(`/lobby/${code}/ws`, { headers: { upgrade: "websocket", authorization: `Bearer ${token}` } });
  const ws = response.webSocket;
  if (!ws) throw new Error(`ws failed: ${response.status}`);
  ws.accept();
  const frames: any[] = [];
  const waiters: ((f: any) => void)[] = [];
  ws.addEventListener("message", (e) => {
    const frame = JSON.parse(e.data as string);
    const w = waiters.shift();
    if (w) w(frame);
    else frames.push(frame);
  });
  const next = () =>
    Promise.race([
      frames.length ? Promise.resolve(frames.shift()) : new Promise((r) => waiters.push(r)),
      new Promise((_, reject) => setTimeout(() => reject(new Error("timed out")), 2000)),
    ]);
  return { ws, next };
}

describe("pairing worker", () => {
  it("registers a receiver and returns a 6-character code", async () => {
    const { code, token, expires_at } = await register();
    expect(code).toMatch(/^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$/);
    expect(token).toMatch(/^[0-9a-f]{64}$/);
    expect(expires_at).toBeGreaterThan(Date.now());
  });

  it("rejects malformed keys", async () => {
    const response = await call("/lobby", { method: "POST", body: JSON.stringify({ pk: "short" }), ip: freshIp() });
    expect(response.status).toBe(400);
  });

  it("looks a code up case-insensitively, with dashes", async () => {
    const { code } = await register();
    const typed = `${code.slice(0, 3)}-${code.slice(3)}`.toLowerCase();
    const found = await call(`/lobby/${typed}`, { ip: freshIp() });
    expect(await found.json()).toMatchObject({ pk: PK });
    expect((await call("/lobby/ZZZZZZ", { ip: freshIp() })).status).toBe(404);
  });

  it("delivers invites to a waiting receiver, including ones sent before it connected", async () => {
    const { code, token } = await register();
    await call(`/lobby/${code}/invite`, { method: "POST", body: JSON.stringify({ sealed: "ZWFybHk" }), ip: freshIp() });
    const receiver = await wait(code, token);
    expect(await receiver.next()).toMatchObject({ t: "invite", sealed: "ZWFybHk" });
    const sent = await call(`/lobby/${code}/invite`, { method: "POST", body: JSON.stringify({ sealed: "bGF0ZQ" }), ip: freshIp() });
    expect(sent.status).toBe(202);
    expect(await receiver.next()).toMatchObject({ t: "invite", sealed: "bGF0ZQ" });
  });

  it("only lets the receiver wait on or release its code", async () => {
    const { code, token } = await register();
    const wrong = await call(`/lobby/${code}/ws`, { headers: { upgrade: "websocket", authorization: `Bearer ${"0".repeat(64)}` } });
    expect(wrong.status).toBe(401);
    expect((await call(`/lobby/${code}`, { method: "DELETE", headers: { authorization: "Bearer nope" } })).status).toBe(401);
    expect((await call(`/lobby/${code}`, { method: "DELETE", headers: { authorization: `Bearer ${token}` } })).status).toBe(204);
    expect((await call(`/lobby/${code}`, { ip: freshIp() })).status).toBe(404);
  });

  it("expires codes and tells the waiting receiver", async () => {
    const { code, token } = await register();
    const receiver = await wait(code, token);
    const stub = env.LOBBIES.get(env.LOBBIES.idFromName(code));
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect(await receiver.next()).toEqual({ t: "expired" });
    expect((await call(`/lobby/${code}`, { ip: freshIp() })).status).toBe(404);
  });

  it("rate-limits code lookups per IP", async () => {
    const ip = freshIp();
    let status = 0;
    for (let i = 0; i < 31; i++) status = (await call("/lobby/ZZZZZZ", { ip })).status;
    expect(status).toBe(429);
  });

  it("reports its version", async () => {
    expect(await (await call("/version")).json()).toEqual({ service: "playbridge-together-pairing", protocol: 1 });
  });
});
