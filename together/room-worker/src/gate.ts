import { DurableObject } from "cloudflare:workers";
import type { Env } from "./config";

// The relay's share of Cloudflare's daily Durable Object budget. Cloudflare's own limit is
// per account and shared with the pairing service, so the relay stops opening new rooms a
// little before it would run the account dry. Rooms already open keep going; they report
// what they used so the count stays close to Cloudflare's.
//
// Units are Durable Object requests: one per connect or close, one per 20 messages a
// member sends.

export const GATE_NAME = "daily";
/** What a new room is charged up front: about one party's worth of connects and pings. */
export const ROOM_RESERVE = 30;

interface Day {
  day: string;
  used: number;
}

export class Gate extends DurableObject<Env> {
  /** Charges a new room against today's budget, or refuses it when the budget is spent. */
  async reserve(units: number, budget: number): Promise<boolean> {
    const today = await this.today();
    if (today.used + units > budget) return false;
    today.used += units;
    await this.ctx.storage.put("d", today);
    return true;
  }

  /** Adds what an open room used beyond its reserve. Never refuses. */
  async report(units: number): Promise<void> {
    if (!(units > 0)) return;
    const today = await this.today();
    today.used += units;
    await this.ctx.storage.put("d", today);
  }

  async used(): Promise<number> {
    return (await this.today()).used;
  }

  private async today(): Promise<Day> {
    const day = new Date().toISOString().slice(0, 10);
    const stored = await this.ctx.storage.get<Day>("d");
    return stored?.day === day ? stored : { day, used: 0 };
  }
}
