/**
 * Rock-solid In-Memory + IndexedDB Client Cache with Stale-While-Revalidate.
 * Avoids localStorage 5MB QuotaExceededError and prevents crashes.
 */

const DB_NAME = 'PlayBridge_Jellyfin_DB';
const DB_VERSION = 1;
const STORE_NAME = 'swr_cache';

const MEMORY_CACHE = new Map<string, { data: any; timestamp: number }>();
const DEFAULT_TTL_MS = 10 * 60 * 1000; // 10 minutes fresh
const DEFAULT_MAX_AGE_MS = 48 * 60 * 60 * 1000; // 48 hours stale

let dbPromise: Promise<IDBDatabase | null> | null = null;

function getIDB(): Promise<IDBDatabase | null> {
  if (typeof window === 'undefined' || !window.indexedDB) {
    return Promise.resolve(null);
  }
  if (!dbPromise) {
    dbPromise = new Promise((resolve) => {
      try {
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onupgradeneeded = (e: any) => {
          const db = e.target.result as IDBDatabase;
          if (!db.objectStoreNames.contains(STORE_NAME)) {
            db.createObjectStore(STORE_NAME, { keyPath: 'key' });
          }
        };
        req.onsuccess = (e: any) => {
          resolve(e.target.result as IDBDatabase);
        };
        req.onerror = () => {
          resolve(null);
        };
      } catch {
        resolve(null);
      }
    });
  }
  return dbPromise;
}

// In-Memory synchronous fast path
export function getCachedData<T>(key: string, maxAgeMs = DEFAULT_MAX_AGE_MS): T | null {
  const mem = MEMORY_CACHE.get(key);
  const now = Date.now();
  if (mem && (now - mem.timestamp) < maxAgeMs) {
    return mem.data as T;
  }
  return null;
}

export function setCachedData<T>(key: string, data: T) {
  const entry = { data, timestamp: Date.now() };
  MEMORY_CACHE.set(key, entry);

  // Persist to IndexedDB asynchronously (no blocking, no 5MB quota limit)
  getIDB().then((db) => {
    if (!db) return;
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      store.put({ key, ...entry });
    } catch {}
  }).catch(() => {});
}

// Hydrate memory cache from IndexedDB on startup
export async function hydrateCacheFromStorage(): Promise<void> {
  const db = await getIDB();
  if (!db) return;
  return new Promise((resolve) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.getAll();
      req.onsuccess = () => {
        const items = req.result || [];
        const now = Date.now();
        for (const item of items) {
          if (item && item.key && (now - item.timestamp) < DEFAULT_MAX_AGE_MS) {
            MEMORY_CACHE.set(item.key, { data: item.data, timestamp: item.timestamp });
          }
        }
        resolve();
      };
      req.onerror = () => resolve();
    } catch {
      resolve();
    }
  });
}

export function isCacheStale(key: string, freshTtlMs = DEFAULT_TTL_MS): boolean {
  const mem = MEMORY_CACHE.get(key);
  if (mem) {
    return (Date.now() - mem.timestamp) > freshTtlMs;
  }
  return true;
}

/**
 * Fetch with Stale-While-Revalidate:
 * 1. If cached, invokes onData(cached) IMMEDIATELY for zero-lag UI.
 * 2. If stale or uncached, fetches fresh data in background, updates cache, and invokes onData(fresh).
 */
export async function fetchWithSWR<T>(
  key: string,
  fetcher: () => Promise<T>,
  onData?: (data: T) => void,
  freshTtlMs = DEFAULT_TTL_MS
): Promise<T> {
  const cached = getCachedData<T>(key);
  let returnedCached = false;

  if (cached !== null) {
    if (onData) onData(cached);
    returnedCached = true;

    // If cache is fresh, return directly without network hit
    if (!isCacheStale(key, freshTtlMs)) {
      return cached;
    }
  }

  // Background or initial fetch
  try {
    const fresh = await fetcher();
    setCachedData(key, fresh);
    if (onData) onData(fresh);
    return fresh;
  } catch (err) {
    if (returnedCached && cached !== null) {
      return cached;
    }
    throw err;
  }
}

export function clearAllCache() {
  MEMORY_CACHE.clear();
  getIDB().then((db) => {
    if (!db) return;
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).clear();
    } catch {}
  }).catch(() => {});
}
