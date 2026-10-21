<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import { SITE } from '$lib/data/site';
</script>

<Seo
  title="Security"
  description="How to report a PlayBridge vulnerability, what is in scope, and how pairing and the encrypted local connection work."
  path="/security"
/>

<section class="section wrap">
  <div class="section-head">
    <span class="eyebrow">Legal</span>
    <h2>Security</h2>
  </div>
  <div class="prose">
    <h3 id="report">Report a vulnerability</h3>
    <p>Found a vulnerability? Please email <a href="mailto:{SITE.email}">{SITE.email}</a> rather than filing a public issue. Include the app, its version, your platform, and steps to reproduce.</p>
    <p>We confirm receipt within 72 hours and aim to ship a fix within 14 days for high-severity issues. Credits go in the release notes unless you ask otherwise.</p>
    <p>Machine-readable contact details are in <a href="/.well-known/security.txt" data-sveltekit-reload>/.well-known/security.txt</a>.</p>

    <h3 id="scope">Scope</h3>
    <ul>
      <li>Android phone app (sender)</li>
      <li>Android TV / Fire TV receiver and its GeckoView browser plugin</li>
      <li>PlayBridge Desktop for macOS, Windows, and Linux</li>
      <li>PlayBridge Video Detector browser extension (Chrome and Firefox)</li>
      <li>The <code>playbridge</code> CLI and its installers</li>
      <li>The iPhone and Apple TV apps (pre-release, built from source)</li>
      <li>playbridge.app, including its download and update endpoints</li>
    </ul>
    <p>Problems in third-party websites, add-ons, debrid services, or a TV's own DLNA software belong with their operators. The design does not try to protect secrets on a device whose operating system is already compromised (rooted or jailbroken).</p>

    <h3 id="versions">Supported versions</h3>
    <p>PlayBridge ships 0.x Alpha releases, versioned separately for each app. Security fixes go into the latest release of each app and the <code>main</code> branch. Older releases do not get backports, so update to the latest release.</p>

    <h3 id="pairing">How pairing works</h3>
    <p>The first time a sender connects, the receiver shows the sender's name and a 6-digit code. Type that code on the sender. The request expires after 60 seconds, and the receiver can deny it.</p>
    <p>Behind the code: both devices make fresh X25519 keys, and the sender commits to its key before seeing the receiver's. Each side derives the 6-digit code from the shared secret, so a device in the middle would make the two codes differ. After the code matches, the sender proves it holds the same key. The receiver then sends a token and its certificate fingerprint, encrypted with AES-256-GCM. The token never travels unencrypted.</p>

    <h3 id="connection">After pairing</h3>
    <p>Senders and receivers talk only over WSS (TLS). Unencrypted control connections are refused, with no fallback. A paired sender checks the receiver's certificate against the saved fingerprint before it sends its token or any command. If the fingerprint changes, the connection stops and you pair again. Until a sender authenticates, a receiver accepts only pairing and sign-in messages.</p>
    <p>The Android phone encrypts saved tokens with an Android Keystore key, and the iPhone app keeps them in the Keychain. Desktop and Apple TV receivers store a one-way verifier of each sender's token instead of the token itself.</p>
    <p>Media is a separate path. Some media servers, especially local ones, use plain HTTP or self-signed certificates, so receivers can play them. That does not weaken the control connection.</p>

    <h3 id="details">Technical details</h3>
    <p>The full requirements are in the <a href={`${SITE.github}/blob/main/docs/SECURITY_STANDARD.md`} rel="noopener">security standard</a>. Known gaps and their fix order are tracked in public in <a href={`${SITE.github}/blob/main/docs/SECURITY_GAPS.md`} rel="noopener">SECURITY_GAPS.md</a>.</p>
  </div>
</section>

<style>
  .prose { max-width: 640px; }
  .prose p { margin-top: 18px; font-size: 15px; line-height: 1.7; }
  .prose h3 { margin-top: 36px; font-size: 19px; scroll-margin-top: 96px; }
  .prose h3:first-child { margin-top: 0; }
  .prose a { color: var(--accent); }
  .prose ul { margin: 12px 0 0; padding-left: 20px; }
  .prose li { margin-top: 6px; font-size: 15px; line-height: 1.6; color: var(--text-dim); }
  .prose code { font-family: var(--font-mono-ui); font-size: 0.88em; }
</style>
