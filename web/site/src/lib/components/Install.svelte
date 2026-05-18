<script lang="ts">
  import Icon from '$lib/icons/Icon.svelte';
  import { INSTALL_TABS } from '$lib/data/site';

  let active = $state('android');
  let copied = $state(false);

  const tab = $derived(INSTALL_TABS.find((t) => t.id === active) ?? INSTALL_TABS[0]);
  const senderTabs = INSTALL_TABS.filter((t) => t.role === 'sender');
  const playerTabs = INSTALL_TABS.filter((t) => t.role === 'player');

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
      <div class="installer__group">
        <span class="installer__group-label">Senders</span>
        {#each senderTabs as t}
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
      <div class="installer__divider" aria-hidden="true"></div>
      <div class="installer__group">
        <span class="installer__group-label">Players</span>
        {#each playerTabs as t}
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
          <button
            type="button"
            class="btn btn--primary"
            onclick={() => window.open('https://' + tab.cmd, '_blank', 'noopener,noreferrer')}
          >
            <Icon name="github" size={13} /> Download on GitHub
          </button>
          <a class="btn" href="https://github.com/playbridgeapp/PlayBridge#readme" rel="noopener" target="_blank">Docs</a>
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

  .installer__group {
    display: flex;
    align-items: flex-end;
    gap: 4px;
  }
  .installer__group-label {
    font-family: 'JetBrains Mono', monospace;
    font-size: 10px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--text-faint);
    padding: 0 6px 15px;
    white-space: nowrap;
  }
  .installer__divider {
    width: 1px;
    background: var(--line-strong);
    align-self: stretch;
    margin: 8px 4px;
    flex-shrink: 0;
  }
</style>
