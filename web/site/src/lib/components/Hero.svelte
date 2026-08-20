<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import CastScene from './visuals/CastScene.svelte';
  import { SITE } from '$lib/data/site';
</script>

<section class="hero wrap">
  <div class="hero__pill">
    <span class="hero__pill-mark">{SITE.versionLabel}</span>
    <span class="hero__pill-copy">Open source · local network</span>
  </div>
  <h1>Your phone. Your TV. <span class="accent">One bridge.</span></h1>
  <p class="hero__sub">
    Browse on your phone. Watch on the TV — same Wi‑Fi, no account.
  </p>
  <div class="hero__ctas">
    <a href="/#install" class="btn btn--primary"
      ><Icon name="download" size={14} /> Get a receiver</a
    >
    <a class="hero__source" href={SITE.github} rel="noopener noreferrer" target="_blank"
      >GitHub</a
    >
  </div>
  <div class="hero__scene">
    <CastScene />
  </div>
</section>
