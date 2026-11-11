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
  /** Android package whose invite links this relay's domain verifies (App Links). Unset: none. */
  ANDROID_APP_PACKAGE?: string;
  /** Comma-separated SHA-256 signing certificate fingerprints for that package. */
  ANDROID_CERT_SHA256?: string;
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
