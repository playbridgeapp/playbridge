<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import { SITE } from '$lib/data/site';
</script>

<Seo
  title="Security"
  description="How to report security issues in PlayBridge and our disclosure policy."
  path="/security"
/>

<section class="section wrap">
  <div class="section-head">
    <span class="eyebrow">Legal</span>
    <h2>Security</h2>
  </div>
  <div class="prose">
    <p>Found a vulnerability? Please email <a href="mailto:{SITE.email}">{SITE.email}</a> rather than filing a public issue.</p>
    <p>We confirm receipt within 72 hours and aim to ship a fix within 14 days for high-severity issues. Credits go in the release notes unless you ask otherwise.</p>
    <p>PlayBridge is local-network only by design. Pairing is approval-based with a fingerprint shown on both devices.</p>
  </div>
</section>

<style>
  .prose { max-width: 640px; }
  .prose p { margin-top: 18px; font-size: 15px; line-height: 1.7; }
  .prose a { color: var(--accent); }
</style>
