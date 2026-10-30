<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
</script>

<Seo
  title="Support"
  description="Get started with PlayBridge, troubleshoot local-network casting, and contact the project."
  path="/support"
/>

<section class="section wrap">
  <div class="section-head">
    <span class="eyebrow">Help</span>
    <h2>Support</h2>
  </div>
  <div class="prose">
    <p>PlayBridge casts web and local video to your TV. It provides no content; choose the video you want to play.</p>

    <h3 id="getting-started">Getting started</h3>
    <ol>
      <li><a href="/receivers">Install a PlayBridge receiver</a> on your TV or desktop and open it.</li>
      <li>Connect your phone and receiver to the same Wi-Fi network.</li>
      <li>Open PlayBridge on your phone, choose the receiver in the device picker, and pair using the code shown on the receiver.</li>
      <li>Open a video page in the phone's browser. Play the video, tap the detected-media button, and cast it to the receiver.</li>
    </ol>

    <h3 id="troubleshooting">Troubleshooting</h3>
    <ul>
      <li><strong>iPhone can't find the receiver:</strong> allow Local Network access for PlayBridge in iPhone Settings. Reopen the device picker after changing the permission.</li>
      <li><strong>The receiver isn't listed:</strong> check that both devices are on the same network and that your Wi-Fi allows devices to communicate. Guest networks may isolate devices.</li>
      <li><strong>Desktop won't connect:</strong> allow PlayBridge through the desktop firewall for your local network, then try pairing again.</li>
    </ul>

    <h3 id="contact">Contact</h3>
    <p>For help or to report a problem, <a href="https://github.com/playbridgeapp/playbridge/issues" rel="noopener">open a GitHub issue</a>. Include your app version, phone or computer platform, receiver type, and the steps that led to the problem.</p>
  </div>
</section>

<style>
  .prose { max-width: 640px; }
  .prose p { margin-top: 18px; font-size: 15px; line-height: 1.7; }
  .prose h3 { margin-top: 36px; font-size: 19px; scroll-margin-top: 96px; }
  .prose a { color: var(--accent); }
  .prose ul, .prose ol { margin: 12px 0 0; padding-left: 20px; }
  .prose li { margin-top: 6px; font-size: 15px; line-height: 1.6; color: var(--text-dim); }
</style>
