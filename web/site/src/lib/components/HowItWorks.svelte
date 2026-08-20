<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import BridgeDiagram from './visuals/BridgeDiagram.svelte';
  import { STEPS } from '$lib/data/site';
</script>

<section class="section wrap" id="how">
  <div class="section-head">
    <span class="eyebrow">How it works</span>
    <h2>Senders cast. Receivers play.</h2>
    <p>
      PlayBridge is a local-network bridge: a <strong>sender</strong> pushes media and a
      <strong>receiver</strong> plays it. Phone, extension, Desktop, CLI, TV — mix any pair on the
      same Wi‑Fi. No account, no cables, no cloud.
    </p>
  </div>

  <div class="how__diagram">
    <BridgeDiagram />
  </div>

  <div class="steps">
    {#each STEPS as step}
      <div class="step">
        <span class="step__phase" class:step__phase--use={step.phase === 'Every time'}>
          {step.phase}
        </span>
        <div class="step__num">{step.num}</div>
        <h3>{step.title}</h3>
        <p>{step.desc}</p>
      </div>
    {/each}
  </div>

  <div class="how__cta">
    <a href="/#install" class="btn btn--primary"
      ><Icon name="download" size={14} /> Get a receiver</a
    >
    <a class="how__link" href="/receivers">Receivers</a>
    <a class="how__link" href="/senders">Senders</a>
  </div>
</section>

<style>
  .section-head strong {
    color: var(--text);
    font-weight: 500;
  }

  .step__phase {
    display: inline-block;
    font-family: var(--font-mono-ui);
    font-size: 9px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--text-faint);
    background: rgba(200, 220, 255, 0.05);
    border: 1px solid var(--line);
    padding: 3px 8px;
    border-radius: 99px;
    margin-bottom: 16px;
  }
  .step__phase--use {
    color: var(--accent);
    background: color-mix(in oklab, var(--accent) 12%, transparent);
    border-color: color-mix(in oklab, var(--accent) 28%, transparent);
  }

  .how__diagram {
    margin: -16px 0 64px;
  }

  .how__cta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 18px;
    margin-top: 48px;
  }
  .how__link {
    font-size: 14px;
    font-weight: 500;
    color: var(--text-dim);
    border-bottom: 1px solid var(--line-strong);
    padding-bottom: 1px;
    transition: color 0.15s ease, border-color 0.15s ease;
  }
  .how__link:hover {
    color: var(--text);
    border-bottom-color: color-mix(in oklab, var(--accent) 60%, transparent);
  }
  @media (max-width: 600px) {
    .how__cta {
      flex-direction: column;
      align-items: stretch;
      gap: 14px;
    }
    .how__cta .btn { justify-content: center; }
    .how__diagram { margin: 0 0 36px; }
  }
</style>
