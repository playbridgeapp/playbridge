<script lang="ts">
  import LogoMark from '$lib/icons/LogoMark.svelte';
  import Icon from '$lib/icons/Icon.svelte';
  import { SITE } from '$lib/data/site';

  let mobileMenuOpen = $state(false);

  function closeMenu() {
    mobileMenuOpen = false;
  }
</script>

<nav class="nav">
  <div class="wrap nav__inner">
    <a class="logo" href="/">
      <LogoMark size={30} />
      <span>{SITE.name}</span>
      <span class="logo__badge">Alpha</span>
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
    <button
      type="button"
      class="nav__toggle"
      onclick={() => (mobileMenuOpen = !mobileMenuOpen)}
      aria-label="Toggle menu"
      aria-expanded={mobileMenuOpen}
    >
      {#if mobileMenuOpen}
        <Icon name="x" size={20} />
      {:else}
        <Icon name="menu" size={20} />
      {/if}
    </button>
  </div>

  {#if mobileMenuOpen}
    <div class="nav__mobile-menu">
      <div class="nav__mobile-links">
        <a href="/#how" onclick={closeMenu}>How it works</a>
        <a href="/#platforms" onclick={closeMenu}>Platforms</a>
        <a href="/#features" onclick={closeMenu}>Features</a>
        <a href="/#install" onclick={closeMenu}>Install</a>
        <a href="/#faq" onclick={closeMenu}>FAQ</a>
        <a class="btn btn--primary nav__mobile-github" href={SITE.github} rel="noopener" target="_blank" onclick={closeMenu}>
          <Icon name="github" size={14} /> GitHub
        </a>
      </div>
    </div>
  {/if}
</nav>
