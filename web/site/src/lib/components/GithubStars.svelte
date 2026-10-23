<script lang="ts">
  /**
   * Quiet star count for a GitHub text link: `GitHub <GithubStars />` → "GitHub ★ 369".
   * Count is fetched at build time (`+layout.server.ts`). Renders nothing when unknown.
   */
  import { page } from '$app/stores';
  import { formatStars } from '$lib/github';

  interface Props {
    /** Override the layout value (mainly for tests); defaults to `page.data.githubStars`. */
    stars?: number | null;
  }

  let { stars }: Props = $props();

  const count = $derived(
    stars !== undefined ? stars : (($page.data.githubStars as number | null | undefined) ?? null)
  );
</script>

{#if count !== null}
  <span class="gh-stars"
    ><span class="gh-stars__mark" aria-hidden="true">★</span>{formatStars(count)}<span
      class="gh-stars__sr"> stars</span
    ></span
  >
{/if}

<style>
  .gh-stars {
    display: inline-flex;
    align-items: baseline;
    gap: 3px;
    margin-left: 6px;
    font-family: var(--font-mono-ui);
    font-size: 0.85em;
    font-weight: 400;
    letter-spacing: 0.02em;
    color: var(--text-faint);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  .gh-stars__mark {
    font-size: 1.1em;
    line-height: 1;
  }
  .gh-stars__sr {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    border: 0;
  }
</style>
