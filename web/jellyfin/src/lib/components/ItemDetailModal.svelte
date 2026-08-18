<script lang="ts">
  import type { JellyfinItem, JellyfinSeason } from '../types';
  import {
    detailModalItem,
    playInBrowser,
    playWithDirectCast,
    playWithLinkedQueue,
    playFolderOrAlbumWithCast,
    shufflePlay,
    shuffleCast,
    toggleFavorite,
    resolveItemPosterUrl,
    resolveItemBackdropUrl
  } from '../stores/appState';
  import { bridgeStatus } from '../cast/playbridge';
  import {
    X,
    Play,
    Cast,
    ListPlus,
    Star,
    Calendar,
    Clock,
    Music,
    Folder,
    Disc,
    User,
    Shuffle,
    Heart
  } from 'lucide-svelte';

  $: item = $detailModalItem;
  let activeSeasonIndex = 0;

  function close() {
    $detailModalItem = null;
  }

  function formatRuntime(ticks?: number): string {
    if (!ticks) return '';
    const totalSecs = Math.round(ticks / (10000 * 1000));
    const mins = Math.floor(totalSecs / 60);
    const secs = totalSecs % 60;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    if (h > 0) return `${h}h ${m}m`;
    return `${m}m ${secs > 0 ? secs + 's' : ''}`;
  }

  $: posterUrl = item ? resolveItemPosterUrl(item) : '';
  $: backdropUrl = item ? resolveItemBackdropUrl(item) : '';
</script>

{#if item}
  <div class="modal-backdrop" on:click={close}>
    <div class="modal-container" on:click|stopPropagation>
      <!-- Close Button -->
      <button class="modal-close-btn" on:click={close} aria-label="Close modal">
        <X size={20} />
      </button>

      <!-- Hero Header Banner with dynamic blurred backdrop -->
      <div class="modal-hero">
        <div
          class="modal-hero-bg"
          style="background-image: url('{backdropUrl || posterUrl}')"
        ></div>
        <div class="modal-hero-gradient"></div>

        <div class="modal-hero-content">
          <!-- Poster -->
          <div class="hero-poster-wrapper" class:music-aspect={item.Type === 'Audio' || item.Type === 'MusicAlbum'}>
            <img src={posterUrl} alt={item.Name} class="hero-poster-img" />
          </div>

          <!-- Main Info -->
          <div class="hero-meta">
            <div class="type-pill">
              {item.Type}
            </div>

            <div class="title-fav-row">
              <h1 class="item-title">{item.Name}</h1>
              <button
                class="fav-btn"
                class:active-fav={item.UserData?.IsFavorite}
                on:click={() => item && toggleFavorite(item)}
                title="Toggle Favorite"
              >
                <Heart size={22} fill={item.UserData?.IsFavorite ? '#e74c3c' : 'none'} color={item.UserData?.IsFavorite ? '#e74c3c' : 'currentColor'} />
              </button>
            </div>

            {#if item.ProductionYear || item.OfficialRating || item.RunTimeTicks}
              <div class="meta-row">
                {#if item.ProductionYear}
                  <span class="meta-badge">
                    <Calendar size={13} />
                    {item.ProductionYear}
                  </span>
                {/if}
                {#if item.OfficialRating}
                  <span class="meta-badge rating-badge">{item.OfficialRating}</span>
                {/if}
                {#if item.CommunityRating}
                  <span class="meta-badge">
                    <Star size={13} fill="#f1c40f" stroke="#f1c40f" />
                    {item.CommunityRating.toFixed(1)}
                  </span>
                {/if}
                {#if item.RunTimeTicks}
                  <span class="meta-badge">
                    <Clock size={13} />
                    {formatRuntime(item.RunTimeTicks)}
                  </span>
                {/if}
              </div>
            {/if}

            {#if item.AlbumArtist || (item.Artists && item.Artists.length > 0)}
              <p class="item-artist">
                <User size={13} />
                <span>{item.AlbumArtist || item.Artists?.[0]}</span>
              </p>
            {/if}

            {#if item.Taglines && item.Taglines.length > 0}
              <p class="item-tagline">{item.Taglines[0]}</p>
            {/if}

            <div class="action-buttons">
              <!-- If Album / Folder / Playlist: Cast All / Play All / Shuffle -->
              {#if (item.Type === 'MusicAlbum' || item.Type === 'Folder' || item.Type === 'Playlist') && item.tracks && item.tracks.length > 0}
                <button class="btn-accent modal-action-btn" on:click={() => item && playFolderOrAlbumWithCast(item, item.tracks, 0)}>
                  <Cast size={18} />
                  <span>Cast All ({item.tracks.length})</span>
                </button>
                <button class="btn-primary modal-action-btn" on:click={() => item && item.tracks && playInBrowser(item, item.tracks, 0)}>
                  <Play size={18} fill="currentColor" />
                  <span>Play All</span>
                </button>
                <button class="btn-secondary modal-action-btn" on:click={() => item && item.tracks && shufflePlay(item, item.tracks)} title="Shuffle Play in Browser">
                  <Shuffle size={17} />
                  <span>Shuffle</span>
                </button>
                <button class="btn-secondary modal-action-btn" on:click={() => item && item.tracks && shuffleCast(item, item.tracks)} title="Shuffle Cast to TV">
                  <Shuffle size={17} />
                  <Cast size={15} />
                </button>
              {:else if item.Type === 'Series' && item.seasons && item.seasons.length > 0}
                <!-- For TV Series: Cast Series Queue (Linked Cast) -->
                <button class="btn-accent modal-action-btn" on:click={() => item && playWithLinkedQueue(item, item.seasons, 0)}>
                  <Cast size={18} />
                  <span>Cast Series Queue</span>
                </button>
                <button class="btn-primary modal-action-btn" on:click={() => item && playInBrowser(item)}>
                  <Play size={18} fill="currentColor" />
                  <span>Play Ep 1</span>
                </button>
              {:else}
                <!-- Standard Single Video / Audio Item -->
                <button class="btn-accent modal-action-btn" on:click={() => item && playWithDirectCast(item)}>
                  <Cast size={18} />
                  <span>Direct Cast</span>
                </button>
                <button class="btn-primary modal-action-btn" on:click={() => item && playInBrowser(item)}>
                  <Play size={18} fill="currentColor" />
                  <span>Play in Browser</span>
                </button>
              {/if}
            </div>
          </div>
        </div>
      </div>

      <!-- Modal Body / Details -->
      <div class="modal-content">
        <!-- Overview & Genres -->
        {#if item.Overview}
          <div class="content-section">
            <h3 class="section-heading">Overview</h3>
            <p class="item-overview">{item.Overview}</p>
          </div>
        {/if}

        {#if item.Genres && item.Genres.length > 0}
          <div class="content-section">
            <div class="genre-tags">
              {#each item.Genres as genre}
                <span class="genre-tag">{genre}</span>
              {/each}
            </div>
          </div>
        {/if}

        <!-- Audio Tracks / Album List View -->
        {#if item.tracks && item.tracks.length > 0}
          <div class="content-section">
            <div class="tracks-header">
              <h3 class="section-heading">Tracks ({item.tracks.length})</h3>
              <div class="tracks-header-actions">
                <button
                  class="btn-secondary tracks-sub-btn"
                  on:click={() => item && item.tracks && shufflePlay(item, item.tracks)}
                >
                  <Shuffle size={14} />
                  <span>Shuffle</span>
                </button>
                <button
                  class="btn-secondary tracks-sub-btn"
                  on:click={() => item && playFolderOrAlbumWithCast(item, item.tracks, 0)}
                >
                  <Cast size={14} />
                  <span>Cast All</span>
                </button>
              </div>
            </div>

            <div class="tracks-list">
              {#each item.tracks as track, index}
                <div class="track-row" on:click={() => item && playInBrowser(track, item.tracks, index)}>
                  <span class="track-number">{track.IndexNumber != null ? track.IndexNumber : index + 1}</span>
                  <div class="track-info">
                    <span class="track-title">{track.Name}</span>
                    {#if track.Artists && track.Artists.length > 0}
                      <span class="track-sub">{track.Artists.join(', ')}</span>
                    {/if}
                  </div>
                  {#if track.RunTimeTicks}
                    <span class="track-duration">{formatRuntime(track.RunTimeTicks)}</span>
                  {/if}
                  <div class="track-actions">
                    <button
                      class="track-action-btn fav-btn-track"
                      class:active-fav={track.UserData?.IsFavorite}
                      title="Favorite track"
                      on:click|stopPropagation={() => toggleFavorite(track)}
                    >
                      <Heart size={15} fill={track.UserData?.IsFavorite ? '#e74c3c' : 'none'} color={track.UserData?.IsFavorite ? '#e74c3c' : 'currentColor'} />
                    </button>
                    <button
                      class="track-action-btn cast-btn"
                      title="Cast from this track"
                      on:click|stopPropagation={() => item && playFolderOrAlbumWithCast(item, item.tracks, index)}
                    >
                      <Cast size={15} />
                    </button>
                    <button
                      class="track-action-btn play-btn"
                      title="Play in browser"
                      on:click|stopPropagation={() => item && playInBrowser(track, item.tracks, index)}
                    >
                      <Play size={14} fill="currentColor" />
                    </button>
                  </div>
                </div>
              {/each}
            </div>
          </div>
        {/if}

        <!-- TV Series Seasons and Episodes List -->
        {#if item.seasons && item.seasons.length > 0}
          <div class="content-section">
            <h3 class="section-heading">Seasons & Episodes</h3>

            <!-- Season Selector Tabs -->
            {#if item.seasons.length > 1}
              <div class="season-tabs">
                {#each item.seasons as season, sIdx}
                  <button
                    class="season-tab"
                    class:active={activeSeasonIndex === sIdx}
                    on:click={() => (activeSeasonIndex = sIdx)}
                  >
                    {season.Name}
                  </button>
                {/each}
              </div>
            {/if}

            <!-- Episodes Grid -->
            {#if item.seasons[activeSeasonIndex]?.Episodes}
              <div class="episodes-list">
                {#each item.seasons[activeSeasonIndex].Episodes as ep, epIdx}
                  <div class="episode-card">
                    <div class="ep-thumb-wrapper" on:click={() => playInBrowser(ep)}>
                      <img
                        src={ep.posterUrl || resolveItemPosterUrl(ep)}
                        alt={ep.Name}
                        class="ep-thumb"
                      />
                      <div class="ep-play-overlay">
                        <Play size={20} fill="currentColor" />
                      </div>
                    </div>

                    <div class="ep-details">
                      <div class="ep-header">
                        <span class="ep-num">
                          E{ep.IndexNumber ?? epIdx + 1}
                        </span>
                        <h4 class="ep-title">{ep.Name}</h4>
                        {#if ep.RunTimeTicks}
                          <span class="ep-duration">{formatRuntime(ep.RunTimeTicks)}</span>
                        {/if}
                      </div>

                      {#if ep.Overview}
                        <p class="ep-overview">{ep.Overview}</p>
                      {/if}

                      <div class="ep-actions">
                        <button
                          class="btn-secondary ep-cast-btn"
                          on:click={() => playWithDirectCast(ep)}
                        >
                          <Cast size={14} />
                          <span>Cast Episode</span>
                        </button>
                        <button
                          class="btn-primary ep-play-btn"
                          on:click={() => playInBrowser(ep)}
                        >
                          <Play size={14} fill="currentColor" />
                          <span>Play</span>
                        </button>
                      </div>
                    </div>
                  </div>
                {/each}
              </div>
            {/if}
          </div>
        {/if}
      </div>
    </div>
  </div>
{/if}

<style>
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.82);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    z-index: 90;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    animation: fadeIn 0.2s ease;
  }

  @keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
  }

  .modal-container {
    background: var(--bg-surface-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius-xl);
    width: 100%;
    max-width: 960px;
    max-height: 90vh;
    overflow-y: auto;
    position: relative;
    box-shadow: var(--shadow-lg);
    display: flex;
    flex-direction: column;
  }

  .modal-close-btn {
    position: absolute;
    top: 16px;
    right: 16px;
    z-index: 20;
    width: 38px;
    height: 38px;
    border-radius: 50%;
    background: rgba(0, 0, 0, 0.65);
    backdrop-filter: blur(8px);
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid rgba(255, 255, 255, 0.15);
    transition: all 0.15s ease;
  }

  .modal-close-btn:hover {
    background: rgba(0, 0, 0, 0.9);
    transform: scale(1.08);
  }

  .modal-hero {
    position: relative;
    padding: 40px 36px 28px;
    overflow: hidden;
    border-bottom: 1px solid var(--border);
  }

  .modal-hero-bg {
    position: absolute;
    inset: 0;
    background-size: cover;
    background-position: center;
    filter: blur(40px) brightness(0.35);
    transform: scale(1.1);
  }

  .modal-hero-gradient {
    position: absolute;
    inset: 0;
    background: linear-gradient(to top, var(--bg-surface-elevated) 0%, transparent 100%);
  }

  .modal-hero-content {
    position: relative;
    z-index: 5;
    display: flex;
    gap: 28px;
    align-items: flex-end;
  }

  .hero-poster-wrapper {
    width: 170px;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-lg);
    overflow: hidden;
    flex-shrink: 0;
    box-shadow: 0 12px 30px rgba(0, 0, 0, 0.6);
    border: 1px solid rgba(255, 255, 255, 0.15);
    background: var(--bg-card);
  }

  .hero-poster-wrapper.music-aspect {
    aspect-ratio: 1 / 1;
  }

  .hero-poster-img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .hero-meta {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .type-pill {
    align-self: flex-start;
    padding: 3px 10px;
    border-radius: var(--radius-full);
    background: rgba(0, 164, 220, 0.2);
    border: 1px solid rgba(0, 164, 220, 0.4);
    color: var(--jf-blue);
    font-size: 0.72rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .title-fav-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .item-title {
    font-size: 1.85rem;
    font-weight: 800;
    color: #fff;
    line-height: 1.2;
    letter-spacing: -0.02em;
  }

  .fav-btn {
    padding: 8px;
    border-radius: 50%;
    color: var(--text-muted);
    background: rgba(255, 255, 255, 0.08);
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid rgba(255, 255, 255, 0.1);
  }

  .fav-btn:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.15);
  }

  .fav-btn.active-fav {
    color: #e74c3c;
    background: rgba(231, 76, 60, 0.15);
    border-color: rgba(231, 76, 60, 0.3);
  }

  .meta-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
  }

  .meta-badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 0.8rem;
    color: var(--text-secondary);
    background: rgba(255, 255, 255, 0.08);
    padding: 3px 8px;
    border-radius: var(--radius-xs);
  }

  .rating-badge {
    border: 1px solid rgba(255, 255, 255, 0.2);
    font-weight: 600;
  }

  .item-artist {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.95rem;
    color: var(--jf-indigo);
    font-weight: 600;
  }

  .item-tagline {
    font-size: 0.88rem;
    font-style: italic;
    color: var(--text-muted);
  }

  .action-buttons {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 6px;
  }

  .modal-action-btn {
    padding: 10px 18px;
    font-size: 0.9rem;
    border-radius: var(--radius-md);
  }

  .modal-content {
    padding: 28px 36px 40px;
    display: flex;
    flex-direction: column;
    gap: 28px;
  }

  .content-section {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .section-heading {
    font-size: 1.15rem;
    font-weight: 700;
    color: #fff;
  }

  .item-overview {
    font-size: 0.92rem;
    color: var(--text-secondary);
    line-height: 1.6;
  }

  .genre-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .genre-tag {
    padding: 4px 10px;
    border-radius: var(--radius-full);
    background: var(--bg-surface);
    border: 1px solid var(--border);
    font-size: 0.78rem;
    color: var(--text-secondary);
  }

  /* Tracks */
  .tracks-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 4px;
  }

  .tracks-header-actions {
    display: flex;
    gap: 8px;
  }

  .tracks-sub-btn {
    padding: 6px 12px;
    font-size: 0.78rem;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .tracks-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
    border-radius: var(--radius-md);
    background: var(--bg-surface);
    border: 1px solid var(--border);
    padding: 6px;
  }

  .track-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: background 0.15s ease;
  }

  .track-row:hover {
    background: rgba(255, 255, 255, 0.06);
  }

  .track-number {
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--text-muted);
    width: 24px;
    text-align: center;
    flex-shrink: 0;
  }

  .track-info {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .track-title {
    font-size: 0.9rem;
    font-weight: 600;
    color: var(--text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .track-sub {
    font-size: 0.75rem;
    color: var(--text-muted);
  }

  .track-duration {
    font-size: 0.78rem;
    color: var(--text-muted);
    font-family: var(--font-mono);
  }

  .track-actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .track-action-btn {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-muted);
    background: transparent;
  }

  .track-action-btn:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.1);
  }

  .fav-btn-track.active-fav {
    color: #e74c3c;
  }

  /* Seasons & Episodes */
  .season-tabs {
    display: flex;
    gap: 6px;
    overflow-x: auto;
    padding-bottom: 4px;
  }

  .season-tab {
    padding: 6px 14px;
    border-radius: var(--radius-full);
    background: var(--bg-surface);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    font-size: 0.82rem;
    font-weight: 500;
    white-space: nowrap;
  }

  .season-tab.active {
    background: var(--jf-blue);
    border-color: transparent;
    color: #fff;
    font-weight: 600;
  }

  .episodes-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-top: 8px;
  }

  .episode-card {
    display: flex;
    gap: 16px;
    padding: 12px;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
  }

  .ep-thumb-wrapper {
    position: relative;
    width: 140px;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-sm);
    overflow: hidden;
    flex-shrink: 0;
    background: var(--bg-card);
    cursor: pointer;
  }

  .ep-thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .ep-play-overlay {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.4);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    opacity: 0;
    transition: opacity 0.15s ease;
  }

  .ep-thumb-wrapper:hover .ep-play-overlay {
    opacity: 1;
  }

  .ep-details {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }

  .ep-header {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .ep-num {
    font-size: 0.78rem;
    font-weight: 700;
    color: var(--jf-blue);
  }

  .ep-title {
    font-size: 0.95rem;
    font-weight: 600;
    color: #fff;
    flex: 1;
  }

  .ep-duration {
    font-size: 0.75rem;
    color: var(--text-muted);
    font-family: var(--font-mono);
  }

  .ep-overview {
    font-size: 0.82rem;
    color: var(--text-secondary);
    line-height: 1.45;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .ep-actions {
    display: flex;
    gap: 8px;
    margin-top: 4px;
  }

  .ep-cast-btn, .ep-play-btn {
    padding: 5px 10px;
    font-size: 0.76rem;
  }

  @media (max-width: 768px) {
    .modal-backdrop {
      padding: 0;
    }
    .modal-container {
      max-height: 100vh;
      max-height: 100dvh;
      height: 100%;
      border-radius: 0;
      border: none;
    }
    .modal-hero {
      padding: 24px 18px 18px;
    }
    .modal-hero-content {
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 16px;
    }
    .hero-poster-wrapper {
      width: 130px;
    }
    .meta-row {
      justify-content: center;
    }
    .action-buttons {
      width: 100%;
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
    }
    .action-buttons button {
      width: 100%;
    }
    .modal-content {
      padding: 18px 18px 32px;
      gap: 20px;
    }
    .episode-card {
      flex-direction: column;
      gap: 10px;
    }
    .ep-thumb-wrapper {
      width: 100%;
      aspect-ratio: 16 / 9;
    }
  }
</style>
