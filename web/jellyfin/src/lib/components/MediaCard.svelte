<script lang="ts">
  import type { JellyfinItem } from '../types';
  import {
    playInBrowser,
    playWithDirectCast,
    openItemDetail,
    resolveItemPosterUrl
  } from '../stores/appState';
  import { Play, Cast, Star, Music, Film, Clapperboard, Folder } from 'lucide-svelte';

  export let item: JellyfinItem;
  export let onOpenFolder: ((item: JellyfinItem) => void) | undefined = undefined;

  let imageError = false;

  $: isFolder = item.Type === 'Folder' || item.IsFolder;
  $: isEpisode = item.Type === 'Episode';
  $: isMusic = item.Type === 'Audio' || item.Type === 'MusicAlbum';
  $: posterUrl = isEpisode && item.backdropUrl ? item.backdropUrl : resolveItemPosterUrl(item);

  $: progressPercent =
    item.UserData?.PlaybackPositionTicks && item.RunTimeTicks
      ? Math.min(100, Math.round((item.UserData.PlaybackPositionTicks / item.RunTimeTicks) * 100))
      : 0;

  function handleCardClick() {
    if (isFolder && onOpenFolder) {
      onOpenFolder(item);
      return;
    }
    openItemDetail(item);
  }

  function handlePlayClick(e: MouseEvent | TouchEvent) {
    e.stopPropagation();
    playInBrowser(item);
  }

  function handleCastClick(e: MouseEvent | TouchEvent) {
    e.stopPropagation();
    playWithDirectCast(item);
  }
</script>

<div class="media-card" on:click={handleCardClick}>
  <div class="poster-container" class:music-aspect={isMusic} class:episode-aspect={isEpisode}>
    {#if posterUrl && !imageError}
      <img
        src={posterUrl}
        alt={item.Name}
        class="poster-img"
        loading="lazy"
        on:error={() => (imageError = true)}
      />
    {:else}
      <!-- Styled Fallback Placeholder -->
      <div class="placeholder-box">
        {#if isMusic}
          <Music size={32} class="placeholder-icon" />
        {:else if item.Type === 'Series' || isEpisode}
          <Clapperboard size={32} class="placeholder-icon" />
        {:else if item.Type === 'Folder'}
          <Folder size={32} class="placeholder-icon" />
        {:else}
          <Film size={32} class="placeholder-icon" />
        {/if}
        <span class="placeholder-title">{item.Name}</span>
      </div>
    {/if}

    <!-- Type Badges -->
    {#if item.Type === 'Series'}
      <span class="type-badge">Series</span>
    {:else if isEpisode}
      <span class="type-badge badge-ep">S{item.ParentIndexNumber || 1}:E{item.IndexNumber || 1}</span>
    {:else if item.Type === 'MusicAlbum'}
      <span class="type-badge badge-music">Album</span>
    {:else if item.Type === 'Audio'}
      <span class="type-badge badge-music">Track</span>
    {:else if item.Type === 'Folder'}
      <span class="type-badge badge-folder">Folder</span>
    {/if}

    <!-- Community Rating -->
    {#if item.CommunityRating}
      <div class="rating-badge">
        <Star size={10} fill="#f1c40f" stroke="#f1c40f" />
        <span>{item.CommunityRating.toFixed(1)}</span>
      </div>
    {/if}

    <!-- Desktop Hover Overlay -->
    <div class="desktop-overlay desktop-only">
      <button class="overlay-btn overlay-play" on:click={handlePlayClick} title="Play in Browser">
        <Play size={18} fill="currentColor" />
      </button>
      <button class="overlay-btn overlay-cast" on:click={handleCastClick} title="Direct Cast">
        <Cast size={18} />
      </button>
    </div>

    <!-- Mobile Quick Cast & Play Badges -->
    <div class="mobile-quick-actions mobile-only">
      <button class="mobile-tap-btn mobile-cast-btn" on:click={handleCastClick} title="Direct Cast">
        <Cast size={14} />
      </button>
      <button class="mobile-tap-btn mobile-play-btn" on:click={handlePlayClick} title="Play in Browser">
        <Play size={13} fill="currentColor" />
      </button>
    </div>

    <!-- Resume Playback Progress Bar -->
    {#if progressPercent > 0}
      <div class="progress-bar-bg">
        <div class="progress-bar-fill" style="width: {progressPercent}%"></div>
      </div>
    {/if}
  </div>

  <div class="card-info">
    {#if isEpisode && item.SeriesName}
      <h4 class="card-title" title={item.SeriesName}>{item.SeriesName}</h4>
      <div class="card-subtitle">
        <span class="ep-tag">S{item.ParentIndexNumber || 1}:E{item.IndexNumber || 1}</span>
        <span class="dot">&bull;</span>
        <span class="ep-name">{item.Name}</span>
      </div>
    {:else}
      <h4 class="card-title" title={item.Name}>{item.Name}</h4>
      <div class="card-subtitle">
        {#if item.AlbumArtist || (item.Artists && item.Artists.length > 0)}
          <span class="artist-name">{item.AlbumArtist || item.Artists?.[0]}</span>
        {:else}
          {#if item.ProductionYear}
            <span>{item.ProductionYear}</span>
          {/if}
          {#if item.OfficialRating}
            <span class="dot">&bull;</span>
            <span>{item.OfficialRating}</span>
          {/if}
          {#if item.Genres && item.Genres.length > 0}
            <span class="dot">&bull;</span>
            <span class="genre-text">{item.Genres[0]}</span>
          {/if}
        {/if}
      </div>
    {/if}
  </div>
</div>

<style>
  .media-card {
    display: flex;
    flex-direction: column;
    gap: 8px;
    cursor: pointer;
    user-select: none;
    position: relative;
    transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  }

  .media-card:hover {
    transform: translateY(-4px);
  }

  .poster-container {
    position: relative;
    width: 100%;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: var(--bg-card);
    border: 1px solid var(--border);
    box-shadow: var(--shadow-sm);
    transition: all 0.2s ease;
  }

  .poster-container.music-aspect {
    aspect-ratio: 1 / 1;
  }

  .poster-container.episode-aspect {
    aspect-ratio: 16 / 9;
  }

  .media-card:hover .poster-container {
    border-color: var(--border-focus);
    box-shadow: var(--shadow-md);
  }

  .poster-img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
    background: var(--bg-surface);
  }

  .placeholder-box {
    width: 100%;
    height: 100%;
    background: linear-gradient(135deg, rgba(255, 255, 255, 0.05) 0%, rgba(0, 164, 220, 0.08) 100%);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 12px;
    text-align: center;
  }

  :global(.placeholder-icon) {
    color: var(--jf-blue);
    opacity: 0.6;
  }

  .placeholder-title {
    font-size: 0.72rem;
    color: var(--text-muted);
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    word-break: break-word;
  }

  .type-badge {
    position: absolute;
    top: 8px;
    left: 8px;
    background: rgba(0, 0, 0, 0.75);
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
    color: #fff;
    font-size: 0.65rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    padding: 2px 6px;
    border-radius: var(--radius-xs);
    border: 1px solid rgba(255, 255, 255, 0.15);
  }

  .badge-ep {
    background: rgba(0, 164, 220, 0.85);
    border-color: rgba(0, 164, 220, 0.4);
  }

  .badge-music {
    background: rgba(122, 90, 248, 0.85);
    border-color: rgba(122, 90, 248, 0.4);
  }

  .badge-folder {
    background: rgba(0, 164, 220, 0.85);
    border-color: rgba(0, 164, 220, 0.4);
  }

  .ep-tag {
    font-weight: 700;
    color: var(--jf-blue);
  }

  .ep-name {
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .rating-badge {
    position: absolute;
    top: 8px;
    right: 8px;
    display: flex;
    align-items: center;
    gap: 3px;
    background: rgba(0, 0, 0, 0.75);
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
    padding: 2px 6px;
    border-radius: var(--radius-xs);
    font-size: 0.7rem;
    font-weight: 700;
    color: #fff;
    border: 1px solid rgba(255, 255, 255, 0.15);
  }

  .desktop-overlay {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.6);
    backdrop-filter: blur(2px);
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    opacity: 0;
    transition: opacity 0.2s ease;
  }

  .media-card:hover .desktop-overlay {
    opacity: 1;
  }

  .overlay-btn {
    width: 42px;
    height: 42px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: transform 0.15s ease, background-color 0.15s ease;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
  }

  .overlay-btn:hover {
    transform: scale(1.12);
  }

  .overlay-play {
    background: var(--jf-blue);
    color: #fff;
  }

  .overlay-cast {
    background: var(--accent-gradient);
    color: #fff;
  }

  /* Mobile Quick Actions (Always Visible on Mobile Cards) */
  .mobile-quick-actions {
    position: absolute;
    bottom: 6px;
    right: 6px;
    display: flex;
    gap: 6px;
    z-index: 5;
  }

  .mobile-tap-btn {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.5);
    border: 1px solid rgba(255, 255, 255, 0.2);
  }

  .mobile-cast-btn {
    background: var(--accent-gradient);
  }

  .mobile-play-btn {
    background: var(--jf-blue);
  }

  .progress-bar-bg {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    height: 4px;
    background: rgba(0, 0, 0, 0.5);
  }

  .progress-bar-fill {
    height: 100%;
    background: var(--jf-blue);
  }

  .card-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 0 2px;
  }

  .card-title {
    font-size: 0.88rem;
    font-weight: 600;
    color: var(--text-primary);
    line-height: 1.25;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .card-subtitle {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 0.75rem;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .artist-name {
    color: var(--jf-indigo);
    font-weight: 500;
  }

  .genre-text {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .dot {
    opacity: 0.5;
  }

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
    .card-title {
      font-size: 0.82rem;
    }
    .card-subtitle {
      font-size: 0.7rem;
    }
  }
</style>
