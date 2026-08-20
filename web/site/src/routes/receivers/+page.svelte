<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import InstallRole from '$lib/components/InstallRole.svelte';
</script>

<Seo
  path="/receivers"
  title="Install receivers"
  description="Watch on Android TV, Apple TV, Desktop, CLI, or a DLNA / UPnP TV. PlayBridge receivers install on the screen; DLNA TVs need nothing extra."
/>

<section class="section wrap role-page">
  <div class="section-head">
    <span class="eyebrow">Receivers</span>
    <h1>Watch on these devices.</h1>
    <p>
      Receivers play what <strong>senders</strong> cast. Install PlayBridge on the screen, or use a
      DLNA / UPnP TV that already has a renderer — the Android phone finds it, nothing to install
      on that set.
    </p>
    <div class="role-page__switch">
      <a class="role-page__link" href="/senders">Looking to cast? Senders</a>
    </div>
  </div>

  <InstallRole role="receiver" />
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
