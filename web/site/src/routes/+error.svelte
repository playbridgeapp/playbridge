<script lang="ts">
  import { page } from '$app/state';
  import Seo from '$lib/seo/Seo.svelte';

  const notFound = $derived(page.status === 404);
  const title = $derived(notFound ? 'Page not found' : 'Something went wrong');
</script>

<Seo
  {title}
  description="This address is not part of playbridge.app."
  path={page.url.pathname}
  noindex
/>

<section class="section wrap error">
  <span class="eyebrow">{page.status}</span>
  <h1>{notFound ? 'Nothing is playing here.' : 'This page did not load.'}</h1>
  <p class="error__lede">
    {#if notFound}
      The link may be old, or the address has a typo. Pick up from one of these instead.
    {:else}
      Try again in a moment, or pick up from one of these instead.
    {/if}
  </p>
  <nav class="error__links" aria-label="Site sections">
    <a class="btn btn--primary" href="/">Home</a>
    <a class="error__link" href="/receivers">Receivers</a>
    <a class="error__link" href="/senders">Senders</a>
  </nav>
</section>

<style>
  .error {
    min-height: 52vh;
  }
  .error .eyebrow {
    display: block;
    margin-bottom: 16px;
  }
  .error h1 {
    font-size: clamp(32px, 4.2vw, 52px);
    max-width: 14em;
  }
  .error__lede {
    margin-top: 20px;
    max-width: 30rem;
    font-size: 17px;
  }
  .error__links {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 18px;
    margin-top: 36px;
  }
  .error__link {
    font-size: 14px;
    font-weight: 500;
    color: var(--text-dim);
    border-bottom: 1px solid var(--line-strong);
    padding-bottom: 1px;
    transition:
      color 0.15s ease,
      border-color 0.15s ease;
  }
  .error__link:hover {
    color: var(--text);
    border-bottom-color: color-mix(in oklab, var(--accent) 60%, transparent);
  }
</style>
