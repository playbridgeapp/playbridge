<script lang="ts">
  import LogoMark from '$lib/icons/LogoMark.svelte';
  import { SITE } from '$lib/data/site';
</script>

<footer class="footer">
  <div class="wrap">
    <div class="footer__grid">
      <div>
        <div class="logo">
          <LogoMark size={22} />
          <span>{SITE.name}</span>
        </div>
        <p class="footer__tag">Open-source casting suite. Your phone, your TV, one bridge.</p>
      </div>
      <div>
        <h4>Product</h4>
        <ul>
          <li><a href="/#platforms">Platforms</a></li>
          <li><a href="/#features">Features</a></li>
          <li><a href="/#install">Install</a></li>
          <li><a href="{SITE.github}/releases" rel="noopener">Changelog</a></li>
        </ul>
      </div>
      <div>
        <h4>Community</h4>
        <ul>
          <li><a href={SITE.github} rel="noopener">GitHub</a></li>
          <li><a href="https://matrix.to/#/#playbridge:matrix.org" rel="noopener">Matrix</a></li>
          <li><a href="{SITE.github}/blob/main/CONTRIBUTING.md" rel="noopener">Contribute</a></li>
          <li><a href="https://github.com/sponsors/truedem0n" rel="noopener">Sponsors</a></li>
        </ul>
      </div>
      <div>
        <h4>Legal</h4>
        <ul>
          <li><a href="/privacy">Privacy</a></li>
          <li><a href="/security">Security</a></li>
          <li><a href="https://www.gnu.org/licenses/gpl-3.0.html" rel="noopener">License (GPL-3.0)</a></li>
          <li><a href="mailto:{SITE.email}">{SITE.email}</a></li>
        </ul>
      </div>
    </div>
    <div class="footer__bottom">
      <span>© 2026 PlayBridge contributors</span>
      <span>{SITE.version}</span>
    </div>
  </div>
</footer>
