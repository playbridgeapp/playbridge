<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
</script>

<Seo
  title="Privacy"
  description="PlayBridge has no accounts, no telemetry, and runs only on your local network. Nothing leaves your home."
  path="/privacy"
/>

<section class="section wrap">
  <div class="section-head">
    <span class="eyebrow">Legal</span>
    <h2>Privacy</h2>
  </div>
  <div class="prose">
    <p><strong>Short version.</strong> PlayBridge has no accounts, no analytics, and no telemetry. It runs entirely on your local network.</p>
    <p>The Sender (phone) and Receiver (TV / desktop) communicate over a WebSocket on your LAN. Nothing about what you watch, search for, or cast is transmitted to PlayBridge or any third party.</p>
    <p>Some optional integrations — Real-Debrid, AllDebrid, Stremio addons — are direct connections from your device to those services. Their privacy policies apply when you use them.</p>
    <p>Crash reports are off by default and never enabled silently.</p>
  </div>
</section>

<style>
  .prose { max-width: 640px; }
  .prose p { margin-top: 18px; font-size: 15px; line-height: 1.7; }
  .prose strong { color: var(--text); }
</style>
