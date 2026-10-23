<script lang="ts">
  import { FEATURE_BANDS, type FeatureBand, type FeatureItem } from '$lib/data/site';
  import Device from './visuals/Device.svelte';
  import { SHOTS, type Shot } from './visuals/screens';

  type Capture = { shot: Shot; kind: 'tv' | 'phone' };

  /** Real captures per feature. FILES and QUEUE have no clean capture yet, so their bands show their partner's capture alone. */
  const CAPTURES: Partial<Record<FeatureItem['visual'], Capture>> = {
    browser: { shot: SHOTS.browser, kind: 'phone' },
    allow: { shot: SHOTS.pairing, kind: 'tv' },
    remote: { shot: SHOTS.remote, kind: 'phone' },
    engine: { shot: SHOTS.engines, kind: 'tv' }
  };

  function captures(band: FeatureBand) {
    const all = [band.primary, band.secondary]
      .map((item) => CAPTURES[item.visual])
      .filter((c): c is Capture => !!c);
    return {
      tv: all.find((c) => c.kind === 'tv'),
      phone: all.find((c) => c.kind === 'phone')
    };
  }
</script>

<section class="section wrap" id="features">
  <div class="section-head">
    <span class="eyebrow">Features</span>
    <h2>Find it on the phone. Play it on the screen.</h2>
  </div>

  <div class="bands">
    {#each FEATURE_BANDS as band, i}
      {@const { tv, phone } = captures(band)}
      <article class="band" class:band--flip={i % 2 === 1}>
        <div class="band__copy">
          <span class="band__tag">{band.primary.tag}</span>
          <h3>{band.primary.title}</h3>
          <p>{band.primary.desc}</p>
          <div class="band__aside">
            <span class="band__tag">{band.secondary.tag}</span>
            <h4>{band.secondary.title}</h4>
            <p>{band.secondary.desc}</p>
          </div>
        </div>
        <div class="band__visual">
          {#if tv && phone}
            <div class="stage" class:stage--phone-right={i % 2 === 1}>
              <Device
                kind="tv"
                class="stage__tv"
                shot={tv.shot}
                sizes="(max-width: 800px) calc(80vw - 32px), 432px"
              />
              <Device
                kind="phone"
                class="stage__phone"
                shot={phone.shot}
                sizes="(max-width: 800px) 26vw, 140px"
              />
            </div>
          {:else if tv}
            <Device kind="tv" class="solo solo--tv" shot={tv.shot} sizes="(max-width: 800px) calc(100vw - 52px), 520px" />
          {:else if phone}
            <Device kind="phone" class="solo solo--phone" shot={phone.shot} sizes="(max-width: 800px) 240px, 280px" />
          {/if}
        </div>
      </article>
    {/each}
  </div>
</section>

<style>
  .bands {
    display: flex;
    flex-direction: column;
  }
  .band {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 48px 64px;
    align-items: center;
    padding: 72px 0;
    border-top: 1px solid var(--line);
  }
  .band:last-child {
    border-bottom: 1px solid var(--line);
  }
  .band--flip .band__copy {
    order: 2;
  }
  .band--flip .band__visual {
    order: 1;
    justify-content: flex-start;
  }
  .band__tag {
    font-family: var(--font-mono-ui);
    font-size: 11px;
    letter-spacing: 0.16em;
    color: var(--text-faint);
    text-transform: uppercase;
  }
  .band__copy h3 {
    margin: 12px 0 12px;
    font-size: clamp(22px, 2.4vw, 30px);
    font-weight: 600;
    letter-spacing: -0.02em;
    line-height: 1.15;
    max-width: 18ch;
  }
  .band__copy > p {
    font-size: 16px;
    max-width: 42ch;
  }
  .band__aside {
    margin-top: 32px;
    padding-top: 24px;
    border-top: 1px solid var(--line);
    max-width: 42ch;
  }
  .band__aside h4 {
    margin: 8px 0 8px;
    font-size: 16px;
    font-weight: 600;
  }
  .band__aside p {
    font-size: 14px;
  }
  .band__visual {
    display: flex;
    justify-content: flex-end;
    min-width: 0;
    width: 100%;
  }

  /* TV + phone composition. The phone sits toward the copy column and overlaps
     the TV's sidebar edge; a ring in the page colour separates the two devices. */
  .stage {
    position: relative;
    width: 100%;
    max-width: 540px;
    aspect-ratio: 100 / 70;
  }
  .stage :global(.stage__tv) {
    position: absolute;
    top: 0;
    right: 0;
    width: 80%;
  }
  .stage :global(.stage__phone) {
    position: absolute;
    left: 0;
    bottom: 0;
    width: 26%;
    box-shadow: 0 0 0 5px var(--bg);
  }
  .stage--phone-right :global(.stage__tv) {
    right: auto;
    left: 0;
  }
  .stage--phone-right :global(.stage__phone) {
    left: auto;
    right: 0;
  }
  .band__visual :global(.solo--tv) {
    width: 100%;
    max-width: 520px;
  }
  .band__visual :global(.solo--phone) {
    width: min(100%, 280px);
  }

  @media (max-width: 800px) {
    .band,
    .band--flip .band__copy,
    .band--flip .band__visual {
      display: flex;
      flex-direction: column;
      order: unset;
    }
    .band {
      gap: 20px;
      padding: 36px 0;
    }
    .band__visual,
    .band--flip .band__visual {
      justify-content: flex-start;
    }
    .band__copy h3 {
      max-width: none;
    }
    .band__visual :global(.solo--phone) {
      width: min(100%, 240px);
    }
  }
</style>
