<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { currentTheme, toggleTheme, type Theme } from '$lib/theme';

  let theme = $state<Theme>('dark');

  $effect(() => {
    theme = currentTheme();
  });

  function onToggle() {
    theme = toggleTheme();
  }
</script>

<button
  type="button"
  class="nav__theme"
  onclick={onToggle}
  aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
  title={theme === 'dark' ? 'Light' : 'Dark'}
>
  {#if theme === 'dark'}
    <Icon name="sun" size={16} />
  {:else}
    <Icon name="moon" size={16} />
  {/if}
</button>
