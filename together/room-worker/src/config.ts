import type { Limits } from "@playbridge-together/shared";
import type { Gate } from "./gate";
import type { Room } from "./room";

export interface Env {
  ROOMS: DurableObjectNamespace<Room>;
  LIMITS: DurableObjectNamespace<Limits>;
  GATE: DurableObjectNamespace<Gate>;
  /** Connects per room per minute. Optional: missing means no limit. */
  CONNECTS_PER_ROOM?: RateLimit;
  /** Connects per IP per minute. Optional: missing means no limit. */
  CONNECTS_PER_IP?: RateLimit;
  /** "true" on PlayBridge's shared relay; only changes copy and defaults. */
  SHARED_TIER: string;
  MAX_MEMBERS: string;
  ROOM_LIFETIME_HOURS: string;
  /** Rooms one network may have open at once. */
  OPEN_ROOMS_PER_IP: string;
  /** New rooms one network may start per hour. */
  ROOMS_PER_IP_PER_HOUR: string;
  /** Durable Object requests the relay may use per UTC day before refusing new rooms. 0: no cap. */
  DAILY_BUDGET: string;
  IDLE_CLOSE_MINUTES: string;
  /** Android package whose invite links this relay's domain verifies (App Links). Unset: none. */
  ANDROID_APP_PACKAGE?: string;
  /** Comma-separated SHA-256 signing certificate fingerprints for that package. */
  ANDROID_CERT_SHA256?: string;
}

export interface RoomLimits {
  maxMembers: number;
  lifetimeMs: number;
  openRoomsPerIp: number;
  roomsPerIpPerHour: number;
  dailyBudget: number;
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
    openRoomsPerIp: num(env.OPEN_ROOMS_PER_IP, 20, 1, 10_000),
    roomsPerIpPerHour: num(env.ROOMS_PER_IP_PER_HOUR, 100, 1, 100_000),
    dailyBudget: num(env.DAILY_BUDGET, 0, 0, 100_000_000),
    idleCloseMs: num(env.IDLE_CLOSE_MINUTES, 30, 1, 24 * 60) * 60_000,
    shared: env.SHARED_TIER === "true",
  };
}

/**
 * The Digital Asset Links statement that lets the PlayBridge app open this relay's invite
 * links directly, or null when the relay isn't set up for it (self-hosted relays live on
 * domains the app can't list, so their links go through the invite page instead).
 */
export function assetLinks(env: Env): unknown[] | null {
  const pkg = env.ANDROID_APP_PACKAGE?.trim();
  const certs = (env.ANDROID_CERT_SHA256 ?? "")
    .split(",")
    .map((cert) => cert.trim().toUpperCase())
    .filter((cert) => /^[0-9A-F]{2}(:[0-9A-F]{2}){31}$/.test(cert));
  if (!pkg || certs.length === 0) return null;
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: pkg, sha256_cert_fingerprints: certs },
    },
  ];
}
