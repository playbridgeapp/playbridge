<script lang="ts">
  import { SITE } from '$lib/data/site';

  interface Props {
    title?: string;
    description?: string;
    path?: string;
    image?: string;
    type?: 'website' | 'article';
    jsonLd?: Record<string, unknown> | null;
  }

  let {
    title,
    description = SITE.description,
    path = '/',
    image = SITE.ogImage,
    type = 'website',
    jsonLd = null
  }: Props = $props();

  const fullTitle = $derived(title ? `${title} — ${SITE.name}` : `${SITE.name} — ${SITE.tagline}`);
  const canonical = $derived(`${SITE.url}${path}`);
  const fullImage = $derived(image.startsWith('http') ? image : `${SITE.url}${image}`);
</script>

<svelte:head>
  <title>{fullTitle}</title>
  <meta name="description" content={description} />
  <link rel="canonical" href={canonical} />

  <meta property="og:type" content={type} />
  <meta property="og:site_name" content={SITE.name} />
  <meta property="og:title" content={fullTitle} />
  <meta property="og:description" content={description} />
  <meta property="og:url" content={canonical} />
  <meta property="og:image" content={fullImage} />

  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:title" content={fullTitle} />
  <meta name="twitter:description" content={description} />
  <meta name="twitter:image" content={fullImage} />

  {#if jsonLd}
    {@html `<script type="application/ld+json">${JSON.stringify(jsonLd)}<\/script>`}
  {/if}
</svelte:head>
