import type { BridgeDevice, DeviceSelection, BridgeState } from "../native-bridge";

export const PAGE_DESTINATION_SECRET_KEY = "pageDestinationSecret:v1";
export const THIS_DEVICE_DESTINATION_ID = "this-device";

export interface PlaybackDestination {
  id: string | null;
  name: string | null;
  kind: "local" | "native" | "external";
  connected: boolean;
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  const b64 = globalThis.btoa(binary);
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64UrlDecode(str: string): Uint8Array | null {
  try {
    let b64 = str.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    const binary = globalThis.atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch {
    return null;
  }
}

export async function getOrCreateInstallSecret(storage: {
  get: (key: string) => Promise<Record<string, unknown>>;
  set: (items: Record<string, unknown>) => Promise<void>;
}): Promise<Uint8Array> {
  const result = await storage.get(PAGE_DESTINATION_SECRET_KEY);
  const stored = result[PAGE_DESTINATION_SECRET_KEY];
  if (typeof stored === "string") {
    const bytes = base64UrlDecode(stored);
    if (bytes && bytes.length === 32) return bytes;
  }
  const secret = new Uint8Array(32);
  globalThis.crypto.getRandomValues(secret);
  await storage.set({ [PAGE_DESTINATION_SECRET_KEY]: base64UrlEncode(secret) });
  return secret;
}

export async function computeDestinationId(
  secret: Uint8Array,
  origin: string,
  endpointKey: string
): Promise<string> {
  const cryptoKey = await globalThis.crypto.subtle.importKey(
    "raw",
    secret as unknown as BufferSource,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const data = new TextEncoder().encode(`${origin}\0${endpointKey}`);
  const signature = await globalThis.crypto.subtle.sign("HMAC", cryptoKey, data);
  return base64UrlEncode(new Uint8Array(signature));
}

export function determineDestinationKind(
  selection: DeviceSelection | undefined,
  devices: BridgeDevice[] = []
): "local" | "native" | "external" {
  if (!selection || selection.kind === "local") {
    return "local";
  }
  if (selection.id.startsWith("playBridge:")) {
    return "native";
  }
  const match = devices.find((d) => d.id === selection.id || (d.protocol && d.uuid && `${d.protocol}:${d.uuid}` === selection.id));
  if (match?.protocol === "playBridge") {
    return "native";
  }
  return "external";
}

export async function formatPlaybackDestination(
  state: BridgeState,
  origin: string,
  consented: boolean,
  secret: Uint8Array
): Promise<PlaybackDestination> {
  const selection = state.selection;
  const isLocal = !selection || selection.kind === "local";
  const kind = determineDestinationKind(selection, state.devices);
  const connected = isLocal ? state.desktopConnected : selection.status === "connected";

  if (!consented) {
    return {
      id: null,
      name: null,
      kind,
      connected,
    };
  }

  if (isLocal) {
    return {
      id: THIS_DEVICE_DESTINATION_ID,
      name: "This computer",
      kind: "local",
      connected,
    };
  }

  const endpointKey = selection.id;
  const id = await computeDestinationId(secret, origin, endpointKey);
  return {
    id,
    name: selection.name,
    kind,
    connected,
  };
}

export async function destinationIdMatches(
  destinationId: string,
  origin: string,
  selection: DeviceSelection | undefined,
  secret: Uint8Array
): Promise<boolean> {
  if (!selection || selection.kind === "local") {
    return destinationId === THIS_DEVICE_DESTINATION_ID;
  }
  if (destinationId === THIS_DEVICE_DESTINATION_ID) {
    return false;
  }
  const expected = await computeDestinationId(secret, origin, selection.id);
  return destinationId === expected;
}
