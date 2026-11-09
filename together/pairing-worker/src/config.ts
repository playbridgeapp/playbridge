import type { Limits } from "@playbridge-together/shared";
import type { Lobby } from "./lobby";

export interface Env {
  LOBBIES: DurableObjectNamespace<Lobby>;
  LIMITS: DurableObjectNamespace<Limits>;
}
