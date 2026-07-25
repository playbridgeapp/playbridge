<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
</script>

<section class="section wrap" id="install">
  <div class="section-head">
    <span class="eyebrow">Install</span>
    <h2>Senders and receivers, separately.</h2>
    <p>
      Pick the job first — cast from a device, or watch on one. Desktop and the CLI do both; each
      page shows the steps for that role.
    </p>
  </div>

  <div class="install-cta">
    <a class="install-card" href="/senders">
      <span class="install-card__eyebrow">Senders</span>
      <h3>Cast from phone, browser, Desktop, or CLI</h3>
      <p>Browse, detect media, and push to a receiver on your Wi‑Fi.</p>
      <span class="install-card__link">
        Install senders <Icon name="arrow" size={12} />
      </span>
    </a>
    <a class="install-card install-card--accent" href="/receivers">
      <span class="install-card__eyebrow">Receivers</span>
      <h3>Watch on TV, Desktop, or CLI</h3>
      <p>Install on the screen you watch on. Approve senders once; they stay trusted.</p>
      <span class="install-card__link">
        Install receivers <Icon name="arrow" size={12} />
      </span>
    </a>
  </div>
</section>

<style>
  .install-cta {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
  }
  .install-card {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 28px 26px;
    border-radius: 16px;
    border: 1px solid var(--line-strong);
    background: var(--surface);
    text-decoration: none;
    color: inherit;
    transition:
      border-color 0.15s ease,
      background 0.15s ease,
      transform 0.15s ease;
  }
  .install-card:hover {
    border-color: rgba(74, 144, 226, 0.45);
    background: rgba(74, 144, 226, 0.06);
    transform: translateY(-1px);
  }
  .install-card--accent {
    border-color: rgba(74, 144, 226, 0.28);
  }
  .install-card__eyebrow {
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--accent);
  }
  .install-card h3 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    line-height: 1.25;
  }
  .install-card p {
    margin: 0;
    font-size: 14px;
    line-height: 1.5;
    color: var(--text-dim);
    flex: 1;
  }
  .install-card__link {
    margin-top: 8px;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 500;
    color: var(--accent);
  }

  @media (max-width: 720px) {
    .install-cta {
      grid-template-columns: 1fr;
    }
  }
</style>
