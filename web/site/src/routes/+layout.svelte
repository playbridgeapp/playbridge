<script lang="ts">
  import '../app.css';
  import Nav from '$lib/components/Nav.svelte';
  import Footer from '$lib/components/Footer.svelte';
  import type { Snippet } from 'svelte';
  import { fontFaceCss, fontFiles } from '$lib/fonts';

  interface Props { children: Snippet; }
  let { children }: Props = $props();
</script>

<svelte:head>
  <link rel="preload" href={fontFiles.serif600} as="font" type="font/woff2" crossorigin="anonymous" />
  <link rel="preload" href={fontFiles.sans400} as="font" type="font/woff2" crossorigin="anonymous" />
  <link rel="preload" href={fontFiles.sans500} as="font" type="font/woff2" crossorigin="anonymous" />
  <link rel="preload" href={fontFiles.sans600} as="font" type="font/woff2" crossorigin="anonymous" />
  {@html `<style>${fontFaceCss}</style>`}
</svelte:head>

<Nav />
<main id="main">
  {@render children()}
</main>
<Footer />
