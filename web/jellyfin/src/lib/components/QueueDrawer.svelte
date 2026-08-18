<script lang="ts">
  import {
    activePlayer,
    isQueueDrawerOpen,
    isLyricsOpen,
    lyricsData,
    playQueueTrack,
    toggleFavorite,
    resolveItemPosterUrl
  } from '../stores/appState';
  import {
    X,
    Music,
    Play,
    Heart,
    Trash2,
    Mic2,
    ListMusic,
    Volume2
  } from 'lucide-svelte';

  let activeTab: 'queue' | 'lyrics' = 'queue';

  $: playerState = $activePlayer;
  $: playlist = playerState.playlist || [];
  $: currentIndex = playerState.currentIndex ?? 0;
  $: currentItem = playerState.item;
  $: lyrics = $lyricsData;

  function close() {
    $isQueueDrawerOpen = false;
  }
</script>

{#if $isQueueDrawerOpen}
  <div class="drawer-backdrop" on:click={close}>
    <div class="drawer-panel" on:click|stopPropagation>
      <!-- Drawer Header -->
      <div class="drawer-header">
        <div class="tab-toggle-group">
          <button
            class="tab-btn"
            class:active={activeTab === 'queue'}
            on:click={() => (activeTab = 'queue')}
          >
            <ListMusic size={16} />
            <span>Up Next ({playlist.length})</span>
          </button>

          {#if currentItem?.Type === 'Audio'}
            <button
              class="tab-btn"
              class:active={activeTab === 'lyrics'}
              on:click={() => (activeTab = 'lyrics')}
            >
              <Mic2 size={16} />
              <span>Lyrics</span>
            </button>
          {/if}
        </div>

        <button class="icon-close-btn" on:click={close} aria-label="Close queue">
          <X size={18} />
        </button>
      </div>

      <!-- Drawer Content -->
      <div class="drawer-body">
        {#if activeTab === 'queue'}
          {#if playlist.length === 0}
            <div class="empty-queue">
              <Music size={40} class="empty-icon" />
              <p>Queue is empty</p>
            </div>
          {:else}
            <div class="queue-list">
              {#each playlist as track, index (track.Id + '-' + index)}
                <div
                  class="queue-row"
                  class:active-track={index === currentIndex}
                  on:click={() => playQueueTrack(index)}
                >
                  <div class="queue-track-left">
                    <span class="track-idx">
                      {#if index === currentIndex}
                        <div class="equalizer-bars">
                          <span class="bar bar1"></span>
                          <span class="bar bar2"></span>
                          <span class="bar bar3"></span>
                        </div>
                      {:else}
                        {index + 1}
                      {/if}
                    </span>

                    <div class="queue-thumb-wrapper">
                      <img src={resolveItemPosterUrl(track)} alt={track.Name} class="queue-thumb" />
                    </div>

                    <div class="queue-info">
                      <span class="queue-title">{track.Name}</span>
                      <span class="queue-artist">
                        {track.AlbumArtist || track.Artists?.[0] || track.SeriesName || ''}
                      </span>
                    </div>
                  </div>

                  <div class="queue-actions" on:click|stopPropagation>
                    <button
                      class="fav-icon-btn"
                      class:active-fav={track.UserData?.IsFavorite}
                      on:click={() => toggleFavorite(track)}
                      title="Favorite track"
                    >
                      <Heart size={15} fill={track.UserData?.IsFavorite ? 'currentColor' : 'none'} />
                    </button>
                  </div>
                </div>
              {/each}
            </div>
          {/if}
        {:else if activeTab === 'lyrics'}
          <!-- Lyrics Screen (Finamp feature) -->
          <div class="lyrics-container">
            {#if lyrics && lyrics.Lyrics && lyrics.Lyrics.length > 0}
              <div class="lyrics-lines">
                {#each lyrics.Lyrics as line}
                  <p class="lyrics-line">{line.Text}</p>
                {/each}
              </div>
            {:else if lyrics && lyrics.Text}
              <pre class="plain-lyrics">{lyrics.Text}</pre>
            {:else}
              <div class="empty-lyrics">
                <Mic2 size={40} class="empty-icon" />
                <p>No lyrics found for this song</p>
              </div>
            {/if}
          </div>
        {/if}
      </div>
    </div>
  </div>
{/if}

<style>
  .drawer-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.7);
    backdrop-filter: blur(4px);
    z-index: 95;
    display: flex;
    justify-content: flex-end;
  }

  .drawer-panel {
    width: 400px;
    max-width: 90vw;
    height: 100%;
    background: var(--bg-surface-elevated);
    border-left: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    box-shadow: var(--shadow-lg);
    animation: slideLeft 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  }

  @keyframes slideLeft {
    from { transform: translateX(100%); }
    to { transform: translateX(0); }
  }

  .drawer-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 18px;
    border-bottom: 1px solid var(--border);
  }

  .tab-toggle-group {
    display: flex;
    gap: 6px;
    background: var(--bg-surface);
    padding: 3px;
    border-radius: var(--radius-full);
    border: 1px solid var(--border);
  }

  .tab-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 5px 12px;
    border-radius: var(--radius-full);
    font-size: 0.78rem;
    font-weight: 600;
    color: var(--text-muted);
  }

  .tab-btn.active {
    background: var(--jf-blue);
    color: #fff;
  }

  .icon-close-btn {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-secondary);
  }

  .icon-close-btn:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.1);
  }

  .drawer-body {
    flex: 1;
    overflow-y: auto;
    padding: 12px;
  }

  .empty-queue, .empty-lyrics {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    padding: 80px 20px;
    color: var(--text-muted);
  }

  :global(.empty-icon) {
    opacity: 0.4;
    color: var(--jf-blue);
  }

  .queue-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .queue-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 10px;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: background 0.15s ease;
  }

  .queue-row:hover {
    background: rgba(255, 255, 255, 0.05);
  }

  .queue-row.active-track {
    background: rgba(0, 164, 220, 0.15);
    border: 1px solid rgba(0, 164, 220, 0.3);
  }

  .queue-track-left {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    flex: 1;
  }

  .track-idx {
    font-size: 0.75rem;
    color: var(--text-muted);
    width: 22px;
    text-align: center;
    flex-shrink: 0;
  }

  .equalizer-bars {
    display: flex;
    align-items: flex-end;
    gap: 2px;
    height: 14px;
    justify-content: center;
  }

  .bar {
    width: 2px;
    background: var(--jf-blue);
    border-radius: 1px;
    animation: eqBounce 1s infinite alternate ease-in-out;
  }

  .bar1 { height: 60%; animation-delay: 0.1s; }
  .bar2 { height: 100%; animation-delay: 0.3s; }
  .bar3 { height: 40%; animation-delay: 0.2s; }

  @keyframes eqBounce {
    0% { height: 20%; }
    100% { height: 100%; }
  }

  .queue-thumb-wrapper {
    width: 36px;
    height: 36px;
    border-radius: var(--radius-xs);
    overflow: hidden;
    flex-shrink: 0;
    background: var(--bg-card);
  }

  .queue-thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .queue-info {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .queue-title {
    font-size: 0.85rem;
    font-weight: 600;
    color: #fff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .queue-artist {
    font-size: 0.72rem;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .queue-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .fav-icon-btn {
    padding: 6px;
    color: var(--text-muted);
    border-radius: 50%;
  }

  .fav-icon-btn:hover {
    color: #fff;
  }

  .fav-icon-btn.active-fav {
    color: #e74c3c;
  }

  /* Lyrics View */
  .lyrics-container {
    padding: 20px 12px;
  }

  .lyrics-lines {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .lyrics-line {
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--text-secondary);
    line-height: 1.5;
    transition: color 0.2s ease;
  }

  .lyrics-line:hover {
    color: #fff;
  }

  .plain-lyrics {
    font-family: inherit;
    font-size: 0.95rem;
    color: var(--text-secondary);
    line-height: 1.7;
    white-space: pre-wrap;
  }
</style>
