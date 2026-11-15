import { DurableObject } from "cloudflare:workers";

// One Limits object per client IP: the rooms that network has open, and how many it opened
// in the last hour. A room holds a slot from creation until it empties, closes or expires;
// an entry past its expiry no longer counts, so a slot that is never handed back frees
// itself when its room would have expired anyway.

const HOUR_MS = 3_600_000;

export type SlotResult = { ok: true } | { ok: false; retryAfterSecs: number };

interface State {
  /** roomId → when that room expires. */
  open: Record<string, number>;
  hourStart: number;
  hourCount: number;
}

export interface LimitsRpc {
  openRoom(room: string, expiresAt: number, maxOpen: number, maxPerHour: number, isNew: boolean): Promise<SlotResult>;
  closeRoom(room: string): Promise<void>;
}

export class Limits extends DurableObject implements LimitsRpc {
  /**
   * Takes a slot for `room`. `isNew` rooms also count against the hourly budget; a room
   * coming back to life after it emptied only needs a slot.
   */
  async openRoom(room: string, expiresAt: number, maxOpen: number, maxPerHour: number, isNew: boolean): Promise<SlotResult> {
    const now = Date.now();
    const state = await this.load(now);
    if (state.open[room] !== undefined) return { ok: true };

    const expiries = Object.values(state.open);
    if (expiries.length >= maxOpen) {
      return { ok: false, retryAfterSecs: (Math.min(...expiries) - now) / 1000 };
    }
    if (isNew) {
      if (state.hourCount >= maxPerHour) {
        return { ok: false, retryAfterSecs: (state.hourStart + HOUR_MS - now) / 1000 };
      }
      state.hourCount++;
    }
    state.open[room] = expiresAt;
    await this.save(state, now);
    return { ok: true };
  }

  async closeRoom(room: string): Promise<void> {
    const now = Date.now();
    const state = await this.load(now);
    if (state.open[room] === undefined) return;
    delete state.open[room];
    await this.save(state, now);
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    const state = await this.load(now);
    if (Object.keys(state.open).length === 0 && now - state.hourStart >= HOUR_MS) {
      await this.ctx.storage.deleteAll();
    } else {
      await this.ctx.storage.setAlarm(this.cleanupAt(state));
    }
  }

  private async load(now: number): Promise<State> {
    const state = (await this.ctx.storage.get<State>("s")) ?? { open: {}, hourStart: now, hourCount: 0 };
    for (const [room, expiresAt] of Object.entries(state.open)) {
      if (expiresAt <= now) delete state.open[room];
    }
    if (now - state.hourStart >= HOUR_MS) {
      state.hourStart = now;
      state.hourCount = 0;
    }
    return state;
  }

  private async save(state: State, now: number): Promise<void> {
    await this.ctx.storage.put("s", state);
    // Clear the object once nothing in it matters any more.
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm < now) await this.ctx.storage.setAlarm(this.cleanupAt(state));
  }

  private cleanupAt(state: State): number {
    return Math.max(state.hourStart + HOUR_MS, ...Object.values(state.open));
  }
}

export function limitsFor(ns: DurableObjectNamespace<Limits>, ip: string) {
  return ns.get(ns.idFromName(ip));
}
