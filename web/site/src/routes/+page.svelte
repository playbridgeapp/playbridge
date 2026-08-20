<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import Hero from '$lib/components/Hero.svelte';
  import HowItWorks from '$lib/components/HowItWorks.svelte';
  import Platforms from '$lib/components/Platforms.svelte';
  import Features from '$lib/components/Features.svelte';
  import InstallCta from '$lib/components/InstallCta.svelte';
  import { SITE } from '$lib/data/site';

  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: SITE.name,
    description: SITE.description,
    applicationCategory: 'MultimediaApplication',
    operatingSystem: 'Android, Android TV, tvOS, macOS, Windows, Linux',
    url: SITE.url,
    downloadUrl: `${SITE.url}/receivers`,
    license: 'https://www.gnu.org/licenses/gpl-3.0.html',
    isAccessibleForFree: true,
    offers: {
      '@type': 'Offer',
      price: '0',
      priceCurrency: 'USD'
    },
    sameAs: [SITE.github, SITE.githubOrg, SITE.discord]
  };
</script>

<Seo path="/" {jsonLd} />

<Hero />
<HowItWorks />
<InstallCta />
<Platforms />
<Features />
