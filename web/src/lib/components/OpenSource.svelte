<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { STATS, SITE } from '$lib/data/site';
</script>

<section class="section wrap" id="open">
  <div class="os__inner">
    <span class="eyebrow os__eyebrow">Open source</span>
    <h2>Built in the open, by people who hate ads more than they like profit.</h2>
    <p class="os__lede">
      PlayBridge is GPLv3 and free forever. Every line lives on GitHub.
      Star us, file issues, send PRs — no investors, no roadmap negotiations.
    </p>
    <div class="os__stats">
      {#each STATS as [v, l]}
        <div>
          <span class="os__stat__val">{v}</span>
          <span class="os__stat__lbl">{l}</span>
        </div>
      {/each}
    </div>
    <div class="os__ctas">
      <a class="btn btn--primary" href={SITE.github} rel="noopener">
        <Icon name="github" size={13} /> Star on GitHub
      </a>
      <a class="btn" href="{SITE.github}/playbridge#documentation" rel="noopener">Read the docs</a>
    </div>
  </div>
</section>

<style>
  .os__eyebrow { display: block; margin-bottom: 16px; }
  .os__lede { margin-top: 18px; font-size: 16px; max-width: 540px; }
</style>
