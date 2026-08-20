<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import InstallRole from '$lib/components/InstallRole.svelte';
</script>

<Seo
  path="/senders"
  title="Install senders"
  description="Install PlayBridge senders: Android phone, browser extensions, Desktop, and CLI. Cast media to receivers on your local network."
/>

<section class="section wrap role-page">
  <div class="section-head">
    <span class="eyebrow">Senders</span>
    <h1>Cast from these devices.</h1>
    <p>
      Senders browse, detect, and push media to a <strong>receiver</strong> on the same Wi‑Fi. Desktop
      and the CLI also work as receivers — this page is only the cast-out path.
    </p>
    <div class="role-page__switch">
      <a class="role-page__link" href="/receivers">Looking to watch? Get a receiver</a>
    </div>
  </div>

  <InstallRole role="sender" />
</section>

<style>
  .role-page {
    padding-top: 48px;
  }
  .role-page h1 {
    font-size: clamp(28px, 4vw, 40px);
    margin: 0 0 12px;
    letter-spacing: -0.02em;
  }
  .role-page .section-head p {
    max-width: 560px;
  }
  .role-page .section-head strong {
    color: var(--text);
    font-weight: 500;
  }
  .role-page__switch {
    margin-top: 20px;
  }
  .role-page__link {
    font-size: 14px;
    font-weight: 500;
    color: var(--text-dim);
    border-bottom: 1px solid var(--line-strong);
    padding-bottom: 1px;
  }
  .role-page__link:hover {
    color: var(--text);
    border-bottom-color: color-mix(in oklab, var(--accent) 60%, transparent);
  }
</style>
