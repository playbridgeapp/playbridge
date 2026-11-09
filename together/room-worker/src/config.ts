import type { Limits } from "@playbridge-together/shared";
import type { Room } from "./room";

export interface Env {
  ROOMS: DurableObjectNamespace<Room>;
  LIMITS: DurableObjectNamespace<Limits>;
  /** "true" on PlayBridge's shared relay; only changes copy and defaults. */
  SHARED_TIER: string;
  MAX_MEMBERS: string;
  ROOM_LIFETIME_HOURS: string;
  ROOMS_PER_IP_PER_DAY: string;
  IDLE_CLOSE_MINUTES: string;
}

export interface RoomLimits {
  maxMembers: number;
  lifetimeMs: number;
  roomsPerIpPerDay: number;
  idleCloseMs: number;
  shared: boolean;
}

export function roomLimits(env: Env): RoomLimits {
  const num = (raw: string, fallback: number, min: number, max: number) => {
    const value = Number(raw);
    return Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback;
  };
  return {
    maxMembers: num(env.MAX_MEMBERS, 8, 2, 32),
    lifetimeMs: num(env.ROOM_LIFETIME_HOURS, 8, 1, 48) * 3_600_000,
    roomsPerIpPerDay: num(env.ROOMS_PER_IP_PER_DAY, 50, 1, 10_000),
    idleCloseMs: num(env.IDLE_CLOSE_MINUTES, 30, 1, 24 * 60) * 60_000,
    shared: env.SHARED_TIER === "true",
  };
}
