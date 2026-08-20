<script lang="ts">
  import { FEATURE_BANDS, type FeatureItem } from '$lib/data/site';
  import AllowPrompt from './visuals/AllowPrompt.svelte';
  import RemoteMock from './visuals/RemoteMock.svelte';
  import QueueMock from './visuals/QueueMock.svelte';
  import EngineMock from './visuals/EngineMock.svelte';
  import LibraryMock from './visuals/LibraryMock.svelte';
  import BrowserMock from './visuals/BrowserMock.svelte';

  function visual(item: FeatureItem) {
    switch (item.visual) {
      case 'allow':
        return AllowPrompt;
      case 'remote':
        return RemoteMock;
      case 'queue':
        return QueueMock;
      case 'engine':
        return EngineMock;
      case 'library':
        return LibraryMock;
      case 'browser':
        return BrowserMock;
    }
  }
</script>

<section class="section wrap" id="features">
  <div class="section-head">
    <span class="eyebrow">Features</span>
    <h2>Find it on the phone. Play it on the screen.</h2>
  </div>

  <div class="bands">
    {#each FEATURE_BANDS as band, i}
      {@const PrimaryVisual = visual(band.primary)}
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
          <PrimaryVisual />
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
  .band__visual :global(.frame) {
    max-width: 100%;
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
  }
</style>
