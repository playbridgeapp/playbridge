/**
 * Validates data supplied by a page to window.playbridge.cast().  Page content
 * is untrusted, so only the small, JSON-shaped cast contract crosses into the
 * native messaging boundary.
 */

export type PageCastItem = {
  url: string;
  title?: string;
  contentType?: string;
  headers?: Record<string, string>;
  subtitles?: string[];
  subtitleResources?: SubtitleResource[];
  metadata?: Record<string, unknown>;
};

export type SubtitleResource = {
  url: string;
  headers?: Record<string, string>;
  label?: string;
  language?: string;
};

export type LinkedPageCastItem = PageCastItem & {
  id: string;
};

export type LinkedPageCastRequest = {
  items: LinkedPageCastItem[];
  startIndex: number;
  metadata?: Record<string, unknown>;
  skipPreplay?: boolean;
  privateNetworkOrigins?: string[];
};

export type LinkedPageCastSupply = {
  requestId: string;
  items: LinkedPageCastItem[];
  endOfList: boolean;
  privateNetworkOrigins?: string[];
};

export type PageCastRequest = {
  items: PageCastItem[];
  startIndex: number;
  metadata?: Record<string, unknown>;
  skipPreplay?: boolean;
  privateNetworkOrigins?: string[];
};

const MAX_ITEMS = 50;
const MAX_SUBTITLES = 16;
const MAX_PRIVATE_ORIGINS = 16;
const MAX_HEADERS = 16;
const MAX_HEADER_BYTES = 16 * 1024;
const MAX_METADATA_BYTES = 16 * 1024;
const MAX_METADATA_DEPTH = 8;
const MAX_METADATA_KEYS = 64;
export const MAX_PAGE_CAST_REQUEST_BYTES = 64 * 1024;
const MAX_ITEM_ID_LENGTH = 128;
const ALLOWED_HEADERS = new Set([
  "authorization",
  "cookie",
  "referer",
  "origin",
  "user-agent",
  "accept",
  "accept-language",
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isHttpUrl(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 8_192) return false;
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") &&
      !url.username && !url.password;
  } catch {
    return false;
  }
}

function privateNetworkOrigins(value: unknown): string[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > MAX_PRIVATE_ORIGINS) return undefined;
  const normalized = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== "string") return undefined;
    try {
      const url = new URL(entry);
      if ((url.protocol !== "http:" && url.protocol !== "https:") ||
          url.username || url.password ||
          (url.pathname !== "/" && url.pathname !== "") || url.search || url.hash) return undefined;
      const hostname = url.hostname.toLowerCase();
      if (hostname.includes("*") || hostname === "localhost" || hostname.endsWith(".localhost")) return undefined;
      normalized.add(url.origin.toLowerCase());
    } catch {
      return undefined;
    }
  }
  return [...normalized];
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.length <= 4_096 ? value : undefined;
}

function shortString(value: unknown, maxLength: number): string | undefined {
  return typeof value === "string" && value.length > 0 && value.length <= maxLength
    ? value
    : undefined;
}

function replayHeaders(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined;
  const entries: Array<[string, string]> = [];
  const names = new Set<string>();
  let bytes = 0;
  for (const [rawName, rawValue] of Object.entries(value)) {
    const lowerName = rawName.toLowerCase();
    if (
      rawName !== rawName.trim() ||
      !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(rawName) ||
      !ALLOWED_HEADERS.has(lowerName) ||
      names.has(lowerName) ||
      typeof rawValue !== "string" ||
      /[\u0000-\u001f\u007f]/.test(rawValue) ||
      ((lowerName === "origin" || lowerName === "referer") && !isHttpUrl(rawValue))
    ) {
      return undefined;
    }
    names.add(lowerName);
    bytes += utf8Bytes(rawName) + utf8Bytes(rawValue);
    if (entries.length >= MAX_HEADERS || bytes > MAX_HEADER_BYTES) return undefined;
    entries.push([rawName, rawValue]);
  }
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

function subtitleResources(value: unknown): SubtitleResource[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > MAX_SUBTITLES) return undefined;
  const resources: SubtitleResource[] = [];
  for (const entry of value) {
    if (!isRecord(entry) || !isHttpUrl(entry.url)) return undefined;
    const headers = replayHeaders(entry.headers);
    if (
      entry.headers !== undefined &&
      (!isRecord(entry.headers) || (!headers && Object.keys(entry.headers).length > 0))
    ) return undefined;
    const label = shortString(entry.label, 256);
    const language = shortString(entry.language, 64);
    if (entry.label !== undefined && !label) return undefined;
    if (entry.language !== undefined && !language) return undefined;
    resources.push({
      url: entry.url,
      ...(headers ? { headers } : {}),
      ...(label ? { label } : {}),
      ...(language ? { language } : {}),
    });
  }
  return resources;
}

function subtitleUrls(value: unknown): string[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > MAX_SUBTITLES) return undefined;
  const subtitles = value.map((entry) => isHttpUrl(entry) ? entry : undefined);
  if (subtitles.some((entry) => entry === undefined)) return undefined;
  return subtitles as string[];
}

function metadata(value: unknown): Record<string, unknown> | undefined {
  // Android's VisualMetadata decoder is the schema authority. Keep only plain
  // JSON objects here so pages cannot pass executable or prototype-backed data.
  if (!isRecord(value)) return undefined;
  try {
    if (!validMetadataShape(value, 0, { keys: 0 })) return undefined;
    const serialized = JSON.stringify(value);
    if (utf8Bytes(serialized) > MAX_METADATA_BYTES) return undefined;
    return JSON.parse(serialized) as Record<string, unknown>;
  } catch {
    return undefined;
  }
}

function utf8Bytes(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function validMetadataShape(value: unknown, depth: number, count: { keys: number }): boolean {
  if (depth > MAX_METADATA_DEPTH) return false;
  if (value === null || typeof value === "boolean" || typeof value === "number") return true;
  if (typeof value === "string") return value.length <= 4_096;
  if (Array.isArray(value)) {
    if (value.length > MAX_METADATA_KEYS) return false;
    return value.every((entry) => validMetadataShape(entry, depth + 1, count));
  }
  if (!isRecord(value)) return false;
  for (const [key, entry] of Object.entries(value)) {
    count.keys += 1;
    if (count.keys > MAX_METADATA_KEYS || key.length > 256) return false;
    if (!validMetadataShape(entry, depth + 1, count)) return false;
  }
  return true;
}

function commonItem(value: Record<string, unknown>): PageCastItem | undefined {
  if (!isHttpUrl(value.url)) return undefined;
  const headers = replayHeaders(value.headers);
  if (
    value.headers !== undefined &&
    (!isRecord(value.headers) || (!headers && Object.keys(value.headers).length > 0))
  ) return undefined;
  const subtitles = subtitleUrls(value.subtitles);
  if (value.subtitles !== undefined && !subtitles) return undefined;
  const credentialedSubtitles = subtitleResources(value.subtitleResources);
  if (value.subtitleResources !== undefined && !credentialedSubtitles) return undefined;
  if ((subtitles?.length ?? 0) + (credentialedSubtitles?.length ?? 0) > MAX_SUBTITLES) {
    return undefined;
  }
  const visualMetadata = value.metadata === undefined ? undefined : metadata(value.metadata);
  if (value.metadata !== undefined && !visualMetadata) return undefined;
  const title = optionalString(value.title);
  const contentType = shortString(value.contentType, 256);
  return {
    url: value.url,
    ...(title ? { title } : {}),
    ...(contentType ? { contentType } : {}),
    ...(headers ? { headers } : {}),
    ...(subtitles ? { subtitles } : {}),
    ...(credentialedSubtitles ? { subtitleResources: credentialedSubtitles } : {}),
    ...(visualMetadata ? { metadata: visualMetadata } : {}),
  };
}

function item(value: unknown): PageCastItem | undefined {
  return isRecord(value) ? commonItem(value) : undefined;
}

/** Normalizes the documented object, playlist, and bare-array page APIs. */
export function normalizePageCastPayload(payload: unknown): PageCastRequest | undefined {
  const source = Array.isArray(payload) ? { items: payload } : payload;
  if (!isRecord(source)) return undefined;

  const values = Array.isArray(source.items) ? source.items : [source];
  if (values.length === 0 || values.length > MAX_ITEMS) return undefined;
  const items = values.map(item);
  if (items.some((entry) => entry === undefined)) return undefined;
  const typedItems = items as PageCastItem[];

  const requestedIndex =
    typeof source.startIndex === "number" && Number.isInteger(source.startIndex)
      ? source.startIndex
      : 0;
  const visualMetadata = source.metadata === undefined ? undefined : metadata(source.metadata);
  if (source.metadata !== undefined && !visualMetadata) return undefined;
  if (source.skipPreplay !== undefined && typeof source.skipPreplay !== "boolean") return undefined;
  const privateOrigins = privateNetworkOrigins(source.privateNetworkOrigins);
  if (source.privateNetworkOrigins !== undefined && !privateOrigins) return undefined;
  return {
    items: typedItems,
    startIndex: Math.max(0, Math.min(requestedIndex, typedItems.length - 1)),
    ...(visualMetadata ? { metadata: visualMetadata } : {}),
    ...(source.skipPreplay !== undefined ? { skipPreplay: source.skipPreplay } : {}),
    ...(privateOrigins?.length ? { privateNetworkOrigins: privateOrigins } : {}),
  };
}

function linkedItem(value: unknown): LinkedPageCastItem | undefined {
  if (!isRecord(value)) return undefined;
  const id = shortString(value.id, MAX_ITEM_ID_LENGTH);
  const common = commonItem(value);
  if (!id || !common) return undefined;
  return {
    ...common,
    id,
  };
}

function linkedItems(value: unknown, allowEmpty = false): LinkedPageCastItem[] | undefined {
  if (!Array.isArray(value) || value.length > MAX_ITEMS || (!allowEmpty && value.length === 0)) return undefined;
  const items = value.map(linkedItem);
  if (items.some((entry) => entry === undefined)) return undefined;
  const typed = items as LinkedPageCastItem[];
  if (new Set(typed.map((entry) => entry.id)).size !== typed.length) return undefined;
  return typed;
}

export function normalizeLinkedPageCastPayload(payload: unknown): LinkedPageCastRequest | undefined {
  if (!isRecord(payload)) return undefined;
  const items = linkedItems(payload.items);
  if (!items) return undefined;
  const requestedIndex = typeof payload.startIndex === "number" && Number.isInteger(payload.startIndex)
    ? payload.startIndex
    : 0;
  if (requestedIndex < 0 || requestedIndex >= items.length) return undefined;
  const visualMetadata = payload.metadata === undefined ? undefined : metadata(payload.metadata);
  if (payload.metadata !== undefined && !visualMetadata) return undefined;
  if (payload.skipPreplay !== undefined && typeof payload.skipPreplay !== "boolean") return undefined;
  const privateOrigins = privateNetworkOrigins(payload.privateNetworkOrigins);
  if (payload.privateNetworkOrigins !== undefined && !privateOrigins) return undefined;
  return {
    items,
    startIndex: requestedIndex,
    ...(visualMetadata ? { metadata: visualMetadata } : {}),
    ...(payload.skipPreplay !== undefined ? { skipPreplay: payload.skipPreplay } : {}),
    ...(privateOrigins?.length ? { privateNetworkOrigins: privateOrigins } : {}),
  };
}

export function normalizeLinkedAppendPayload(payload: unknown): { items: LinkedPageCastItem[]; privateNetworkOrigins?: string[] } | undefined {
  if (!isRecord(payload)) return undefined;
  const items = linkedItems(payload.items);
  const privateOrigins = privateNetworkOrigins(payload.privateNetworkOrigins);
  if (!items || (payload.privateNetworkOrigins !== undefined && !privateOrigins)) return undefined;
  return {
    items,
    ...(privateOrigins?.length ? { privateNetworkOrigins: privateOrigins } : {}),
  };
}

export function normalizeLinkedJumpPayload(payload: unknown): { index: number } | undefined {
  if (!isRecord(payload) || typeof payload.index !== "number" || !Number.isInteger(payload.index) || payload.index < 0) {
    return undefined;
  }
  return { index: payload.index };
}

export function normalizeLinkedSupplyPayload(payload: unknown): LinkedPageCastSupply | undefined {
  if (!isRecord(payload)) return undefined;
  const requestId = shortString(payload.requestId, 128);
  const endOfList = payload.endOfList === true;
  const items = linkedItems(payload.items, endOfList);
  const privateOrigins = privateNetworkOrigins(payload.privateNetworkOrigins);
  if (!requestId || !items || (items.length === 0 && !endOfList) ||
      (payload.privateNetworkOrigins !== undefined && !privateOrigins)) return undefined;
  return {
    requestId,
    items,
    endOfList,
    ...(privateOrigins?.length ? { privateNetworkOrigins: privateOrigins } : {}),
  };
}

export function pageCastRequestWithinLimit(value: unknown): boolean {
  try {
    return utf8Bytes(JSON.stringify(value)) <= MAX_PAGE_CAST_REQUEST_BYTES;
  } catch {
    return false;
  }
}
