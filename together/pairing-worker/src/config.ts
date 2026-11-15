import type { Lobby } from "./lobby";

export interface Env {
  LOBBIES: DurableObjectNamespace<Lobby>;
  /** Per-IP limits. Each is optional: a missing or failing limiter lets the call through. */
  REGISTER_LIMIT?: RateLimit;
  LOOKUP_LIMIT?: RateLimit;
  INVITE_LIMIT?: RateLimit;
}
