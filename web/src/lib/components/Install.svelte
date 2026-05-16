<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { INSTALL_TABS } from '$lib/data/site';

  let active = $state('android');
  let copied = $state(false);

  const tab = $derived(INSTALL_TABS.find((t) => t.id === active) ?? INSTALL_TABS[0]);

  async function copy() {
    if (typeof navigator === 'undefined' || !navigator.clipboard) return;
    await navigator.clipboard.writeText(tab.cmd);
    copied = true;
    setTimeout(() => (copied = false), 1400);
  }
</script>

<section class="section wrap" id="install">
  <div class="section-head">
    <span class="eyebrow">Get started</span>
    <h2>Install on every device you own.</h2>
  </div>
  <div class="installer">
    <div class="installer__tabs" role="tablist">
      {#each INSTALL_TABS as t}
        <button
          type="button"
          class="installer__tab"
          class:installer__tab--active={active === t.id}
          onclick={() => (active = t.id)}
          role="tab"
          aria-selected={active === t.id}
        >
          <Icon name={t.icon} size={13} /> {t.label}
        </button>
      {/each}
    </div>
    <div class="installer__panel">
      <div>
        <h3>{tab.title}</h3>
        <ol class="installer__steps">
          {#each tab.steps as [k, v], i}
            <li>
              <span class="n">{String(i + 1).padStart(2, '0')}</span>
              <span>
                <strong>{k}</strong>
                <span class="step-desc">{v}</span>
              </span>
            </li>
          {/each}
        </ol>
        <div class="cta-row">
          <a class="btn btn--primary" href="https://{tab.cmd}" rel="noopener">
            <Icon name="github" size={13} /> Download on GitHub
          </a>
          <a class="btn" href="https://github.com/playbridge/playbridge#documentation" rel="noopener">Docs</a>
        </div>
      </div>
      <div>
        <div class="mono panel-label">GITHUB RELEASES</div>
        <div class="installer__cmd">
          <code>{tab.cmd}</code>
          <button type="button" class="copy-btn" onclick={copy}>
            {copied ? '✓ COPIED' : 'COPY'}
          </button>
        </div>
        <div class="installer__meta">
          {#each tab.meta as [k, v]}
            <div class="installer__meta__row">
              <span class="meta-k">{k}</span>
              <span>{v}</span>
            </div>
          {/each}
        </div>
      </div>
    </div>
  </div>
</section>

<style>
  .step-desc { color: var(--text-dim); }
  .cta-row { margin-top: 28px; display: flex; gap: 8px; }
  .panel-label { font-size: 10px; letter-spacing: 0.16em; color: var(--text-faint); margin-bottom: 8px; }
  .meta-k { color: var(--text-faint); }
</style>
