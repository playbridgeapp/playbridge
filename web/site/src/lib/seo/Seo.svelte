<script lang="ts">
  import { SITE } from '$lib/data/site';

  interface Props {
    title?: string;
    description?: string;
    path?: string;
    image?: string;
    /** Pixel size of `image`; defaults match the generated /og.png (1200×630). */
    imageWidth?: number;
    imageHeight?: number;
    imageAlt?: string;
    /** Set on pages that must stay out of search results (e.g. the 404 page). */
    noindex?: boolean;
    type?: 'website' | 'article';
    jsonLd?: Record<string, unknown> | null;
  }

  let {
    title,
    description = SITE.description,
    path = '/',
    image = SITE.ogImage,
    imageWidth = 1200,
    imageHeight = 630,
    imageAlt = 'PlayBridge: “Your phone. Your TV. One bridge.” beside the Android TV receiver pairing screen.',
    noindex = false,
    type = 'website',
    jsonLd = null
  }: Props = $props();

  const fullTitle = $derived(title ? `${title} — ${SITE.name}` : SITE.title);
  const canonical = $derived(`${SITE.url}${path}`);
  const fullImage = $derived(image.startsWith('http') ? image : `${SITE.url}${image}`);
</script>

<svelte:head>
  <title>{fullTitle}</title>
  <meta name="description" content={description} />
  {#if noindex}
    <meta name="robots" content="noindex" />
  {:else}
    <link rel="canonical" href={canonical} />
  {/if}

  <meta property="og:type" content={type} />
  <meta property="og:site_name" content={SITE.name} />
  <meta property="og:locale" content="en_US" />
  <meta property="og:title" content={fullTitle} />
  <meta property="og:description" content={description} />
  <meta property="og:url" content={canonical} />
  <meta property="og:image" content={fullImage} />
  <meta property="og:image:width" content={String(imageWidth)} />
  <meta property="og:image:height" content={String(imageHeight)} />
  <meta property="og:image:alt" content={imageAlt} />

  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:title" content={fullTitle} />
  <meta name="twitter:description" content={description} />
  <meta name="twitter:image" content={fullImage} />
  <meta name="twitter:image:alt" content={imageAlt} />

  {#if jsonLd}
    {@html `<script type="application/ld+json">${JSON.stringify(jsonLd)}<\/script>`}
  {/if}
</svelte:head>
