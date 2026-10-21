<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import CastScene from './visuals/CastScene.svelte';
  import { SITE } from '$lib/data/site';
</script>

<section class="hero wrap">
  <div class="hero__grid">
    <div class="hero__copy">
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
    </div>
    <div class="hero__scene">
      <CastScene />
    </div>
  </div>
</section>

<style>
  /* Wide screens: copy left, device scene right, both in the first viewport.
     Below 1024px the scene stacks under the copy (spacing from app.css). */
  @media (min-width: 1024px) {
    .hero__grid {
      display: grid;
      grid-template-columns: minmax(0, 0.82fr) minmax(0, 1.18fr);
      gap: 48px;
      align-items: center;
    }
    .hero__copy h1 {
      /* One phrase per line: “Your phone.” / “Your TV.” / “One bridge.” */
      max-width: 6.4em;
    }
    .hero__scene {
      margin-top: 0;
      padding-top: 0;
      border-top: 0;
      /* Let the scene bleed into the right gutter on wide viewports. */
      margin-right: calc(-1 * clamp(0px, (100vw - 1080px) / 2 + 8px, 160px));
    }
  }
</style>
