import { DurableObject } from "cloudflare:workers";

// One Limits object per client IP. Fixed-window counters, stored in memory and
// SQLite so a hit survives eviction. Each Worker binds its own copy.

export interface LimitsRpc {
  hit(kind: string, max: number, windowMs: number): Promise<boolean>;
}

export class Limits extends DurableObject implements LimitsRpc {
  async hit(kind: string, max: number, windowMs: number): Promise<boolean> {
    const now = Date.now();
    const key = `w:${kind}`;
    const window = (await this.ctx.storage.get<{ start: number; count: number }>(key)) ?? {
      start: now,
      count: 0,
    };
    if (now - window.start >= windowMs) {
      window.start = now;
      window.count = 0;
    }
    if (window.count >= max) return false;
    window.count++;
    await this.ctx.storage.put(key, window);
    // Clear the object once its longest window has passed.
    const alarm = await this.ctx.storage.getAlarm();
    const expiry = window.start + windowMs;
    if (alarm === null || alarm < expiry) await this.ctx.storage.setAlarm(expiry);
    return true;
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAll();
  }
}

export function limitsFor(ns: DurableObjectNamespace<Limits>, ip: string) {
  return ns.get(ns.idFromName(ip));
}
