<script lang="ts">
  import Seo from '$lib/seo/Seo.svelte';
  import Icon from '$lib/icons/Icon.svelte';
  import InstallRole from '$lib/components/InstallRole.svelte';
</script>

<Seo
  path="/receivers"
  title="Install receivers"
  description="Install PlayBridge receivers: Android TV, Apple TV, Desktop, and CLI. Watch casts from phones, extensions, and more on your local network."
/>

<section class="section wrap role-page">
  <div class="section-head">
    <span class="eyebrow">Receivers</span>
    <h1>Watch on these devices.</h1>
    <p>
      Receivers play what <strong>senders</strong> cast. Put one on the screen you watch on, approve
      senders once, and they stay trusted. Desktop and the CLI also send — this page is the
      receive path.
    </p>
    <div class="cta-row role-page__switch">
      <a class="btn" href="/senders">
        Looking to cast? Install senders <Icon name="arrow" size={12} />
      </a>
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
</style>
