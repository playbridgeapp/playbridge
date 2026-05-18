<script lang="ts">
  import LogoMark from '$lib/icons/LogoMark.svelte';
  import Icon from '$lib/icons/Icon.svelte';
  import { SITE } from '$lib/data/site';
</script>

<nav class="nav">
  <div class="wrap nav__inner">
    <a class="logo" href="/">
      <LogoMark size={30} />
      <span>{SITE.name}</span>
    </a>
    <div class="nav__links">
      <a href="/#how">How it works</a>
      <a href="/#platforms">Platforms</a>
      <a href="/#features">Features</a>
      <a href="/#install">Install</a>
      <a href="/#faq">FAQ</a>
    </div>
    <div class="nav__actions">
      <a class="btn" href={SITE.github} rel="noopener"><Icon name="github" size={13} /> GitHub</a>
      <a class="btn btn--primary" href="/#install">Get the app</a>
    </div>
  </div>
</nav>
