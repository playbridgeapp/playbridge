<script lang="ts">
  import LogoMark from '$lib/icons/LogoMark.svelte';
  import Icon from '$lib/icons/Icon.svelte';
  import ThemeToggle from './ThemeToggle.svelte';
  import { SITE } from '$lib/data/site';
  import { page } from '$app/stores';
  import { onMount } from 'svelte';

  let mobileMenuOpen = $state(false);
  let menuEl = $state<HTMLDivElement | null>(null);
  let toggleEl = $state<HTMLButtonElement | null>(null);

  function closeMenu() {
    mobileMenuOpen = false;
  }

  function toggleMenu() {
    mobileMenuOpen = !mobileMenuOpen;
  }

  $effect(() => {
    if (typeof document === 'undefined') return;
    document.body.style.overflow = mobileMenuOpen ? 'hidden' : '';
    return () => {
      document.body.style.overflow = '';
    };
  });

  onMount(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && mobileMenuOpen) {
        closeMenu();
        toggleEl?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  $effect(() => {
    if (mobileMenuOpen) {
      queueMicrotask(() => {
        const first = menuEl?.querySelector<HTMLElement>('a, button');
        first?.focus();
      });
    }
  });

  const path = $derived($page.url.pathname);
  const onSenders = $derived(path.startsWith('/senders'));
  const onReceivers = $derived(path.startsWith('/receivers'));
</script>

<a class="skip-link" href="#main">Skip to content</a>

<nav class="nav" aria-label="Primary">
  <div class="wrap nav__inner">
    <a class="logo" href="/">
      <LogoMark size={30} />
      <span>{SITE.name}</span>
      <span class="logo__badge">{SITE.versionLabel}</span>
    </a>
    <div class="nav__links">
      <a href="/#how">How it works</a>
      <a href="/senders" class:nav__link--active={onSenders} aria-current={onSenders ? 'page' : undefined}
        >Senders</a
      >
      <a
        href="/receivers"
        class:nav__link--active={onReceivers}
        aria-current={onReceivers ? 'page' : undefined}>Receivers</a
      >
      <a href="/#features">Features</a>
    </div>
    <div class="nav__actions">
      <a class="nav__source" href={SITE.github} rel="noopener noreferrer" target="_blank"
        >GitHub</a
      >
      <a class="btn btn--primary" href="/#install">Get a receiver</a>
    </div>
    <ThemeToggle />
    <button
      type="button"
      class="nav__toggle"
      bind:this={toggleEl}
      onclick={toggleMenu}
      aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
      aria-expanded={mobileMenuOpen}
      aria-controls="mobile-nav"
    >
      {#if mobileMenuOpen}
        <Icon name="x" size={20} />
      {:else}
        <Icon name="menu" size={20} />
      {/if}
    </button>
  </div>
</nav>

{#if mobileMenuOpen}
  <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
  <div
    class="nav__mobile-menu"
    id="mobile-nav"
    role="dialog"
    aria-modal="true"
    aria-label="Menu"
    tabindex="-1"
    bind:this={menuEl}
    onclick={(e) => {
      if (e.target === e.currentTarget) closeMenu();
    }}
  >
    <div class="nav__mobile-links">
      <a href="/#how" onclick={closeMenu}>How it works</a>
      <a href="/senders" onclick={closeMenu}>Senders</a>
      <a href="/receivers" onclick={closeMenu}>Receivers</a>
      <a href="/#features" onclick={closeMenu}>Features</a>
      <a class="btn btn--primary nav__mobile-cta" href="/#install" onclick={closeMenu}
        >Get a receiver</a
      >
      <a
        class="nav__mobile-source"
        href={SITE.github}
        rel="noopener noreferrer"
        target="_blank"
        onclick={closeMenu}
      >GitHub</a
      >
    </div>
  </div>
{/if}
