<script lang="ts">
  type IconName =
    | 'phone' | 'tv' | 'cast' | 'desktop' | 'firefox' | 'apple' | 'android'
    | 'windows' | 'linux'
    | 'github' | 'link' | 'plus' | 'arrow' | 'download'
    | 'menu' | 'x';

  interface Props {
    name: IconName;
    size?: number;
    stroke?: number;
    class?: string;
  }

  let { name, size = 18, stroke = 1.4, class: cls = '' }: Props = $props();
</script>

<svg
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width={stroke}
  stroke-linecap="round"
  stroke-linejoin="round"
  class={cls}
  aria-hidden="true"
>
  {#if name === 'phone'}
    <rect x="6" y="2" width="12" height="20" rx="2" />
    <path d="M11 18h2" />
  {:else if name === 'tv'}
    <rect x="2" y="5" width="20" height="13" rx="1.5" />
    <path d="M8 21h8" />
  {:else if name === 'cast'}
    <path d="M3 19a8 8 0 0 1 6 6" />
    <path d="M3 14a13 13 0 0 1 11 11" />
    <circle cx="4" cy="22" r="0.6" fill="currentColor" />
    <path d="M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5" />
  {:else if name === 'desktop'}
    <rect x="2" y="4" width="20" height="13" rx="1.5" />
    <path d="M8 21h8M12 17v4" />
  {:else if name === 'firefox'}
    <circle cx="12" cy="12" r="9" />
    <path d="M5 9c2-3 5-3 7-1M19 9c-2-3-5-3-7-1M8 14c1 3 7 3 8 0" />
  {:else if name === 'apple'}
    <path d="M16 3c0 2-1.5 4-3.5 4M8 8c-2 0-4 2-4 5 0 5 4 9 5 9 1 0 2-1 3-1s2 1 3 1c1 0 5-4 5-9 0-3-2-5-4-5-1 0-2 1-3 1s-3-1-5-1z" />
  {:else if name === 'android'}
    <path d="M5 11a7 7 0 0 1 14 0v6H5z" />
    <path d="M9 7L7 5M15 7l2-2" />
    <circle cx="9" cy="13" r=".5" fill="currentColor" />
    <circle cx="15" cy="13" r=".5" fill="currentColor" />
  {:else if name === 'windows'}
    <path d="M3 5.5L11 4v7.5H3zM12 3.8L21 2.5V11.5H12zM3 12.5h8V20l-8-1.2zM12 12.5h9V21.5L12 20.2z" fill="currentColor" stroke="none" />
  {:else if name === 'linux'}
    <rect x="2" y="3" width="20" height="13" rx="2" />
    <path d="M7 9l2 2-2 2M11 13h6" />
    <path d="M8 20h8M12 16v4" />
  {:else if name === 'github'}
    <path
      d="M12 2a10 10 0 0 0-3.2 19.5c.5.1.7-.2.7-.5v-2c-2.7.6-3.3-1.2-3.3-1.2-.5-1.1-1.1-1.4-1.1-1.4-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.3 1.1 2.9.8.1-.7.4-1.1.6-1.4-2.2-.3-4.5-1.1-4.5-4.9 0-1.1.4-2 1-2.7-.1-.3-.5-1.3.1-2.7 0 0 .8-.3 2.7 1 .8-.2 1.6-.3 2.5-.3s1.7.1 2.5.3c1.9-1.3 2.7-1 2.7-1 .5 1.4.2 2.4.1 2.7.6.7 1 1.6 1 2.7 0 3.8-2.3 4.6-4.5 4.9.4.3.7.9.7 1.8v2.7c0 .3.2.6.7.5A10 10 0 0 0 12 2z"
      fill="currentColor"
      stroke="none"
    />
  {:else if name === 'link'}
    <path d="M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" />
    <path d="M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" />
  {:else if name === 'plus'}
    <path d="M12 5v14M5 12h14" />
  {:else if name === 'arrow'}
    <path d="M5 12h14M13 6l6 6-6 6" />
  {:else if name === 'download'}
    <path d="M12 3v12M7 10l5 5 5-5" />
    <path d="M4 19h16" />
  {:else if name === 'menu'}
    <path d="M4 6h16M4 12h16M4 18h16" />
  {:else if name === 'x'}
    <path d="M18 6L6 18M6 6l12 12" />
  {/if}
</svg>
