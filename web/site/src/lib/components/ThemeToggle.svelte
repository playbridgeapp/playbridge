<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { applyPref, cyclePref, currentTheme, readPref, type Theme, type ThemePref } from '$lib/theme';

  let pref = $state<ThemePref>('auto');
  let theme = $state<Theme>('dark');

  $effect(() => {
    pref = readPref();
    theme = currentTheme();
    if (typeof window === 'undefined') return;
    const mq = window.matchMedia('(prefers-color-scheme: light)');
    const onChange = () => {
      if (readPref() === 'auto') applyPref('auto');
      theme = currentTheme();
      pref = readPref();
    };
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  });

  function onToggle() {
    pref = cyclePref();
    theme = currentTheme();
  }

  const label = $derived(
    pref === 'auto' ? 'Theme: Auto (follows system)' : pref === 'dark' ? 'Theme: Dark' : 'Theme: Light'
  );
</script>

<button type="button" class="nav__theme" onclick={onToggle} aria-label={label} title={label}>
  {#if pref === 'auto'}
    <Icon name="theme-auto" size={16} />
  {:else if theme === 'dark'}
    <Icon name="sun" size={16} />
  {:else}
    <Icon name="moon" size={16} />
  {/if}
</button>
