<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import CastScene from './visuals/CastScene.svelte';
  import { SITE } from '$lib/data/site';
</script>

<section class="hero wrap">
  <div class="hero__pill">Alpha • Open source casting suite</div>
  <h1>Your phone. Your TV. <span class="accent">One bridge.</span></h1>
  <p class="hero__sub">
    PlayBridge turns any sender into a remote for any receiver. Browse on your phone, watch on your
    TV — or Desktop, or the CLI.
  </p>
  <div class="hero__ctas">
    <a href="/#install" class="btn btn--primary"
      ><Icon name="download" size={14} /> Get started</a
    >
    <a href="/receivers" class="btn">Install receivers</a>
    <a class="btn btn--ghost" href={SITE.github} rel="noopener noreferrer" target="_blank"
      ><Icon name="github" size={13} /> GitHub</a
    >
  </div>
  <div class="hero__scene">
    <CastScene />
  </div>
</section>
