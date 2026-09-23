<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import { SITE } from '$lib/data/site';
</script>

<Seo
  title="Privacy"
  description="How PlayBridge stores app data and when browsing, casting, and optional services contact other devices or websites."
  path="/privacy"
/>

<section class="section wrap">
  <div class="section-head">
    <span class="eyebrow">Legal</span>
    <h2>Privacy</h2>
  </div>
  <div class="prose">
    <p><strong>Short version.</strong> PlayBridge does not require an account or include an in-app analytics or advertising SDK. Casting to a receiver on your local network can stay local. Browsing the web, searching, updating filter lists, and using a remote receiver or proxy can send data outside your local network.</p>

    <h3>Data on your device</h3>
    <p>The iOS sender stores browser history, bookmarks, downloads, cast history, settings, and saved receiver details on your device. Receiver pairing credentials are stored in the iOS Keychain. You can clear browser history and website data in Browser settings and forget saved receivers in the Devices screen. PlayBridge does not operate an account service that stores your browsing or casting history.</p>

    <h3>Connections you choose</h3>
    <p>Websites you visit and the search engine you select receive ordinary browser requests and may receive your IP address, browser information, cookies, and search terms. The iOS browser can download content-blocking lists from their publishers. Those sites and list publishers handle data under their own policies.</p>
    <p>When you cast, the selected receiver receives the media address and information needed to play it, such as request headers. Casting a file from your phone sends that file to the receiver. If you configure a remote proxy or another third-party service, requests and media information may also pass through that service. Review its privacy policy before using it.</p>

    <h3>Retention and contact</h3>
    <p>Information kept by the iOS app remains there until you clear or remove it, subject to your device's storage and backup settings. External websites, search providers, receivers, and services control their own retention. PlayBridge does not include a built-in crash-reporting or analytics service; operating-system or app-store diagnostics are controlled separately by your device settings.</p>
    <p>For privacy questions, contact <a href="mailto:{SITE.email}">{SITE.email}</a>.</p>
  </div>
</section>

<style>
  .prose { max-width: 640px; }
  .prose p { margin-top: 18px; font-size: 15px; line-height: 1.7; }
  .prose h3 { margin-top: 30px; font-size: 19px; }
  .prose strong { color: var(--text); }
</style>
