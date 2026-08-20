<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { SENDERS, RECEIVERS } from '$lib/data/site';
</script>

<section class="section wrap" id="platforms">
  <div class="section-head">
    <span class="eyebrow">Platforms</span>
    <h2>Watch on one device. Cast from another.</h2>
    <p>
      Receivers play. Senders push. Desktop and the CLI do both — install guides are split by role so
      the steps stay clear.
    </p>
  </div>

  <div class="pairing">
    <div class="pairing__col">
      <div class="pairing__label">
        <span class="eyebrow">Watch on</span>
        <p>Install a receiver on the device you actually watch on.</p>
      </div>
      <ul class="pairing__list">
        {#each RECEIVERS as c}
          <li>
            <a class="pairing__item" href={c.href}>
              <span class="pairing__icon"><Icon name={c.icon} /></span>
              <span class="pairing__copy">
                <strong>{c.name}</strong>
                <span>{c.desc}</span>
              </span>
            </a>
          </li>
        {/each}
      </ul>
    </div>

    <div class="pairing__col">
      <div class="pairing__label">
        <span class="eyebrow">Cast from</span>
        <p>Browse and pick on these — they push content to a receiver.</p>
      </div>
      <ul class="pairing__list">
        {#each SENDERS as c}
          <li>
            <a class="pairing__item" href={c.href}>
              <span class="pairing__icon"><Icon name={c.icon} /></span>
              <span class="pairing__copy">
                <strong>{c.name}</strong>
                <span>{c.desc}</span>
              </span>
            </a>
          </li>
        {/each}
      </ul>
    </div>
  </div>

  <div class="pairing__cta">
    <a href="/receivers" class="btn btn--primary">Get a receiver</a>
    <a class="pairing__link" href="/senders">All senders</a>
  </div>
</section>

<style>
  .pairing {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 48px;
    border-top: 1px solid var(--line);
  }
  .pairing__col {
    padding-top: 32px;
  }
  .pairing__col + .pairing__col {
    border-left: 1px solid var(--line);
    padding-left: 48px;
  }
  .pairing__label {
    margin-bottom: 20px;
    max-width: 42ch;
  }
  .pairing__label .eyebrow {
    display: block;
    margin-bottom: 8px;
  }
  .pairing__label p {
    font-size: 14px;
  }
  .pairing__list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .pairing__item {
    display: flex;
    align-items: flex-start;
    gap: 14px;
    padding: 16px 0;
    border-top: 1px solid var(--line);
    color: inherit;
  }
  .pairing__item:hover strong {
    color: var(--accent);
  }
  .pairing__icon {
    width: 32px;
    height: 32px;
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    color: var(--accent);
    border: 1px solid var(--line);
    border-radius: 8px;
  }
  .pairing__icon :global(svg) {
    width: 16px;
    height: 16px;
  }
  .pairing__copy {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .pairing__copy strong {
    font-size: 15px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  .pairing__copy span {
    font-size: 13px;
    line-height: 1.45;
    color: var(--text-dim);
  }
  .pairing__cta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 18px;
    margin-top: 32px;
  }
  .pairing__link {
    font-size: 14px;
    font-weight: 500;
    color: var(--text-dim);
    border-bottom: 1px solid var(--line-strong);
    padding-bottom: 1px;
    transition:
      color 0.15s ease,
      border-color 0.15s ease;
  }
  .pairing__link:hover {
    color: var(--text);
    border-bottom-color: color-mix(in oklab, var(--accent) 60%, transparent);
  }

  @media (max-width: 800px) {
    .pairing {
      grid-template-columns: 1fr;
      gap: 24px;
    }
    .pairing__cta {
      flex-direction: column;
      align-items: stretch;
    }
    .pairing__cta .btn { justify-content: center; }
    .pairing__col + .pairing__col {
      border-left: 0;
      padding-left: 0;
      border-top: 1px solid var(--line);
    }
  }
</style>
