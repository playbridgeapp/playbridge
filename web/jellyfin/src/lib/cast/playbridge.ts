import { writable } from 'svelte/store';
import type {
  JellyfinItem,
  PlayBridgeCastPayload,
  PlayBridgeItem,
  PlayBridgeLinkSession,
  VisualMetadata
} from '../types';

export interface DiagnosticLog {
  id: string;
  time: string;
  type: 'info' | 'success' | 'warn' | 'error' | 'cast' | 'feedback';
  message: string;
  data?: any;
}

export const bridgeStatus = writable<{
  available: boolean;
  linkedCast: boolean;
  checked: boolean;
}>({
  available: false,
  linkedCast: false,
  checked: false
});

export const diagnosticLogs = writable<DiagnosticLog[]>([]);
export const activeLinkedSession = writable<PlayBridgeLinkSession | null>(null);
export const activeCastPayload = writable<any | null>(null);

export function addDiagnosticLog(type: DiagnosticLog['type'], message: string, data?: any) {
  const log: DiagnosticLog = {
    id: Math.random().toString(36).substring(2, 9),
    time: new Date().toTimeString().slice(0, 8),
    type,
    message,
    data
  };
  diagnosticLogs.update((logs) => [log, ...logs.slice(0, 100)]);
}

export function initPlayBridgeDetector() {
  let attempts = 0;
  const maxAttempts = 20;

  function check() {
    if (typeof window !== 'undefined' && window.playbridge && typeof window.playbridge.cast === 'function') {
      const hasLinked = Boolean(window.playbridge.capabilities?.linkedCast);
      bridgeStatus.set({
        available: true,
        linkedCast: hasLinked,
        checked: true
      });
      addDiagnosticLog('success', 'PlayBridge bridge detected in window.playbridge', {
        capabilities: window.playbridge.capabilities
      });
    } else if (++attempts < maxAttempts) {
      setTimeout(check, 250);
    } else {
      bridgeStatus.set({
        available: false,
        linkedCast: false,
        checked: true
      });
      addDiagnosticLog('warn', 'window.playbridge not detected after 5s. Running in fallback mode.');
    }
  }

  if (typeof window !== 'undefined') {
    check();

    // Listen for feedback events from the native app / extension bridge
    window.addEventListener('PlayBridgeFeedback', (e: any) => {
      const detail = e?.detail;
      addDiagnosticLog('feedback', 'PlayBridgeFeedback received', detail);
    });
  }
}

/**
 * Formats metadata strictly according to PlayBridge VisualMetadata Wire/Moshi contract.
 * Only includes fields declared in messages.proto:
 * title, year, rating, runtime, overview, genres, cast, director, backdropUrl, posterUrl, logoUrl, season, episode, episodeTitle
 */
export function formatVisualMetadata(item: JellyfinItem, posterUrl?: string, backdropUrl?: string): Record<string, any> {
  const meta: Record<string, any> = {
    title: item.Name
  };

  if (item.ProductionYear) {
    meta.year = String(item.ProductionYear);
  }
  if (item.Overview) {
    meta.overview = item.Overview.slice(0, 2000);
  }
  if (item.Genres && item.Genres.length > 0) {
    meta.genres = item.Genres.slice(0, 8);
  }
  const resolvedPoster = posterUrl || item.posterUrl;
  if (resolvedPoster) {
    meta.posterUrl = resolvedPoster;
  }
  const resolvedBackdrop = backdropUrl || item.backdropUrl;
  if (resolvedBackdrop) {
    meta.backdropUrl = resolvedBackdrop;
  }

  if (item.Type === 'Episode') {
    meta.title = item.SeriesName || item.Name;
    if (item.ParentIndexNumber != null) meta.season = item.ParentIndexNumber;
    if (item.IndexNumber != null) meta.episode = item.IndexNumber;
    meta.episodeTitle = item.Name;
  } else if (item.Type === 'Audio') {
    const artist = item.AlbumArtist || (item.Artists && item.Artists.length > 0 ? item.Artists[0] : '');
    meta.title = artist ? `${item.Name} — ${artist}` : item.Name;
    if (item.Album) {
      meta.overview = `Album: ${item.Album}${item.Overview ? '\n' + item.Overview : ''}`;
    }
    if (item.RunTimeTicks) {
      meta.runtime = String(Math.round(item.RunTimeTicks / (10000 * 1000)));
    }
  }

  return meta;
}

export function isLocalNetworkUrl(url: string): boolean {
  return /^https?:\/\/(?:10\.|192\.168\.|172\.(?:1[6-9]|2[0-9]|3[01])\.|127\.|localhost)/i.test(url);
}

/**
 * Builds standard single cast payload.
 */
export function buildSingleCastPayload(item: JellyfinItem, streamUrl: string, posterUrl?: string, backdropUrl?: string) {
  let title = item.Name;
  if (item.Type === 'Episode' && item.IndexNumber != null) {
    const s = item.ParentIndexNumber ?? 1;
    title = `S${s}E${item.IndexNumber} · ${item.Name}`;
  } else if (item.Type === 'Audio' && item.Artists && item.Artists.length > 0) {
    title = `${item.Name} — ${item.Artists[0]}`;
  }

  const isLocal = isLocalNetworkUrl(streamUrl);
  const isHls = streamUrl.includes('.m3u8');
  const contentType = item.Type === 'Audio' ? 'audio/mpeg' : isHls ? 'application/vnd.apple.mpegurl' : 'video/mp4';

  return {
    url: streamUrl,
    title,
    contentType,
    localNetwork: isLocal,
    metadata: formatVisualMetadata(item, posterUrl, backdropUrl)
  };
}

/**
 * Builds playlist cast payload for casting all tracks in an album, folder, or playlist.
 * Max 50 items per payload as enforced by page-cast.ts.
 */
export function buildPlaylistCastPayload(
  items: Array<{ item: JellyfinItem; streamUrl: string; posterUrl?: string; backdropUrl?: string }>,
  startIndex = 0,
  playlistTitle = 'Playlist',
  playlistPosterUrl?: string
) {
  // Cap at 50 items for direct cast validation
  const sliced = items.slice(0, 50);

  const castItems = sliced.map(({ item, streamUrl, posterUrl, backdropUrl }) => {
    let title = item.Name;
    if (item.Type === 'Episode' && item.IndexNumber != null) {
      const s = item.ParentIndexNumber ?? 1;
      title = `S${s}E${item.IndexNumber} · ${item.Name}`;
    } else if (item.Type === 'Audio' && item.Artists && item.Artists.length > 0) {
      title = `${item.Name} — ${item.Artists[0]}`;
    }
    const isHls = streamUrl.includes('.m3u8');
    const contentType = item.Type === 'Audio' ? 'audio/mpeg' : isHls ? 'application/vnd.apple.mpegurl' : 'video/mp4';
    return {
      url: streamUrl,
      title,
      contentType,
      metadata: formatVisualMetadata(item, posterUrl, backdropUrl)
    };
  });

  const hasLocal = items.some(({ streamUrl }) => isLocalNetworkUrl(streamUrl));

  return {
    startIndex: Math.max(0, Math.min(startIndex, castItems.length - 1)),
    localNetwork: hasLocal,
    metadata: {
      title: playlistTitle,
      posterUrl: playlistPosterUrl || items[0]?.posterUrl
    },
    items: castItems
  };
}

export function castDirect(payload: any): boolean {
  activeCastPayload.set(payload);
  const itemCount = Array.isArray(payload?.items) ? payload.items.length : 1;
  addDiagnosticLog('cast', `window.playbridge.cast() invoked (${itemCount} item(s)): ${payload?.title || payload?.metadata?.title || 'Playlist'}`, payload);

  if (typeof window !== 'undefined' && window.playbridge?.cast) {
    try {
      window.playbridge.cast(payload);
      addDiagnosticLog('success', `window.playbridge.cast() dispatched successfully (${itemCount} items)`);
      return true;
    } catch (err: any) {
      addDiagnosticLog('error', `window.playbridge.cast() threw exception: ${err.message}`, err);
      return false;
    }
  } else {
    addDiagnosticLog('warn', 'window.playbridge not found on this window. Payload logged for inspection.');
    return false;
  }
}

export async function castLinkedQueue(
  items: any[],
  startIndex = 0,
  metadata?: any
): Promise<PlayBridgeLinkSession | null> {
  addDiagnosticLog('cast', `Starting linked cast session with ${items.length} items (startIndex=${startIndex})`, { items, metadata });

  if (typeof window !== 'undefined' && window.playbridge?.linkCast) {
    try {
      const initialItems = items.slice(0, 2);
      let remainingItems = items.slice(2);

      const session = await window.playbridge.linkCast({
        items: initialItems,
        startIndex,
        metadata: metadata || items[startIndex]?.metadata,
        localNetwork: items.some((i) => isLocalNetworkUrl(i.url))
      });

      activeLinkedSession.set(session);
      addDiagnosticLog('success', `Linked cast session opened: ${session.sessionId}`, { sessionId: session.sessionId });

      session.addEventListener('statechange', (event: any) => {
        const state = event.detail || {};
        addDiagnosticLog('info', `Linked state: ${state.state} · item ${state.currentIndex}/${state.totalCount}`, state);
      });

      session.addEventListener('needitems', async (event: any) => {
        const need = event.detail || { count: 2, requestId: 'req-' + Date.now() };
        const batch = remainingItems.splice(0, need.count);
        addDiagnosticLog('info', `needitems(${need.count}) → supplying ${batch.length} items (remaining: ${remainingItems.length})`, {
          requestId: need.requestId,
          batch
        });

        try {
          await session.provideItems(need.requestId, {
            items: batch,
            endOfList: remainingItems.length === 0
          });
          addDiagnosticLog('success', `provideItems satisfied for ${need.requestId}`);
        } catch (err: any) {
          remainingItems.unshift(...batch);
          addDiagnosticLog('error', `provideItems failed: ${err.message}`, err);
        }
      });

      session.addEventListener('ended', (event: any) => {
        addDiagnosticLog('warn', `Linked session ended: ${event.detail?.reason || 'unknown'}`);
        activeLinkedSession.set(null);
      });

      return session;
    } catch (err: any) {
      addDiagnosticLog('error', `window.playbridge.linkCast failed: ${err.message}`, err);
      return null;
    }
  } else {
    addDiagnosticLog('warn', 'Linked cast not supported by current environment. Falling back to direct cast array.');
    castDirect(buildPlaylistCastPayload(items.map(i => ({ item: i, streamUrl: i.url, posterUrl: i.metadata?.posterUrl })), startIndex, metadata?.title, metadata?.posterUrl));
    return null;
  }
}
