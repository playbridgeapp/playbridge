<script lang="ts">
  import { onMount } from 'svelte';
  import Navbar from './lib/components/Navbar.svelte';
  import LoginScreen from './lib/components/LoginScreen.svelte';
  import HeroSpotlight from './lib/components/HeroSpotlight.svelte';
  import MediaSection from './lib/components/MediaSection.svelte';
  import MediaCard from './lib/components/MediaCard.svelte';
  import ItemDetailModal from './lib/components/ItemDetailModal.svelte';
  import VideoPlayer from './lib/components/VideoPlayer.svelte';
  import ServerConnectModal from './lib/components/ServerConnectModal.svelte';
  import DiagnosticsDrawer from './lib/components/DiagnosticsDrawer.svelte';
  import QueueDrawer from './lib/components/QueueDrawer.svelte';

  import {
    activeTab,
    serverConfig,
    userViews,
    latestMedia,
    resumeMedia,
    nextUpMedia,
    libraryLatestMap,
    moviesList,
    showsList,
    allLibraryItems,
    searchQuery,
    selectedGenre,
    sortBy,
    isLoadingLibrary,
    initializeSession,
    playFolderOrAlbumWithCast,
    playInBrowser,
    shufflePlay,
    shuffleCast,
    activeToast
  } from './lib/stores/appState';
  import { initPlayBridgeDetector } from './lib/cast/playbridge';
  import * as jfApi from './lib/api/jellyfin';
  import { getCachedData } from './lib/api/cache';
  import type { JellyfinItem } from './lib/types';
  import {
    Film,
    Clapperboard,
    Sparkles,
    Search,
    Loader2,
    SlidersHorizontal,
    Folder,
    Cast,
    Play,
    Shuffle,
    Music,
    Tv,
    Layers,
    ChevronRight,
    ArrowLeft
  } from 'lucide-svelte';

  let customViewItems: JellyfinItem[] = [];
  let isLoadingCustomView = false;
  let activeLibraryViewId: string | null = null;
  let folderStack: Array<{ id: string; name: string }> = [];

  onMount(() => {
    initPlayBridgeDetector();
    initializeSession();
  });

  // Active view object
  $: currentView = $userViews.find((v) => v.Id === $activeTab);

  // When active tab switches to a user view, initialize folder stack and load root of that view
  $: if (
    $serverConfig.connected &&
    currentView &&
    currentView.Id !== activeLibraryViewId &&
    $activeTab !== 'home' &&
    $activeTab !== 'search' &&
    $activeTab !== 'favorites' &&
    $activeTab !== 'movies' &&
    $activeTab !== 'shows'
  ) {
    activeLibraryViewId = currentView.Id;
    folderStack = [{ id: currentView.Id, name: currentView.Name }];
    loadFolderLevel(currentView.Id, currentView.CollectionType, false);
  }

  async function loadFolderLevel(folderId: string, collectionType?: string, isSubfolder = false) {
    if (!$serverConfig.connected || $serverConfig.isDemo) return;

    const isTypedRoot =
      !isSubfolder &&
      (collectionType === 'movies' || collectionType === 'tvshows' || collectionType === 'music');

    const queryOptions: any = {
      parentId: folderId,
      recursive: isTypedRoot ? true : false
    };

    if (isTypedRoot) {
      if (collectionType === 'movies') queryOptions.includeItemTypes = 'Movie';
      else if (collectionType === 'tvshows') queryOptions.includeItemTypes = 'Series';
      else if (collectionType === 'music') queryOptions.includeItemTypes = 'MusicAlbum';
    }

    const cacheKey = `lib_${$serverConfig.userId}_${folderId}_${queryOptions.includeItemTypes || 'direct'}_${queryOptions.recursive}`;
    const cached = getCachedData<{ items: JellyfinItem[] }>(cacheKey);
    if (cached && cached.items && cached.items.length > 0) {
      customViewItems = cached.items;
      isLoadingCustomView = false;
    } else {
      isLoadingCustomView = true;
    }

    try {
      const res = await jfApi.getLibraryItems(
        $serverConfig.url,
        $serverConfig.userId,
        $serverConfig.token,
        queryOptions,
        (fresh) => {
          customViewItems = fresh.items;
        }
      );
      customViewItems = res.items;
    } catch (err) {
      console.error('Failed to fetch folder items', err);
    } finally {
      isLoadingCustomView = false;
    }
  }

  function handleNavigateIntoFolder(folderItem: JellyfinItem) {
    folderStack = [...folderStack, { id: folderItem.Id, name: folderItem.Name }];
    loadFolderLevel(folderItem.Id, currentView?.CollectionType, true);
  }

  function handleNavigateToBreadcrumb(index: number) {
    folderStack = folderStack.slice(0, index + 1);
    const target = folderStack[folderStack.length - 1];
    loadFolderLevel(target.id, currentView?.CollectionType, folderStack.length > 1);
  }

  function handleBackOneFolder() {
    if (folderStack.length > 1) {
      handleNavigateToBreadcrumb(folderStack.length - 2);
    }
  }

  // Featured Spotlight item
  $: featuredItem =
    $latestMedia.length > 0
      ? $latestMedia[0]
      : $moviesList.length > 0
      ? $moviesList[0]
      : $allLibraryItems.length > 0
      ? $allLibraryItems[0]
      : null;

  // Genres extraction
  $: availableGenres = Array.from(
    new Set(
      $allLibraryItems
        .flatMap((i) => i.Genres || [])
        .filter(Boolean)
    )
  );

  // Filtered Movies
  $: filteredMovies = $moviesList.filter((m) => {
    const matchGenre = $selectedGenre === 'all' || (m.Genres && m.Genres.includes($selectedGenre));
    const matchSearch = !$searchQuery || m.Name.toLowerCase().includes($searchQuery.toLowerCase());
    return matchGenre && matchSearch;
  });

  // Filtered Shows
  $: filteredShows = $showsList.filter((s) => {
    const matchGenre = $selectedGenre === 'all' || (s.Genres && s.Genres.includes($selectedGenre));
    const matchSearch = !$searchQuery || s.Name.toLowerCase().includes($searchQuery.toLowerCase());
    return matchGenre && matchSearch;
  });

  // Search Results
  $: searchResults = $searchQuery.trim()
    ? $allLibraryItems.filter((i) =>
        i.Name.toLowerCase().includes($searchQuery.toLowerCase()) ||
        (i.Overview && i.Overview.toLowerCase().includes($searchQuery.toLowerCase())) ||
        (i.Genres && i.Genres.some((g) => g.toLowerCase().includes($searchQuery.toLowerCase()))) ||
        (i.AlbumArtist && i.AlbumArtist.toLowerCase().includes($searchQuery.toLowerCase())) ||
        (i.Artists && i.Artists.some((a) => a.toLowerCase().includes($searchQuery.toLowerCase())))
      )
    : [];

  // Favorites
  $: favoriteItems = $allLibraryItems.filter((i) => i.UserData?.IsFavorite);

  function handleBatchCastFolder() {
    if (!currentView || customViewItems.length === 0) return;
    const folderItem: JellyfinItem = {
      Id: currentView.Id,
      Name: currentView.Name,
      Type: 'Folder',
      tracks: customViewItems
    };
    playFolderOrAlbumWithCast(folderItem, customViewItems, 0);
  }

  function handlePlayFolder() {
    if (!currentView || customViewItems.length === 0) return;
    const folderItem: JellyfinItem = {
      Id: currentView.Id,
      Name: currentView.Name,
      Type: 'Folder',
      tracks: customViewItems
    };
    playInBrowser(folderItem, customViewItems, 0, false);
  }

  function handleShufflePlayFolder() {
    if (!currentView || customViewItems.length === 0) return;
    const folderItem: JellyfinItem = {
      Id: currentView.Id,
      Name: currentView.Name,
      Type: 'Folder',
      tracks: customViewItems
    };
    shufflePlay(folderItem, customViewItems);
  }

  function handleShuffleCastFolder() {
    if (!currentView || customViewItems.length === 0) return;
    const folderItem: JellyfinItem = {
      Id: currentView.Id,
      Name: currentView.Name,
      Type: 'Folder',
      tracks: customViewItems
    };
    shuffleCast(folderItem, customViewItems);
  }
</script>

{#if !$serverConfig.connected}
  <!-- Full Screen Login / Connect Screen -->
  <LoginScreen />
{:else}
  <!-- Main Jellyfin Web Client Layout -->
  <div class="app-layout">
    <Navbar />

    <!-- Floating Cast Toast Notification -->
    {#if $activeToast}
      <div class="toast-notification">
        <div class="toast-badge">
          <Cast size={15} />
        </div>
        <span class="toast-text">{$activeToast.message}</span>
      </div>
    {/if}

    <main class="main-content">
      {#if $isLoadingLibrary}
        <div class="loading-state">
          <Loader2 size={36} class="spinner" />
          <p>Loading library from {$serverConfig.serverName || 'Jellyfin'}...</p>
        </div>
      {:else}
        <!-- TAB: HOME -->
        {#if $activeTab === 'home'}
          {#if featuredItem}
            <HeroSpotlight item={featuredItem} />
          {/if}

          <div class="sections-wrapper">
            <!-- 1. My Media (Jellyfin Official Library Tiles) -->
            {#if $userViews.length > 0}
              <section class="my-media-section">
                <div class="section-header-row">
                  <h2 class="section-title">My Media</h2>
                  <span class="section-sub">{$userViews.length} libraries on {$serverConfig.serverName}</span>
                </div>

                <div class="library-tiles-grid">
                  {#each $userViews as view (view.Id)}
                    <button class="library-tile-card" on:click={() => ($activeTab = view.Id)}>
                      <div class="tile-icon-box">
                        {#if view.CollectionType === 'movies'}
                          <Film size={26} />
                        {:else if view.CollectionType === 'tvshows'}
                          <Clapperboard size={26} />
                        {:else if view.CollectionType === 'music'}
                          <Music size={26} />
                        {:else}
                          <Folder size={26} />
                        {/if}
                      </div>

                      <div class="tile-info">
                        <span class="tile-title">{view.Name}</span>
                        <span class="tile-kind">
                          {view.CollectionType === 'movies'
                            ? 'Movies'
                            : view.CollectionType === 'tvshows'
                            ? 'TV Shows'
                            : view.CollectionType === 'music'
                            ? 'Music'
                            : 'Library'}
                        </span>
                      </div>
                    </button>
                  {/each}
                </div>
              </section>
            {/if}

            <!-- 2. Continue Watching (Resume) -->
            {#if $resumeMedia.length > 0}
              <MediaSection
                title="Continue Watching"
                subtitle="Pick up where you left off"
                items={$resumeMedia}
              />
            {/if}

            <!-- 3. Next Up (Television In-Progress Episodes) -->
            {#if $nextUpMedia.length > 0}
              <MediaSection
                title="Next Up"
                subtitle="Next unplayed episodes in your shows"
                items={$nextUpMedia}
              />
            {/if}

            <!-- 4. Dedicated Recently Added Shelves per Library -->
            {#each $userViews as view (view.Id)}
              {#if $libraryLatestMap[view.Id] && $libraryLatestMap[view.Id].length > 0}
                <MediaSection
                  title={`Recently Added in ${view.Name}`}
                  subtitle={`New additions to your ${view.Name} library`}
                  items={$libraryLatestMap[view.Id]}
                  onSeeAll={() => ($activeTab = view.Id)}
                />
              {/if}
            {/each}

            <!-- Fallback generic sections if server does not have segmented latest -->
            {#if Object.keys($libraryLatestMap).length === 0}
              {#if $latestMedia.length > 0}
                <MediaSection
                  title="Latest Media"
                  subtitle="Recently added to {$serverConfig.serverName}"
                  items={$latestMedia}
                />
              {/if}

              {#if $moviesList.length > 0}
                <MediaSection
                  title="Movies"
                  subtitle="Feature films and cinema"
                  items={$moviesList}
                />
              {/if}

              {#if $showsList.length > 0}
                <MediaSection
                  title="TV Series"
                  subtitle="Series with full episode and seasonal direct casting"
                  items={$showsList}
                />
              {/if}
            {/if}
          </div>

        <!-- TAB: MOVIES -->
        {:else if $activeTab === 'movies'}
          <div class="library-view">
            <div class="view-header">
              <div>
                <h1 class="view-title">Movies</h1>
                <p class="view-subtitle">{filteredMovies.length} titles in library</p>
              </div>

              <!-- Genre filters -->
              {#if availableGenres.length > 0}
                <div class="genre-filter-bar">
                  <button
                    class="genre-chip"
                    class:active={$selectedGenre === 'all'}
                    on:click={() => ($selectedGenre = 'all')}
                  >
                    All
                  </button>
                  {#each availableGenres as genre}
                    <button
                      class="genre-chip"
                      class:active={$selectedGenre === genre}
                      on:click={() => ($selectedGenre = genre)}
                    >
                      {genre}
                    </button>
                  {/each}
                </div>
              {/if}
            </div>

            <div class="media-grid">
              {#each filteredMovies as item (item.Id)}
                <MediaCard {item} />
              {/each}
            </div>
          </div>

        <!-- TAB: TV SHOWS -->
        {:else if $activeTab === 'shows'}
          <div class="library-view">
            <div class="view-header">
              <div>
                <h1 class="view-title">TV Shows</h1>
                <p class="view-subtitle">{filteredShows.length} series in library</p>
              </div>

              {#if availableGenres.length > 0}
                <div class="genre-filter-bar">
                  <button
                    class="genre-chip"
                    class:active={$selectedGenre === 'all'}
                    on:click={() => ($selectedGenre = 'all')}
                  >
                    All
                  </button>
                  {#each availableGenres as genre}
                    <button
                      class="genre-chip"
                      class:active={$selectedGenre === genre}
                      on:click={() => ($selectedGenre = genre)}
                    >
                      {genre}
                    </button>
                  {/each}
                </div>
              {/if}
            </div>

            <div class="media-grid">
              {#each filteredShows as item (item.Id)}
                <MediaCard {item} />
              {/each}
            </div>
          </div>

        <!-- TAB: DYNAMIC USER FOLDERS / CUSTOM VIEWS -->
        {:else if currentView}
          <div class="library-view">
            <div class="view-header">
              <div class="view-title-group">
                <!-- Breadcrumbs and back button -->
                {#if folderStack.length > 1}
                  <div class="breadcrumbs-bar">
                    <button class="back-folder-btn" on:click={handleBackOneFolder} title="Go back to parent folder">
                      <ArrowLeft size={16} />
                      <span>Back</span>
                    </button>

                    <div class="crumbs-trail">
                      {#each folderStack as crumb, idx}
                        {#if idx > 0}
                          <ChevronRight size={13} class="crumb-sep" />
                        {/if}
                        {#if idx === folderStack.length - 1}
                          <span class="crumb-current">{crumb.name}</span>
                        {:else}
                          <button class="crumb-link" on:click={() => handleNavigateToBreadcrumb(idx)}>
                            {crumb.name}
                          </button>
                        {/if}
                      {/each}
                    </div>
                  </div>
                {/if}

                <h1 class="view-title">{folderStack.length > 0 ? folderStack[folderStack.length - 1].name : currentView.Name}</h1>
                <p class="view-subtitle">
                  {customViewItems.length} items in {folderStack.length > 0 ? folderStack[folderStack.length - 1].name : currentView.Name}
                </p>
              </div>

              <!-- Quick actions for Music/Audio folders -->
              {#if currentView.CollectionType === 'music' || customViewItems.some((i) => i.Type === 'Audio' || i.Type === 'MusicAlbum')}
                <div class="batch-cast-actions">
                  <button class="btn-primary batch-btn" on:click={handleBatchCastFolder}>
                    <Cast size={16} />
                    <span>Cast All</span>
                  </button>
                  <button class="btn-secondary batch-btn" on:click={handlePlayFolder}>
                    <Play size={16} />
                    <span>Play</span>
                  </button>
                  <button class="btn-secondary batch-btn" on:click={handleShufflePlayFolder} title="Shuffle Play in Browser">
                    <Shuffle size={16} />
                    <span>Shuffle</span>
                  </button>
                  <button class="btn-accent batch-btn" on:click={handleShuffleCastFolder} title="Shuffle Cast to PlayBridge Receiver">
                    <Shuffle size={16} />
                    <span>Shuffle Cast</span>
                  </button>
                </div>
              {/if}
            </div>

            {#if isLoadingCustomView}
              <div class="loading-state">
                <Loader2 size={32} class="spinner" />
                <p>Loading items...</p>
              </div>
            {:else if customViewItems.length === 0}
              <div class="empty-state">
                <Folder size={44} class="empty-icon" />
                <p>No media found in this folder</p>
              </div>
            {:else}
              <div class="media-grid">
                {#each customViewItems as item (item.Id)}
                  <MediaCard {item} onOpenFolder={handleNavigateIntoFolder} />
                {/each}
              </div>
            {/if}
          </div>

        <!-- TAB: SEARCH -->
        {:else if $activeTab === 'search'}
          <div class="library-view">
            <div class="view-header">
              <div>
                <h1 class="view-title">Search Results</h1>
                <p class="view-subtitle">
                  {searchResults.length} matches for "{$searchQuery}"
                </p>
              </div>
            </div>

            {#if searchResults.length === 0}
              <div class="empty-state">
                <Search size={44} class="empty-icon" />
                <p>No results found for "{$searchQuery}"</p>
                <span class="empty-sub">Try searching for a movie, series, track, or artist.</span>
              </div>
            {:else}
              <div class="media-grid">
                {#each searchResults as item (item.Id)}
                  <MediaCard {item} onOpenFolder={handleNavigateIntoFolder} />
                {/each}
              </div>
            {/if}
          </div>

        <!-- TAB: FAVORITES -->
        {:else if $activeTab === 'favorites'}
          <div class="library-view">
            <div class="view-header">
              <div>
                <h1 class="view-title">Favorites</h1>
                <p class="view-subtitle">{favoriteItems.length} bookmarked titles</p>
              </div>
            </div>

            {#if favoriteItems.length === 0}
              <div class="empty-state">
                <Sparkles size={44} class="empty-icon" />
                <p>No favorites yet</p>
              </div>
            {:else}
              <div class="media-grid">
                {#each favoriteItems as item (item.Id)}
                  <MediaCard {item} onOpenFolder={handleNavigateIntoFolder} />
                {/each}
              </div>
            {/if}
          </div>
        {/if}
      {/if}
    </main>

    <!-- Modals & Overlays -->
    <ItemDetailModal />
    <VideoPlayer />
    <QueueDrawer />
    <ServerConnectModal />
    <DiagnosticsDrawer />
  </div>
{/if}

<style>
  .app-layout {
    min-height: 100vh;
    min-height: 100dvh;
    display: flex;
    flex-direction: column;
    background-color: var(--bg-base);
    padding-bottom: calc(120px + env(safe-area-inset-bottom, 0px));
  }

  @media (min-width: 769px) {
    .app-layout {
      padding-bottom: 74px;
    }
  }

  /* Floating Toast Notification */
  .toast-notification {
    position: fixed;
    top: 74px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(18, 22, 30, 0.95);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    border: 1px solid rgba(122, 90, 248, 0.4);
    box-shadow: 0 8px 28px rgba(0, 0, 0, 0.6);
    padding: 8px 16px;
    border-radius: var(--radius-full);
    display: flex;
    align-items: center;
    gap: 10px;
    z-index: 80;
    animation: toastSlideDown 0.3s cubic-bezier(0.16, 1, 0.3, 1);
    max-width: 90vw;
  }

  @keyframes toastSlideDown {
    from { opacity: 0; transform: translate(-50%, -10px); }
    to { opacity: 1; transform: translate(-50%, 0); }
  }

  .toast-badge {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    background: var(--accent-gradient);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    flex-shrink: 0;
  }

  .toast-text {
    font-size: 0.86rem;
    font-weight: 600;
    color: #fff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
  }

  .loading-state {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding: 80px 20px;
    color: var(--text-muted);
  }

  .spinner {
    animation: spin 1s linear infinite;
    color: var(--jf-blue);
  }

  @keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
  }

  .sections-wrapper {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  /* My Media (Library Tiles) Section */
  .my-media-section {
    padding: 16px 32px 8px;
    max-width: 1440px;
    margin: 0 auto;
    width: 100%;
  }

  .section-header-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 14px;
  }

  .section-title {
    font-size: 1.25rem;
    font-weight: 700;
    color: #ffffff;
    letter-spacing: -0.01em;
  }

  .section-sub {
    font-size: 0.78rem;
    color: var(--text-muted);
  }

  .library-tiles-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px;
  }

  .library-tile-card {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 16px;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    cursor: pointer;
    text-align: left;
    transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  }

  .library-tile-card:hover {
    background: var(--bg-surface-elevated);
    border-color: var(--jf-blue);
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  .tile-icon-box {
    width: 44px;
    height: 44px;
    border-radius: var(--radius-md);
    background: linear-gradient(135deg, rgba(0, 164, 220, 0.15), rgba(122, 90, 248, 0.15));
    border: 1px solid rgba(0, 164, 220, 0.25);
    color: var(--jf-blue);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: all 0.2s ease;
  }

  .library-tile-card:hover .tile-icon-box {
    background: linear-gradient(135deg, var(--jf-blue), var(--jf-purple));
    color: #fff;
  }

  .tile-info {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .tile-title {
    font-size: 0.95rem;
    font-weight: 700;
    color: #ffffff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .tile-kind {
    font-size: 0.72rem;
    color: var(--text-muted);
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    margin-top: 2px;
  }

  .library-view {
    padding: 28px 32px 64px;
    max-width: 1440px;
    margin: 0 auto;
    width: 100%;
  }

  .view-header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    margin-bottom: 24px;
    gap: 16px;
    flex-wrap: wrap;
  }

  .view-title-group {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  /* Breadcrumbs Navigation */
  .breadcrumbs-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 6px;
    flex-wrap: wrap;
  }

  .back-folder-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: var(--radius-full);
    background: var(--bg-surface);
    border: 1px solid var(--border);
    color: var(--jf-blue);
    font-size: 0.78rem;
    font-weight: 600;
  }

  .back-folder-btn:hover {
    background: var(--bg-surface-elevated);
    border-color: var(--jf-blue);
    color: #fff;
  }

  .crumbs-trail {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.8rem;
    flex-wrap: wrap;
  }

  :global(.crumb-sep) {
    color: var(--text-muted);
  }

  .crumb-link {
    background: transparent;
    color: var(--text-secondary);
    font-size: 0.8rem;
    font-weight: 500;
    padding: 2px 4px;
  }

  .crumb-link:hover {
    color: var(--jf-blue);
    text-decoration: underline;
  }

  .crumb-current {
    color: #ffffff;
    font-weight: 700;
    font-size: 0.8rem;
  }

  .view-title {
    font-size: 1.75rem;
    font-weight: 800;
    color: #fff;
    letter-spacing: -0.02em;
  }

  .view-subtitle {
    font-size: 0.85rem;
    color: var(--text-muted);
    margin-top: 3px;
  }

  .batch-cast-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .batch-btn {
    padding: 8px 14px;
    font-size: 0.85rem;
  }

  .genre-filter-bar {
    display: flex;
    gap: 6px;
    overflow-x: auto;
    padding-bottom: 4px;
    scrollbar-width: none;
  }

  .genre-chip {
    padding: 5px 12px;
    border-radius: var(--radius-full);
    background: var(--bg-surface);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    font-size: 0.78rem;
    font-weight: 500;
    white-space: nowrap;
  }

  .genre-chip:hover {
    background: var(--bg-surface-elevated);
    color: #fff;
  }

  .genre-chip.active {
    background: var(--jf-blue);
    border-color: transparent;
    color: #fff;
    font-weight: 600;
  }

  .media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
    gap: 20px 16px;
  }

  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    padding: 60px 20px;
    color: var(--text-muted);
    gap: 10px;
  }

  :global(.empty-icon) {
    opacity: 0.4;
    color: var(--jf-blue);
  }

  .empty-sub {
    font-size: 0.8rem;
    color: var(--text-muted);
  }

  @media (max-width: 768px) {
    .my-media-section {
      padding: 12px 14px 4px;
    }
    .library-tiles-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 8px;
    }
    .library-tile-card {
      padding: 10px 12px;
      gap: 10px;
    }
    .tile-icon-box {
      width: 36px;
      height: 36px;
    }
    .tile-title {
      font-size: 0.85rem;
    }
    .library-view {
      padding: 14px 12px 28px;
    }
    .view-header {
      margin-bottom: 14px;
      gap: 10px;
    }
    .view-title {
      font-size: 1.35rem;
    }
    .media-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px 10px;
    }
    .batch-cast-actions {
      width: 100%;
    }
    .batch-cast-actions button {
      flex: 1;
    }
  }

  @media (min-width: 480px) and (max-width: 768px) {
    .media-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 14px 12px;
    }
  }
</style>
