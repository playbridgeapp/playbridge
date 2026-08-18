<script lang="ts">
  import {
    activeTab,
    serverConfig,
    savedAccounts,
    userViews,
    searchQuery,
    isServerModalOpen,
    isDiagnosticsOpen,
    loadDemoMode,
    logout,
    refreshServerLibrary,
    switchAccount,
    removeSavedAccount,
    clearAppCache
  } from '../stores/appState';
  import { bridgeStatus } from '../cast/playbridge';
  import {
    Tv,
    Search,
    Server,
    Activity,
    Radio,
    Film,
    Clapperboard,
    Sparkles,
    User,
    Check,
    LogOut,
    RefreshCw,
    Folder,
    Home,
    Music,
    Cast,
    X,
    Plus,
    Trash2,
    Layers,
    ChevronDown,
    RotateCcw
  } from 'lucide-svelte';

  let showProfileMenu = false;
  let showMoreViewsMenu = false;
  let showMobileSearch = false;

  $: currentServerId = $serverConfig.url ? `${$serverConfig.url}_${$serverConfig.userId}` : '';

  // Segregate views so navbar never stretches out of control
  $: musicViews = $userViews.filter((v) => v.CollectionType === 'music');
  $: otherFolderViews = $userViews.filter(
    (v) => v.CollectionType !== 'movies' && v.CollectionType !== 'tvshows' && v.CollectionType !== 'music'
  );

  function toggleProfileMenu() {
    showProfileMenu = !showProfileMenu;
    showMoreViewsMenu = false;
  }

  function closeProfileMenu() {
    showProfileMenu = false;
  }

  function toggleMobileSearch() {
    showMobileSearch = !showMobileSearch;
    if (showMobileSearch) {
      $activeTab = 'search';
    }
  }

  function handleRefresh() {
    refreshServerLibrary($serverConfig);
    closeProfileMenu();
  }

  function handleLogout() {
    logout();
    closeProfileMenu();
  }

  function handleSwitch(acc: any) {
    switchAccount(acc);
    closeProfileMenu();
  }

  function handleRemoveAccount(id: string) {
    removeSavedAccount(id);
  }
</script>

<!-- Top Navbar -->
<header class="navbar">
  {#if showMobileSearch}
    <!-- Expanded Mobile Search Bar -->
    <div class="mobile-search-bar">
      <Search size={18} class="search-bar-icon" />
      <input
        type="text"
        placeholder="Search movies, shows, music..."
        bind:value={$searchQuery}
        autofocus
        on:input={() => {
          if ($activeTab !== 'search') $activeTab = 'search';
        }}
      />
      {#if $searchQuery}
        <button class="clear-search-btn" on:click={() => ($searchQuery = '')}>
          <X size={16} />
        </button>
      {/if}
      <button class="close-search-btn" on:click={() => (showMobileSearch = false)}>
        Done
      </button>
    </div>
  {:else}
    <div class="nav-left">
      <!-- Brand Logo -->
      <div class="brand" on:click={() => ($activeTab = 'home')}>
        <div class="logo-icon">
          <svg viewBox="0 0 100 100" fill="none" class="brand-svg">
            <path d="M50 18 L82 74 L66 74 L50 44 L34 74 L18 74 Z" fill="url(#brand-grad)"/>
            <defs>
              <linearGradient id="brand-grad" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#00A4DC"/>
                <stop offset="100%" stop-color="#7A5AF8"/>
              </linearGradient>
            </defs>
          </svg>
        </div>
        <div class="brand-text">
          <span class="brand-name">PlayBridge</span>
          <span class="brand-sub">Cast Suite</span>
        </div>
      </div>

      <!-- Desktop Navigation Tabs (Compact & Responsive) -->
      <nav class="nav-links desktop-only">
        <button
          class="nav-link"
          class:active={$activeTab === 'home'}
          on:click={() => ($activeTab = 'home')}
        >
          <Home size={15} />
          <span>Home</span>
        </button>

        <button
          class="nav-link"
          class:active={$activeTab === 'movies'}
          on:click={() => ($activeTab = 'movies')}
        >
          <Film size={15} />
          <span>Movies</span>
        </button>

        <button
          class="nav-link"
          class:active={$activeTab === 'shows'}
          on:click={() => ($activeTab = 'shows')}
        >
          <Clapperboard size={15} />
          <span>Shows</span>
        </button>

        <!-- Dynamic Music Views (If any) -->
        {#each musicViews as mView (mView.Id)}
          <button
            class="nav-link"
            class:active={$activeTab === mView.Id}
            on:click={() => ($activeTab = mView.Id)}
          >
            <Music size={15} />
            <span>{mView.Name}</span>
          </button>
        {/each}

        <!-- Other Folder Views in sleek "Libraries" Dropdown (Prevents Navbar Overflow) -->
        {#if otherFolderViews.length > 0}
          <div class="more-views-container">
            <button
              class="nav-link more-btn"
              class:active={otherFolderViews.some((v) => $activeTab === v.Id)}
              on:click={() => (showMoreViewsMenu = !showMoreViewsMenu)}
            >
              <Folder size={15} />
              <span>Libraries</span>
              <ChevronDown size={13} />
            </button>

            {#if showMoreViewsMenu}
              <div class="more-views-dropdown" on:mouseleave={() => (showMoreViewsMenu = false)}>
                {#each otherFolderViews as view (view.Id)}
                  <button
                    class="dropdown-item"
                    class:active-item={$activeTab === view.Id}
                    on:click={() => {
                      $activeTab = view.Id;
                      showMoreViewsMenu = false;
                    }}
                  >
                    <Folder size={14} />
                    <span>{view.Name}</span>
                    {#if $activeTab === view.Id}
                      <Check size={14} class="check-icon" />
                    {/if}
                  </button>
                {/each}
              </div>
            {/if}
          </div>
        {/if}

        <button
          class="nav-link"
          class:active={$activeTab === 'favorites'}
          on:click={() => ($activeTab = 'favorites')}
        >
          <Sparkles size={15} />
          <span>Favorites</span>
        </button>
      </nav>
    </div>

    <!-- Right Controls: Always fixed and never pushed off screen -->
    <div class="nav-right">
      <!-- Desktop Search Bar -->
      <div class="search-box desktop-only">
        <Search size={16} class="search-icon" />
        <input
          type="text"
          placeholder="Search media..."
          bind:value={$searchQuery}
          on:input={() => {
            if ($searchQuery.trim().length > 0 && $activeTab !== 'search') {
              $activeTab = 'search';
            }
          }}
        />
        {#if $searchQuery}
          <button class="clear-search" on:click={() => ($searchQuery = '')}>&times;</button>
        {/if}
      </div>

      <!-- Mobile Search Toggle Icon -->
      <button
        class="btn-icon mobile-only"
        on:click={toggleMobileSearch}
        title="Search"
      >
        <Search size={18} />
      </button>

      <!-- PlayBridge Cast Status Icon Pill (Uses sleek icon instead of text) -->
      <button
        class="cast-icon-pill"
        class:cast-active={$bridgeStatus.available}
        on:click={() => ($isDiagnosticsOpen = true)}
        title={$bridgeStatus.available
          ? 'PlayBridge Bridge Active'
          : 'PlayBridge Bridge not detected'}
      >
        <Cast size={17} class="cast-symbol" />
        {#if $bridgeStatus.available}
          <span class="pulsing-dot-inline"></span>
        {/if}
      </button>

      <!-- Diagnostics Drawer Toggle -->
      <button
        class="btn-icon"
        on:click={() => ($isDiagnosticsOpen = !$isDiagnosticsOpen)}
        title="PlayBridge Diagnostics & Event Inspector"
      >
        <Activity size={18} />
      </button>

      <!-- Multi-Server & Multi-User Profile Menu -->
      <div class="profile-container">
        <button class="profile-btn" on:click={toggleProfileMenu}>
          <div class="avatar">
            <User size={15} />
          </div>
          <span class="server-badge desktop-only">
            {$serverConfig.username || 'User'}
          </span>
        </button>

        {#if showProfileMenu}
          <div class="profile-dropdown" on:mouseleave={closeProfileMenu}>
            <!-- Active Server / User Banner -->
            <div class="dropdown-header">
              <div class="active-badge-tag">ACTIVE SESSION</div>
              <p class="user-title">{$serverConfig.username || 'Guest'}</p>
              <p class="server-subtitle">{$serverConfig.serverName || 'Jellyfin Server'}</p>
              {#if $serverConfig.url}
                <p class="server-url-sub">{$serverConfig.url}</p>
              {/if}
            </div>

            <!-- Multi-Server / Multi-User Switcher List -->
            {#if $savedAccounts.length > 1}
              <div class="dropdown-divider"></div>
              <div class="section-label">SWITCH SERVER / USER</div>
              <div class="saved-accounts-list">
                {#each $savedAccounts as acc (acc.id)}
                  {#if acc.id !== currentServerId}
                    <div class="account-item-row">
                      <button class="account-switch-btn" on:click={() => handleSwitch(acc)}>
                        <div class="account-avatar-sm">
                          {acc.username.slice(0, 2).toUpperCase()}
                        </div>
                        <div class="account-meta">
                          <span class="account-user">{acc.username}</span>
                          <span class="account-server">{acc.serverName}</span>
                        </div>
                      </button>
                      <button
                        class="account-del-btn"
                        on:click|stopPropagation={() => handleRemoveAccount(acc.id)}
                        title="Remove profile"
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  {/if}
                {/each}
              </div>
            {/if}

            <div class="dropdown-divider"></div>

            {#if !$serverConfig.isDemo}
              <button class="dropdown-item" on:click={handleRefresh}>
                <RefreshCw size={15} />
                <span>Refresh Library</span>
              </button>
            {/if}

            <button
              class="dropdown-item"
              on:click={() => {
                $isServerModalOpen = true;
                closeProfileMenu();
              }}
            >
              <Plus size={15} />
              <span>Add Server or User</span>
            </button>

            <button
              class="dropdown-item"
              on:click={() => {
                loadDemoMode();
                closeProfileMenu();
              }}
            >
              <Sparkles size={15} />
              <span>Explore Demo Library</span>
              {#if $serverConfig.isDemo}
                <Check size={15} class="check-icon" />
              {/if}
            </button>

            <button
              class="dropdown-item"
              on:click={() => {
                clearAppCache(false);
                closeProfileMenu();
              }}
            >
              <RotateCcw size={15} />
              <span>Clear Cache & Re-sync</span>
            </button>

            <button
              class="dropdown-item"
              on:click={() => {
                $isDiagnosticsOpen = true;
                closeProfileMenu();
              }}
            >
              <Activity size={15} />
              <span>Diagnostics Log</span>
            </button>

            <div class="dropdown-divider"></div>

            <button class="dropdown-item logout-item" on:click={handleLogout}>
              <LogOut size={15} />
              <span>Sign Out</span>
            </button>
          </div>
        {/if}
      </div>
    </div>
  {/if}
</header>

<!-- Mobile Bottom Navigation Bar (Thumb Friendly) -->
<nav class="mobile-bottom-nav mobile-only">
  <button
    class="bottom-nav-item"
    class:active={$activeTab === 'home'}
    on:click={() => ($activeTab = 'home')}
  >
    <Home size={20} />
    <span>Home</span>
  </button>

  <button
    class="bottom-nav-item"
    class:active={$activeTab === 'movies'}
    on:click={() => ($activeTab = 'movies')}
  >
    <Film size={20} />
    <span>Movies</span>
  </button>

  <button
    class="bottom-nav-item"
    class:active={$activeTab === 'shows'}
    on:click={() => ($activeTab = 'shows')}
  >
    <Clapperboard size={20} />
    <span>Shows</span>
  </button>

  <!-- If Music library exists, add direct tab -->
  {#if musicViews.length > 0}
    <button
      class="bottom-nav-item"
      class:active={$activeTab === musicViews[0].Id}
      on:click={() => ($activeTab = musicViews[0].Id)}
    >
      <Music size={20} />
      <span>Music</span>
    </button>
  {/if}

  <button
    class="bottom-nav-item"
    class:active={$activeTab === 'favorites'}
    on:click={() => ($activeTab = 'favorites')}
  >
    <Sparkles size={20} />
    <span>Favs</span>
  </button>

  <button
    class="bottom-nav-item"
    class:active={$activeTab === 'search'}
    on:click={() => {
      $activeTab = 'search';
      showMobileSearch = true;
    }}
  >
    <Search size={20} />
    <span>Search</span>
  </button>
</nav>

<style>
  .navbar {
    position: sticky;
    top: 0;
    z-index: 50;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 20px;
    height: 64px;
    background-color: rgba(10, 14, 20, 0.88);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    border-bottom: 1px solid var(--border);
    gap: 16px;
  }

  .nav-left {
    display: flex;
    align-items: center;
    gap: 20px;
    min-width: 0;
    flex: 1;
    overflow: hidden;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
    user-select: none;
    flex-shrink: 0;
  }

  .logo-icon {
    width: 30px;
    height: 30px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .brand-svg {
    width: 100%;
    height: 100%;
  }

  .brand-text {
    display: flex;
    flex-direction: column;
  }

  .brand-name {
    font-size: 1.05rem;
    font-weight: 800;
    letter-spacing: -0.02em;
    color: #ffffff;
    line-height: 1.1;
  }

  .brand-sub {
    font-size: 0.65rem;
    color: var(--text-muted);
    font-weight: 500;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  .nav-links {
    display: flex;
    align-items: center;
    gap: 4px;
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .nav-links::-webkit-scrollbar {
    display: none;
  }

  .nav-link {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 7px 12px;
    font-size: 0.82rem;
    font-weight: 500;
    color: var(--text-secondary);
    border-radius: var(--radius-full);
    transition: all 0.15s ease;
    white-space: nowrap;
    flex-shrink: 0;
  }

  .nav-link:hover {
    color: #ffffff;
    background-color: rgba(255, 255, 255, 0.05);
  }

  .nav-link.active {
    color: #ffffff;
    background-color: rgba(255, 255, 255, 0.12);
    font-weight: 600;
  }

  .more-views-container {
    position: relative;
  }

  .more-btn {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .more-views-dropdown {
    position: absolute;
    top: calc(100% + 6px);
    left: 0;
    width: 200px;
    background-color: var(--bg-surface-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    box-shadow: var(--shadow-lg);
    padding: 6px;
    z-index: 60;
    animation: fadeIn 0.15s ease;
  }

  .nav-right {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-shrink: 0;
    margin-left: auto;
  }

  .search-box {
    position: relative;
    display: flex;
    align-items: center;
  }

  :global(.search-icon) {
    position: absolute;
    left: 12px;
    color: var(--text-muted);
    pointer-events: none;
  }

  .search-box input {
    width: 170px;
    height: 36px;
    padding: 0 30px 0 34px;
    font-size: 0.8rem;
    background-color: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-full);
    color: #ffffff;
    transition: all 0.2s ease;
  }

  .search-box input:focus {
    width: 220px;
    outline: none;
    border-color: var(--jf-blue);
    background-color: var(--bg-surface-elevated);
  }

  .clear-search {
    position: absolute;
    right: 10px;
    color: var(--text-muted);
    font-size: 1.1rem;
    line-height: 1;
    padding: 2px;
  }

  .btn-icon {
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    color: var(--text-secondary);
    background: transparent;
    transition: all 0.15s ease;
    flex-shrink: 0;
  }

  .btn-icon:hover {
    color: #ffffff;
    background-color: rgba(255, 255, 255, 0.08);
  }

  /* Sleek Cast Icon Button */
  .cast-icon-pill {
    position: relative;
    width: 36px;
    height: 36px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    color: var(--text-muted);
    cursor: pointer;
    transition: all 0.2s ease;
    flex-shrink: 0;
  }

  .cast-icon-pill:hover {
    color: #fff;
    background: var(--bg-surface-elevated);
  }

  .cast-icon-pill.cast-active {
    color: var(--jf-purple);
    border-color: rgba(122, 90, 248, 0.4);
    background: rgba(122, 90, 248, 0.12);
    box-shadow: 0 0 12px rgba(122, 90, 248, 0.3);
  }

  .pulsing-dot-inline {
    position: absolute;
    top: 4px;
    right: 4px;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background-color: var(--jf-purple);
    box-shadow: 0 0 8px var(--jf-purple);
    animation: pulse 1.8s infinite;
  }

  @keyframes pulse {
    0% { transform: scale(0.9); opacity: 0.8; }
    50% { transform: scale(1.3); opacity: 1; }
    100% { transform: scale(0.9); opacity: 0.8; }
  }

  .profile-container {
    position: relative;
    flex-shrink: 0;
  }

  .profile-btn {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 10px 4px 4px;
    border-radius: var(--radius-full);
    background-color: var(--bg-surface);
    border: 1px solid var(--border);
    transition: all 0.15s ease;
  }

  .profile-btn:hover {
    background-color: var(--bg-surface-elevated);
  }

  .avatar {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    background: linear-gradient(135deg, var(--jf-blue), var(--jf-purple));
    color: #ffffff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 0.75rem;
    font-weight: 700;
  }

  .server-badge {
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--text-primary);
  }

  .profile-dropdown {
    position: absolute;
    top: calc(100% + 8px);
    right: 0;
    width: 260px;
    background-color: var(--bg-surface-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg);
    padding: 8px;
    z-index: 60;
    animation: fadeIn 0.15s ease;
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(-6px); }
    to { opacity: 1; transform: translateY(0); }
  }

  .dropdown-header {
    padding: 10px 12px;
  }

  .active-badge-tag {
    font-size: 0.65rem;
    font-weight: 800;
    color: var(--jf-blue);
    letter-spacing: 0.06em;
    margin-bottom: 4px;
  }

  .user-title {
    font-size: 0.95rem;
    font-weight: 700;
    color: #ffffff;
  }

  .server-subtitle {
    font-size: 0.78rem;
    color: var(--text-secondary);
    margin-top: 2px;
  }

  .server-url-sub {
    font-size: 0.7rem;
    color: var(--text-muted);
    font-family: var(--font-mono);
    word-break: break-all;
    margin-top: 2px;
  }

  .section-label {
    font-size: 0.66rem;
    font-weight: 700;
    color: var(--text-muted);
    padding: 6px 12px 2px;
    letter-spacing: 0.05em;
  }

  .saved-accounts-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .account-item-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-radius: var(--radius-sm);
    transition: background 0.15s ease;
  }

  .account-item-row:hover {
    background: rgba(255, 255, 255, 0.06);
  }

  .account-switch-btn {
    display: flex;
    align-items: center;
    gap: 8px;
    flex: 1;
    padding: 6px 8px;
    text-align: left;
    min-width: 0;
  }

  .account-avatar-sm {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    color: var(--jf-blue);
    font-size: 0.65rem;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .account-meta {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .account-user {
    font-size: 0.8rem;
    font-weight: 600;
    color: #fff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .account-server {
    font-size: 0.68rem;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .account-del-btn {
    padding: 6px;
    color: var(--text-muted);
    border-radius: 50%;
    margin-right: 4px;
  }

  .account-del-btn:hover {
    color: #e74c3c;
    background: rgba(231, 76, 60, 0.15);
  }

  .dropdown-divider {
    height: 1px;
    background-color: var(--border);
    margin: 6px 0;
  }

  .dropdown-item {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    font-size: 0.82rem;
    color: var(--text-secondary);
    border-radius: var(--radius-sm);
    transition: all 0.15s ease;
  }

  .dropdown-item:hover {
    color: #ffffff;
    background-color: rgba(255, 255, 255, 0.06);
  }

  .dropdown-item.active-item {
    color: var(--jf-blue);
    font-weight: 600;
  }

  :global(.check-icon) {
    margin-left: auto;
    color: var(--jf-blue);
  }

  .logout-item:hover {
    color: var(--status-error);
    background-color: rgba(235, 87, 87, 0.1);
  }

  /* Mobile Search Bar */
  .mobile-search-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    height: 100%;
  }

  :global(.search-bar-icon) {
    color: var(--text-muted);
  }

  .mobile-search-bar input {
    flex: 1;
    height: 38px;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-full);
    padding: 0 14px;
    color: #fff;
    font-size: 0.9rem;
  }

  .clear-search-btn {
    padding: 6px;
    color: var(--text-muted);
  }

  .close-search-btn {
    font-size: 0.85rem;
    font-weight: 600;
    color: var(--jf-blue);
    padding: 6px 8px;
  }

  /* Mobile Bottom Nav */
  .mobile-bottom-nav {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: calc(56px + env(safe-area-inset-bottom, 0px));
    padding-bottom: env(safe-area-inset-bottom, 0px);
    background: rgba(10, 14, 20, 0.95);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-top: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: space-around;
    z-index: 40;
  }

  .bottom-nav-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    flex: 1;
    height: 100%;
    color: var(--text-muted);
    font-size: 0.68rem;
    font-weight: 500;
  }

  .bottom-nav-item.active {
    color: var(--jf-blue);
    font-weight: 700;
  }

  /* Responsive Rules */
  .desktop-only {
    display: flex;
  }
  .mobile-only {
    display: none;
  }

  @media (max-width: 768px) {
    .desktop-only {
      display: none !important;
    }
    .mobile-only {
      display: flex !important;
    }
    .navbar {
      padding: 0 16px;
      height: 56px;
    }
  }
</style>
