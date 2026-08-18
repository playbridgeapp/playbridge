<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import {
    activePlayer,
    playWithDirectCast,
    serverConfig,
    skipNextTrack,
    skipPrevTrack,
    resolveItemStreamUrl,
    resolveItemPosterUrl,
    resolveItemBackdropUrl,
    isShuffle,
    repeatMode,
    isQueueDrawerOpen,
    toggleShuffle,
    cycleRepeatMode,
    toggleFavorite
  } from '../stores/appState';
  import {
    bridgeStatus,
    activeLinkedSession,
    addDiagnosticLog
  } from '../cast/playbridge';
  import * as jfApi from '../api/jellyfin';
  import {
    Play,
    Pause,
    RotateCcw,
    RotateCw,
    Volume2,
    VolumeX,
    Maximize,
    Minimize,
    Cast,
    X,
    ChevronUp,
    ChevronDown,
    SkipForward,
    SkipBack,
    Music,
    Shuffle,
    Repeat,
    Repeat1,
    ListMusic,
    Heart,
    Loader2,
    Zap
  } from 'lucide-svelte';

  let moviEl: any;
  let videoEl: HTMLVideoElement;
  let audioEl: HTMLAudioElement;
  let prebufferAudioEl: HTMLAudioElement;
  let playerContainer: HTMLDivElement;

  let isMoviLoaded = false;
  let isMoviLoading = false;
  let moviSupported = true;
  let warmedUrl = '';

  let isPlaying = false;
  let currentTime = 0;
  let duration = 0;
  let volume = 1;
  let isMuted = false;
  let isFullscreen = false;
  let showControls = true;
  let hideControlsTimer: any;
  let reportProgressTimer: any;

  $: playerState = $activePlayer;
  $: isCastingActive = playerState.isCasting;
  $: isAudio = playerState.item?.Type === 'Audio';
  $: hasPlaylist = (playerState.playlist?.length ?? 0) > 1;
  $: isFavorite = !!playerState.item?.UserData?.IsFavorite;
  $: posterUrl = playerState.item ? resolveItemPosterUrl(playerState.item) : '';
  $: backdropUrl = playerState.item ? resolveItemBackdropUrl(playerState.item) : '';
  $: authHeadersJson = JSON.stringify({
    'X-Emby-Authorization': jfApi.getAuthHeader($serverConfig.token)
  });

  // Next Track / Item in playlist queue for pre-buffering
  $: nextItem = (hasPlaylist && playerState.playlist && playerState.currentIndex != null)
    ? playerState.playlist[playerState.currentIndex + 1]
    : null;
  $: nextStreamUrl = nextItem ? resolveItemStreamUrl(nextItem) : '';
  $: nextPosterUrl = nextItem ? resolveItemPosterUrl(nextItem) : '';

  // Pre-load next artwork image into browser cache
  $: if (nextPosterUrl && typeof Image !== 'undefined') {
    const img = new Image();
    img.src = nextPosterUrl;
  }

  // Lazy-load movi-player on demand when video playback starts
  $: if (playerState.isOpen && !isAudio && !isCastingActive && !isMoviLoaded && moviSupported && typeof window !== 'undefined') {
    ensureMoviPlayer();
  }

  async function ensureMoviPlayer() {
    if (typeof window === 'undefined') return;
    if (customElements.get('movi-player')) {
      isMoviLoaded = true;
      return true;
    }
    isMoviLoading = true;
    try {
      // Dynamic on-demand code splitting chunk import
      await import('movi-player/element');
      isMoviLoaded = true;
      return true;
    } catch (err) {
      console.warn('Movi player load failed or not supported, falling back to native video:', err);
      moviSupported = false;
      return false;
    } finally {
      isMoviLoading = false;
    }
  }

  // Pre-buffer next media stream headers & first 1MB chunk into browser HTTP cache
  function warmNextMedia(url: string) {
    if (!url || warmedUrl === url || typeof fetch === 'undefined') return;
    warmedUrl = url;
    try {
      fetch(url, {
        method: 'GET',
        headers: {
          Range: 'bytes=0-1048576',
          'X-Emby-Authorization': jfApi.getAuthHeader($serverConfig.token)
        }
      }).catch(() => {});
    } catch {}
  }

  onMount(() => {
    document.addEventListener('fullscreenchange', handleFullscreenChange);
  });

  onDestroy(() => {
    document.removeEventListener('fullscreenchange', handleFullscreenChange);
    clearInterval(reportProgressTimer);
    clearTimeout(hideControlsTimer);
  });

  function handleFullscreenChange() {
    isFullscreen = !!document.fullscreenElement;
  }

  function togglePlay() {
    if (isCastingActive) {
      isPlaying = !isPlaying;
      addDiagnosticLog('info', `Cast Remote: Toggle Play (${isPlaying ? 'Playing' : 'Paused'})`);
      return;
    }
    if (isAudio && audioEl) {
      if (audioEl.paused) audioEl.play().catch(() => {});
      else audioEl.pause();
      return;
    }
    if (moviEl) {
      if (moviEl.paused) moviEl.play().catch(() => {});
      else moviEl.pause();
      return;
    }
    if (videoEl) {
      if (videoEl.paused) videoEl.play().catch(() => {});
      else videoEl.pause();
    }
  }

  function handleMediaEnded() {
    addDiagnosticLog('info', `Playback ended for: ${playerState.title}`);
    skipNextTrack();
  }

  function handleTimeUpdate() {
    if (isAudio && audioEl) {
      currentTime = audioEl.currentTime;
      duration = audioEl.duration || 0;
    } else if (moviEl) {
      currentTime = moviEl.currentTime || 0;
      duration = moviEl.duration || 0;
    } else if (videoEl) {
      currentTime = videoEl.currentTime || 0;
      duration = videoEl.duration || 0;
    }

    // Pre-buffer next playlist item when approaching the end of current track or after 5 seconds of stable playback
    if (nextStreamUrl && (currentTime > 5 || (duration > 0 && duration - currentTime <= 20))) {
      warmNextMedia(nextStreamUrl);
    }
  }

  function handleSeek(e: Event) {
    const target = e.target as HTMLInputElement;
    const seekTime = parseFloat(target.value);
    currentTime = seekTime;
    if (isAudio && audioEl) {
      audioEl.currentTime = seekTime;
    } else if (moviEl) {
      moviEl.currentTime = seekTime;
    } else if (videoEl) {
      videoEl.currentTime = seekTime;
    }
  }

  function skip(seconds: number) {
    if (isAudio && audioEl) {
      audioEl.currentTime = Math.max(0, Math.min(duration, audioEl.currentTime + seconds));
    } else if (moviEl) {
      moviEl.currentTime = Math.max(0, Math.min(duration, moviEl.currentTime + seconds));
    } else if (videoEl) {
      videoEl.currentTime = Math.max(0, Math.min(duration, videoEl.currentTime + seconds));
    }
  }

  function toggleMute() {
    if (isAudio && audioEl) {
      audioEl.muted = !audioEl.muted;
      isMuted = audioEl.muted;
    } else if (moviEl) {
      moviEl.muted = !moviEl.muted;
      isMuted = moviEl.muted;
    } else if (videoEl) {
      videoEl.muted = !videoEl.muted;
      isMuted = videoEl.muted;
    }
  }

  function handleVolumeChange(e: Event) {
    const target = e.target as HTMLInputElement;
    volume = parseFloat(target.value);
    if (isAudio && audioEl) {
      audioEl.volume = volume;
      audioEl.muted = volume === 0;
      isMuted = audioEl.muted;
    } else if (moviEl) {
      moviEl.volume = volume;
      moviEl.muted = volume === 0;
      isMuted = moviEl.muted;
    } else if (videoEl) {
      videoEl.volume = volume;
      videoEl.muted = volume === 0;
      isMuted = videoEl.muted;
    }
  }

  function toggleFullscreen() {
    if (!playerContainer) return;
    if (!document.fullscreenElement) {
      playerContainer.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  }

  function handleMouseMove() {
    showControls = true;
    clearTimeout(hideControlsTimer);
    hideControlsTimer = setTimeout(() => {
      if (isPlaying) {
        showControls = false;
      }
    }, 3500);
  }

  function minimizePlayer() {
    activePlayer.update((s) => ({ ...s, isExpanded: false }));
  }

  function expandPlayer() {
    activePlayer.update((s) => ({ ...s, isExpanded: true }));
  }

  function openQueue() {
    $isQueueDrawerOpen = true;
  }

  function closePlayer() {
    if (audioEl) audioEl.pause();
    if (moviEl) moviEl.pause();
    if (videoEl) videoEl.pause();

    if (!$serverConfig.isDemo && playerState.item && $serverConfig.url) {
      const ticks = Math.round(currentTime * 1000 * 10000);
      jfApi.reportPlaybackStopped($serverConfig.url, $serverConfig.token, playerState.item.Id, ticks);
    }

    activePlayer.set({
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
  }

  function triggerDirectCast() {
    if (playerState.item) {
      if (audioEl) audioEl.pause();
      if (moviEl) moviEl.pause();
      if (videoEl) videoEl.pause();
      playWithDirectCast(playerState.item);
    }
  }

  function disconnectCast() {
    if ($activeLinkedSession) {
      $activeLinkedSession.unlink().catch(() => {});
      $activeLinkedSession = null;
    }
    activePlayer.update((state) => ({
      ...state,
      isCasting: false,
      isLinkedCast: false
    }));
    if (isAudio && audioEl) {
      audioEl.play().catch(() => {});
    } else if (moviEl) {
      moviEl.play().catch(() => {});
    } else if (videoEl) {
      videoEl.play().catch(() => {});
    }
  }

  function formatTime(seconds: number): string {
    if (isNaN(seconds) || seconds < 0) return '0:00';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    if (h > 0) {
      return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    }
    return `${m}:${s.toString().padStart(2, '0')}`;
  }
</script>

{#if playerState.isOpen}
  <!-- ==================== ACTIVE & PRE-BUFFER AUDIO ELEMENTS ==================== -->
  {#if isAudio && !isCastingActive}
    <audio
      bind:this={audioEl}
      src={playerState.streamUrl}
      autoplay
      on:play={() => (isPlaying = true)}
      on:pause={() => (isPlaying = false)}
      on:timeupdate={handleTimeUpdate}
      on:ended={handleMediaEnded}
      class="hidden-audio-el"
    ></audio>

    <!-- Background Pre-Buffer Audio Element for Gapless Next Track Transition -->
    {#if nextStreamUrl}
      <audio
        bind:this={prebufferAudioEl}
        src={nextStreamUrl}
        preload="auto"
        class="hidden-audio-el"
      ></audio>
    {/if}
  {/if}

  <!-- ==================== MINI PLAYER BAR (Bottom Floating Dock) ==================== -->
  {#if !playerState.isExpanded}
    <div class="mini-player-bar" on:click={expandPlayer}>
      <!-- Top Progress Line -->
      {#if duration > 0}
        <div class="mini-progress-track">
          <div class="mini-progress-fill" style="width: {(currentTime / duration) * 100}%"></div>
        </div>
      {/if}

      <!-- Left Media Info -->
      <div class="mini-left">
        {#if posterUrl}
          <div class="mini-thumb-wrapper">
            <img src={posterUrl} alt={playerState.title} class="mini-thumb" />
          </div>
        {/if}

        <div class="mini-meta">
          <div class="mini-title-row">
            <span class="mini-title">{playerState.title}</span>
            {#if isCastingActive}
              <div class="mini-cast-indicator" title="Casting to PlayBridge">
                <Cast size={15} class="cast-glow-icon" />
              </div>
            {/if}
          </div>
          {#if playerState.item?.AlbumArtist || (playerState.item?.Artists && playerState.item.Artists.length > 0)}
            <span class="mini-sub">{playerState.item.AlbumArtist || playerState.item.Artists?.[0]}</span>
          {:else if playerState.season && playerState.episode}
            <span class="mini-sub">S{playerState.season}E{playerState.episode}</span>
          {:else}
            <span class="mini-sub">{isCastingActive ? 'PlayBridge Receiver' : (isAudio ? 'Audio Player' : 'Video Player')}</span>
          {/if}
        </div>
      </div>

      <!-- Right Controls -->
      <div class="mini-controls" on:click|stopPropagation>
        {#if playerState.item}
          <button
            class="mini-btn"
            class:active-fav={isFavorite}
            on:click={() => playerState.item && toggleFavorite(playerState.item)}
            title="Favorite"
          >
            <Heart size={16} fill={isFavorite ? '#e74c3c' : 'none'} color={isFavorite ? '#e74c3c' : 'currentColor'} />
          </button>
        {/if}

        {#if hasPlaylist}
          <button class="mini-btn" on:click={skipPrevTrack} title="Previous track">
            <SkipBack size={17} />
          </button>
        {/if}

        <button class="mini-btn mini-play-btn" on:click={togglePlay} title={isPlaying ? 'Pause' : 'Play'}>
          {#if isPlaying}
            <Pause size={18} fill="currentColor" />
          {:else}
            <Play size={18} fill="currentColor" />
          {/if}
        </button>

        {#if hasPlaylist}
          <button class="mini-btn" on:click={skipNextTrack} title="Next track">
            <SkipForward size={17} />
          </button>
        {/if}

        {#if hasPlaylist}
          <button class="mini-btn" on:click={openQueue} title="Queue & Lyrics">
            <ListMusic size={17} />
          </button>
        {/if}

        <button class="mini-btn mini-expand-btn" on:click={expandPlayer} title="Expand player">
          <ChevronUp size={18} />
        </button>

        <button class="mini-btn mini-close-btn" on:click={closePlayer} title="Stop & close">
          <X size={18} />
        </button>
      </div>
    </div>
  {/if}

  <!-- ==================== FULL EXPANDED PLAYER OVERLAY ==================== -->
  {#if playerState.isExpanded}
    <div
      class="player-overlay"
      bind:this={playerContainer}
      on:mousemove={handleMouseMove}
    >
      <!-- Top Header Bar Overlay -->
      <div class="player-top-bar" class:visible={showControls || !isPlaying || isCastingActive}>
        <button class="icon-btn-large" on:click={minimizePlayer} title="Minimize to mini-player (keep browsing)">
          <ChevronDown size={22} />
        </button>

        <div class="title-group">
          <h3 class="playing-title">{playerState.title}</h3>
          {#if playerState.season && playerState.episode}
            <span class="playing-sub">Season {playerState.season} &bull; Episode {playerState.episode}</span>
          {:else if playerState.item?.AlbumArtist || (playerState.item?.Artists && playerState.item.Artists.length > 0)}
            <span class="playing-sub">{playerState.item.AlbumArtist || playerState.item.Artists?.[0]}</span>
          {/if}
        </div>

        <div class="top-actions">
          {#if hasPlaylist}
            <button class="queue-counter-btn" on:click={openQueue} title="View Queue & Lyrics">
              <ListMusic size={16} />
              <span>{((playerState.currentIndex ?? 0) + 1)} / {playerState.playlist?.length}</span>
            </button>
          {/if}

          {#if !isCastingActive}
            <button class="cast-btn" on:click={triggerDirectCast} title="Switch to PlayBridge Casting">
              <Cast size={16} />
              <span>Cast to TV</span>
            </button>
          {:else}
            <button class="cast-btn active-cast" on:click={disconnectCast} title="Disconnect cast">
              <Cast size={16} />
              <span>Casting Active</span>
            </button>
          {/if}

          <button class="icon-btn-large" on:click={closePlayer} title="Stop and Close Player">
            <X size={22} />
          </button>
        </div>
      </div>

      <!-- Center Player Body -->
      {#if isCastingActive}
        <!-- Cast HUD (Playing on TV Receiver) -->
        <div class="cast-hud-container">
          <div class="cast-poster-card">
            {#if posterUrl}
              <img src={posterUrl} alt={playerState.title} class="cast-hud-poster" />
            {/if}
            <div class="cast-hud-glow"></div>
          </div>

          <div class="cast-status-card">
            <div class="cast-signal-row">
              <div class="tv-icon-wrapper">
                <Cast size={32} class="tv-icon" />
                <span class="radar-pulse"></span>
              </div>
              <div class="signal-details">
                <h4>Streaming to PlayBridge Receiver</h4>
                <p class="signal-url">{playerState.streamUrl}</p>
              </div>
            </div>

            <div class="cast-actions-row">
              <button class="btn-accent" on:click={minimizePlayer}>
                <span>Browse Library</span>
              </button>
              <button class="btn-secondary" on:click={disconnectCast}>
                <span>Play on This Screen</span>
              </button>
            </div>
          </div>
        </div>
      {:else if isAudio}
        <!-- Audio Fullscreen Player HUD (Finamp Aesthetic) -->
        <div class="audio-hud-container">
          {#if backdropUrl}
            <div class="audio-bg-blur" style="background-image: url('{backdropUrl}')"></div>
          {/if}

          <div class="audio-card">
            <div class="audio-artwork-wrapper">
              {#if posterUrl}
                <img src={posterUrl} alt={playerState.title} class="audio-artwork" />
              {:else}
                <div class="audio-placeholder">
                  <Music size={64} />
                </div>
              {/if}
            </div>

            <div class="audio-meta">
              <div class="audio-title-fav-row">
                <h2 class="audio-title">{playerState.title}</h2>
                {#if playerState.item}
                  <button
                    class="fav-btn-round"
                    class:active-fav={isFavorite}
                    on:click={() => playerState.item && toggleFavorite(playerState.item)}
                    title="Favorite"
                  >
                    <Heart size={20} fill={isFavorite ? '#e74c3c' : 'none'} color={isFavorite ? '#e74c3c' : 'currentColor'} />
                  </button>
                {/if}
              </div>
              <p class="audio-artist">
                {playerState.item?.AlbumArtist || playerState.item?.Artists?.[0] || 'Unknown Artist'}
              </p>
              {#if playerState.item?.Album}
                <p class="audio-album">{playerState.item.Album}</p>
              {/if}

              <!-- Pre-buffer / Up Next indicator -->
              {#if nextItem}
                <div class="next-up-indicator">
                  <Zap size={12} class="zap-icon" />
                  <span>Next: {nextItem.Name}</span>
                </div>
              {/if}
            </div>

            <!-- Scrubber -->
            <div class="audio-scrubber-box">
              <input
                type="range"
                min="0"
                max={duration || 100}
                value={currentTime}
                on:input={handleSeek}
                class="scrubber-range"
              />
              <div class="time-display">
                <span>{formatTime(currentTime)}</span>
                <span>{formatTime(duration)}</span>
              </div>
            </div>

            <!-- Finamp Music Controls: Shuffle, Prev, Play, Next, Repeat -->
            <div class="audio-controls-row">
              <button
                class="mode-icon-btn"
                class:active-mode={$isShuffle}
                on:click={toggleShuffle}
                title="Shuffle"
              >
                <Shuffle size={20} />
              </button>

              {#if hasPlaylist}
                <button class="control-icon-btn" on:click={skipPrevTrack} title="Previous track">
                  <SkipBack size={24} />
                </button>
              {/if}

              <button class="play-toggle-btn-large" on:click={togglePlay}>
                {#if isPlaying}
                  <Pause size={28} fill="currentColor" />
                {:else}
                  <Play size={28} fill="currentColor" />
                {/if}
              </button>

              {#if hasPlaylist}
                <button class="control-icon-btn" on:click={skipNextTrack} title="Next track">
                  <SkipForward size={24} />
                </button>
              {/if}

              <button
                class="mode-icon-btn"
                class:active-mode={$repeatMode !== 'off'}
                on:click={cycleRepeatMode}
                title="Repeat Mode ({$repeatMode})"
              >
                {#if $repeatMode === 'one'}
                  <Repeat1 size={20} />
                {:else}
                  <Repeat size={20} />
                {/if}
              </button>
            </div>
          </div>
        </div>
      {:else}
        <!-- Video Player: Dynamic Movi Player with Native Hardware Video Fallback -->
        {#if isMoviLoading}
          <div class="video-loading-box">
            <Loader2 size={40} class="spinner" />
            <p>Initializing high-performance player engine...</p>
          </div>
        {:else if isMoviLoaded && moviSupported}
          <div class="movi-player-wrapper">
            <movi-player
              bind:this={moviEl}
              src={playerState.streamUrl}
              poster={posterUrl}
              title={playerState.title}
              controls
              autoplay
              playsinline
              theme="dark"
              themecolor="#00A4DC #7A5AF8"
              ambientmode
              headers={authHeadersJson}
              on:play={() => (isPlaying = true)}
              on:pause={() => (isPlaying = false)}
              on:timeupdate={handleTimeUpdate}
              on:ended={handleMediaEnded}
              class="movi-element"
            ></movi-player>
          </div>
        {:else}
          <div class="video-player-container">
            <video
              bind:this={videoEl}
              src={playerState.streamUrl}
              poster={posterUrl}
              autoplay
              playsinline
              controls
              on:play={() => (isPlaying = true)}
              on:pause={() => (isPlaying = false)}
              on:timeupdate={handleTimeUpdate}
              on:ended={handleMediaEnded}
              class="native-video-el"
            >
              <track kind="captions" />
            </video>
          </div>
        {/if}
      {/if}
    </div>
  {/if}
{/if}

<style>
  .hidden-audio-el {
    display: none;
  }

  /* ==================== MINI PLAYER BAR ==================== */
  .mini-player-bar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 64px;
    background: rgba(14, 17, 24, 0.95);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-top: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 18px;
    z-index: 45;
    cursor: pointer;
    box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.5);
    transition: transform 0.2s ease;
  }

  .mini-progress-track {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 2px;
    background: rgba(255, 255, 255, 0.1);
  }

  .mini-progress-fill {
    height: 100%;
    background: var(--jf-blue);
  }

  .mini-left {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
    flex: 1;
  }

  .mini-thumb-wrapper {
    position: relative;
    width: 42px;
    height: 42px;
    border-radius: var(--radius-sm);
    overflow: hidden;
    flex-shrink: 0;
    background: var(--bg-card);
    border: 1px solid var(--border);
  }

  .mini-thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .mini-meta {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .mini-title-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .mini-title {
    font-size: 0.88rem;
    font-weight: 600;
    color: #fff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .mini-cast-indicator {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: var(--jf-purple);
    filter: drop-shadow(0 0 6px rgba(122, 90, 248, 0.8));
    animation: pulseCast 2s infinite ease-in-out;
  }

  @keyframes pulseCast {
    0% { transform: scale(0.95); opacity: 0.8; }
    50% { transform: scale(1.1); opacity: 1; }
    100% { transform: scale(0.95); opacity: 0.8; }
  }

  .mini-sub {
    font-size: 0.74rem;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 1px;
  }

  .mini-controls {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .mini-btn {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-secondary);
    background: var(--bg-surface);
    border: 1px solid var(--border);
  }

  .mini-btn:hover {
    color: #fff;
    background: var(--bg-surface-elevated);
  }

  .mini-btn.active-fav {
    color: #e74c3c;
    border-color: rgba(231, 76, 60, 0.3);
  }

  .mini-play-btn {
    background: var(--jf-blue);
    color: #fff;
    border-color: transparent;
  }

  .mini-play-btn:hover {
    filter: brightness(1.1);
  }

  .mini-close-btn:hover {
    background: var(--status-error);
    border-color: transparent;
    color: #fff;
  }

  @media (max-width: 768px) {
    .mini-player-bar {
      bottom: calc(56px + env(safe-area-inset-bottom, 0px));
      height: 56px;
      padding: 0 12px;
    }
    .mini-thumb-wrapper {
      width: 36px;
      height: 36px;
    }
    .mini-expand-btn {
      display: none;
    }
  }

  /* ==================== FULL EXPANDED PLAYER OVERLAY ==================== */
  .player-overlay {
    position: fixed;
    inset: 0;
    background: #000000;
    z-index: 100;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    overflow: hidden;
  }

  .player-top-bar {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    padding: 16px 20px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: linear-gradient(to bottom, rgba(0, 0, 0, 0.85) 0%, transparent 100%);
    z-index: 30;
    opacity: 0;
    transition: opacity 0.3s ease;
    pointer-events: none;
  }

  .player-top-bar.visible {
    opacity: 1;
    pointer-events: auto;
  }

  .title-group {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    max-width: 50%;
  }

  .playing-title {
    font-size: 1.05rem;
    font-weight: 700;
    color: #fff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .playing-sub {
    font-size: 0.76rem;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .top-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .queue-counter-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    background: rgba(255, 255, 255, 0.1);
    color: var(--text-secondary);
    padding: 6px 10px;
    border-radius: var(--radius-full);
    font-size: 0.75rem;
    font-weight: 600;
    border: 1px solid rgba(255, 255, 255, 0.12);
  }

  .queue-counter-btn:hover {
    background: rgba(255, 255, 255, 0.2);
    color: #fff;
  }

  .icon-btn-large {
    width: 38px;
    height: 38px;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.12);
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid rgba(255, 255, 255, 0.15);
  }

  .icon-btn-large:hover {
    background: rgba(255, 255, 255, 0.25);
  }

  .cast-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 7px 12px;
    border-radius: var(--radius-full);
    font-size: 0.8rem;
    font-weight: 600;
    background: rgba(255, 255, 255, 0.12);
    color: #fff;
    border: 1px solid rgba(255, 255, 255, 0.18);
  }

  .cast-btn.active-cast {
    background: rgba(122, 90, 248, 0.25);
    border-color: var(--jf-purple);
    color: var(--jf-indigo);
  }

  /* Movi Player Element Container */
  .movi-player-wrapper {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    background: #000;
  }

  :global(movi-player) {
    width: 100% !important;
    height: 100% !important;
    display: block;
  }

  .video-loading-box {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 16px;
    color: var(--text-secondary);
    background: #000;
  }

  .spinner {
    animation: spin 1s linear infinite;
    color: var(--jf-blue);
  }

  @keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
  }

  /* Video Player Container */
  .video-player-container {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    background: #000;
  }

  .native-video-el {
    width: 100%;
    height: 100%;
    max-height: 100vh;
    object-fit: contain;
  }

  /* Audio HUD Fullscreen Player */
  .audio-hud-container {
    position: relative;
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 40px 20px;
    overflow: hidden;
  }

  .audio-bg-blur {
    position: absolute;
    inset: -40px;
    background-size: cover;
    background-position: center;
    filter: blur(50px) brightness(0.25);
    transform: scale(1.1);
  }

  .audio-card {
    position: relative;
    z-index: 10;
    max-width: 420px;
    width: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 20px;
    background: rgba(20, 24, 33, 0.85);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border: 1px solid var(--border);
    border-radius: var(--radius-xl);
    padding: 30px 28px;
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.7);
  }

  .audio-artwork-wrapper {
    width: 200px;
    height: 200px;
    border-radius: var(--radius-lg);
    overflow: hidden;
    border: 1px solid var(--border);
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.6);
  }

  .audio-artwork {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .audio-placeholder {
    width: 100%;
    height: 100%;
    background: var(--bg-surface);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--jf-blue);
  }

  .audio-meta {
    text-align: center;
    display: flex;
    flex-direction: column;
    gap: 4px;
    width: 100%;
  }

  .audio-title-fav-row {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
  }

  .audio-title {
    font-size: 1.25rem;
    font-weight: 700;
    color: #fff;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .fav-btn-round {
    padding: 6px;
    border-radius: 50%;
    color: var(--text-muted);
  }

  .fav-btn-round:hover {
    color: #fff;
  }

  .fav-btn-round.active-fav {
    color: #e74c3c;
  }

  .audio-artist {
    font-size: 0.92rem;
    color: var(--jf-indigo);
    font-weight: 600;
  }

  .audio-album {
    font-size: 0.78rem;
    color: var(--text-muted);
  }

  .next-up-indicator {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
    font-size: 0.72rem;
    font-weight: 600;
    color: var(--jf-blue);
    background: rgba(0, 164, 220, 0.12);
    border: 1px solid rgba(0, 164, 220, 0.25);
    padding: 3px 10px;
    border-radius: var(--radius-full);
    margin-top: 4px;
    align-self: center;
  }

  :global(.zap-icon) {
    animation: zapPulse 1.5s infinite ease-in-out;
  }

  @keyframes zapPulse {
    0%, 100% { transform: scale(1); opacity: 0.8; }
    50% { transform: scale(1.2); opacity: 1; }
  }

  .audio-scrubber-box {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .scrubber-range {
    width: 100%;
    height: 4px;
    background: rgba(255, 255, 255, 0.2);
    border-radius: var(--radius-full);
    outline: none;
    cursor: pointer;
  }

  .time-display {
    display: flex;
    justify-content: space-between;
    font-size: 0.75rem;
    color: var(--text-secondary);
    font-family: var(--font-mono);
  }

  .audio-controls-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    padding: 0 8px;
  }

  .mode-icon-btn {
    padding: 8px;
    border-radius: 50%;
    color: var(--text-muted);
    background: transparent;
  }

  .mode-icon-btn:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.08);
  }

  .mode-icon-btn.active-mode {
    color: var(--jf-blue);
    background: rgba(0, 164, 220, 0.15);
  }

  .control-icon-btn {
    color: #fff;
    padding: 8px;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.08);
  }

  .control-icon-btn:hover {
    color: var(--jf-blue);
    background: rgba(255, 255, 255, 0.15);
  }

  .play-toggle-btn-large {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    background: var(--jf-blue);
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 4px 16px rgba(0, 164, 220, 0.4);
    transition: transform 0.15s ease;
  }

  .play-toggle-btn-large:hover {
    transform: scale(1.08);
  }

  /* Cast HUD */
  .cast-hud-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 28px;
    padding: 32px 20px;
  }

  .cast-poster-card {
    position: relative;
    width: 180px;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-lg);
    overflow: hidden;
    box-shadow: 0 16px 40px rgba(0, 0, 0, 0.8);
    border: 1px solid var(--border);
  }

  .cast-hud-poster {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .cast-status-card {
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 24px 28px;
    max-width: 480px;
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .cast-signal-row {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .tv-icon-wrapper {
    position: relative;
    width: 54px;
    height: 54px;
    border-radius: var(--radius-md);
    background: rgba(122, 90, 248, 0.15);
    border: 1px solid rgba(122, 90, 248, 0.3);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--jf-purple);
  }

  .radar-pulse {
    position: absolute;
    inset: -6px;
    border-radius: var(--radius-md);
    border: 2px solid var(--jf-purple);
    animation: radarPulse 2s infinite;
    opacity: 0;
  }

  @keyframes radarPulse {
    0% { transform: scale(0.9); opacity: 0.8; }
    100% { transform: scale(1.3); opacity: 0; }
  }

  .signal-details h4 {
    font-size: 1rem;
    font-weight: 700;
    color: #fff;
  }

  .signal-url {
    font-size: 0.74rem;
    color: var(--text-muted);
    font-family: var(--font-mono);
    word-break: break-all;
    margin-top: 2px;
  }

  .cast-actions-row {
    display: flex;
    gap: 10px;
  }

  .cast-actions-row button {
    flex: 1;
  }
</style>
