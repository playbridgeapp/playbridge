import { writable, derived, get } from 'svelte/store';
import type { JellyfinItem, ServerConfig, JellyfinSeason, SavedAccount } from '../types';
import { DEMO_MOVIES, DEMO_SHOWS, DEMO_ALL_ITEMS } from '../data/demoData';
import * as jfApi from '../api/jellyfin';
import { getCachedData, hydrateCacheFromStorage, clearAllCache } from '../api/cache';
import {
  castDirect,
  castLinkedQueue,
  buildSingleCastPayload,
  buildPlaylistCastPayload,
  formatVisualMetadata,
  addDiagnosticLog
} from '../cast/playbridge';

const STORAGE_KEY = 'playbridge_jellyfin_session';
const ACCOUNTS_KEY = 'playbridge_jellyfin_saved_accounts';

const INITIAL_SERVER: ServerConfig = {
  url: '',
  token: '',
  userId: '',
  username: '',
  serverName: '',
  isDemo: false,
  connected: false
};

export const serverConfig = writable<ServerConfig>(INITIAL_SERVER);
export const savedAccounts = writable<SavedAccount[]>([]);
export const userViews = writable<jfApi.UserView[]>([]);
export const activeTab = writable<string>('home');
export const searchQuery = writable<string>('');
export const selectedGenre = writable<string>('all');
export const sortBy = writable<string>('DateCreated');

// Jellyfin Home Sections & Per-Library Stores (Matching Official Jellyfin Web)
export const nextUpMedia = writable<JellyfinItem[]>([]);
export const libraryLatestMap = writable<Record<string, JellyfinItem[]>>({});

// Modals and drawers
export const isServerModalOpen = writable<boolean>(false);
export const isDiagnosticsOpen = writable<boolean>(false);
export const detailModalItem = writable<JellyfinItem | null>(null);
export const isQueueDrawerOpen = writable<boolean>(false);
export const isLyricsOpen = writable<boolean>(false);
export const lyricsData = writable<{ Lyrics?: Array<{ Text: string; Start?: number }>; Text?: string } | null>(null);

// Music Player Modes (Inspired by Finamp)
export const isShuffle = writable<boolean>(false);
export const repeatMode = writable<'off' | 'all' | 'one'>('off'); // 'off' | 'all' | 'one'

// Toast notification
export const activeToast = writable<{ message: string; type: 'cast' | 'info' | 'success' } | null>(null);
let toastTimeout: any;

export function showToast(message: string, type: 'cast' | 'info' | 'success' = 'cast') {
  activeToast.set({ message, type });
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => {
    activeToast.set(null);
  }, 3500);
}

// Player state
export interface ActivePlayerState {
  isOpen: boolean; // active media session
  isExpanded: boolean; // true = full screen overlay, false = floating mini player
  item: JellyfinItem | null;
  streamUrl: string;
  isCasting: boolean;
  isLinkedCast: boolean;
  title: string;
  season?: number;
  episode?: number;
  playlist?: JellyfinItem[];
  currentIndex?: number;
}

export const activePlayer = writable<ActivePlayerState>({
  isOpen: false,
  isExpanded: false,
  item: null,
  streamUrl: '',
  isCasting: false,
  isLinkedCast: false,
  title: '',
  playlist: [],
  currentIndex: 0
});

// Library State
export const latestMedia = writable<JellyfinItem[]>([]);
export const resumeMedia = writable<JellyfinItem[]>([]);
export const moviesList = writable<JellyfinItem[]>([]);
export const showsList = writable<JellyfinItem[]>([]);
export const allLibraryItems = writable<JellyfinItem[]>([]);
export const isLoadingLibrary = writable<boolean>(false);
export const serverError = writable<string | null>(null);

// Utility: Fisher-Yates array shuffle
export function shuffleArray<T>(array: T[]): T[] {
  const arr = [...array];
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

export function getSavedAccountsFromStorage(): SavedAccount[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(ACCOUNTS_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function saveAccountToList(account: SavedAccount) {
  if (typeof window === 'undefined') return;
  const current = getSavedAccountsFromStorage();
  const existingIdx = current.findIndex(
    (a) => a.id === account.id || (a.url === account.url && a.userId === account.userId)
  );
  if (existingIdx >= 0) {
    current[existingIdx] = { ...current[existingIdx], ...account, lastActive: Date.now() };
  } else {
    current.push({ ...account, lastActive: Date.now() });
  }
  savedAccounts.set(current);
  localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(current));
}

export function removeSavedAccount(accountId: string) {
  if (typeof window === 'undefined') return;
  const current = getSavedAccountsFromStorage().filter((a) => a.id !== accountId);
  savedAccounts.set(current);
  localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(current));

  const active = get(serverConfig);
  if (active.url && `${active.url}_${active.userId}` === accountId) {
    if (current.length > 0) {
      switchAccount(current[0]);
    } else {
      logout();
    }
  }
  showToast('Account removed', 'info');
}

export async function switchAccount(account: SavedAccount) {
  isLoadingLibrary.set(true);
  serverError.set(null);

  // Always reset to home view on switched server
  activeTab.set('home');

  if (account.isDemo) {
    loadDemoMode();
    return;
  }

  const config: ServerConfig = {
    url: account.url,
    token: account.token,
    userId: account.userId,
    username: account.username,
    serverName: account.serverName,
    isDemo: false,
    connected: true
  };

  serverConfig.set(config);
  if (typeof window !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
  }

  // Update last active in list
  saveAccountToList(account);

  // Clear previous server's state first
  userViews.set([]);
  latestMedia.set([]);
  resumeMedia.set([]);
  moviesList.set([]);
  showsList.set([]);
  allLibraryItems.set([]);

  // Pre-fill from cache immediately (0ms instant switch)
  const cachedViews = getCachedData<jfApi.UserView[]>(`views_${config.userId}`);
  if (cachedViews) userViews.set(cachedViews);

  const cachedMovies = getCachedData<{ items: JellyfinItem[] }>(`lib_${config.userId}_root_Movie__`);
  if (cachedMovies) moviesList.set(cachedMovies.items);

  const cachedShows = getCachedData<{ items: JellyfinItem[] }>(`lib_${config.userId}_root_Series__`);
  if (cachedShows) showsList.set(cachedShows.items);

  if (cachedMovies && cachedShows) {
    allLibraryItems.set([...cachedMovies.items, ...cachedShows.items]);
  }

  showToast(`Switched to ${account.username} on ${account.serverName}`, 'success');
  addDiagnosticLog('success', `Switched active server to ${account.serverName} (${account.username})`, config);

  // Refresh in background
  await refreshServerLibrary(config);
}

// Initialize Session on Startup
export async function initializeSession() {
  if (typeof window === 'undefined') return;

  // Hydrate memory cache from IndexedDB
  await hydrateCacheFromStorage();

  // Load saved accounts
  const accounts = getSavedAccountsFromStorage();
  savedAccounts.set(accounts);

  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved) {
    try {
      const config: ServerConfig = JSON.parse(saved);
      if (config.isDemo) {
        loadDemoMode();
        return;
      }
      if (config.url && config.token && config.userId) {
        serverConfig.set(config);

        // Pre-fill from cache immediately
        const cachedViews = getCachedData<jfApi.UserView[]>(`views_${config.userId}`);
        if (cachedViews) userViews.set(cachedViews);

        const cachedMovies = getCachedData<{ items: JellyfinItem[] }>(`lib_${config.userId}_root_Movie__`);
        if (cachedMovies) moviesList.set(cachedMovies.items);

        const cachedShows = getCachedData<{ items: JellyfinItem[] }>(`lib_${config.userId}_root_Series__`);
        if (cachedShows) showsList.set(cachedShows.items);

        if (cachedMovies && cachedShows) {
          allLibraryItems.set([...cachedMovies.items, ...cachedShows.items]);
        }

        // Validate token silently (only logs out on explicit 401/403)
        const valid = await jfApi.validateToken(config.url, config.userId, config.token);
        if (valid) {
          addDiagnosticLog('success', `Restored active Jellyfin session: ${config.serverName} (${config.username})`, config);
          await refreshServerLibrary(config);
        } else {
          addDiagnosticLog('warn', 'Saved Jellyfin session expired (401 Unauthorized). Please sign in again.');
          logout();
        }
      }
    } catch (e) {
      console.error('Session init error:', e);
    } finally {
      isLoadingLibrary.set(false);
    }
  }
}

// Initialize demo mode
export function loadDemoMode() {
  const config: ServerConfig = {
    url: '',
    token: '',
    userId: 'demo-user-1',
    username: 'Demo User',
    serverName: 'PlayBridge Demo Showcase',
    isDemo: true,
    connected: true
  };
  serverConfig.set(config);
  userViews.set([
    { Id: 'view-movies', Name: 'Movies', CollectionType: 'movies' },
    { Id: 'view-shows', Name: 'TV Shows', CollectionType: 'tvshows' }
  ]);
  latestMedia.set(DEMO_MOVIES);
  resumeMedia.set(DEMO_MOVIES.filter((m) => m.UserData?.PlaybackPositionTicks));
  nextUpMedia.set([
    {
      Id: 'demo-ep-next',
      Name: 'The Alibi',
      Type: 'Episode',
      SeriesName: 'Chernobyl',
      SeasonName: 'Season 1',
      IndexNumber: 2,
      ParentIndexNumber: 1,
      RunTimeTicks: 36000000000,
      Overview: 'With millions of people at risk, Ulana Khomyuk investigates the causes of the explosion.',
      posterUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&auto=format&fit=crop&q=80',
      backdropUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1600&auto=format&fit=crop&q=80',
      UserData: { PlaybackPositionTicks: 0, Played: false }
    }
  ]);
  libraryLatestMap.set({
    'view-movies': DEMO_MOVIES,
    'view-shows': DEMO_SHOWS
  });
  moviesList.set(DEMO_MOVIES);
  showsList.set(DEMO_SHOWS);
  allLibraryItems.set(DEMO_ALL_ITEMS);
  serverError.set(null);
  if (typeof window !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
  }
  addDiagnosticLog('info', 'Loaded built-in Demo media library');
}

// Connect to live Jellyfin Server
export async function connectToJellyfinServer(serverUrl: string, username: string, password = '', rememberMe = true) {
  isLoadingLibrary.set(true);
  serverError.set(null);
  try {
    const cleanUrl = jfApi.cleanServerUrl(serverUrl);
    addDiagnosticLog('info', `Pinging Jellyfin server at ${cleanUrl}...`);
    const ping = await jfApi.pingServer(cleanUrl);

    addDiagnosticLog('info', `Authenticating user "${username}"...`);
    const auth = await jfApi.authenticateByName(cleanUrl, username, password);

    const config: ServerConfig = {
      url: cleanUrl,
      token: auth.token,
      userId: auth.userId,
      username: auth.username,
      serverName: ping.serverName,
      isDemo: false,
      connected: true
    };

    serverConfig.set(config);

    const account: SavedAccount = {
      id: `${cleanUrl}_${auth.userId}`,
      url: cleanUrl,
      token: auth.token,
      userId: auth.userId,
      username: auth.username,
      serverName: ping.serverName,
      lastActive: Date.now()
    };

    if (rememberMe && typeof window !== 'undefined') {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
      saveAccountToList(account);
    }
    addDiagnosticLog('success', `Connected & authenticated on Jellyfin: ${ping.serverName}`, config);

    // Fetch user library content with background cache warming
    await refreshServerLibrary(config);
    isServerModalOpen.set(false);
  } catch (err: any) {
    serverError.set(err.message || 'Failed to connect to Jellyfin server');
    addDiagnosticLog('error', `Connection error: ${err.message}`, err);
    throw err;
  } finally {
    isLoadingLibrary.set(false);
  }
}

export function logout() {
  serverConfig.set(INITIAL_SERVER);
  userViews.set([]);
  latestMedia.set([]);
  resumeMedia.set([]);
  nextUpMedia.set([]);
  libraryLatestMap.set({});
  moviesList.set([]);
  showsList.set([]);
  allLibraryItems.set([]);
  if (typeof window !== 'undefined') {
    localStorage.removeItem(STORAGE_KEY);
  }
  addDiagnosticLog('info', 'Logged out from Jellyfin server');
}

export async function refreshServerLibrary(config: ServerConfig) {
  if (config.isDemo) {
    loadDemoMode();
    return;
  }

  try {
    const [views, latest, resume, nextUp, moviesRes, showsRes] = await Promise.all([
      jfApi.getUserViews(config.url, config.userId, config.token, (fresh) => userViews.set(fresh)),
      jfApi.getLatestMedia(config.url, config.userId, config.token, undefined, (fresh) => latestMedia.set(fresh)),
      jfApi.getResumeItems(config.url, config.userId, config.token, (fresh) => resumeMedia.set(fresh)),
      jfApi.getNextUpEpisodes(config.url, config.userId, config.token, (fresh) => nextUpMedia.set(fresh)),
      jfApi.getLibraryItems(config.url, config.userId, config.token, { includeItemTypes: 'Movie' }, (fresh) => moviesList.set(fresh.items)),
      jfApi.getLibraryItems(config.url, config.userId, config.token, { includeItemTypes: 'Series' }, (fresh) => showsList.set(fresh.items))
    ]);

    userViews.set(views);
    latestMedia.set(latest.length > 0 ? latest : moviesRes.items);
    resumeMedia.set(resume);
    nextUpMedia.set(nextUp);
    moviesList.set(moviesRes.items);
    showsList.set(showsRes.items);
    allLibraryItems.set([...moviesRes.items, ...showsRes.items]);

    // Fetch per-library recently added in parallel for each library view
    if (views.length > 0) {
      const latestPromises = views.map(async (v) => {
        const items = await jfApi.getLatestMedia(config.url, config.userId, config.token, v.Id, (fresh) => {
          libraryLatestMap.update((m) => ({ ...m, [v.Id]: fresh }));
        });
        return { viewId: v.Id, items };
      });
      const perLibResults = await Promise.all(latestPromises);
      const newMap: Record<string, JellyfinItem[]> = {};
      for (const res of perLibResults) {
        if (res.items && res.items.length > 0) {
          newMap[res.viewId] = res.items;
        }
      }
      libraryLatestMap.set(newMap);
    }

    addDiagnosticLog('info', `Loaded ${views.length} libraries, ${moviesRes.items.length} movies, ${showsRes.items.length} series, and ${nextUp.length} next up episodes.`);
  } catch (err: any) {
    addDiagnosticLog('warn', `Failed to refresh server library in background: ${err.message}`);
  } finally {
    isLoadingLibrary.set(false);
  }
}

export async function clearAppCache(fullReset = false) {
  clearAllCache();
  if (fullReset) {
    if (typeof window !== 'undefined') {
      localStorage.clear();
    }
    logout();
    showToast('App data & cache fully reset', 'info');
  } else {
    showToast('Cache cleared. Re-syncing library...', 'info');
    const config = get(serverConfig);
    if (config.connected) {
      userViews.set([]);
      latestMedia.set([]);
      resumeMedia.set([]);
      nextUpMedia.set([]);
      libraryLatestMap.set({});
      moviesList.set([]);
      showsList.set([]);
      allLibraryItems.set([]);
      await refreshServerLibrary(config);
    }
  }
}

// Open Detail View
export async function openItemDetail(item: JellyfinItem) {
  const config = get(serverConfig);
  if (!config.isDemo && item.Type === 'Series' && (!item.seasons || item.seasons.length === 0)) {
    try {
      const seasons = await jfApi.getSeasons(config.url, config.userId, config.token, item.Id, (fresh) => {
        item.seasons = fresh;
      });
      item.seasons = seasons;
    } catch (err) {
      console.error('Failed to load seasons', err);
    }
  } else if (!config.isDemo && (item.Type === 'MusicAlbum' || item.Type === 'Folder' || item.Type === 'Playlist') && !item.tracks) {
    try {
      const tracks = await jfApi.getPlayableFolderItems(config.url, config.userId, config.token, item.Id, (fresh) => {
        item.tracks = fresh;
      });
      item.tracks = tracks;
    } catch (err) {
      console.error('Failed to load album/folder tracks', err);
    }
  }
  detailModalItem.set(item);
}

// Media Stream Resolver
export function resolveItemStreamUrl(item: JellyfinItem): string {
  const config = get(serverConfig);
  if (config.isDemo || item.streamUrl) {
    return item.streamUrl || 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4';
  }
  if (item.Type === 'Audio') {
    return jfApi.buildAudioStreamUrl(config.url, item.Id, config.token);
  }
  const mediaSourceId = item.MediaSources && item.MediaSources[0]?.Id;
  return jfApi.buildDirectStreamUrl(config.url, item.Id, config.token, mediaSourceId);
}

export function resolveItemPosterUrl(item: JellyfinItem): string {
  const config = get(serverConfig);
  if (config.isDemo || item.posterUrl) {
    return item.posterUrl || 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg';
  }
  return jfApi.buildImageUrl(config.url, item.Id, config.token, 'Primary', { maxWidth: 500, quality: 90 });
}

export function resolveItemBackdropUrl(item: JellyfinItem): string {
  const config = get(serverConfig);
  if (config.isDemo || item.backdropUrl) {
    return item.backdropUrl || 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=1600&q=80';
  }
  if (item.BackdropImageTags && item.BackdropImageTags.length > 0) {
    return jfApi.buildImageUrl(config.url, item.Id, config.token, 'Backdrop', { maxWidth: 1920, quality: 85 });
  }
  return resolveItemPosterUrl(item);
}

// Trigger Playback in Browser Video/Audio Player (Single Item, Folder, Album, or Playlist)
export async function playInBrowser(
  item: JellyfinItem,
  playlist?: JellyfinItem[],
  startIndex = 0,
  forceExpand = false
) {
  const config = get(serverConfig);
  let queue: JellyfinItem[] = [];

  // If playlist provided, filter to playable media items
  if (playlist && playlist.length > 0) {
    queue = playlist.filter((i) => i.Type === 'Audio' || i.Type === 'Movie' || i.Type === 'Episode' || i.Type === 'Video');
  }

  // If item is a container (Folder, MusicAlbum, Playlist) and queue is empty, resolve playable children
  if (queue.length === 0 && (item.Type === 'Folder' || item.Type === 'MusicAlbum' || item.Type === 'Playlist')) {
    if (item.tracks && item.tracks.length > 0) {
      queue = item.tracks.filter((i) => i.Type === 'Audio' || i.Type === 'Movie' || i.Type === 'Episode' || i.Type === 'Video');
    } else if (!config.isDemo) {
      try {
        const tracks = await jfApi.getPlayableFolderItems(config.url, config.userId, config.token, item.Id);
        item.tracks = tracks;
        queue = tracks;
      } catch (err) {
        console.error('Failed to resolve folder tracks for in-browser playback', err);
      }
    }
  } else if (queue.length === 0 && item.Type === 'Series') {
    // If item is a series, resolve all episodes across seasons
    if (!config.isDemo && (!item.seasons || item.seasons.length === 0)) {
      try {
        item.seasons = await jfApi.getSeasons(config.url, config.userId, config.token, item.Id);
      } catch {}
    }
    for (const s of (item.seasons || [])) {
      for (const ep of s.Episodes) {
        queue.push(ep);
      }
    }
  }

  if (queue.length === 0) {
    queue = [item];
  }

  const validIndex = Math.max(0, Math.min(startIndex, queue.length - 1));
  const targetItem = queue[validIndex];
  const streamUrl = resolveItemStreamUrl(targetItem);

  // Audio plays in mini player by default unless forceExpand is requested; Videos expand
  const shouldExpand = forceExpand || (targetItem.Type === 'Movie' || targetItem.Type === 'Episode');

  // Close detail modal so user can continue browsing
  detailModalItem.set(null);

  activePlayer.set({
    isOpen: true,
    isExpanded: shouldExpand,
    item: targetItem,
    streamUrl,
    isCasting: false,
    isLinkedCast: false,
    title: targetItem.Name,
    season: targetItem.ParentIndexNumber,
    episode: targetItem.IndexNumber,
    playlist: queue,
    currentIndex: validIndex
  });

  // Fetch lyrics in background for audio tracks
  if (targetItem.Type === 'Audio' && !config.isDemo) {
    jfApi.getItemLyrics(config.url, config.token, targetItem.Id).then((res) => {
      lyricsData.set(res);
    }).catch(() => {
      lyricsData.set(null);
    });
  }

  if (queue.length > 1) {
    showToast(`Playing "${targetItem.Name}" (${validIndex + 1}/${queue.length})`, 'info');
  } else {
    showToast(`Playing "${targetItem.Name}"`, 'info');
  }
}

// Trigger Shuffle Play for Folders, Albums, and Playlists (Finamp Inspired)
export async function shufflePlay(parentItem: JellyfinItem, providedItems?: JellyfinItem[]): Promise<void> {
  const config = get(serverConfig);
  let tracks: JellyfinItem[] = [];

  if (providedItems && providedItems.length > 0) {
    tracks = providedItems.filter((i) => i.Type === 'Audio' || i.Type === 'Movie' || i.Type === 'Episode');
  }

  if (tracks.length === 0 && !config.isDemo) {
    try {
      tracks = await jfApi.getPlayableFolderItems(config.url, config.userId, config.token, parentItem.Id);
      parentItem.tracks = tracks;
    } catch (err) {
      console.error('Failed to resolve tracks for shuffle play', err);
    }
  }

  if (tracks.length === 0) {
    showToast(`No playable tracks found to shuffle`, 'info');
    return;
  }

  const shuffled = shuffleArray(tracks);
  isShuffle.set(true);
  await playInBrowser(shuffled[0], shuffled, 0, false);
  showToast(`Shuffling "${parentItem.Name}" (${shuffled.length} tracks)`, 'info');
}

// Trigger Shuffle Direct Cast for Folders, Albums, and Playlists
export async function shuffleCast(parentItem: JellyfinItem, providedItems?: JellyfinItem[]): Promise<boolean> {
  const config = get(serverConfig);
  let tracks: JellyfinItem[] = [];

  if (providedItems && providedItems.length > 0) {
    tracks = providedItems.filter((i) => i.Type === 'Audio' || i.Type === 'Movie' || i.Type === 'Episode');
  }

  if (tracks.length === 0 && !config.isDemo) {
    try {
      tracks = await jfApi.getPlayableFolderItems(config.url, config.userId, config.token, parentItem.Id);
      parentItem.tracks = tracks;
    } catch (err) {
      console.error('Failed to resolve tracks for shuffle cast', err);
    }
  }

  if (tracks.length === 0) {
    showToast(`No playable tracks found to shuffle cast`, 'info');
    return false;
  }

  const shuffled = shuffleArray(tracks);
  isShuffle.set(true);
  return playFolderOrAlbumWithCast(parentItem, shuffled, 0);
}

// Toggle Shuffle mode on active queue
export function toggleShuffle() {
  isShuffle.update((val) => {
    const next = !val;
    const current = get(activePlayer);
    if (current.playlist && current.playlist.length > 1) {
      if (next) {
        // Shuffle remaining queue while keeping current item
        const curItem = current.item;
        const others = current.playlist.filter((i) => i.Id !== curItem?.Id);
        const shuffled = curItem ? [curItem, ...shuffleArray(others)] : shuffleArray(current.playlist);
        activePlayer.update((s) => ({ ...s, playlist: shuffled, currentIndex: 0 }));
      }
    }
    showToast(next ? 'Shuffle enabled' : 'Shuffle disabled', 'info');
    return next;
  });
}

// Cycle Repeat mode: off -> all -> one -> off
export function cycleRepeatMode() {
  repeatMode.update((mode) => {
    let next: 'off' | 'all' | 'one' = 'off';
    if (mode === 'off') next = 'all';
    else if (mode === 'all') next = 'one';
    else next = 'off';

    const label = next === 'all' ? 'Repeat All' : next === 'one' ? 'Repeat One' : 'Repeat Off';
    showToast(label, 'info');
    return next;
  });
}

// Toggle Favorite Item on Jellyfin Server (Finamp Feature)
export async function toggleFavorite(item: JellyfinItem): Promise<boolean> {
  const config = get(serverConfig);
  const currentFav = !!item.UserData?.IsFavorite;
  const nextFav = !currentFav;

  // Optimistic local update
  if (!item.UserData) {
    item.UserData = { IsFavorite: nextFav };
  } else {
    item.UserData.IsFavorite = nextFav;
  }

  // Update in all stores
  allLibraryItems.update((items) => items.map((i) => (i.Id === item.Id ? { ...i, UserData: { ...i.UserData, IsFavorite: nextFav } } : i)));
  moviesList.update((items) => items.map((i) => (i.Id === item.Id ? { ...i, UserData: { ...i.UserData, IsFavorite: nextFav } } : i)));
  showsList.update((items) => items.map((i) => (i.Id === item.Id ? { ...i, UserData: { ...i.UserData, IsFavorite: nextFav } } : i)));

  showToast(nextFav ? `Added "${item.Name}" to Favorites` : `Removed "${item.Name}" from Favorites`, 'success');

  if (!config.isDemo && config.url && config.userId) {
    try {
      await jfApi.toggleFavoriteItem(config.url, config.userId, config.token, item.Id, nextFav);
    } catch (err) {
      console.error('Failed to update favorite on server', err);
    }
  }
  return nextFav;
}

// Trigger Direct PlayBridge Casting (Single Item)
export async function playWithDirectCast(item: JellyfinItem): Promise<boolean> {
  let targetItem = item;
  const config = get(serverConfig);

  // If a series is clicked, resolve its first episode
  if (item.Type === 'Series') {
    if (!config.isDemo && (!item.seasons || item.seasons.length === 0)) {
      try {
        item.seasons = await jfApi.getSeasons(config.url, config.userId, config.token, item.Id);
      } catch {}
    }
    const firstEp = item.seasons?.[0]?.Episodes?.[0];
    if (firstEp) {
      targetItem = firstEp;
    }
  } else if ((item.Type === 'MusicAlbum' || item.Type === 'Folder' || item.Type === 'Playlist') && !item.streamUrl) {
    // If an album/folder is clicked, cast the whole album/folder!
    return playFolderOrAlbumWithCast(item, item.tracks, 0);
  }

  const streamUrl = resolveItemStreamUrl(targetItem);
  const posterUrl = resolveItemPosterUrl(targetItem);
  const backdropUrl = resolveItemBackdropUrl(targetItem);

  const payload = buildSingleCastPayload(targetItem, streamUrl, posterUrl, backdropUrl);
  const success = castDirect(payload);

  // Close details modal so user stays in library
  detailModalItem.set(null);

  // Show Toast
  showToast(`Casting "${payload.title || targetItem.Name}" to PlayBridge`, 'cast');

  // Keep player in bottom Mini Bar (not taking over screen)
  activePlayer.set({
    isOpen: true,
    isExpanded: false,
    item: targetItem,
    streamUrl,
    isCasting: true,
    isLinkedCast: false,
    title: payload.title || targetItem.Name,
    season: targetItem.ParentIndexNumber,
    episode: targetItem.IndexNumber,
    playlist: [targetItem],
    currentIndex: 0
  });

  return success;
}

// Trigger Batch Playlist Casting for Folders, Music Albums, and Playlists
export async function playFolderOrAlbumWithCast(
  parentItem: JellyfinItem,
  providedItems?: JellyfinItem[],
  startIndex = 0
): Promise<boolean> {
  const config = get(serverConfig);
  let tracks: JellyfinItem[] = [];

  // Filter providedItems to only include playable items (Audio, Movie, Episode)
  if (providedItems && providedItems.length > 0) {
    tracks = providedItems.filter((i) => i.Type === 'Audio' || i.Type === 'Movie' || i.Type === 'Episode');
  }

  // If no playable items in providedItems, query server recursively with cache
  if (tracks.length === 0 && !config.isDemo) {
    try {
      tracks = await jfApi.getPlayableFolderItems(config.url, config.userId, config.token, parentItem.Id);
      parentItem.tracks = tracks;
    } catch (err) {
      console.error('Failed to fetch items for folder/album', err);
    }
  }

  if (tracks.length === 0) {
    addDiagnosticLog('warn', `No playable audio/video tracks found in "${parentItem.Name}"`);
    showToast(`No playable tracks found in "${parentItem.Name}"`, 'info');
    return false;
  }

  const playlistTitle = parentItem.Name;
  const playlistPoster = resolveItemPosterUrl(parentItem);
  const playlistBackdrop = resolveItemBackdropUrl(parentItem);

  const playlistItems = tracks.map((track) => ({
    item: track,
    streamUrl: resolveItemStreamUrl(track),
    posterUrl: resolveItemPosterUrl(track) || playlistPoster,
    backdropUrl: resolveItemBackdropUrl(track) || playlistBackdrop
  }));

  const payload = buildPlaylistCastPayload(playlistItems, startIndex, playlistTitle, playlistPoster);
  const success = castDirect(payload);

  // Close details modal so user stays in library
  detailModalItem.set(null);

  showToast(`Casting "${playlistTitle}" (${tracks.length} items) to PlayBridge`, 'cast');

  const startTrack = tracks[startIndex] || tracks[0];
  activePlayer.set({
    isOpen: true,
    isExpanded: false, // Mini player bar
    item: startTrack,
    streamUrl: resolveItemStreamUrl(startTrack),
    isCasting: true,
    isLinkedCast: false,
    title: `${playlistTitle} (${startIndex + 1}/${tracks.length})`,
    playlist: tracks,
    currentIndex: startIndex
  });

  return success;
}

// Trigger Linked Queue Cast for Series / Playlists
export async function playWithLinkedQueue(series: JellyfinItem, seasons?: JellyfinSeason[], startEpisodeIndex = 0) {
  const allEpisodes: JellyfinItem[] = [];
  const config = get(serverConfig);
  let sourceSeasons = seasons || series.seasons || [];

  if (sourceSeasons.length === 0 && !config.isDemo) {
    try {
      sourceSeasons = await jfApi.getSeasons(config.url, config.userId, config.token, series.Id);
      series.seasons = sourceSeasons;
    } catch {}
  }

  for (const s of sourceSeasons) {
    for (const ep of s.Episodes) {
      allEpisodes.push(ep);
    }
  }

  if (allEpisodes.length === 0) {
    return playWithDirectCast(series);
  }

  const castItems = allEpisodes.map((ep) => {
    const sUrl = resolveItemStreamUrl(ep);
    const pUrl = resolveItemPosterUrl(ep);
    const bUrl = resolveItemBackdropUrl(ep);
    let epTitle = ep.Name;
    if (ep.IndexNumber != null) {
      const s = ep.ParentIndexNumber ?? 1;
      epTitle = `S${s}E${ep.IndexNumber} · ${ep.Name}`;
    }
    return {
      id: ep.Id,
      url: sUrl,
      title: epTitle,
      contentType: sUrl.includes('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4',
      metadata: formatVisualMetadata(ep, pUrl, bUrl)
    };
  });

  const metadata = formatVisualMetadata(series, resolveItemPosterUrl(series), resolveItemBackdropUrl(series));
  await castLinkedQueue(castItems, startEpisodeIndex, metadata);

  detailModalItem.set(null);
  showToast(`Casting "${series.Name}" queue to PlayBridge`, 'cast');

  const startingEp = allEpisodes[startEpisodeIndex] || allEpisodes[0];
  activePlayer.set({
    isOpen: true,
    isExpanded: false,
    item: startingEp,
    streamUrl: resolveItemStreamUrl(startingEp),
    isCasting: true,
    isLinkedCast: true,
    title: `${series.Name} · ${startingEp.Name}`,
    season: startingEp.ParentIndexNumber,
    episode: startingEp.IndexNumber,
    playlist: allEpisodes,
    currentIndex: startEpisodeIndex
  });
}

// Queue navigation helpers with Repeat One / Repeat All / Next support
export function skipNextTrack() {
  const current = get(activePlayer);
  const rep = get(repeatMode);

  if (rep === 'one' && current.item) {
    // Replay same track
    playInBrowser(current.item, current.playlist, current.currentIndex, current.isExpanded);
    return;
  }

  if (!current.playlist || current.playlist.length <= 1) return;

  const nextIdx = (current.currentIndex ?? 0) + 1;
  if (nextIdx >= current.playlist.length) {
    if (rep === 'all') {
      // Loop back to first track
      const firstItem = current.playlist[0];
      if (current.isCasting) {
        playWithDirectCast(firstItem);
      } else {
        playInBrowser(firstItem, current.playlist, 0, current.isExpanded);
      }
    }
    return;
  }

  const nextItem = current.playlist[nextIdx];
  if (current.isCasting) {
    playWithDirectCast(nextItem);
  } else {
    playInBrowser(nextItem, current.playlist, nextIdx, current.isExpanded);
  }
}

export function skipPrevTrack() {
  const current = get(activePlayer);
  if (!current.playlist || current.playlist.length <= 1) return;
  const prevIdx = ((current.currentIndex ?? 0) - 1 + current.playlist.length) % current.playlist.length;
  const prevItem = current.playlist[prevIdx];
  if (current.isCasting) {
    playWithDirectCast(prevItem);
  } else {
    playInBrowser(prevItem, current.playlist, prevIdx, current.isExpanded);
  }
}

export function playQueueTrack(index: number) {
  const current = get(activePlayer);
  if (!current.playlist || index < 0 || index >= current.playlist.length) return;
  const target = current.playlist[index];
  if (current.isCasting) {
    playWithDirectCast(target);
  } else {
    playInBrowser(target, current.playlist, index, current.isExpanded);
  }
}
