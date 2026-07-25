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
    <a href="/receivers" class="btn btn--primary"
      ><Icon name="download" size={14} /> Install receivers</a
    >
    <a href="/senders" class="btn"><Icon name="cast" size={14} /> Install senders</a>
    <a class="btn" href={SITE.github} rel="noopener"
      ><Icon name="github" size={13} /> View on GitHub</a
    >
  </div>
  <div class="hero__scene">
    <CastScene />
  </div>
</section>
