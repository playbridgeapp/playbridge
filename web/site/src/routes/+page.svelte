<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import Hero from '$lib/components/Hero.svelte';
  import HowItWorks from '$lib/components/HowItWorks.svelte';
  import Platforms from '$lib/components/Platforms.svelte';
  import Features from '$lib/components/Features.svelte';
  import Install from '$lib/components/Install.svelte';
  import { SITE } from '$lib/data/site';

  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: SITE.name,
    description: SITE.description,
    applicationCategory: 'MultimediaApplication',
    operatingSystem: 'Android, Android TV, tvOS, macOS, Windows, Linux',
    url: SITE.url,
    downloadUrl: `${SITE.github}/releases`,
    softwareVersion: SITE.version,
    license: 'https://www.gnu.org/licenses/gpl-3.0.html',
    offers: {
      '@type': 'Offer',
      price: '0',
      priceCurrency: 'USD'
    },
    image: `${SITE.url}${SITE.ogImage}`
  };
</script>

<Seo path="/" {jsonLd} />

<Hero />
<HowItWorks />
<Platforms />
<Features />
<Install />
