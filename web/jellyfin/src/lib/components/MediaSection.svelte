<script lang="ts">
  import type { JellyfinItem } from '../types';
  import MediaCard from './MediaCard.svelte';
  import { ChevronLeft, ChevronRight } from 'lucide-svelte';

  export let title: string;
  export let subtitle: string = '';
  export let items: JellyfinItem[] = [];
  export let onSeeAll: (() => void) | undefined = undefined;

  let scrollContainer: HTMLDivElement;

  function scroll(direction: 'left' | 'right') {
    if (!scrollContainer) return;
    const distance = scrollContainer.clientWidth * 0.75;
    scrollContainer.scrollBy({
      left: direction === 'left' ? -distance : distance,
      behavior: 'smooth'
    });
  }
</script>

{#if items && items.length > 0}
  <section class="media-section">
    <div class="section-header">
      <div class="header-titles">
        <h2 class="section-title">{title}</h2>
        {#if subtitle}
          <p class="section-subtitle">{subtitle}</p>
        {/if}
      </div>

      <div class="scroll-controls">
        {#if onSeeAll}
          <button class="see-all-btn" on:click={onSeeAll}>
            View All &rarr;
          </button>
        {/if}
        <button class="control-btn" on:click={() => scroll('left')} aria-label="Scroll left">
          <ChevronLeft size={18} />
        </button>
        <button class="control-btn" on:click={() => scroll('right')} aria-label="Scroll right">
          <ChevronRight size={18} />
        </button>
      </div>
    </div>

    <div class="cards-carousel" bind:this={scrollContainer}>
      {#each items as item (item.Id)}
        <div class="carousel-item">
          <MediaCard {item} />
        </div>
      {/each}
    </div>
  </section>
{/if}

<style>
  .media-section {
    padding: 0 36px;
    margin-bottom: 36px;
  }

  .section-header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    margin-bottom: 14px;
  }

  .section-title {
    font-size: 1.25rem;
    font-weight: 700;
    letter-spacing: -0.01em;
    color: var(--text-primary);
  }

  .section-subtitle {
    font-size: 0.8rem;
    color: var(--text-muted);
    margin-top: 2px;
  }

  .scroll-controls {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .see-all-btn {
    font-size: 0.78rem;
    font-weight: 600;
    color: var(--jf-blue);
    background: transparent;
    padding: 6px 10px;
    border-radius: var(--radius-sm);
    margin-right: 6px;
    transition: all 0.15s ease;
  }

  .see-all-btn:hover {
    background: rgba(0, 164, 220, 0.12);
    color: #fff;
  }

  .control-btn {
    width: 32px;
    height: 32px;
    border-radius: var(--radius-sm);
    background: var(--bg-surface);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .control-btn:hover {
    background: var(--bg-surface-elevated);
    color: #fff;
    border-color: var(--text-muted);
  }

  .cards-carousel {
    display: flex;
    gap: 16px;
    overflow-x: auto;
    padding-bottom: 12px;
    scroll-behavior: smooth;
    scrollbar-width: thin;
  }

  .carousel-item {
    width: 175px;
    flex-shrink: 0;
  }

  @media (max-width: 768px) {
    .media-section {
      padding: 0 16px;
      margin-bottom: 24px;
    }
    .carousel-item {
      width: 130px;
    }
  }
</style>
