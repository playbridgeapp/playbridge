<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { SENDERS, RECEIVERS } from '$lib/data/site';
</script>

<!--
  One install section for the homepage: receivers first (the primary choice), senders second.
  `#install` (hero, nav) targets the section; `#platforms` (footer) targets the pairing grid.
-->
<section class="section wrap install" id="install">
  <div class="section-head">
    <span class="eyebrow">Install</span>
    <h2>Start with the screen you watch on.</h2>
    <p>
      Install a receiver where you watch. Then cast to it from any sender — Desktop and the CLI do
      both.
    </p>
  </div>

  <div class="pick" id="platforms">
    <div class="pick__col pick__col--watch">
      <div class="pick__label">
        <span class="pick__num">01</span>
        <span class="pick__role">Watch on</span>
        <span class="pick__hint">Receiver</span>
      </div>
      <ul class="pick__list">
        {#each RECEIVERS as c}
          <li>
            <a class="pick__item" href={c.href}>
              <span class="pick__icon"><Icon name={c.icon} size={16} /></span>
              <span class="pick__copy">
                <strong>{c.name}</strong>
                <span>{c.desc}</span>
              </span>
            </a>
          </li>
        {/each}
      </ul>
      <div class="pick__cta">
        <a href="/receivers" class="btn btn--primary"
          ><Icon name="download" size={14} /> Get a receiver</a
        >
      </div>
    </div>

    <div class="pick__col pick__col--cast">
      <div class="pick__label">
        <span class="pick__num">02</span>
        <span class="pick__role">Cast from</span>
        <span class="pick__hint">Sender</span>
      </div>
      <ul class="pick__list">
        {#each SENDERS as c}
          <li>
            <a class="pick__item" href={c.href}>
              <span class="pick__icon"><Icon name={c.icon} size={16} /></span>
              <span class="pick__copy">
                <strong>{c.name}</strong>
                <span>{c.desc}</span>
              </span>
            </a>
          </li>
        {/each}
      </ul>
      <div class="pick__cta">
        <a class="pick__link" href="/senders">All senders <Icon name="arrow" size={12} /></a>
      </div>
    </div>
  </div>
</section>

<style>
  .pick {
    /* Clear the sticky nav when jumping to /#platforms. */
    scroll-margin-top: 96px;
    display: grid;
    grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
    gap: 0;
    align-items: stretch;
  }
  .pick__col {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .pick__col--watch {
    padding: 28px 32px 32px;
    border-radius: 12px;
    border: 1px solid color-mix(in oklab, var(--accent) 32%, var(--line));
    background: var(--surface);
  }
  .pick__col--cast {
    padding: 29px 0 32px 40px;
  }

  .pick__label {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 12px;
  }
  .pick__num {
    font-family: var(--font-mono-ui);
    font-size: 11px;
    letter-spacing: 0.14em;
    color: var(--accent);
  }
  .pick__role {
    font-size: 18px;
    font-weight: 600;
    letter-spacing: -0.01em;
    color: var(--text);
  }
  .pick__col--cast .pick__num,
  .pick__col--cast .pick__role {
    color: var(--text-dim);
  }
  .pick__hint {
    margin-left: auto;
    font-family: var(--font-mono-ui);
    font-size: 10px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--text-faint);
  }

  .pick__list {
    list-style: none;
    margin: 0;
    padding: 0;
    flex: 1;
  }
  .pick__item {
    display: flex;
    align-items: flex-start;
    gap: 14px;
    padding: 14px 0;
    border-top: 1px solid var(--line);
    color: inherit;
  }
  .pick__item:hover strong {
    color: var(--accent);
  }
  .pick__icon {
    width: 32px;
    height: 32px;
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    color: var(--accent);
    border: 1px solid var(--line);
    border-radius: 8px;
  }
  .pick__col--cast .pick__icon {
    color: var(--text-dim);
  }
  .pick__copy {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }
  .pick__copy strong {
    font-size: 15px;
    font-weight: 600;
    letter-spacing: -0.01em;
    transition: color 0.15s ease;
  }
  .pick__copy span {
    font-size: 13px;
    line-height: 1.45;
    color: var(--text-dim);
  }
  .pick__col--cast .pick__copy strong {
    font-size: 14px;
    font-weight: 500;
  }
  .pick__col--cast .pick__copy span {
    color: var(--text-faint);
  }

  .pick__cta {
    margin-top: 20px;
    display: flex;
    align-items: center;
    min-height: 44px;
  }
  .pick__link {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    font-weight: 500;
    color: var(--text-dim);
    border-bottom: 1px solid var(--line-strong);
    padding-bottom: 1px;
    transition:
      color 0.15s ease,
      border-color 0.15s ease;
  }
  .pick__link:hover {
    color: var(--text);
    border-bottom-color: color-mix(in oklab, var(--accent) 60%, transparent);
  }

  @media (max-width: 860px) {
    .pick {
      grid-template-columns: 1fr;
      gap: 24px;
    }
    .pick__col--cast {
      padding: 4px 0 0;
    }
  }
  @media (max-width: 600px) {
    .pick__col--watch {
      padding: 22px 18px 20px;
    }
    .pick__cta .btn {
      width: 100%;
      justify-content: center;
    }
  }
</style>
